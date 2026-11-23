"""
Aladin token API.

A deliberately small FastAPI service with one real job: hand the Android app a
short-lived LiveKit token so it can join a room that the Aladin agent is
already sitting in.

Why this exists at all
----------------------
LiveKit room tokens must be signed with the LiveKit API secret. That secret
cannot ship inside an APK — anyone can decompile an APK — so the app has to ask
a trusted server for a token, and that server holds the secret.

Security model
--------------
* The app authenticates with a **device token** (``ALADIN_DEVICE_TOKEN``), a
  shared secret that is *not* an AI key and grants no AI access by itself.
* Tokens are scoped to a single room, a single identity, and a short TTL.
* The app can present **non-secret preferences** (provider, voice, language,
  web-search toggle). These are validated against an allowlist and stamped by
  *this server* into LiveKit room metadata, which the agent reads.
* The app can never write room metadata directly, so a compromised client
  cannot inject arbitrary agent configuration.

Bring-your-own-key
------------------
A user's own Gemini or OpenAI key cannot travel in room metadata: LiveKit
delivers metadata to every participant, so the app's own transport would carry
the key. Instead the app registers its key here over TLS, this process holds it
in memory for a short TTL, and the agent worker fetches it privately over a
separate, server-to-server authenticated endpoint::

    POST   /v1/session/keys        (device token)  app registers its key
    GET    /v1/session/keys/{room} (internal token) agent fetches it
    DELETE /v1/session/keys/{room} (either)         session ended, wipe it

The key is never written to disk, never logged, and never echoed back in a
response. ``GET /v1/config`` reports whether the feature is available so the app
can be honest about it instead of offering a control that does nothing. See
``aladin/session_keys.py`` and ``docs/ARCHITECTURE.md``.

Run it with::

    uvicorn aladin.token_server:app --host 0.0.0.0 --port 8080
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import secrets
import time
from collections import defaultdict, deque
from typing import Any, Literal

import aiohttp

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field, field_validator

from .config import AI_PROVIDERS, SUPPORTED_LANGUAGES, Settings, SettingsError
from .latency import TIMING_VERSION, TestChatTimings, round_ms
from .logging_setup import configure_logging, redact_secrets_in_text
from .providers import available_providers, describe_provider
from .session_keys import (
    MAX_KEY_LENGTH,
    MIN_KEY_LENGTH,
    SessionKeyError,
    SessionKeyStore,
    validate_api_key,
)
from .app_updates import router as app_update_router

logger = logging.getLogger("aladin.token_api")

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------


def _load_settings() -> Settings:
    settings = Settings.from_env()
    configure_logging(settings.log_level)
    return settings


settings = _load_settings()

#: Room-metadata keys the app is allowed to influence. Secrets are absent by
#: construction — notably there is no ``provider_api_key`` here.
APP_CONFIGURABLE_KEYS: frozenset[str] = frozenset(
    {
        "ai_provider",
        "model",
        "voice",
        "languages",
        "web_search",
        "instructions_extra",
        "temperature",
        "thinking",
        "affective_dialog",
        "proactivity",
        "noise_cancellation",
        "greeting",
        # Voice architecture and the modular pipeline's independent selections.
        # These name *which* providers run — never a credential for them — so
        # they are safe to travel in room metadata, which every participant can
        # read. Every Cartesia *key* travels the session-key channel instead.
        "voice_mode",
        "llm_model",
        "tts_provider",
        "stt_provider",
        "cartesia_model",
        "cartesia_voice",
        "cartesia_language",
        "cartesia_speed",
    }
)

#: Keys that look like credentials. Rejected outright, with a clear message,
#: so a future client change cannot accidentally leak a key into metadata.
_SECRET_LIKE_KEYS = frozenset(
    {
        "api_key",
        "apikey",
        "provider_api_key",
        "gemini_api_key",
        "google_api_key",
        "openai_api_key",
        "cartesia_api_key",
        "tavily_api_key",
        "brave_search_api_key",
        "device_token",
        "livekit_api_secret",
        "token",
        "secret",
        "password",
    }
)


# ---------------------------------------------------------------------------
# Rate limiting
# ---------------------------------------------------------------------------


class _RateLimiter:
    """
    Tiny in-memory sliding-window limiter.

    Enough to stop a client accidentally hammering the endpoint; not a
    substitute for a real gateway if this is ever exposed to the internet.
    """

    def __init__(self, *, max_requests: int, window_seconds: float) -> None:
        self._max = max_requests
        self._window = window_seconds
        self._hits: dict[str, deque[float]] = defaultdict(deque)

    def check(self, key: str) -> bool:
        now = time.monotonic()
        bucket = self._hits[key]
        while bucket and now - bucket[0] > self._window:
            bucket.popleft()
        if len(bucket) >= self._max:
            return False
        bucket.append(now)
        return True


_token_limiter = _RateLimiter(max_requests=30, window_seconds=60.0)

#: Session keys live only in this process, only for as long as a session needs
#: them. See ``aladin/session_keys.py``.
session_keys = SessionKeyStore(
    ttl_seconds=settings.session_key_ttl_seconds,
    max_entries=settings.session_key_max_entries,
)

#: Uploading a key is rarer than asking for a token, so it gets its own, tighter
#: budget: a client that needs to register keys in a loop is a client that is
#: misbehaving.
_key_limiter = _RateLimiter(max_requests=20, window_seconds=60.0)

if settings.byok_enabled:
    logger.info(
        "Bring-your-own-key enabled (ttl=%ss, max_entries=%d)",
        settings.session_key_ttl_seconds,
        settings.session_key_max_entries,
    )
else:
    # Said out loud, once, with the reason: a silently disabled feature is the
    # hardest kind to diagnose.
    logger.info(
        "Bring-your-own-key disabled: %s", settings.byok_disabled_reason
    )


# ---------------------------------------------------------------------------
# Schemas
# ---------------------------------------------------------------------------


class AgentConfig(BaseModel):
    """Non-secret per-session preferences requested by the app."""

    model_config = {"extra": "forbid"}

    ai_provider: Literal["gemini", "openai"] | None = None
    model: str | None = Field(default=None, max_length=120)
    voice: str | None = Field(default=None, max_length=60)
    languages: list[str] | None = None
    web_search: bool | None = None
    instructions_extra: str | None = Field(default=None, max_length=2000)
    temperature: float | None = Field(default=None, ge=0.0, le=1.0)
    thinking: bool | None = None
    affective_dialog: bool | None = None
    proactivity: bool | None = None
    noise_cancellation: bool | None = None
    greeting: bool | None = None

    @field_validator("languages")
    @classmethod
    def _validate_languages(cls, value: list[str] | None) -> list[str] | None:
        if value is None:
            return None
        cleaned: list[str] = []
        for item in value:
            code = str(item).strip().lower().split("-")[0]
            if code not in SUPPORTED_LANGUAGES:
                raise ValueError(
                    f"unsupported language {item!r}; "
                    f"supported: {', '.join(SUPPORTED_LANGUAGES)}"
                )
            if code not in cleaned:
                cleaned.append(code)
        if not cleaned:
            raise ValueError("languages must not be empty when provided")
        return cleaned


class TokenRequest(BaseModel):
    model_config = {"extra": "forbid"}

    #: Optional caller-chosen room name. Generated if omitted.
    room: str | None = Field(default=None, max_length=120)
    #: Optional display name / identity for the participant.
    identity: str | None = Field(default=None, max_length=120)
    config: AgentConfig | None = None


class TokenResponse(BaseModel):
    url: str
    token: str
    room: str
    identity: str
    expires_in: int


class HealthResponse(BaseModel):
    status: str
    version: str
    provider: str
    model: str
    web_search: bool
    languages: list[str]


class SessionKeyRequest(BaseModel):
    """
    A user-supplied provider key, scoped to one room.

    ``extra="forbid"`` and the deliberately small field list mean a client
    cannot smuggle anything else along with the key, and the room is required:
    a key with no room could not be fetched, and accepting one would just be
    storage with no owner.
    """

    model_config = {"extra": "forbid"}

    room: str = Field(min_length=1, max_length=120)
    provider: Literal["gemini", "openai", "cartesia"]
    #: Validated for shape only. The value is never logged and never returned.
    api_key: str = Field(min_length=1, max_length=1024)
    #: Credentials for the session's *other* providers, keyed by provider.
    #:
    #: The modular voice pipeline uses two providers at once — one to reason,
    #: one to speak (Cartesia) — so one key is not always enough. Each value is
    #: validated exactly as strictly as ``api_key``, and, like ``api_key``,
    #: neither the values nor their shape are ever logged or returned to a
    #: device-token holder. ``cartesia`` is the expected key here.
    credentials: dict[Literal["cartesia"], str] | None = None


class SessionKeyResponse(BaseModel):
    """
    Acknowledgement that a key was stored.

    Note what is *not* here: the key. There is no endpoint that returns a key to
    a device-token holder, by design.
    """

    stored: bool
    room: str
    provider: str
    expires_in: int


class SessionKeyFetchResponse(BaseModel):
    """
    A stored key, returned to the agent worker only.

    This is the single place a user's key leaves the server, and it requires the
    internal token to reach. It is never returned to a client holding only the
    device token.
    """

    room: str
    provider: str
    api_key: str
    expires_in: int
    #: The session's additional provider credentials (e.g. the Cartesia voice
    #: key). Additive: an agent that predates the modular pipeline ignores this
    #: field and keeps working with ``api_key`` alone.
    credentials: dict[str, str] = Field(default_factory=dict)


class ProviderTestRequest(BaseModel):
    """
    "Test connection" for a provider credential the app is about to save.

    The key is checked and then forgotten: this endpoint has no store and no
    session, so testing a key never leaves a copy behind on the server. The key
    is validated for shape first (the same checks the session-key store applies)
    and is never logged — including on failure.
    """

    model_config = {"extra": "forbid"}

    provider: Literal["cartesia"] = "cartesia"
    api_key: str = Field(min_length=MIN_KEY_LENGTH, max_length=MAX_KEY_LENGTH)
    #: Optional: the model and voice the app is configured to use. When given,
    #: the test says whether they are usable, so "my key works but the voice id
    #: is wrong" is distinguishable from "my key is wrong".
    model: str = Field(default="", max_length=64)
    voice: str = Field(default="", max_length=128)


class ProviderTestResponse(BaseModel):
    """Result of a connection test. Contains no part of the credential."""

    ok: bool
    provider: str
    message: str
    #: ``None`` when the check could not be performed (e.g. a network error).
    key_valid: bool | None = None
    voice_checked: bool = False
    voice_valid: bool | None = None
    model_checked: bool = False
    model_valid: bool | None = None
    voices_available: int | None = None


class DeleteSessionKeyResponse(BaseModel):
    """Result of wiping a session key."""

    deleted: bool
    room: str


class AppUpdateResponse(BaseModel):
    enabled: bool
    version: str = ""
    version_code: int = 0
    download_url: str = ""
    notes: str = ""


class PublicConfigResponse(BaseModel):
    """Everything the app needs to render Settings, with no secrets."""

    ai_provider: str
    available_providers: list[str]
    model: str
    voice: str
    languages: list[str]
    available_languages: list[str]
    web_search_available: bool
    token_ttl_seconds: int
    empty_room_timeout_seconds: int
    agent_name: str
    supports_bring_your_own_key: bool = False
    #: ``modular`` (STT -> LLM -> TTS) or ``realtime`` (speech-to-speech).
    voice_mode: str = "modular"
    #: Which provider speaks, and which providers could.
    tts_provider: str = "cartesia"
    available_tts_providers: list[str] = Field(default_factory=list)
    stt_provider: str = "auto"
    available_stt_providers: list[str] = Field(default_factory=list)
    available_llm_providers: list[str] = Field(default_factory=list)
    #: Whether this *server* holds a Cartesia key. A user's key is registered
    #: per session and is deliberately invisible here — this flag only says
    #: whether a session could speak without one. Never the key itself.
    cartesia_configured: bool = False
    cartesia_model: str = ""
    cartesia_voice: str = ""

class TestChatRequest(BaseModel):
    """Text prompt sent to the secured ChatGPT/Aladin test bridge."""

    model_config = {"extra": "forbid"}

    message: str = Field(min_length=1, max_length=2000)
    timeout_seconds: float = Field(default=25.0, ge=5.0, le=60.0)


class TestChatResponse(BaseModel):
    """Assistant text captured from the real LiveKit Aladin session.

    Fields measured *by this server* come from
    :class:`aladin.latency.TestChatTimings`:

    ``latency_ms``
        ``connect_ms + response_wait_ms`` — the end-to-end latency Aladin is
        responsible for. Cold-start waiting is not part of it.
    ``agent_join_wait_ms``
        Time the dispatched agent needed to appear in the room; on LiveKit
        Cloud this is where scale-to-zero cold starts are reported.
    ``total_wall_ms`` / ``unaccounted_ms``
        Full request wall clock, and the part of it not covered by the named
        fields (normally ~0 ms).

    The remaining timing fields are measured *inside the agent* and echoed back
    unchanged: ``agent_session_startup_ms`` (session start -> ready),
    ``prompt_to_response_ms`` (prompt arrival -> assistant response) and
    ``ready_to_response_ms`` (session ready -> assistant response).
    """

    room: str
    response: str
    latency_ms: int
    connect_ms: int | None = None
    agent_join_wait_ms: int | None = None
    response_wait_ms: int | None = None
    total_wall_ms: int | None = None
    unaccounted_ms: int | None = None
    attempts: int | None = None
    timing_version: int | None = None
    # Agent-side clocks.
    agent_session_startup_ms: int | None = None
    prompt_to_response_ms: int | None = None
    ready_to_response_ms: int | None = None



# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------


def _extract_bearer(authorization: str | None) -> str | None:
    if not authorization:
        return None
    parts = authorization.split(None, 1)
    if len(parts) == 2 and parts[0].lower() == "bearer":
        return parts[1].strip()
    return authorization.strip()


def require_device_token(
    authorization: str | None = Header(default=None),
    x_aladin_device_token: str | None = Header(default=None),
) -> None:
    """
    Authenticate the caller via a shared device token.

    Compared with :func:`secrets.compare_digest` to avoid leaking the token
    through response-timing differences.
    """
    if not settings.device_token:
        # Fail closed: an unconfigured server must never mint tokens.
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Server is not configured (ALADIN_DEVICE_TOKEN missing).",
        )
    presented = x_aladin_device_token or _extract_bearer(authorization)
    if not presented or not secrets.compare_digest(presented, settings.device_token):
        logger.warning("Rejected token request: invalid or missing device token")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing device token.",
            headers={"WWW-Authenticate": "Bearer"},
        )


def require_internal_token(
    authorization: str | None = Header(default=None),
    x_aladin_internal_token: str | None = Header(default=None),
) -> None:
    """
    Authenticate the *agent worker* via its own shared secret.

    Deliberately separate from :func:`require_device_token`. The device token
    ships inside every app install; the internal token exists only on the
    server and the agent host. If they were the same value, an app could read
    other sessions' keys back out of the store, which is precisely what this
    endpoint must not allow.
    """
    reason = settings.byok_disabled_reason
    if reason is not None:
        # Fail closed, and say why: a 503 with a reason is far easier to act on
        # than a 401 that looks like a wrong password.
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"Bring-your-own-key is not available on this server: {reason}.",
        )

    presented = x_aladin_internal_token or _extract_bearer(authorization)
    if not presented or not secrets.compare_digest(presented, settings.internal_token):
        logger.warning("Rejected session-key fetch: invalid or missing internal token")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing internal token.",
            headers={"WWW-Authenticate": "Bearer"},
        )


# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------


app = FastAPI(
    title="Aladin Token API",
    version="0.1.0",
    description=(
        "Mints short-lived LiveKit room tokens for the Aladin Android app. "
        "Holds no AI capability of its own — the realtime voice agent runs "
        "separately and never exposes its keys to clients."
    ),
)

app.include_router(app_update_router)

app.add_middleware(
    CORSMiddleware,
    allow_origins=list(settings.allowed_origins),
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-Aladin-Device-Token"],
)


@app.exception_handler(SettingsError)
async def _settings_error_handler(_: Request, exc: SettingsError) -> JSONResponse:
    """Surface configuration problems as 500s with a useful message."""
    logger.error("Configuration error: %s", exc)
    return JSONResponse(status_code=500, content={"detail": str(exc)})


def _json_safe(value: Any) -> Any:
    """
    Coerce a value into something JSON can encode.

    Pydantic puts raw exception objects inside ``ctx`` for custom validators
    (our language validator raises ``ValueError``), and those are not
    serialisable. Starlette's default handler runs ``jsonable_encoder`` for
    exactly this reason; because we build the response ourselves, we have to
    do the same.
    """
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [_json_safe(item) for item in value]
    if isinstance(value, BaseException):
        return redact_secrets_in_text(str(value))
    return redact_secrets_in_text(str(value))


def _scrub_validation_errors(errors: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """
    Remove submitted values from validation errors.

    FastAPI's default 422 body echoes the offending input back to the caller::

        {"detail": [{"loc": ["body", "config", "provider_api_key"],
                     "input": "AIzaSy...", ...}]}

    That is normally helpful, but here it means a client that mistakenly puts a
    Gemini key in the request gets that key reflected straight back — and error
    bodies end up in proxies, crash reporters and logs. Since this endpoint
    exists to keep credentials off the wire, the value is dropped instead.
    """
    scrubbed: list[dict[str, Any]] = []
    for error in errors:
        clean = {key: value for key, value in error.items() if key != "input"}

        # Keep a hint that a value was present, without revealing it.
        location = error.get("loc") or ()
        field = str(location[-1]).lower() if location else ""
        if "input" in error:
            if field in _SECRET_LIKE_KEYS:
                clean["input"] = "<redacted: credentials are not accepted here>"
            else:
                clean["input"] = "<redacted>"

        if isinstance(clean.get("msg"), str):
            clean["msg"] = redact_secrets_in_text(clean["msg"])
        scrubbed.append(_json_safe(clean))
    return scrubbed


@app.exception_handler(RequestValidationError)
async def _validation_error_handler(
    _: Request, exc: RequestValidationError
) -> JSONResponse:
    """Return 422s that never echo credentials back to the caller."""
    return JSONResponse(
        status_code=422,
        content={"detail": _scrub_validation_errors(exc.errors())},
    )


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@app.get("/health", response_model=HealthResponse, tags=["meta"])
async def health() -> HealthResponse:
    """Liveness probe. Exposes no secrets."""
    provider = describe_provider(settings)
    return HealthResponse(
        status="ok",
        version="0.1.0",
        provider=provider["provider"],
        model=provider["model"],
        web_search=bool(provider["web_search_active"]),
        languages=list(settings.languages),
    )

@app.get("/v1/config", response_model=PublicConfigResponse, tags=["meta"])
async def public_config() -> PublicConfigResponse:
    """
    Public, unauthenticated configuration for populating the app's Settings UI.

    Contains no secrets: keys never appear here.
    """
    return PublicConfigResponse(
        ai_provider=settings.ai_provider,
        available_providers=list(AI_PROVIDERS),
        model=settings.resolved_model,
        voice=settings.resolved_voice,
        languages=list(settings.languages),
        available_languages=list(SUPPORTED_LANGUAGES),
        web_search_available=settings.has_web_search_credentials,
        token_ttl_seconds=settings.token_ttl_seconds,
        empty_room_timeout_seconds=settings.empty_room_timeout_seconds,
        agent_name=settings.agent_name,
        # Reported honestly: the app uses this to decide whether to offer the
        # bring-your-own-key flow at all, and says "pending backend support"
        # when it is false rather than pretending a saved key is in use.
        supports_bring_your_own_key=settings.byok_enabled,
        voice_mode=settings.voice_mode,
        tts_provider=settings.tts_provider,
        available_tts_providers=list(available_providers("tts")),
        stt_provider=settings.stt_provider,
        available_stt_providers=list(available_providers("stt")),
        available_llm_providers=list(available_providers("llm")),
        # A boolean, never the key: the app can say whether this server can
        # speak on its own, and a user-supplied key stays per-session.
        cartesia_configured=bool(settings.cartesia_api_key),
        cartesia_model=settings.resolved_cartesia_model,
        cartesia_voice=settings.resolved_cartesia_voice,
    )


def _build_room_metadata(config: AgentConfig | None) -> str | None:
    """
    Convert validated app preferences into room metadata.

    Metadata is authored here, on the server — the agent trusts it, so the app
    must not be able to pass anything that is not on the allowlist.
    """
    if config is None:
        return None

    payload = config.model_dump(exclude_none=True)
    if not payload:
        return None

    clean: dict[str, Any] = {}
    for key, value in payload.items():
        lowered = key.lower()
        if lowered in _SECRET_LIKE_KEYS:
            # Should be unreachable: the schema forbids these. Defence in depth.
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=(
                    f"'{key}' is not accepted here. API keys are never sent "
                    "through room metadata; see docs/ROADMAP.md."
                ),
            )
        if key not in APP_CONFIGURABLE_KEYS:
            continue
        clean[key] = value

    if not clean:
        return None

    import json

    return json.dumps({"aladin": clean})


def _sanitise_identifier(raw: str | None, *, prefix: str, max_length: int = 64) -> str:
    """Keep only characters that are safe in a LiveKit identity/room name."""
    if not raw or not raw.strip():
        return f"{prefix}-{secrets.token_hex(4)}"
    safe = "".join(
        character
        for character in raw.strip()
        if character.isalnum() or character in "-_"
    )
    safe = safe[:max_length]
    return safe or f"{prefix}-{secrets.token_hex(4)}"


@app.post(
    "/v1/token",
    response_model=TokenResponse,
    tags=["tokens"],
    dependencies=[Depends(require_device_token)],
)
async def create_token(payload: TokenRequest, request: Request) -> TokenResponse:
    """
    Mint a short-lived LiveKit token for the app.

    Requires the device token in ``Authorization: Bearer <token>`` or
    ``X-Aladin-Device-Token: <token>``.
    """
    from livekit import api

    client_key = request.client.host if request.client else "unknown"
    if not _token_limiter.check(client_key):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many token requests. Slow down.",
        )

    if not settings.livekit_api_secret:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Server is not configured (LIVEKIT_API_SECRET missing).",
        )

    room_name = _sanitise_identifier(payload.room, prefix="aladin-room")
    identity = _sanitise_identifier(payload.identity, prefix="device")

    grants = api.VideoGrants(
        room_join=True,
        room=room_name,
        can_publish=True,        # the app publishes its microphone
        can_subscribe=True,      # and subscribes to the agent's audio
        can_publish_data=True,   # used for captions and source links
    )

    token = (
        api.AccessToken(settings.livekit_api_key, settings.livekit_api_secret)
        .with_identity(identity)
        .with_name(identity)
        .with_grants(grants)
        .with_ttl(_seconds(settings.token_ttl_seconds))
        .to_jwt()
    )

    metadata = _build_room_metadata(payload.config)
    if metadata:
        logger.info(
            "Session preferences applied for room=%s: %s",
            room_name,
            redact_secrets_in_text(metadata),
        )

    logger.info(
        "Issued token room=%s identity=%s ttl=%ss",
        room_name,
        identity,
        settings.token_ttl_seconds,
    )

    return TokenResponse(
        url=settings.livekit_url,
        token=token,
        room=room_name,
        identity=identity,
        expires_in=settings.token_ttl_seconds,
    )


def _seconds(value: int) -> Any:
    """Build a ``datetime.timedelta`` for the token TTL."""
    from datetime import timedelta

    return timedelta(seconds=max(60, int(value)))


#: The bridge waits for the dispatched agent for as long as the caller's own
#: timeout allows, reserving this much of it for the answer itself.
_MIN_RESPONSE_BUDGET_SECONDS = 3.0
#: How often the response future is polled while waiting for the agent.
_RESPONSE_POLL_SECONDS = 0.25
#: How often the prompt is re-published if no answer has arrived yet.
_REPUBLISH_EVERY_SECONDS = 2.0


def _clock() -> float:
    """Monotonic seconds; a module-level seam so tests can drive time."""
    return time.perf_counter()


async def _wait_for_agent_join(room: Any, *, timeout: float) -> bool:
    """Wait until a remote participant (the dispatched agent) is in the room.

    Returns ``True`` if the agent is present, ``False`` on timeout. The wait is
    bounded and always returns so the caller can *report* a cold start instead
    of hiding it inside the response latency.
    """
    if getattr(room, "remote_participants", None):
        return True
    if timeout <= 0:
        return False

    joined = asyncio.Event()

    def _on_participant_connected(participant: Any = None) -> None:
        joined.set()

    room.on("participant_connected", _on_participant_connected)
    try:
        await asyncio.wait_for(joined.wait(), timeout=timeout)
        return True
    except asyncio.TimeoutError:
        return False
    finally:
        remove = getattr(room, "off", None)
        if callable(remove):
            remove("participant_connected", _on_participant_connected)


@app.post(
    "/v1/test/chat",
    response_model=TestChatResponse,
    tags=["test"],
    dependencies=[Depends(require_device_token)],
)
async def test_chat(payload: TestChatRequest, request: Request) -> TestChatResponse:
    """Run a text-only prompt through the real Aladin LiveKit agent.

    This endpoint is intentionally protected by the same device token as the
    Android app. It creates an ephemeral LiveKit participant, waits for the
    dispatched agent to join, sends a test packet, waits for the agent's caption
    response, then disconnects. No provider key or LiveKit secret is returned.

    Timing semantics are defined in :mod:`aladin.latency`. The important one:
    waiting for a cold (scaled-to-zero) agent is reported separately as
    ``agent_join_wait_ms`` and is *never* counted as response latency.
    """
    client_key = request.client.host if request.client else "unknown"
    if not _token_limiter.check("test:" + client_key):
        raise HTTPException(status_code=429, detail="Too many test requests. Slow down.")

    from livekit import api, rtc

    room_name = _sanitise_identifier(None, prefix="aladin-test")
    identity = _sanitise_identifier(None, prefix="chatgpt-test")

    token_builder = (
        api.AccessToken(settings.livekit_api_key, settings.livekit_api_secret)
        .with_identity(identity)
        .with_name(identity)
        .with_grants(
            api.VideoGrants(
                room_join=True,
                room=room_name,
                can_publish=True,
                can_subscribe=True,
                can_publish_data=True,
            )
        )
        .with_ttl(_seconds(120))
    )

    # The production agent uses an explicit dispatch name, so this ephemeral
    # test room must request that same agent when the room is first created.
    # Without this, the test participant can connect but no Aladin worker joins.
    if settings.agent_name:
        token_builder = token_builder.with_room_config(
            api.RoomConfiguration(
                agents=[api.RoomAgentDispatch(agent_name=settings.agent_name)]
            )
        )

    token = token_builder.to_jwt()

    room = rtc.Room()
    response_future: asyncio.Future[dict[str, Any]] = asyncio.get_running_loop().create_future()

    @room.on("data_received")
    def _on_data(packet: Any) -> None:
        if packet.topic != "aladin.test.response":
            return
        try:
            body = json.loads(packet.data.decode("utf-8"))
            if body.get("type") == "response" and isinstance(body.get("text"), str):
                if not response_future.done():
                    response_future.set_result(body)
        except (UnicodeDecodeError, json.JSONDecodeError):
            return

    started_at = _clock()
    connect_ms: int | None = None
    agent_join_wait_ms = 0
    try:
        await room.connect(settings.livekit_url, token)
        connect_ms = round_ms(_clock() - started_at)

        # The agent is dispatched when this room is created and may take a
        # while to appear (LiveKit Cloud scales idle agents to zero). Wait for
        # it explicitly, then report that wait on its own: it is environment
        # start-up cost, not Aladin's response latency.
        join_started_at = _clock()
        agent_joined = await _wait_for_agent_join(
            room,
            timeout=max(
                0.0,
                payload.timeout_seconds
                - (join_started_at - started_at)
                - _MIN_RESPONSE_BUDGET_SECONDS,
            ),
        )
        agent_join_wait_ms = round_ms(_clock() - join_started_at)
        if not agent_joined:
            logger.warning(
                "Aladin test agent did not join room=%s within the request budget; publishing anyway",
                room_name,
            )

        message = json.dumps({"type": "prompt", "message": payload.message})
        deadline = started_at + payload.timeout_seconds
        first_publish_at: float | None = None
        last_publish_at: float | None = None
        attempts = 0
        response_payload: dict[str, Any] | None = None
        # Publish, then poll for the answer. Data published before the agent's
        # session is ready is queued by the agent, so re-publishing only
        # matters when a packet is genuinely lost — it never *adds* latency.
        while response_payload is None:
            now = _clock()
            if now >= deadline:
                break
            if last_publish_at is None or (now - last_publish_at) >= _REPUBLISH_EVERY_SECONDS:
                publish_started_at = _clock()
                await room.local_participant.publish_data(
                    message,
                    reliable=True,
                    topic="aladin.test.prompt",
                )
                last_publish_at = _clock()
                attempts += 1
                if first_publish_at is None:
                    first_publish_at = publish_started_at
            try:
                response_payload = await asyncio.wait_for(
                    asyncio.shield(response_future),
                    timeout=max(0.01, min(_RESPONSE_POLL_SECONDS, deadline - _clock())),
                )
            except asyncio.TimeoutError:
                continue

        if response_payload is None or first_publish_at is None:
            raise HTTPException(
                status_code=504,
                detail="Timed out waiting for Aladin.",
            )
        response = response_payload.get("text", "")
        timings = TestChatTimings(
            connect_ms=connect_ms or 0,
            agent_join_wait_ms=agent_join_wait_ms,
            response_wait_ms=round_ms(_clock() - first_publish_at),
            total_wall_ms=round_ms(_clock() - started_at),
            attempts=attempts,
        )
        return TestChatResponse(
            room=room_name,
            response=response,
            timing_version=TIMING_VERSION,
            **timings.as_payload_fields(),
            agent_session_startup_ms=response_payload.get("agent_session_startup_ms"),
            prompt_to_response_ms=response_payload.get("prompt_to_response_ms"),
            ready_to_response_ms=response_payload.get("ready_to_response_ms"),
        )
    finally:
        await room.disconnect()


# ---------------------------------------------------------------------------
# Provider connection test
# ---------------------------------------------------------------------------


#: Cartesia's public API. The auth header name and API version are read from the
#: installed plugin rather than copied here, so this test can never drift from
#: what the agent's TTS will actually send.
_CARTESIA_DEFAULT_BASE_URL = "https://api.cartesia.ai"


def _load_cartesia_plugin_facts() -> tuple[tuple[str, str, str], tuple[str, ...]]:
    """
    Read Cartesia's facts from the installed plugin, once, at import time.

    Why not lazily, inside the request
    ---------------------------------
    This *was* an in-request import, and it was a genuine bug: the first request
    to need it could race the plugin's own import and get a partially
    initialised module, and the broad ``except`` turned that into an empty model
    list — i.e. a degraded check with no explanation. Resolving at import time
    (single-threaded, before the server accepts traffic) removes the race, and
    the failure is now *logged* rather than swallowed, so "the model check
    silently stopped working" cannot happen again.

    Falls back to defaults if the plugin is absent, which is the normal state of
    a token API deployed without the voice plugins.
    """
    base_url = _CARTESIA_DEFAULT_BASE_URL
    auth_header, api_version = "X-API-Key", ""

    try:
        from livekit.plugins.cartesia.constants import (
            API_AUTH_HEADER,
            API_VERSION,
            API_VERSION_HEADER,  # noqa: F401  (documents the header we rely on)
        )

        auth_header, api_version = API_AUTH_HEADER, API_VERSION
    except Exception as exc:
        logger.info(
            "Cartesia plugin not available for the connection test (%s); using "
            "default header names",
            exc,
        )

    models: tuple[str, ...] = ()
    try:
        from typing import get_args

        from livekit.plugins.cartesia.models import TTSModels

        models = tuple(str(item) for item in get_args(TTSModels))
    except Exception as exc:
        logger.warning(
            "Could not read Cartesia's model list (%s); the connection test "
            "will not be able to validate the selected model",
            exc,
        )

    return (base_url, auth_header, api_version), models


(_CARTESIA_BASE_URL, _CARTESIA_AUTH_HEADER, _CARTESIA_API_VERSION), _CARTESIA_MODELS = (
    _load_cartesia_plugin_facts()
)


def _cartesia_client_settings() -> tuple[str, str, str]:
    """``(base_url, auth_header, api_version)`` as the installed plugin uses them."""
    return _CARTESIA_BASE_URL, _CARTESIA_AUTH_HEADER, _CARTESIA_API_VERSION


def _cartesia_known_models() -> tuple[str, ...]:
    """Model ids the installed plugin declares, for a local (offline) check."""
    return _CARTESIA_MODELS


async def _verify_cartesia_key(api_key: str, *, timeout: float = 10.0) -> tuple[bool | None, str, int | None, list[str]]:
    """
    Ask Cartesia whether a key works. Returns ``(ok, message, voices, ids)``.

    ``ok`` is ``None`` when the question could not be asked (network failure),
    which is deliberately distinct from a rejection: telling a user their key is
    invalid when the check could not run would be a false accusation.

    The key is sent to Cartesia and nowhere else; it is never logged, never
    stored, and never included in the returned message.
    """
    import aiohttp

    base_url, auth_header, api_version = _cartesia_client_settings()
    headers = {auth_header: api_key}
    if api_version:
        headers["Cartesia-Version"] = api_version

    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(
                f"{base_url}/voices",
                headers=headers,
                timeout=aiohttp.ClientTimeout(total=timeout),
            ) as response:
                status = int(response.status)
                if status in (401, 403):
                    return False, "Cartesia rejected this API key.", None, []
                if status >= 400:
                    # The body is not relayed: a provider's error text is outside
                    # our control and this response goes to a client.
                    return (
                        None,
                        f"Cartesia returned HTTP {status}; the key could not be "
                        "verified right now.",
                        None,
                        [],
                    )
                payload = await response.json()
    except Exception:
        # Includes timeouts and DNS failures. The exception text is not relayed:
        # it can contain the request URL, which carries no key here but is still
        # provider detail a client does not need.
        return None, "Could not reach Cartesia to verify the key.", None, []

    voices: list[str] = []
    if isinstance(payload, list):
        for item in payload:
            if isinstance(item, dict) and isinstance(item.get("id"), str):
                voices.append(item["id"])
    elif isinstance(payload, dict):
        data = payload.get("data")
        if isinstance(data, list):
            for item in data:
                if isinstance(item, dict) and isinstance(item.get("id"), str):
                    voices.append(item["id"])

    return True, "Cartesia accepted this API key.", len(voices), voices


@app.post(
    "/v1/providers/test",
    response_model=ProviderTestResponse,
    tags=["providers"],
    dependencies=[Depends(require_device_token)],
)
async def test_provider_connection(
    payload: ProviderTestRequest, request: Request
) -> ProviderTestResponse:
    """
    Check a provider credential before the app saves it.

    Requires the device token. The credential is tested in flight and dropped —
    this endpoint writes nothing, so "Test Connection" cannot leave a key behind
    on the server. The response describes the *outcome* only.
    """
    client_key = request.client.host if request.client else "unknown"
    if not _key_limiter.check(client_key):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many connection tests. Slow down.",
        )

    try:
        # Same shape checks as a stored session key, so a value that passes here
        # cannot later be rejected by the store (and vice versa).
        api_key = validate_api_key(
            payload.api_key,
            transport_secrets=(
                settings.livekit_api_secret,
                settings.device_token,
                settings.internal_token,
            ),
        )
    except SessionKeyError as exc:
        # str(exc) is written to never contain the key — see validate_api_key.
        return ProviderTestResponse(
            ok=False,
            provider=payload.provider,
            message=str(exc),
            key_valid=False,
        )

    ok, message, voice_count, voice_ids = await _verify_cartesia_key(api_key)
    if ok is not True:
        return ProviderTestResponse(
            ok=False,
            provider=payload.provider,
            message=message,
            key_valid=ok,
            voices_available=voice_count,
        )

    voice_checked = bool(payload.voice)
    voice_valid: bool | None = None
    if voice_checked:
        # A voice id the account cannot see is a real, common misconfiguration,
        # and it is worth catching here rather than as silence in a live session.
        voice_valid = payload.voice in voice_ids

    model_checked = bool(payload.model)
    model_valid: bool | None = None
    if model_checked:
        known = _cartesia_known_models()
        model_valid = (payload.model in known) if known else None

    problems: list[str] = []
    if voice_valid is False:
        problems.append("the selected voice id was not found on this account")
    if model_valid is False:
        problems.append(f"the model {payload.model!r} is not one this plugin supports")

    if problems:
        return ProviderTestResponse(
            ok=False,
            provider=payload.provider,
            message="The key works, but " + " and ".join(problems) + ".",
            key_valid=True,
            voice_checked=voice_checked,
            voice_valid=voice_valid,
            model_checked=model_checked,
            model_valid=model_valid,
            voices_available=voice_count,
        )

    logger.info(
        "Provider connection test ok provider=%s voices=%s", payload.provider, voice_count
    )
    return ProviderTestResponse(
        ok=True,
        provider=payload.provider,
        message=message,
        key_valid=True,
        voice_checked=voice_checked,
        voice_valid=voice_valid,
        model_checked=model_checked,
        model_valid=model_valid,
        voices_available=voice_count,
    )


# ---------------------------------------------------------------------------
# Session keys (bring your own key)
# ---------------------------------------------------------------------------


@app.post(
    "/v1/session/keys",
    response_model=SessionKeyResponse,
    tags=["session keys"],
    dependencies=[Depends(require_device_token)],
)
async def register_session_key(
    payload: SessionKeyRequest, request: Request
) -> SessionKeyResponse:
    """
    Register a provider key for one room, for a short time.

    Called by the app *before* it joins the room, so the agent finds the key
    when it starts. The key is held in memory only; see
    ``aladin/session_keys.py`` for why it cannot travel in room metadata.

    Requires the device token. Returns no part of the key.
    """
    client_key = request.client.host if request.client else "unknown"
    if not _key_limiter.check(client_key):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many key registrations. Slow down.",
        )

    reason = settings.byok_disabled_reason
    if reason is not None:
        # Fail closed: the app checks /v1/config first and would normally not
        # even try. Reaching here means the server changed underneath it.
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"Bring-your-own-key is not available on this server: {reason}.",
        )

    # The agent looks the key up by the room name it joined, which comes from
    # the token response and is already sanitised. Normalising here means an app
    # that registers a key under a raw name still lands on the name the agent
    # will use.
    room_name = _sanitise_identifier(payload.room, prefix="aladin-room")

    try:
        entry = session_keys.put(
            room=room_name,
            provider=payload.provider,
            api_key=payload.api_key,
            # Refuse the server's own transport secrets: pasting one into the
            # provider-key field is a plausible mistake with a nasty outcome.
            transport_secrets=(
                settings.livekit_api_secret,
                settings.device_token,
                settings.internal_token,
            ),
            # The voice provider's key, when the session needs a second one.
            credentials=payload.credentials,
        )
    except SessionKeyError as exc:
        # str(exc) never contains the key — see session_keys.validate_api_key.
        logger.warning(
            "Rejected session key room=%s provider=%s: %s",
            room_name,
            payload.provider,
            exc,
        )
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)
        ) from exc

    return SessionKeyResponse(
        stored=True,
        room=entry.room,
        provider=entry.provider,
        expires_in=session_keys.remaining(entry),
    )


@app.get(
    "/v1/session/keys/{room}",
    response_model=SessionKeyFetchResponse,
    tags=["session keys"],
    dependencies=[Depends(require_internal_token)],
)


async def fetch_session_key(room: str) -> SessionKeyFetchResponse:
    """
    Hand a stored key to the agent worker.

    Requires the internal token — never the device token — so an app install
    cannot read keys back. A missing key is a 404, which is the agent's signal
    to fall back to whatever the server is configured with.
    """
    entry = session_keys.get(room)
    if entry is None:
        logger.info("Session key not found room=%s", room)
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No session key registered for this room.",
        )

    logger.info(
        "Released session key room=%s provider=%s also=%s ttl=%ss",
        entry.room,
        entry.provider,
        ",".join(sorted(entry.credentials)) or "none",
        session_keys.remaining(entry),
    )
    return SessionKeyFetchResponse(
        room=entry.room,
        provider=entry.provider,
        api_key=entry.api_key,
        expires_in=session_keys.remaining(entry),
        credentials=dict(entry.credentials),
    )


@app.delete(
    "/v1/session/keys/{room}",
    response_model=DeleteSessionKeyResponse,
    tags=["session keys"],
    dependencies=[Depends(require_internal_token)],
)
async def delete_session_key(room: str) -> DeleteSessionKeyResponse:
    """
    Wipe a key as soon as its session ends.

    The TTL is a backstop; this is the normal path. Called by the agent when the
    session finishes, so a key's lifetime is the session's lifetime rather than
    a fixed window.
    """
    return DeleteSessionKeyResponse(deleted=session_keys.delete(room), room=room)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    """Run the token API with uvicorn (``python -m aladin.token_server``)."""
    import uvicorn

    try:
        settings.require_for_token_api()
    except SettingsError as exc:
        print(f"\n❌ {exc}\n")
        raise SystemExit(1) from exc

    uvicorn.run(
        "aladin.token_server:app",
        host=settings.token_api_host,
        port=settings.token_api_port,
        log_level=settings.log_level.lower(),
    )


if __name__ == "__main__":
    main()