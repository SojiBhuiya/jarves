"""
Tests for the bring-your-own-key store.

This module holds user secrets, so the tests are written around three
questions rather than around the API surface:

1. **Can a key outlive what it is for?** (TTL, delete, capacity eviction)
2. **Can a key end up somewhere it should not?** (logs, ``repr``, another room,
   or the response body — the last one is covered in ``test_byok_endpoints``)
3. **Can a caller store something that is not a provider key?** (empty,
   whitespace, oversized, or one of the server's own transport secrets)
"""

from __future__ import annotations

import logging
import time

import pytest

from aladin.session_keys import (
    ALLOWED_PROVIDERS,
    MAX_KEY_LENGTH,
    MIN_KEY_LENGTH,
    SessionKey,
    SessionKeyError,
    SessionKeyStore,
    looks_like_transport_secret,
    validate_api_key,
)

GEMINI_KEY = "AIzaSyFAKEKEYVALUE1234567"
OPENAI_KEY = "sk-proj-FAKEKEYVALUE1234567890abcdef"


class FakeClock:
    """A monotonic clock the test drives by hand."""

    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def store(clock: FakeClock) -> SessionKeyStore:
    return SessionKeyStore(ttl_seconds=60, max_entries=3, clock=clock)


# ---------------------------------------------------------------------------
# Storing and retrieving
# ---------------------------------------------------------------------------


class TestRoundTrip:
    def test_stores_and_returns_a_key(self, store: SessionKeyStore) -> None:
        store.put(room="aladin-room-1", provider="gemini", api_key=GEMINI_KEY)

        entry = store.get("aladin-room-1")
        assert entry is not None
        assert entry.api_key == GEMINI_KEY
        assert entry.provider == "gemini"
        assert entry.room == "aladin-room-1"

    def test_unknown_room_returns_none(self, store: SessionKeyStore) -> None:
        assert store.get("never-registered") is None

    def test_key_is_scoped_to_one_room(self, store: SessionKeyStore) -> None:
        store.put(room="room-a", provider="gemini", api_key=GEMINI_KEY)

        # The whole point of keying by room: a session cannot reach another
        # session's key, even by guessing a nearby name.
        assert store.get("room-b") is None
        assert "room-b" not in store

    def test_replacing_a_room_key_keeps_one_entry(self, store: SessionKeyStore) -> None:
        store.put(room="room-a", provider="gemini", api_key=GEMINI_KEY)
        store.put(room="room-a", provider="openai", api_key=OPENAI_KEY)

        entry = store.get("room-a")
        assert entry is not None
        assert entry.api_key == OPENAI_KEY
        assert entry.provider == "openai"
        assert len(store) == 1

    def test_provider_is_normalised(self, store: SessionKeyStore) -> None:
        entry = store.put(room="room-a", provider="  GeMiNi ", api_key=GEMINI_KEY)
        assert entry.provider == "gemini"

    def test_room_name_is_stripped(self, store: SessionKeyStore) -> None:
        store.put(room="  room-a  ", provider="gemini", api_key=GEMINI_KEY)
        assert store.get("room-a") is not None

    def test_every_allowed_provider_can_be_stored(self, store: SessionKeyStore) -> None:
        for provider in ALLOWED_PROVIDERS:
            store.put(room=f"room-{provider}", provider=provider, api_key=GEMINI_KEY)
            assert store.get(f"room-{provider}") is not None


# ---------------------------------------------------------------------------
# Lifetime
# ---------------------------------------------------------------------------


class TestLifetime:
    def test_key_expires(self, store: SessionKeyStore, clock: FakeClock) -> None:
        store.put(room="room-a", provider="gemini", api_key=GEMINI_KEY)

        clock.advance(59)
        assert store.get("room-a") is not None, "must not expire early"

        clock.advance(2)
        assert store.get("room-a") is None

    def test_expired_key_is_removed_not_just_hidden(
        self, store: SessionKeyStore, clock: FakeClock
    ) -> None:
        store.put(room="room-a", provider="gemini", api_key=GEMINI_KEY)
        clock.advance(120)

        assert store.get("room-a") is None
        # A later call cannot revive it, and it is not occupying capacity.
        assert len(store) == 0

    def test_expiry_is_absolute_not_sliding(
        self, store: SessionKeyStore, clock: FakeClock
    ) -> None:
        store.put(room="room-a", provider="gemini", api_key=GEMINI_KEY)
        clock.advance(30)
        store.get("room-a")  # a read must not extend the lifetime
        clock.advance(31)
        assert store.get("room-a") is None

    def test_seconds_remaining_counts_down(
        self, store: SessionKeyStore, clock: FakeClock
    ) -> None:
        # Asked of the store, so it uses the same clock that set the deadline:
        # an entry's own seconds_remaining() falls back to the real clock.
        entry = store.put(room="room-a", provider="gemini", api_key=GEMINI_KEY)
        assert store.remaining(entry) == 60
        clock.advance(20)
        assert store.remaining(entry) == 40
        clock.advance(1_000)
        assert store.remaining(entry) == 0, "never negative"

    def test_a_read_uses_the_store_clock_not_the_wall_clock(
        self, clock: FakeClock
    ) -> None:
        """
        The clock the store was given is the only clock it may consult.

        This is the regression test for a bug that passed on a freshly booted
        machine and failed on a long-running one: ``get`` asked the *entry* for
        its expiry, and an entry with no explicit ``now`` falls back to the real
        ``time.monotonic()``. A test clock sitting far below the real one then
        made every entry look expired, so keys were silently deleted the moment
        they were read. In production both clocks are ``time.monotonic`` and the
        bug is invisible — which is exactly why it needs a test.

        The clock here is deliberately placed in the past *relative to the real
        monotonic clock*, so the two disagree on any machine, however long it
        has been up.
        """
        clock.now = time.monotonic() - 100_000
        store = SessionKeyStore(ttl_seconds=60, max_entries=3, clock=clock)

        store.put(room="room-a", provider="gemini", api_key=GEMINI_KEY)

        entry = store.get("room-a")
        assert entry is not None, "a key 60s from its deadline is not expired"
        assert store.remaining(entry) == 60
        assert len(store) == 1

    def test_entry_reports_remaining_against_an_explicit_time(self) -> None:
        entry = SessionKey(
            room="room-a", provider="gemini", api_key=GEMINI_KEY, expires_at=1_000.0
        )
        assert entry.seconds_remaining(now=940.0) == 60
        assert entry.seconds_remaining(now=1_060.0) == 0
        assert entry.is_expired(now=999.9) is False
        assert entry.is_expired(now=1_000.0) is True

    def test_delete_removes_the_key(self, store: SessionKeyStore) -> None:
        store.put(room="room-a", provider="gemini", api_key=GEMINI_KEY)
        assert store.delete("room-a") is True
        assert store.get("room-a") is None
        assert store.delete("room-a") is False, "idempotent"

    def test_purge_expired_reports_what_it_removed(
        self, store: SessionKeyStore, clock: FakeClock
    ) -> None:
        store.put(room="room-a", provider="gemini", api_key=GEMINI_KEY)
        clock.advance(30)
        store.put(room="room-b", provider="openai", api_key=OPENAI_KEY)

        clock.advance(31)  # room-a has expired, room-b has not
        assert store.purge_expired() == 1
        assert store.get("room-b") is not None

    def test_clear_removes_everything(self, store: SessionKeyStore) -> None:
        store.put(room="room-a", provider="gemini", api_key=GEMINI_KEY)
        store.put(room="room-b", provider="openai", api_key=OPENAI_KEY)
        store.clear()
        assert len(store) == 0

    def test_ttl_is_capped_by_max_ttl(self) -> None:
        aggressive = SessionKeyStore(ttl_seconds=99_999, max_ttl_seconds=120)
        assert aggressive.ttl_seconds == 120

    def test_invalid_construction_is_refused(self) -> None:
        with pytest.raises(ValueError):
            SessionKeyStore(ttl_seconds=0)
        with pytest.raises(ValueError):
            SessionKeyStore(max_entries=0)


# ---------------------------------------------------------------------------
# Hard eviction
# ---------------------------------------------------------------------------


class TestEviction:
    def test_store_stays_bounded(
        self, store: SessionKeyStore, clock: FakeClock
    ) -> None:
        # max_entries=3: the fourth registration must not grow the store.
        for index in range(6):
            store.put(room=f"room-{index}", provider="gemini", api_key=GEMINI_KEY)
            clock.advance(1)

        assert len(store) == 3

    def test_eviction_prefers_the_oldest(
        self, store: SessionKeyStore, clock: FakeClock
    ) -> None:
        store.put(room="first", provider="gemini", api_key=GEMINI_KEY)
        clock.advance(1)
        store.put(room="second", provider="gemini", api_key=GEMINI_KEY)
        clock.advance(1)
        store.put(room="third", provider="gemini", api_key=GEMINI_KEY)
        clock.advance(1)

        store.put(room="fourth", provider="gemini", api_key=GEMINI_KEY)

        # Earliest registration == closest to expiry here, because every entry
        # gets the same TTL. So the store keeps the newest keys and drops the
        # oldest — which is why this is a memory bound and not flood protection:
        # a burst of registrations does displace other pending keys.
        assert store.get("first") is None
        assert store.get("fourth") is not None
        assert len(store) == 3

    def test_expired_entries_do_not_consume_capacity(
        self, store: SessionKeyStore, clock: FakeClock
    ) -> None:
        for index in range(3):
            store.put(room=f"old-{index}", provider="gemini", api_key=GEMINI_KEY)

        clock.advance(120)  # everything expires

        store.put(room="fresh", provider="gemini", api_key=GEMINI_KEY)
        assert len(store) == 1
        assert store.get("fresh") is not None


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------


class TestValidation:
    @pytest.mark.parametrize("provider", ["", "anthropic", "GEMINI-2", "gemini extra"])
    def test_unknown_provider_is_refused(
        self, store: SessionKeyStore, provider: str
    ) -> None:
        with pytest.raises(SessionKeyError):
            store.put(room="room-a", provider=provider, api_key=GEMINI_KEY)

    @pytest.mark.parametrize("room", ["", "   "])
    def test_empty_room_is_refused(self, store: SessionKeyStore, room: str) -> None:
        with pytest.raises(SessionKeyError):
            store.put(room=room, provider="gemini", api_key=GEMINI_KEY)

    def test_short_key_is_refused(self, store: SessionKeyStore) -> None:
        with pytest.raises(SessionKeyError, match="too short"):
            store.put(room="room-a", provider="gemini", api_key="a" * (MIN_KEY_LENGTH - 1))

    def test_oversized_key_is_refused(self, store: SessionKeyStore) -> None:
        with pytest.raises(SessionKeyError, match="too long"):
            store.put(
                room="room-a", provider="gemini", api_key="a" * (MAX_KEY_LENGTH + 1)
            )

    def test_whitespace_inside_a_key_is_refused(self, store: SessionKeyStore) -> None:
        # Usually a paste accident: half a key plus a newline and a comment.
        with pytest.raises(SessionKeyError, match="whitespace"):
            store.put(
                room="room-a", provider="gemini", api_key="AIzaSyFAKE KEYVALUE123"
            )

    def test_non_string_key_is_refused(self, store: SessionKeyStore) -> None:
        with pytest.raises(SessionKeyError):
            store.put(room="room-a", provider="gemini", api_key=None)  # type: ignore[arg-type]

    def test_key_is_stripped_not_stored_with_surrounding_space(
        self, store: SessionKeyStore
    ) -> None:
        entry = store.put(
            room="room-a", provider="gemini", api_key=f"  {GEMINI_KEY}  "
        )
        assert entry.api_key == GEMINI_KEY

    def test_minimum_length_key_is_accepted(self, store: SessionKeyStore) -> None:
        entry = store.put(
            room="room-a", provider="gemini", api_key="a" * MIN_KEY_LENGTH
        )
        assert len(entry.api_key) == MIN_KEY_LENGTH

    def test_rejected_key_is_not_stored(self, store: SessionKeyStore) -> None:
        with pytest.raises(SessionKeyError):
            store.put(room="room-a", provider="gemini", api_key="short")
        assert store.get("room-a") is None
        assert len(store) == 0


class TestTransportSecretRefusal:
    """Pasting the server's own secret into the key field must not work."""

    DEVICE_TOKEN = "device-token-value-32-characters-min"
    LIVEKIT_SECRET = "livekit-secret-value-32-characters-long"
    INTERNAL_TOKEN = "internal-token-value-32-characters"

    def test_transport_secrets_are_recognised(self) -> None:
        assert looks_like_transport_secret(self.LIVEKIT_SECRET, self.LIVEKIT_SECRET)
        assert not looks_like_transport_secret("something-else-entirely", self.LIVEKIT_SECRET)
        assert not looks_like_transport_secret(self.LIVEKIT_SECRET), "no secrets supplied"

    def test_store_refuses_a_transport_secret(self, store: SessionKeyStore) -> None:
        with pytest.raises(SessionKeyError, match="transport secret"):
            store.put(
                room="room-a",
                provider="gemini",
                api_key=self.LIVEKIT_SECRET,
                transport_secrets=(self.LIVEKIT_SECRET, self.DEVICE_TOKEN),
            )

    @pytest.mark.parametrize(
        "secret",
        [DEVICE_TOKEN, LIVEKIT_SECRET, INTERNAL_TOKEN],
    )
    def test_each_transport_secret_is_refused(self, secret: str) -> None:
        with pytest.raises(SessionKeyError):
            validate_api_key(
                secret,
                transport_secrets=(self.DEVICE_TOKEN, self.LIVEKIT_SECRET, self.INTERNAL_TOKEN),
            )

    def test_an_ordinary_key_is_not_mistaken_for_a_transport_secret(self) -> None:
        assert (
            validate_api_key(
                GEMINI_KEY,
                transport_secrets=(self.DEVICE_TOKEN, self.LIVEKIT_SECRET),
            )
            == GEMINI_KEY
        )

    def test_empty_transport_secrets_do_not_match_everything(self) -> None:
        # An unset secret must not turn into a wildcard that rejects everything.
        assert validate_api_key(GEMINI_KEY, transport_secrets=("",)) == GEMINI_KEY


# ---------------------------------------------------------------------------
# Never leaking
# ---------------------------------------------------------------------------


class TestNeverLeaks:
    def test_repr_hides_the_key(self, store: SessionKeyStore) -> None:
        entry = store.put(room="room-a", provider="gemini", api_key=GEMINI_KEY)

        for rendered in (repr(entry), str(entry)):
            assert GEMINI_KEY not in rendered
            assert "redacted" in rendered
            # The room and provider are fine to show: they are not secrets, and
            # they are what makes a log line useful.
            assert "room-a" in rendered

    def test_repr_hides_even_a_short_key(self) -> None:
        entry = SessionKey(
            room="room-a", provider="gemini", api_key="a" * MIN_KEY_LENGTH,
            expires_at=0.0,
        )
        assert "a" * MIN_KEY_LENGTH not in repr(entry)

    def test_logging_a_stored_key_does_not_print_it(
        self, store: SessionKeyStore, caplog: pytest.LogCaptureFixture
    ) -> None:
        with caplog.at_level(logging.DEBUG, logger="aladin.session_keys"):
            store.put(room="room-a", provider="gemini", api_key=GEMINI_KEY)
            store.get("room-a")
            store.delete("room-a")

        assert GEMINI_KEY not in caplog.text
        assert "room-a" in caplog.text, "the log line should still be useful"

    def test_validation_error_does_not_echo_the_value(self) -> None:
        secret = "AIzaSy With Space 1234567890"
        with pytest.raises(SessionKeyError) as caught:
            validate_api_key(secret)
        assert secret not in str(caught.value)

    def test_transport_secret_error_names_no_value(self) -> None:
        secret = "livekit-secret-value-32-characters-long"
        with pytest.raises(SessionKeyError) as caught:
            validate_api_key(secret, transport_secrets=(secret,))
        assert secret not in str(caught.value)


class TestConcurrency:
    def test_parallel_writes_do_not_corrupt_the_store(self) -> None:
        import threading

        concurrent = SessionKeyStore(ttl_seconds=60, max_entries=50)
        errors: list[BaseException] = []

        def writer(worker: int) -> None:
            try:
                for index in range(20):
                    concurrent.put(
                        room=f"room-{worker}-{index}",
                        provider="gemini",
                        api_key=GEMINI_KEY,
                    )
                    concurrent.get(f"room-{worker}-{index}")
            except BaseException as exc:  # pragma: no cover - failure path
                errors.append(exc)

        threads = [threading.Thread(target=writer, args=(n,)) for n in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        assert not errors
        assert len(concurrent) <= 50
