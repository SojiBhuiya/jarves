"""
Aladin backend configuration.

Everything the backend needs comes from environment variables (see
``.env.example``). Nothing is hard-coded and no secret ever has a default
value, so a misconfigured deployment fails loudly instead of silently running
with a placeholder key.

Two entry points:

* :meth:`Settings.from_env` — read the process environment.
* :meth:`Settings.apply_overrides` — apply runtime overrides that arrive from
  *server-authored* room metadata.

The override mechanism is what makes the Android app's "API Settings" screen
possible without redeploying the backend: the app sends its preferences to the
token server, the token server validates them, and the token server stamps them
into the LiveKit room metadata. The app never writes room metadata directly, so
a compromised client cannot inject arbitrary configuration.

.. warning::
   ``apply_overrides`` accepts a ``provider_api_key`` entry so that a user can
   bring their own AI key. Only ever call it with data the *server* produced.
   Never pass a client-controlled JSON body straight into it.
"""

from __future__ import annotations

import os
import secrets
from dataclasses import dataclass, field, replace
from typing import Any, Mapping

__all__ = [
    "AI_PROVIDERS",
    "CARTESIA_SPEED_RANGE",
    "DEFAULT_CARTESIA_LANGUAGE",
    "DEFAULT_CARTESIA_MODEL",
    "DEFAULT_CARTESIA_VOICE",
    "DEFAULT_LANGUAGES",
    "DEFAULT_MODULAR_LLM_MODELS",
    "STT_PROVIDERS",
    "TTS_PROVIDERS",
    "VOICE_MODES",
    "LANGUAGE_NAMES",
    "OVERRIDABLE_KEYS",
    "SUPPORTED_LANGUAGES",
    "Settings",
    "SettingsError",
    "redact",
]


class SettingsError(RuntimeError):
    """Raised when the environment is missing required configuration."""


# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

#: Providers that can serve the **LLM** half of the modular pipeline, or the
#: whole conversation in realtime mode. ``ai_provider`` selects among these.
AI_PROVIDERS: tuple[str, ...] = ("gemini", "openai")

#: How Aladin speaks.
#:
#: ``modular``   STT -> LLM -> TTS. The pipeline in :mod:`aladin.voice_pipeline`.
#: ``realtime``  The existing speech-to-speech realtime model (Gemini Live).
#:
#: Kept as two modes rather than one code path because they are genuinely
#: different architectures: a realtime model owns both halves of the audio and
#: does its own turn detection, while the modular pipeline needs an explicit
#: STT, an explicit TTS and a VAD.
VOICE_MODES: tuple[str, ...] = ("modular", "realtime")

#: Providers that can render speech in the modular pipeline.
#: Cartesia is the primary; the others exist so a session can still speak when
#: the primary is unavailable.
TTS_PROVIDERS: tuple[str, ...] = ("cartesia", "gemini", "openai")

#: Providers that can transcribe speech. ``auto`` resolves at runtime from the
#: credentials that are actually present (see ``resolved_stt_provider``).
STT_PROVIDERS: tuple[str, ...] = ("auto", "cartesia", "gemini", "openai")

#: Text-only models used by the modular pipeline's LLM stage. The realtime
#: models in ``GEMINI_API_MODELS`` emit *audio*, which is exactly what the
#: modular pipeline must not use for its LLM stage.
DEFAULT_MODULAR_LLM_MODELS: Mapping[str, str] = {
    "gemini": "gemini-2.5-flash",
    "openai": "gpt-4o-mini",
}

DEFAULT_CARTESIA_MODEL = "sonic-3"
#: Cartesia's own plugin default ("Katie - Friendly Fixer"), so an operator who
#: configures nothing gets the voice the SDK would have chosen anyway.
DEFAULT_CARTESIA_VOICE = "f786b574-daa5-4673-aa0c-cbe3e8534c02"
DEFAULT_CARTESIA_LANGUAGE = "en"
#: Cartesia's documented speed range, exposed as a safe subset.
CARTESIA_SPEED_RANGE: tuple[float, float] = (0.6, 1.5)

#: Languages Aladin V1 is tuned for. Gemini Live supports all three natively,
#: including mid-conversation switching.
SUPPORTED_LANGUAGES: tuple[str, ...] = ("bn", "en", "hi")

DEFAULT_LANGUAGES: tuple[str, ...] = SUPPORTED_LANGUAGES

#: Human-readable names, injected into the system prompt.
LANGUAGE_NAMES: Mapping[str, str] = {
    "bn": "Bangla (Bengali, বাংলা)",
    "en": "English",
    "hi": "Hindi (हिन्दी)",
}

#: Gemini Live models reachable with a Google AI Studio API key.
#: Mirrors KNOWN_GEMINI_API_MODELS in livekit-plugins-google 1.8.x, which
#: rejects a model name used against the wrong Google API surface.
GEMINI_API_MODELS: tuple[str, ...] = (
    "gemini-2.5-flash-native-audio-preview-12-2025",
    "gemini-3.8-live",
    "gemini-3.8-live-extended-thinking",
    "gemini-3.1-flash-live-preview",
)

#: Gemini Live models that require Vertex AI credentials (no API key).
GEMINI_VERTEX_MODELS: tuple[str, ...] = ("gemini-live-2.5-flash-native-audio",)

#: Kept for backwards-compatible imports.
GEMINI_MODELS: tuple[str, ...] = GEMINI_API_MODELS + GEMINI_VERTEX_MODELS

#: Default for the API-key path. Vertex-only models cannot be used here: the
#: plugin raises "is a VertexAI model, but vertexai=False".
DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
DEFAULT_OPENAI_MODEL = "gpt-realtime"

DEFAULT_GEMINI_VOICE = "Puck"
DEFAULT_OPENAI_VOICE = "marin"

#: Keys that may be overridden at session start. Anything outside this set is
#: ignored, so a call cannot silently reconfigure the worker.
OVERRIDABLE_KEYS: frozenset[str] = frozenset(
    {
        "ai_provider",
        "model",
        "voice",
        # Voice architecture and the modular pipeline's independent selections.
        # None of these is a secret: they only name which provider is used,
        # never a credential for it.
        "voice_mode",
        "llm_model",
        "tts_provider",
        "stt_provider",
        "cartesia_model",
        "cartesia_voice",
        "cartesia_language",
        "cartesia_speed",
        "languages",
        "web_search",
        "instructions_extra",
        "temperature",
        "thinking",
        "affective_dialog",
        "proactivity",
        "noise_cancellation",
        "greeting",
        "captions",
        "provider_api_key",
        # Which provider a ``provider_api_key`` override belongs to. Needed
        # because a session can carry two keys (one reasoning, one voice), and
        # the key must land in the right field rather than in whichever one the
        # provider selection happens to imply.
        "key_provider",
    }
)

#: Where a brought-along key lands, by the provider it belongs to.
#: ``cartesia`` is here because Cartesia speaks in the modular pipeline, so its
#: key travels the same authenticated channel as the reasoning key.
_API_KEY_FIELD: Mapping[str, str] = {
    "gemini": "gemini_api_key",
    "openai": "openai_api_key",
    "cartesia": "cartesia_api_key",
}

_TRUTHY = frozenset({"1", "true", "yes", "on", "y", "t"})
_FALSY = frozenset({"0", "false", "no", "off", "n", "f", ""})


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------


def redact(value: str | None) -> str:
    """
    Render a secret for logs without leaking it.

    Returns ``"<unset>"`` for empty values so logs distinguish "not configured"
    from "configured but hidden" — a distinction that saves a lot of debugging
    time.
    """
    if not value:
        return "<unset>"
    if len(value) <= 8:
        return "*" * len(value)
    return f"{value[:4]}…{value[-2:]} (len={len(value)})"


def _get(env: Mapping[str, str], key: str, default: str = "") -> str:
    raw = env.get(key)
    return default if raw is None else raw.strip()


def _get_bool(env: Mapping[str, str], key: str, default: bool) -> bool:
    raw = env.get(key)
    if raw is None:
        return default
    normalised = raw.strip().lower()
    if normalised in _TRUTHY:
        return True
    if normalised in _FALSY:
        return False
    # Unknown value: keep the safer default rather than guessing.
    return default


def _get_int(env: Mapping[str, str], key: str, default: int) -> int:
    raw = env.get(key, "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise SettingsError(f"{key} must be an integer, got {raw!r}") from exc


def _choice(
    env: Mapping[str, str], key: str, allowed: tuple[str, ...], default: str
) -> str:
    """
    Read a value that must be one of ``allowed``.

    Unknown values raise rather than silently falling back: a typo in
    ``ALADIN_TTS_PROVIDER`` that quietly ran Gemini instead of Cartesia would be
    exactly the kind of "why is my voice wrong" bug this codebase avoids.
    """
    raw = _get(env, key, default).lower()
    if raw not in allowed:
        raise SettingsError(
            f"{key} must be one of {', '.join(allowed)}; got {raw!r}"
        )
    return raw


def _get_optional_float(env: Mapping[str, str], key: str) -> float | None:
    raw = env.get(key, "").strip()
    if not raw:
        return None
    try:
        return float(raw)
    except ValueError as exc:
        raise SettingsError(f"{key} must be a number, got {raw!r}") from exc


def parse_languages(raw: str | Any) -> tuple[str, ...]:
    """
    Normalise a language specification into a validated tuple.

    Accepts ``"bn,en,hi"``, ``"bn en hi"``, or a list. Unknown codes raise
    rather than silently degrading, because a silently-dropped language is a
    bug the user only notices mid-conversation.
    """
    if raw is None or raw == "":
        return DEFAULT_LANGUAGES

    if isinstance(raw, str):
        parts = [p.strip().lower() for p in raw.replace(";", ",").split(",")]
        # Also tolerate space-separated input.
        if len(parts) == 1 and " " in parts[0]:
            parts = [p for p in parts[0].split() if p]
    elif isinstance(raw, (list, tuple, set, frozenset)):
        parts = [str(p).strip().lower() for p in raw]
    else:
        raise SettingsError(f"cannot interpret languages value: {raw!r}")

    codes: list[str] = []
    for part in parts:
        if not part:
            continue
        # Allow "bn-BD" style tags by taking the primary subtag.
        primary = part.split("-")[0]
        if primary not in SUPPORTED_LANGUAGES:
            raise SettingsError(
                f"unsupported language {part!r}; "
                f"Aladin V1 supports {', '.join(SUPPORTED_LANGUAGES)}"
            )
        if primary not in codes:
            codes.append(primary)

    if not codes:
        raise SettingsError("no languages selected; pick at least one of bn, en, hi")

    return tuple(codes)


def _clamp_cartesia_speed(value: float) -> float:
    """Clamp Cartesia speed into the range its API documents."""
    low, high = CARTESIA_SPEED_RANGE
    return max(low, min(high, float(value)))


def clamp_temperature(value: float | None) -> float | None:
    """Clamp temperature into the 0.0–1.0 range the realtime models accept."""
    if value is None:
        return None
    return max(0.0, min(1.0, float(value)))


# --------------------------------------------------------------------------
# Settings
# --------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Settings:
    """Immutable snapshot of backend configuration."""

    # --- LiveKit transport -------------------------------------------------
    livekit_url: str = ""
    livekit_api_key: str = ""
    livekit_api_secret: str = ""

    # --- Provider selection ------------------------------------------------
    ai_provider: str = "gemini"
    gemini_api_key: str = ""
    openai_api_key: str = ""

    # --- Model / voice -----------------------------------------------------
    model: str = ""
    voice: str = ""
    temperature: float | None = None

    # --- Voice architecture ------------------------------------------------
    #: ``modular`` (STT -> LLM -> TTS, Cartesia speaking by default) or
    #: ``realtime`` (the original speech-to-speech path).
    voice_mode: str = "modular"
    #: Text-only model for the modular pipeline's LLM stage. Empty means the
    #: per-provider default in ``DEFAULT_MODULAR_LLM_MODELS``. Deliberately
    #: separate from ``model``: ``model`` names a *realtime* model, and reusing
    #: it would silently try to build a text LLM from an audio model name.
    llm_model: str = ""
    #: Which provider speaks. Primary is Cartesia.
    tts_provider: str = "cartesia"
    #: Which provider transcribes. ``auto`` picks from available credentials.
    stt_provider: str = "auto"

    # --- Cartesia (primary TTS) --------------------------------------------
    #: A user's Cartesia key normally arrives per session over the
    #: internal-token channel; this field is the server-side fallback for a
    #: self-hosted deployment. Never logged, never sent to the app.
    cartesia_api_key: str = ""
    cartesia_model: str = ""
    cartesia_voice: str = ""
    cartesia_language: str = DEFAULT_CARTESIA_LANGUAGE
    #: Optional prosody. ``None`` leaves Cartesia's own default in place.
    cartesia_speed: float | None = None

    # --- Conversation ------------------------------------------------------
    languages: tuple[str, ...] = DEFAULT_LANGUAGES
    instructions_extra: str = ""

    # --- Capabilities ------------------------------------------------------
    web_search: bool = True
    tavily_api_key: str = ""
    brave_search_api_key: str = ""

    # --- Agent behaviour ---------------------------------------------------
    noise_cancellation: bool = True
    session_resumption: bool = True
    affective_dialog: bool = False
    proactivity: bool = False
    thinking: bool = False
    greeting: bool = True
    agent_name: str = ""
    captions: bool = True

    # --- Bring your own key ------------------------------------------------
    #: Whether the app may hand this server a provider key for its session.
    allow_byok: bool = True
    #: Shared secret the *agent* uses to fetch a stored key. Deliberately a
    #: different secret from ``device_token``: the app must never be able to
    #: read keys, only to register its own.
    internal_token: str = ""
    session_key_ttl_seconds: int = 900
    session_key_max_entries: int = 500
    #: Where the *agent* reaches the token API. Not the same thing as
    #: ``token_api_host``, which is the address the API binds to: a server
    #: listening on 0.0.0.0 is still reached over a concrete host.
    token_api_url: str = "http://127.0.0.1:8080"

    # --- Token API ---------------------------------------------------------
    token_api_host: str = "0.0.0.0"
    token_api_port: int = 8080
    device_token: str = ""
    token_ttl_seconds: int = 900
    empty_room_timeout_seconds: int = 300
    allowed_origins: tuple[str, ...] = (
        "http://localhost:3000",
        "http://127.0.0.1:3000",
    )

    # --- App update delivery ----------------------------------------------
    github_release_token: str = ""
    github_release_repo: str = "SojiBhuiya/Aladin"
    github_release_asset: str = "Aladin-release.apk"
    # Oldest app version this backend still supports, e.g. "0.1.0". Empty means
    # every version is supported, which is the safe default: a wrong value here
    # tells users their build is obsolete.
    app_min_supported_version: str = ""

    # --- Logging -----------------------------------------------------------
    log_level: str = "INFO"
    log_transcripts: bool = True

    # ---------------------------------------------------------------- env ---

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "Settings":
        """Build a :class:`Settings` from environment variables."""
        e: Mapping[str, str] = os.environ if env is None else env

        provider = _get(e, "ALADIN_AI_PROVIDER", "gemini").lower()
        if provider not in AI_PROVIDERS:
            raise SettingsError(
                f"ALADIN_AI_PROVIDER must be one of {', '.join(AI_PROVIDERS)}; "
                f"got {provider!r}"
            )

        origins_raw = _get(
            e, "ALADIN_ALLOWED_ORIGINS", "http://localhost:3000,http://127.0.0.1:3000"
        )
        origins = tuple(o.strip() for o in origins_raw.split(",") if o.strip())

        return cls(
            livekit_url=_get(e, "LIVEKIT_URL"),
            livekit_api_key=_get(e, "LIVEKIT_API_KEY"),
            livekit_api_secret=_get(e, "LIVEKIT_API_SECRET"),
            ai_provider=provider,
            # GOOGLE_API_KEY is the name LiveKit's own docs use; accept both.
            gemini_api_key=_get(e, "GEMINI_API_KEY") or _get(e, "GOOGLE_API_KEY"),
            openai_api_key=_get(e, "OPENAI_API_KEY"),
            model=_get(e, "ALADIN_MODEL"),
            voice=_get(e, "ALADIN_VOICE"),
            temperature=clamp_temperature(_get_optional_float(e, "ALADIN_TEMPERATURE")),
            voice_mode=_choice(e, "ALADIN_VOICE_MODE", VOICE_MODES, "modular"),
            llm_model=_get(e, "ALADIN_LLM_MODEL"),
            tts_provider=_choice(e, "ALADIN_TTS_PROVIDER", TTS_PROVIDERS, "cartesia"),
            stt_provider=_choice(e, "ALADIN_STT_PROVIDER", STT_PROVIDERS, "auto"),
            cartesia_api_key=_get(e, "CARTESIA_API_KEY"),
            cartesia_model=_get(e, "ALADIN_CARTESIA_MODEL"),
            cartesia_voice=_get(e, "ALADIN_CARTESIA_VOICE"),
            cartesia_language=(
                _get(e, "ALADIN_CARTESIA_LANGUAGE") or DEFAULT_CARTESIA_LANGUAGE
            ),
            cartesia_speed=_get_optional_float(e, "ALADIN_CARTESIA_SPEED"),
            languages=parse_languages(_get(e, "ALADIN_LANGUAGES")),
            instructions_extra=_get(e, "ALADIN_INSTRUCTIONS_EXTRA"),
            web_search=_get_bool(e, "ALADIN_WEB_SEARCH", True),
            tavily_api_key=_get(e, "TAVILY_API_KEY"),
            brave_search_api_key=_get(e, "BRAVE_SEARCH_API_KEY"),
            noise_cancellation=_get_bool(e, "ALADIN_NOISE_CANCELLATION", True),
            session_resumption=_get_bool(e, "ALADIN_SESSION_RESUMPTION", True),
            affective_dialog=_get_bool(e, "ALADIN_AFFECTIVE_DIALOG", False),
            proactivity=_get_bool(e, "ALADIN_PROACTIVITY", False),
            thinking=_get_bool(e, "ALADIN_THINKING", False),
            agent_name=_get(e, "ALADIN_AGENT_NAME"),
            captions=_get_bool(e, "ALADIN_CAPTIONS", True),
            allow_byok=_get_bool(e, "ALADIN_ALLOW_BYOK", True),
            internal_token=_get(e, "ALADIN_INTERNAL_TOKEN"),
            session_key_ttl_seconds=_get_int(
                e, "ALADIN_SESSION_KEY_TTL_SECONDS", 900
            ),
            session_key_max_entries=_get_int(
                e, "ALADIN_SESSION_KEY_MAX_ENTRIES", 500
            ),
            token_api_url=_get(e, "ALADIN_TOKEN_API_URL", "http://127.0.0.1:8080"),
            token_api_host=_get(e, "ALADIN_TOKEN_API_HOST", "0.0.0.0"),
            token_api_port=_get_int(e, "ALADIN_TOKEN_API_PORT", 8080),
            device_token=_get(e, "ALADIN_DEVICE_TOKEN"),
            token_ttl_seconds=_get_int(e, "ALADIN_TOKEN_TTL_SECONDS", 900),
            empty_room_timeout_seconds=_get_int(
                e, "ALADIN_EMPTY_ROOM_TIMEOUT_SECONDS", 300
            ),
            allowed_origins=origins,
            github_release_token=_get(e, "ALADIN_GITHUB_RELEASE_TOKEN"),
            github_release_repo=_get(e, "ALADIN_GITHUB_RELEASE_REPO", "SojiBhuiya/Aladin"),
            github_release_asset=_get(e, "ALADIN_GITHUB_RELEASE_ASSET", "Aladin-release.apk"),
            app_min_supported_version=_get(e, "ALADIN_APP_MIN_SUPPORTED_VERSION"),
            log_level=_get(e, "ALADIN_LOG_LEVEL", "INFO").upper(),
            log_transcripts=_get_bool(e, "ALADIN_LOG_TRANSCRIPTS", True),
        )

    # ----------------------------------------------------------- overrides ---

    def apply_overrides(self, overrides: Mapping[str, Any] | None) -> "Settings":
        """
        Return a copy with validated runtime overrides applied.

        Unknown keys are ignored; invalid values raise :class:`SettingsError`
        so a bad push is visible immediately instead of half-applied.

        .. warning::
           ``overrides`` must be **server-authored** (LiveKit room metadata set
           by the token server). Never pass untrusted client input here.
        """
        if not overrides:
            return self

        unknown = set(overrides) - OVERRIDABLE_KEYS
        if unknown:
            raise SettingsError(
                f"unknown override keys: {', '.join(sorted(unknown))}"
            )

        changes: dict[str, Any] = {}

        if "ai_provider" in overrides and overrides["ai_provider"]:
            provider = str(overrides["ai_provider"]).strip().lower()
            if provider not in AI_PROVIDERS:
                raise SettingsError(
                    f"ai_provider must be one of {', '.join(AI_PROVIDERS)}; "
                    f"got {provider!r}"
                )
            changes["ai_provider"] = provider

        if "model" in overrides and overrides["model"]:
            changes["model"] = str(overrides["model"]).strip()

        if "voice_mode" in overrides and overrides["voice_mode"]:
            mode = str(overrides["voice_mode"]).strip().lower()
            if mode not in VOICE_MODES:
                raise SettingsError(
                    f"voice_mode must be one of {', '.join(VOICE_MODES)}; "
                    f"got {mode!r}"
                )
            changes["voice_mode"] = mode

        for key, allowed in (
            ("tts_provider", TTS_PROVIDERS),
            ("stt_provider", STT_PROVIDERS),
        ):
            if key in overrides and overrides[key]:
                value = str(overrides[key]).strip().lower()
                if value not in allowed:
                    raise SettingsError(
                        f"{key} must be one of {', '.join(allowed)}; got {value!r}"
                    )
                changes[key] = value

        if "llm_model" in overrides and overrides["llm_model"]:
            changes["llm_model"] = str(overrides["llm_model"]).strip()

        for key in ("cartesia_model", "cartesia_voice", "cartesia_language"):
            if key in overrides and overrides[key]:
                changes[key] = str(overrides[key]).strip()

        if "cartesia_speed" in overrides and overrides["cartesia_speed"] is not None:
            try:
                changes["cartesia_speed"] = _clamp_cartesia_speed(
                    float(overrides["cartesia_speed"])
                )
            except (TypeError, ValueError) as exc:
                raise SettingsError(
                    f"cartesia_speed must be numeric: {exc}"
                ) from exc

        if "voice" in overrides and overrides["voice"]:
            changes["voice"] = str(overrides["voice"]).strip()

        if "languages" in overrides and overrides["languages"]:
            changes["languages"] = parse_languages(overrides["languages"])

        if "instructions_extra" in overrides:
            changes["instructions_extra"] = str(overrides["instructions_extra"]).strip()

        if "temperature" in overrides and overrides["temperature"] is not None:
            try:
                changes["temperature"] = clamp_temperature(
                    float(overrides["temperature"])
                )
            except (TypeError, ValueError) as exc:
                raise SettingsError(f"temperature must be numeric: {exc}") from exc

        for key in (
            "web_search",
            "thinking",
            "affective_dialog",
            "proactivity",
            "noise_cancellation",
            "greeting",
            "captions",
        ):
            if key in overrides and overrides[key] is not None:
                changes[key] = bool(overrides[key])

        # Bring-your-own-key path. Routes to whichever provider is selected.
        # One key per (room, provider): the LLM key and the Cartesia key are
        # separate credentials and are applied separately, so selecting
        # Cartesia for speech never displaces the reasoning key.
        if "provider_api_key" in overrides and overrides["provider_api_key"]:
            key = str(overrides["provider_api_key"]).strip()
            target = str(
                overrides.get("key_provider")
                or changes.get("ai_provider", self.ai_provider)
            ).strip().lower()
            changes[_API_KEY_FIELD.get(target, "openai_api_key")] = key

        return replace(self, **changes) if changes else self

    # ------------------------------------------------------------ derived ---

    @property
    def resolved_model(self) -> str:
        """Configured model, or the default for the selected provider."""
        if self.model:
            return self.model
        return (
            DEFAULT_GEMINI_MODEL
            if self.ai_provider == "gemini"
            else DEFAULT_OPENAI_MODEL
        )

    @property
    def resolved_voice(self) -> str:
        """Configured voice, or the default for the selected provider."""
        if self.voice:
            return self.voice
        return (
            DEFAULT_GEMINI_VOICE
            if self.ai_provider == "gemini"
            else DEFAULT_OPENAI_VOICE
        )

    @property
    def resolved_llm_model(self) -> str:
        """
        The text model the modular pipeline's LLM stage should use.

        Falls back to ``DEFAULT_MODULAR_LLM_MODELS``, which is a *text* model in
        every case: a realtime audio model would be the wrong thing to hand a
        text pipeline, and the two model fields are kept separate for exactly
        that reason.
        """
        if self.llm_model:
            return self.llm_model
        return DEFAULT_MODULAR_LLM_MODELS.get(self.ai_provider, "gemini-2.5-flash")

    @property
    def resolved_cartesia_model(self) -> str:
        """Cartesia model, or the plugin's own default."""
        return self.cartesia_model or DEFAULT_CARTESIA_MODEL

    @property
    def resolved_cartesia_voice(self) -> str:
        """Cartesia voice id, or the plugin's own default."""
        return self.cartesia_voice or DEFAULT_CARTESIA_VOICE

    @property
    def resolved_cartesia_language(self) -> str:
        """Cartesia language code, or the plugin's own default."""
        return self.cartesia_language or DEFAULT_CARTESIA_LANGUAGE

    @property
    def resolved_stt_provider(self) -> str:
        """
        Which provider actually transcribes this session.

        ``auto`` resolves against the credentials that are really present,
        preferring the provider that is already needed for speech: a deployment
        whose only extra key is Cartesia then needs no *second* key just to hear
        the user. The order is Cartesia, then the selected LLM provider (its key
        is already required), then nothing.

        Returns ``""`` when nothing can transcribe — the caller reports that
        rather than silently starting a pipeline that cannot hear.
        """
        if self.stt_provider != "auto":
            return self.stt_provider
        if self.cartesia_api_key:
            return "cartesia"
        if self.provider_api_key:
            return self.ai_provider
        return ""

    @property
    def provider_api_key(self) -> str:
        """The API key belonging to the currently selected provider."""
        return (
            self.gemini_api_key
            if self.ai_provider == "gemini"
            else self.openai_api_key
        )

    @property
    def byok_disabled_reason(self) -> str | None:
        """
        Why bring-your-own-key is unavailable, or ``None`` when it works.

        A reason rather than a bare boolean, because this decision is reported
        to the app (``/v1/config``) *and* logged at startup: "BYOK is off" with
        no explanation is the kind of thing that costs an afternoon.
        """
        if not self.allow_byok:
            return "ALADIN_ALLOW_BYOK is false"
        if not self.internal_token:
            return (
                "ALADIN_INTERNAL_TOKEN is not set — the agent has no way to "
                "fetch a stored key"
            )
        if self.device_token and secrets.compare_digest(
            self.internal_token, self.device_token
        ):
            # Distinct secrets, distinct powers: the device token is held by
            # every app install, the internal token only by the agent. Sharing
            # one value would let a client read keys back out.
            return "ALADIN_INTERNAL_TOKEN must differ from ALADIN_DEVICE_TOKEN"
        return None

    @property
    def byok_enabled(self) -> bool:
        """Whether the app can bring its own key for a session."""
        return self.byok_disabled_reason is None

    @property
    def has_web_search_credentials(self) -> bool:
        """True when web search can actually run for the selected provider."""
        if not self.web_search:
            return False
        if self.ai_provider == "gemini":
            # Uses Gemini's built-in Google Search grounding: no extra key.
            return True
        return bool(self.tavily_api_key or self.brave_search_api_key)

    # ---------------------------------------------------------- validation ---

    def missing_for_agent(self) -> list[str]:
        """Names of required variables the agent worker still needs."""
        missing: list[str] = []
        if not self.livekit_url:
            missing.append("LIVEKIT_URL")
        if not self.livekit_api_key:
            missing.append("LIVEKIT_API_KEY")
        if not self.livekit_api_secret:
            missing.append("LIVEKIT_API_SECRET")
        if not self.provider_api_key:
            missing.append(
                "GEMINI_API_KEY" if self.ai_provider == "gemini" else "OPENAI_API_KEY"
            )
        return missing

    def missing_for_token_api(self) -> list[str]:
        """Names of required variables the token API still needs."""
        missing: list[str] = []
        if not self.livekit_url:
            missing.append("LIVEKIT_URL")
        if not self.livekit_api_key:
            missing.append("LIVEKIT_API_KEY")
        if not self.livekit_api_secret:
            missing.append("LIVEKIT_API_SECRET")
        if not self.device_token:
            missing.append(
                "ALADIN_DEVICE_TOKEN "
                "(generate: python -c \"import secrets; print(secrets.token_urlsafe(32))\")"
            )
        return missing

    def require_for_agent(self) -> None:
        """Raise :class:`SettingsError` if the agent worker cannot start."""
        missing = self.missing_for_agent()
        if missing:
            raise SettingsError(
                "Aladin agent cannot start — missing configuration: "
                + ", ".join(missing)
                + "\nCopy .env.example to .env and fill these in."
            )

    def require_for_token_api(self) -> None:
        """Raise :class:`SettingsError` if the token API cannot start."""
        missing = self.missing_for_token_api()
        if missing:
            raise SettingsError(
                "Aladin token API cannot start — missing configuration: "
                + ", ".join(missing)
                + "\nCopy .env.example to .env and fill these in."
            )

    # -------------------------------------------------------------- logs ---

    def redacted(self) -> dict[str, Any]:
        """A log-safe view of this configuration. Contains no raw secrets."""
        return {
            "livekit_url": self.livekit_url or "<unset>",
            "livekit_api_key": redact(self.livekit_api_key),
            "livekit_api_secret": redact(self.livekit_api_secret),
            "ai_provider": self.ai_provider,
            "gemini_api_key": redact(self.gemini_api_key),
            "openai_api_key": redact(self.openai_api_key),
            # Cartesia is a credential like any other: the describe dict says
            # whether a key is present, never what it is.
            "cartesia_api_key": redact(self.cartesia_api_key),
            "model": self.resolved_model,
            "voice": self.resolved_voice,
            "voice_mode": self.voice_mode,
            "tts_provider": self.tts_provider,
            "stt_provider": self.resolved_stt_provider or "<unavailable>",
            "llm_model": self.resolved_llm_model,
            "cartesia_model": self.resolved_cartesia_model,
            "cartesia_voice": self.resolved_cartesia_voice,
            "cartesia_language": self.resolved_cartesia_language,
            "cartesia_speed": self.cartesia_speed,
            "temperature": self.temperature,
            "languages": list(self.languages),
            "web_search": self.web_search,
            "tavily_api_key": redact(self.tavily_api_key),
            "brave_search_api_key": redact(self.brave_search_api_key),
            "noise_cancellation": self.noise_cancellation,
            "session_resumption": self.session_resumption,
            "affective_dialog": self.affective_dialog,
            "proactivity": self.proactivity,
            "thinking": self.thinking,
            "agent_name": self.agent_name or "<auto-dispatch>",
            "captions": self.captions,
            "device_token": redact(self.device_token),
            "internal_token": redact(self.internal_token),
            "byok_enabled": self.byok_enabled,
            "byok_disabled_reason": self.byok_disabled_reason or "",
            "session_key_ttl_seconds": self.session_key_ttl_seconds,
            "session_key_max_entries": self.session_key_max_entries,
            "token_api_url": self.token_api_url,
            "token_ttl_seconds": self.token_ttl_seconds,
            "github_release_token": redact(self.github_release_token),
            "github_release_repo": self.github_release_repo,
            "github_release_asset": self.github_release_asset,
            "app_min_supported_version": self.app_min_supported_version,
            "log_level": self.log_level,
            "log_transcripts": self.log_transcripts,
        }


def generate_device_token() -> str:
    """Generate a cryptographically strong device token for ``.env``."""
    return secrets.token_urlsafe(32)
