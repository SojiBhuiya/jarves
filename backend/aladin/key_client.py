"""
Client the agent worker uses to fetch (and then wipe) a user's session key.

The agent never receives a key through LiveKit: room metadata and dispatch
metadata are both visible to every participant. Instead it asks the token API
directly, over a server-to-server channel authenticated with the *internal*
token — a secret that exists only on the server and the agent host.

Why this is a separate module
-----------------------------
Fetch policy is the part that has to be right, and it is easier to get right
when it is not tangled up with the LiveKit session callback:

* **A missing key is not an error.** Most sessions do not use bring-your-own-key
  at all, so a 404 is the ordinary case and must be quiet.
* **A key that has not arrived yet is worth a short wait.** The app registers
  its key and *then* joins the room, but a self-hosted deployment can dispatch
  an agent into a room the moment it is created. Retrying briefly closes that
  race without making every session slower.
* **A failure is never fatal to the session.** If the token API is down, the
  agent falls back to the server's own key and says so; a voice assistant that
  refuses to start because a *backup* credential was unavailable would be a
  worse product.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Mapping
from dataclasses import dataclass, field

logger = logging.getLogger("aladin.key_client")

#: Whole seconds to wait for a key that has not arrived yet.
DEFAULT_LOOKUP_TIMEOUT = 3.0
#: Delay between lookups while waiting.
DEFAULT_LOOKUP_INTERVAL = 0.5
#: Per-request timeout. The token API is on the same host or the same private
#: network; anything slower than this is a problem worth failing over.
DEFAULT_REQUEST_TIMEOUT = 5.0


class SessionKeyError(RuntimeError):
    """Raised when the token API could not be asked about a session key."""


@dataclass(frozen=True, slots=True)
class FetchedKey:
    """
    The credentials the token API released for one room.

    A session may need more than one: the modular pipeline reasons with one
    provider and speaks with another, so the answer is "here is that room's
    configuration" rather than "here is the key". ``api_key`` stays the primary
    (the reasoning provider, as it always was) and ``credentials`` holds the
    rest, keyed by provider.
    """

    room: str
    provider: str
    api_key: str
    expires_in: int
    #: Additional provider credentials for this session, e.g.
    #: ``{"cartesia": "…"}``. Never logged, never printed.
    credentials: Mapping[str, str] = field(default_factory=dict)

    def key_for(self, provider: str) -> str | None:
        """The fetched key for ``provider``, or ``None`` if this room has none."""
        normalised = (provider or "").strip().lower()
        if normalised == self.provider:
            return self.api_key
        return self.credentials.get(normalised)

    @property
    def provider_names(self) -> tuple[str, ...]:
        """Every provider this fetch carries a credential for."""
        return (self.provider, *sorted(self.credentials))

    def __repr__(self) -> str:
        """Secret-free, like every other representation in this flow."""
        return (
            f"FetchedKey(room={self.room!r}, provider={self.provider!r}, "
            f"api_key=<redacted len={len(self.api_key)}>, "
            f"credentials=<{', '.join(sorted(self.credentials)) or 'none'}>, "
            f"expires_in={self.expires_in}s)"
        )

    __str__ = __repr__


class SessionKeyClient:
    """
    Minimal async client for the token API's session-key endpoints.

    ``base_url`` is the address the *agent* reaches the API on, which is not
    necessarily the address the API binds to: a server listening on
    ``0.0.0.0:8080`` is reached over a concrete host.
    """

    def __init__(
        self,
        *,
        base_url: str,
        internal_token: str,
        request_timeout: float = DEFAULT_REQUEST_TIMEOUT,
        session: object | None = None,
    ) -> None:
        self._base_url = (base_url or "").rstrip("/")
        self._internal_token = internal_token or ""
        self._request_timeout = request_timeout
        #: Injectable so tests can supply a fake transport.
        self._session = session

    @property
    def is_configured(self) -> bool:
        """Whether this client has everything it needs to make a request."""
        return bool(self._base_url and self._internal_token)

    def _headers(self) -> dict[str, str]:
        return {"X-Aladin-Internal-Token": self._internal_token}

    async def _request(self, method: str, path: str) -> tuple[int, dict]:
        """Perform one request. Returns (status, json-body-or-empty)."""
        if not self.is_configured:
            raise SessionKeyError(
                "session key client is not configured (needs a token API URL "
                "and ALADIN_INTERNAL_TOKEN)"
            )

        url = f"{self._base_url}{path}"

        if self._session is not None:
            return await self._request_with(self._session, method, url)

        import aiohttp

        try:
            async with aiohttp.ClientSession() as session:
                return await self._request_with(session, method, url)
        except SessionKeyError:
            raise
        except Exception as exc:
            raise SessionKeyError(f"token API unreachable: {exc}") from exc

    async def _request_with(self, session: object, method: str, url: str) -> tuple[int, dict]:
        """Run a request against an object that looks like an aiohttp session."""
        import aiohttp

        try:
            async with session.request(  # type: ignore[attr-defined]
                method,
                url,
                headers=self._headers(),
                timeout=aiohttp.ClientTimeout(total=self._request_timeout),
            ) as response:
                status = int(response.status)
                if status == 404:
                    return status, {}
                if status >= 400:
                    # Read the body for the reason, but never log it blindly:
                    # an error body could echo something sensitive. The token
                    # API scrubs credentials from its own errors, and the
                    # fallback below truncates whatever else arrives.
                    body = await response.text()
                    raise SessionKeyError(
                        f"token API returned {status}: {body[:200]}"
                    )
                data = await response.json()
                return status, data if isinstance(data, dict) else {}
        except SessionKeyError:
            raise
        except Exception as exc:
            raise SessionKeyError(f"token API request failed: {exc}") from exc

    # ------------------------------------------------------------------ api ---

    async def fetch(self, room: str) -> FetchedKey | None:
        """
        Fetch the key registered for ``room``.

        Returns ``None`` when no key is registered — the normal case for a
        session that uses the server's own credentials. Raises
        :class:`SessionKeyError` when the API could not be asked.
        """
        status, data = await self._request("GET", f"/v1/session/keys/{room}")
        if status == 404:
            return None

        api_key = data.get("api_key")
        if not isinstance(api_key, str) or not api_key:
            raise SessionKeyError("token API returned no usable key")

        # Additional provider credentials (e.g. the Cartesia key for this
        # session's voice). Absent on an older token API, which is fine: the
        # caller then runs on whatever the server has configured.
        raw_credentials = data.get("credentials")
        credentials: dict[str, str] = {}
        if isinstance(raw_credentials, Mapping):
            for provider, value in raw_credentials.items():
                if isinstance(value, str) and value:
                    credentials[str(provider).strip().lower()] = value

        return FetchedKey(
            room=str(data.get("room") or room),
            provider=str(data.get("provider") or ""),
            api_key=api_key,
            expires_in=int(data.get("expires_in") or 0),
            credentials=credentials,
        )

    async def wait_for_key(
        self,
        room: str,
        *,
        timeout: float = DEFAULT_LOOKUP_TIMEOUT,
        interval: float = DEFAULT_LOOKUP_INTERVAL,
    ) -> FetchedKey | None:
        """
        Fetch, retrying briefly while the answer is "not yet".

        The app registers its key before joining the room, so a miss usually
        means the agent got there first — a ``wait_for_key`` that gives up
        immediately would lose that race on a fast dispatch. A miss that
        persists for ``timeout`` seconds is treated as "no key", not as an
        error, so a session without bring-your-own-key never waits long.

        Transport failures are *not* retried: if the API is down, waiting will
        not fix it, and the caller needs to know in order to fall back.
        """
        deadline = asyncio.get_running_loop().time() + max(0.0, timeout)
        attempts = 0

        while True:
            attempts += 1
            fetched = await self.fetch(room)
            if fetched is not None:
                if attempts > 1:
                    logger.info(
                        "Session key appeared after %d attempt(s) room=%s", attempts, room
                    )
                return fetched

            if asyncio.get_running_loop().time() >= deadline:
                if attempts > 1:
                    logger.debug(
                        "No session key after %d attempt(s) room=%s", attempts, room
                    )
                return None
            await asyncio.sleep(interval)

    async def delete(self, room: str) -> bool:
        """
        Wipe a key once its session has ended.

        Best-effort by design: the TTL already bounds the key's life, so a
        failure here leaks nothing that time would not have removed anyway. It
        is still worth doing eagerly, because it makes the lifetime "the
        session" rather than "the TTL".
        """
        try:
            _, data = await self._request("DELETE", f"/v1/session/keys/{room}")
        except SessionKeyError as exc:
            logger.warning("Could not delete session key room=%s: %s", room, exc)
            return False
        return bool(data.get("deleted"))
