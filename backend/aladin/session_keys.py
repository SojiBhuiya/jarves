"""
Short-lived, in-memory store for user-supplied AI keys (bring-your-own-key).

The problem this solves
-----------------------
A user's own Gemini or OpenAI key has to reach the *agent worker*, which runs in
a different process from the token API and may run on a different machine
entirely. The obvious channels are both wrong:

* **Room metadata** — LiveKit delivers it to every participant in the room, so
  the app's own transport would carry the key. Anyone in the room could read it.
* **Job metadata at dispatch** — same problem: dispatch metadata travels through
  the LiveKit server and is visible to participants.

So the key travels over its own authenticated channel, once:

.. code-block:: text

   app ──POST /v1/session/keys (device token)──▶ token API
                                                    │  in memory, TTL'd
                                                    ▼  keyed by room
   agent ──GET /v1/session/keys/{room} (internal token)──▶ key
   agent ──DELETE /v1/session/keys/{room} on session end─▶ gone

Properties this store guarantees
--------------------------------
* **Never persisted.** Nothing touches the disk: no file, no database, no log.
  A key exists only as long as the session that needs it *and* the process that
  holds it.
* **Hard eviction.** Entries expire, are deleted when a session ends, and are
  evicted when the store is full — so a client that uploads keys and never
  cleans up cannot grow the process without bound.
* **Scoped to one room.** A key is only ever returned for the room it was
  registered for; there is no "list all keys" operation and no way to enumerate
  rooms.
* **Redacted by construction.** The dataclass that holds the key has a
  ``__repr__`` that never prints it, because the most likely way for a secret to
  leak is a stray log line or traceback.

.. warning::
   This store is per-process. If the token API is scaled to multiple workers or
   replicas, the agent may fetch from a worker that does not hold the key. That
   is a deployment limitation, not a bug: BYOK requires either a single token
   API process or a shared store. The token API logs a warning at startup when
   it is configured in a way that suggests multiple workers. See
   ``docs/SETUP.md``.
"""

from __future__ import annotations

import logging
import secrets
import threading
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Final

logger = logging.getLogger("aladin.session_keys")

#: Refuse absurd input before it reaches the store. Real provider keys are
#: comfortably inside these bounds (Gemini keys are ~39 characters, OpenAI
#: project keys are ~50–160), so a value outside them is a mistake or an
#: attempt to make the process hold something large.
MIN_KEY_LENGTH: Final[int] = 8
MAX_KEY_LENGTH: Final[int] = 512

#: Providers whose keys may be stored. Mirrors the reasoning providers in
#: :data:`aladin.config.AI_PROVIDERS` plus Cartesia, which is the modular
#: pipeline's voice: its key travels the same authenticated channel because the
#: agent needs it at session start just as much as it needs the reasoning key.
#: Kept here rather than imported so this module has no import cycle with config.
ALLOWED_PROVIDERS: Final[tuple[str, ...]] = ("gemini", "openai", "cartesia")


class SessionKeyError(ValueError):
    """Raised when a key cannot be stored. The message never contains the key."""


def _preview(value: str) -> str:
    """A log-safe fingerprint of a secret: length and a short prefix only."""
    if len(value) <= 8:
        return f"len={len(value)}"
    return f"{value[:4]}…len={len(value)}"


def looks_like_transport_secret(candidate: str, *transport_secrets: str) -> bool:
    """
    True when a candidate key is one of the server's own transport secrets.

    A user pasting the LiveKit API secret (or the device token) into the AI-key
    field is a plausible mistake with a nasty consequence: the app would have
    handed the room-token signing secret to a third-party API. Refusing it costs
    nothing and turns a silent vulnerability into a clear error message.

    Compared in constant time so this cannot be used as an oracle.
    """
    for secret in transport_secrets:
        if secret and secrets.compare_digest(candidate, secret):
            return True
    return False


def validate_api_key(candidate: str, *, transport_secrets: tuple[str, ...] = ()) -> str:
    """
    Validate a client-supplied key and return it stripped.

    Raises :class:`SessionKeyError` with a message that never echoes the value.
    """
    if not isinstance(candidate, str):
        raise SessionKeyError("api_key must be a string")

    key = candidate.strip()
    if len(key) < MIN_KEY_LENGTH:
        raise SessionKeyError(
            f"api_key is too short to be a provider key (min {MIN_KEY_LENGTH} characters)"
        )
    if len(key) > MAX_KEY_LENGTH:
        raise SessionKeyError(
            f"api_key is too long (max {MAX_KEY_LENGTH} characters)"
        )
    if any(character.isspace() for character in key):
        # Provider keys never contain whitespace; a pasted line break or a
        # trailing comment means the user copied the wrong thing.
        raise SessionKeyError(
            "api_key contains whitespace — check that the whole key was copied "
            "and nothing else was"
        )
    if looks_like_transport_secret(key, *transport_secrets):
        raise SessionKeyError(
            "that value is a server transport secret, not a provider API key; "
            "it must never leave the server"
        )
    return key


@dataclass(frozen=True, slots=True)
class SessionKey:
    """One stored key, scoped to the room it was registered for."""

    room: str
    provider: str
    #: The secret itself. Never logged, never serialised, never compared with
    #: ``==`` in a log statement.
    api_key: str
    #: ``time.monotonic()``-based deadline, so a wall-clock change cannot
    #: extend a key's life.
    expires_at: float
    #: Credentials for the *other* providers this session needs, keyed by
    #: provider. The modular pipeline runs on more than one provider at once —
    #: Gemini reasons while Cartesia speaks — so "the key for this room" is not
    #: always a single value. The primary ``api_key`` above stays exactly as it
    #: was; this holds only the additional ones. Never logged.
    credentials: Mapping[str, str] = field(default_factory=dict)

    def key_for(self, provider: str) -> str | None:
        """
        The stored key for ``provider`` in this session, or ``None``.

        The primary provider is answered from ``api_key`` so a session that
        brings one key behaves exactly as it always did.
        """
        normalised = (provider or "").strip().lower()
        if normalised == self.provider:
            return self.api_key
        return self.credentials.get(normalised)

    @property
    def provider_names(self) -> tuple[str, ...]:
        """Every provider this session holds a credential for."""
        return (self.provider, *sorted(self.credentials))

    def is_expired(self, *, now: float | None = None) -> bool:
        """Whether this entry's TTL has passed."""
        return (time.monotonic() if now is None else now) >= self.expires_at

    def seconds_remaining(self, *, now: float | None = None) -> int:
        """Whole seconds left before expiry, floored at zero."""
        current = time.monotonic() if now is None else now
        return max(0, int(self.expires_at - current))

    def __repr__(self) -> str:
        """
        Deliberately secret-free.

        ``api_key`` is excluded rather than shown, so a traceback, a debugger
        dump or an over-eager log statement cannot print it.
        """
        return (
            f"SessionKey(room={self.room!r}, provider={self.provider!r}, "
            f"api_key=<redacted {_preview(self.api_key)}>, "
            # Provider names only: the additional credentials are secrets too,
            # so even their values' shapes stay out of the repr.
            f"credentials=<{', '.join(sorted(self.credentials)) or 'none'}>, "
            f"expires_in={self.seconds_remaining()}s)"
        )

    def __str__(self) -> str:
        """See :meth:`__repr__`."""
        return self.__repr__()


class SessionKeyStore:
    """
    Thread-safe, bounded, TTL'd map of ``room -> SessionKey``.

    One entry per room: registering a key for a room that already has one
    replaces it, which is what a user switching provider mid-session expects.
    """

    def __init__(
        self,
        *,
        ttl_seconds: int = 900,
        max_entries: int = 500,
        max_ttl_seconds: int = 3600,
        clock: Callable[[], float] | None = None,
    ) -> None:
        if ttl_seconds < 1:
            raise ValueError("ttl_seconds must be positive")
        if max_entries < 1:
            raise ValueError("max_entries must be positive")
        self._ttl = min(int(ttl_seconds), int(max_ttl_seconds))
        self._max_entries = int(max_entries)
        self._keys: dict[str, SessionKey] = {}
        # A plain lock is enough: every operation is a few dict lookups.
        self._lock = threading.Lock()
        # Injectable so expiry can be tested without sleeping. Monotonic, so a
        # clock adjustment cannot extend a key's life.
        self._clock: Callable[[], float] = clock or time.monotonic

    # ------------------------------------------------------------------ api ---

    @property
    def ttl_seconds(self) -> int:
        """Lifetime of a stored key, in seconds."""
        return self._ttl

    @property
    def max_entries(self) -> int:
        """Upper bound on stored keys. Reaching it evicts the next to expire."""
        return self._max_entries

    def put(
        self,
        *,
        room: str,
        provider: str,
        api_key: str,
        transport_secrets: tuple[str, ...] = (),
        credentials: Mapping[str, str] | None = None,
    ) -> SessionKey:
        """
        Store a key for ``room``, plus any additional provider credentials the
        session needs.

        One entry per room, as before: registering again replaces it, so the
        session that is starting now is the one whose configuration is held.
        ``credentials`` is the additive part — the modular pipeline needs a
        *second* provider (Cartesia speaks while another provider reasons), and
        each of those values is validated exactly as strictly as the primary
        key: shape, provider allowlist, and refusal of the server's own
        transport secrets.

        The caller is responsible for authenticating the request; this method
        only validates shape and provider.

        Raises :class:`SessionKeyError` for anything it cannot store.
        """
        if not room or not room.strip():
            raise SessionKeyError("room must not be empty")

        normalised_provider = (provider or "").strip().lower()
        if normalised_provider not in ALLOWED_PROVIDERS:
            raise SessionKeyError(
                f"provider must be one of {', '.join(ALLOWED_PROVIDERS)}; "
                f"got {normalised_provider!r}"
            )

        validated_extra: dict[str, str] = {}
        for raw_provider, raw_key in (credentials or {}).items():
            extra_provider = str(raw_provider or "").strip().lower()
            if extra_provider == normalised_provider:
                # The primary slot already holds this provider's key; a second
                # copy could only disagree with it.
                raise SessionKeyError(
                    f"{extra_provider!r} appears both as the primary provider "
                    "and in credentials; send each provider's key once"
                )
            if extra_provider not in ALLOWED_PROVIDERS:
                raise SessionKeyError(
                    f"credentials provider must be one of "
                    f"{', '.join(ALLOWED_PROVIDERS)}; got {extra_provider!r}"
                )
            validated_extra[extra_provider] = validate_api_key(
                raw_key, transport_secrets=transport_secrets
            )

        entry = SessionKey(
            room=room.strip(),
            provider=normalised_provider,
            api_key=validate_api_key(api_key, transport_secrets=transport_secrets),
            expires_at=self._clock() + self._ttl,
            credentials=validated_extra,
        )

        with self._lock:
            self._purge_expired_locked()
            self._keys[entry.room] = entry
            self._evict_overflow_locked()

        # Log the fact, never the value: provider *names* only, so an operator
        # can see that Cartesia was supplied without seeing the key.
        logger.info(
            "Stored session key room=%s provider=%s also=%s ttl=%ss entries=%d",
            entry.room,
            entry.provider,
            ",".join(sorted(validated_extra)) or "none",
            self._ttl,
            len(self),
        )
        return entry

    def get(self, room: str) -> SessionKey | None:
        """
        Return the key for ``room``, or ``None`` when absent or expired.

        An expired entry is removed as a side effect, so a stale key cannot be
        revived by a later call.
        """
        with self._lock:
            self._purge_expired_locked()
            entry = self._keys.get(room)
            if entry is None:
                return None
            # Asked with this store's clock, never the entry's default. The
            # two are the same in production (both `time.monotonic`) and
            # different under an injected clock, where the default would judge
            # every entry against a clock that never set its deadline.
            if entry.is_expired(now=self._clock()):
                del self._keys[entry.room]
                logger.info("Session key expired room=%s", room)
                return None
            return entry

    def delete(self, room: str) -> bool:
        """Remove the key for ``room``. Returns whether one was present."""
        with self._lock:
            removed = self._keys.pop(room, None)
        if removed is not None:
            logger.info("Deleted session key room=%s (session ended)", room)
            return True
        return False

    def remaining(self, entry: SessionKey) -> int:
        """
        Whole seconds left before ``entry`` expires, floored at zero.

        Asked of the store rather than of the entry, so the answer always comes
        from the same clock that set the deadline. (An entry's own
        ``seconds_remaining()`` falls back to the real monotonic clock, which is
        right in production and wrong under an injected test clock — that
        mismatch is exactly what this method removes.)
        """
        return max(0, int(entry.expires_at - self._clock()))

    def purge_expired(self) -> int:
        """Drop every expired entry. Returns how many were removed."""
        with self._lock:
            return self._purge_expired_locked()

    def clear(self) -> None:
        """Remove everything. Used by tests and by an orderly shutdown."""
        with self._lock:
            self._keys.clear()

    def __len__(self) -> int:
        with self._lock:
            self._purge_expired_locked()
            return len(self._keys)

    def __contains__(self, room: object) -> bool:
        return isinstance(room, str) and self.get(room) is not None

    # ------------------------------------------------------------- internal ---

    def _purge_expired_locked(self) -> int:
        now = self._clock()
        expired = [room for room, entry in self._keys.items() if entry.is_expired(now=now)]
        for room in expired:
            del self._keys[room]
        if expired:
            logger.info("Purged %d expired session key(s)", len(expired))
        return len(expired)

    def _evict_overflow_locked(self) -> None:
        """
        Keep the store bounded.

        Evicts the entries closest to expiry first. Since every entry gets the
        same TTL, that is the same as evicting the *earliest registration*, so
        the entries that survive are the most recently registered ones — a
        reasonable bet, because the session starting right now is the one that
        just registered, while an old entry is more likely to belong to a client
        that died before it ever joined the room.

        Be precise about what this does **not** do: it is not flood protection.
        A client that registers more than ``max_entries`` keys in a burst will
        evict other people's pending keys, and the endpoint will report success
        for every one of those registrations. Bounding the store protects the
        *server's* memory; it cannot protect one user's key from another user's
        traffic. Doing that would need per-room ownership or a real datastore,
        which is out of scope here and is listed as a residual risk in
        ``docs/ARCHITECTURE.md``.
        """
        overflow = len(self._keys) - self._max_entries
        if overflow <= 0:
            return
        ordered = sorted(self._keys.values(), key=lambda entry: entry.expires_at)
        for entry in ordered[:overflow]:
            del self._keys[entry.room]
        logger.warning(
            "Session key store at capacity (%d); evicted %d entr(ies)",
            self._max_entries,
            overflow,
        )
