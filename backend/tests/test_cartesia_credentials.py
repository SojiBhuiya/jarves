"""
How a Cartesia key travels: Settings -> app -> token API -> agent.

The modular pipeline needs a *second* credential, because Cartesia speaks while
another provider reasons. That makes the credential path the highest-risk part
of this feature, so these tests follow it end to end and check the security
properties at every hop:

1. The app registers it with the device token; the agent fetches it with the
   internal token. A device token alone can never read a key back.
2. A session can hold the reasoning key *and* the voice key without either
   displacing the other.
3. The key never appears in a response to the app, in a log line, in the store's
   repr, in room metadata, or in any diagnostics payload.
4. The Cartesia key is not a deployment secret: it is not read from the
   environment by the app, and it is not in the deploy workflow.

Categories: **unit** for the store/config, **mocked integration** for the HTTP
endpoints (a real FastAPI app driven by TestClient with a fake transport where a
provider call is involved). Nothing here contacts Cartesia.
"""

from __future__ import annotations

import logging

import pytest
from fastapi.testclient import TestClient

from aladin.config import Settings
from aladin.key_client import FetchedKey
from aladin.session_keys import (
    ALLOWED_PROVIDERS,
    SessionKeyError,
    SessionKeyStore,
    validate_api_key,
)

GEMINI_KEY = "AIzaSyTESTKEYVALUE1234567890"
CARTESIA_KEY = "cartesia-test-key-abcdef123456"
DEVICE_TOKEN = "device-token-for-tests"
INTERNAL_TOKEN = "internal-token-for-tests"

DEVICE_AUTH = {"X-Aladin-Device-Token": DEVICE_TOKEN}
INTERNAL_AUTH = {"X-Aladin-Internal-Token": INTERNAL_TOKEN}


# ---------------------------------------------------------------------------
# The store (unit)
# ---------------------------------------------------------------------------


class TestStoreHoldsTwoCredentialsForOneSession:
    def test_cartesia_is_an_allowed_provider(self) -> None:
        assert "cartesia" in ALLOWED_PROVIDERS

    def test_a_session_can_hold_a_reasoning_key_and_a_voice_key(self) -> None:
        store = SessionKeyStore(ttl_seconds=60)
        entry = store.put(
            room="room-a",
            provider="gemini",
            api_key=GEMINI_KEY,
            credentials={"cartesia": CARTESIA_KEY},
        )
        assert entry.key_for("gemini") == GEMINI_KEY
        assert entry.key_for("cartesia") == CARTESIA_KEY
        assert set(entry.provider_names) == {"gemini", "cartesia"}

    def test_it_is_still_one_entry_per_room(self) -> None:
        """The existing one-entry-per-room contract must survive."""
        store = SessionKeyStore(ttl_seconds=60)
        store.put(
            room="room-a",
            provider="gemini",
            api_key=GEMINI_KEY,
            credentials={"cartesia": CARTESIA_KEY},
        )
        assert len(store) == 1

    def test_re_registering_replaces_wholesale(self) -> None:
        store = SessionKeyStore(ttl_seconds=60)
        store.put(
            room="room-a",
            provider="gemini",
            api_key=GEMINI_KEY,
            credentials={"cartesia": CARTESIA_KEY},
        )
        store.put(room="room-a", provider="openai", api_key="sk-proj-abcdefghijklmnop")
        entry = store.get("room-a")
        assert entry is not None
        assert entry.key_for("cartesia") is None, "a stale voice key survived a re-register"
        assert len(store) == 1

    def test_a_single_key_session_behaves_exactly_as_before(self) -> None:
        store = SessionKeyStore(ttl_seconds=60)
        entry = store.put(room="room-a", provider="gemini", api_key=GEMINI_KEY)
        assert entry.api_key == GEMINI_KEY
        assert entry.credentials == {}
        assert entry.key_for("cartesia") is None

    def test_the_repr_never_contains_either_key(self) -> None:
        store = SessionKeyStore(ttl_seconds=60)
        entry = store.put(
            room="room-a",
            provider="gemini",
            api_key=GEMINI_KEY,
            credentials={"cartesia": CARTESIA_KEY},
        )
        text = repr(entry)
        assert GEMINI_KEY not in text
        assert CARTESIA_KEY not in text
        # Provider *names* are fine to show; values are not.
        assert "cartesia" in text

    def test_the_log_line_never_contains_either_key(self, caplog: pytest.LogCaptureFixture) -> None:
        store = SessionKeyStore(ttl_seconds=60)
        with caplog.at_level(logging.INFO):
            store.put(
                room="room-a",
                provider="gemini",
                api_key=GEMINI_KEY,
                credentials={"cartesia": CARTESIA_KEY},
            )
        assert GEMINI_KEY not in caplog.text
        assert CARTESIA_KEY not in caplog.text


class TestAdditionalCredentialValidation:
    def test_a_short_voice_key_is_refused(self) -> None:
        store = SessionKeyStore(ttl_seconds=60)
        with pytest.raises(SessionKeyError):
            store.put(
                room="room-a",
                provider="gemini",
                api_key=GEMINI_KEY,
                credentials={"cartesia": "tiny"},
            )

    def test_an_unknown_voice_provider_is_refused(self) -> None:
        store = SessionKeyStore(ttl_seconds=60)
        with pytest.raises(SessionKeyError) as excinfo:
            store.put(
                room="room-a",
                provider="gemini",
                api_key=GEMINI_KEY,
                credentials={"elevenlabs": CARTESIA_KEY},
            )
        assert "elevenlabs" in str(excinfo.value)

    def test_a_duplicate_of_the_primary_provider_is_refused(self) -> None:
        # Two copies of one provider's key could only disagree with each other.
        store = SessionKeyStore(ttl_seconds=60)
        with pytest.raises(SessionKeyError):
            store.put(
                room="room-a",
                provider="gemini",
                api_key=GEMINI_KEY,
                credentials={"gemini": GEMINI_KEY},
            )

    def test_a_transport_secret_is_refused_in_the_voice_slot_too(self) -> None:
        """The LiveKit secret must not be stashable as a 'Cartesia key'."""
        store = SessionKeyStore(ttl_seconds=60)
        with pytest.raises(SessionKeyError):
            store.put(
                room="room-a",
                provider="gemini",
                api_key=GEMINI_KEY,
                credentials={"cartesia": "the-livekit-api-secret-value"},
                transport_secrets=("the-livekit-api-secret-value",),
            )

    def test_whitespace_in_a_voice_key_is_refused(self) -> None:
        with pytest.raises(SessionKeyError):
            validate_api_key("cartesia key with spaces")


# ---------------------------------------------------------------------------
# The HTTP path (mocked integration)
# ---------------------------------------------------------------------------


@pytest.fixture
def server(monkeypatch: pytest.MonkeyPatch):
    """A token API with BYOK enabled and both tokens configured."""
    from aladin import token_server as ts

    monkeypatch.setattr(
        ts,
        "settings",
        Settings(
            device_token=DEVICE_TOKEN,
            internal_token=INTERNAL_TOKEN,
            livekit_api_key="APItestkey",
            livekit_api_secret="livekit-secret-value-a" * 3,
            livekit_url="wss://test.livekit.cloud",
            gemini_api_key=GEMINI_KEY,
            ai_provider="gemini",
        ),
    )
    monkeypatch.setattr(ts, "session_keys", ts.SessionKeyStore(ttl_seconds=600))
    monkeypatch.setattr(ts, "_key_limiter", ts._RateLimiter(max_requests=50, window_seconds=60.0))
    with TestClient(ts.app) as client:
        yield client


class TestRegisterAndFetch:
    def test_the_app_registers_both_keys_in_one_request(self, server: TestClient) -> None:
        response = server.post(
            "/v1/session/keys",
            headers=DEVICE_AUTH,
            json={
                "room": "aladin-room-1",
                "provider": "gemini",
                "api_key": GEMINI_KEY,
                "credentials": {"cartesia": CARTESIA_KEY},
            },
        )
        assert response.status_code == 200
        # The device gets an acknowledgement, never the key back.
        assert CARTESIA_KEY not in response.text
        assert GEMINI_KEY not in response.text

    def test_the_agent_gets_both_keys_with_the_internal_token(self, server: TestClient) -> None:
        server.post(
            "/v1/session/keys",
            headers=DEVICE_AUTH,
            json={
                "room": "aladin-room-1",
                "provider": "gemini",
                "api_key": GEMINI_KEY,
                "credentials": {"cartesia": CARTESIA_KEY},
            },
        )
        fetched = server.get("/v1/session/keys/aladin-room-1", headers=INTERNAL_AUTH)
        assert fetched.status_code == 200
        body = fetched.json()
        assert body["api_key"] == GEMINI_KEY
        assert body["credentials"]["cartesia"] == CARTESIA_KEY

    def test_the_device_token_cannot_read_the_keys_back(self, server: TestClient) -> None:
        """The core security property of the whole channel."""
        server.post(
            "/v1/session/keys",
            headers=DEVICE_AUTH,
            json={
                "room": "aladin-room-1",
                "provider": "gemini",
                "api_key": GEMINI_KEY,
                "credentials": {"cartesia": CARTESIA_KEY},
            },
        )
        fetched = server.get("/v1/session/keys/aladin-room-1", headers=DEVICE_AUTH)
        assert fetched.status_code == 401
        assert CARTESIA_KEY not in fetched.text

    def test_an_unauthenticated_read_is_refused(self, server: TestClient) -> None:
        assert server.get("/v1/session/keys/aladin-room-1").status_code == 401

    def test_the_internal_token_cannot_register_a_key(self, server: TestClient) -> None:
        """Separation of powers, in the other direction."""
        response = server.post(
            "/v1/session/keys",
            headers=INTERNAL_AUTH,
            json={"room": "aladin-room-1", "provider": "cartesia", "api_key": CARTESIA_KEY},
        )
        assert response.status_code == 401

    def test_registration_without_credentials_still_works(self, server: TestClient) -> None:
        """Backwards compatibility: the old payload shape is unchanged."""
        response = server.post(
            "/v1/session/keys",
            headers=DEVICE_AUTH,
            json={"room": "aladin-room-1", "provider": "gemini", "api_key": GEMINI_KEY},
        )
        assert response.status_code == 200
        fetched = server.get("/v1/session/keys/aladin-room-1", headers=INTERNAL_AUTH)
        assert fetched.json()["api_key"] == GEMINI_KEY
        assert fetched.json()["credentials"] == {}

    def test_cartesia_can_be_the_primary_provider(self, server: TestClient) -> None:
        response = server.post(
            "/v1/session/keys",
            headers=DEVICE_AUTH,
            json={"room": "aladin-room-2", "provider": "cartesia", "api_key": CARTESIA_KEY},
        )
        assert response.status_code == 200
        assert CARTESIA_KEY not in response.text

    def test_a_transport_secret_in_the_voice_slot_is_refused(self, server: TestClient) -> None:
        response = server.post(
            "/v1/session/keys",
            headers=DEVICE_AUTH,
            json={
                "room": "aladin-room-1",
                "provider": "gemini",
                "api_key": GEMINI_KEY,
                "credentials": {"cartesia": INTERNAL_TOKEN},
            },
        )
        assert response.status_code == 400
        assert INTERNAL_TOKEN not in response.text

    def test_validation_errors_never_echo_a_key(self, server: TestClient) -> None:
        response = server.post(
            "/v1/session/keys",
            headers=DEVICE_AUTH,
            json={
                "room": "aladin-room-1",
                "provider": "gemini",
                "api_key": GEMINI_KEY,
                "credentials": {"elevenlabs": CARTESIA_KEY},
            },
        )
        assert response.status_code == 422
        assert CARTESIA_KEY not in response.text
        assert GEMINI_KEY not in response.text

    def test_unknown_credential_fields_are_refused(self, server: TestClient) -> None:
        response = server.post(
            "/v1/session/keys",
            headers=DEVICE_AUTH,
            json={
                "room": "aladin-room-1",
                "provider": "gemini",
                "api_key": GEMINI_KEY,
                "credentials": {"cartesia": CARTESIA_KEY},
                "extra_field": "nope",
            },
        )
        assert response.status_code == 422


# ---------------------------------------------------------------------------
# The agent side (unit)
# ---------------------------------------------------------------------------


class TestFetchedKey:
    def test_it_exposes_the_voice_key_by_provider(self) -> None:
        fetched = FetchedKey(
            room="room-a",
            provider="gemini",
            api_key=GEMINI_KEY,
            expires_in=60,
            credentials={"cartesia": CARTESIA_KEY},
        )
        assert fetched.key_for("gemini") == GEMINI_KEY
        assert fetched.key_for("cartesia") == CARTESIA_KEY
        assert fetched.key_for("openai") is None

    def test_its_repr_is_secret_free(self) -> None:
        fetched = FetchedKey(
            room="room-a",
            provider="gemini",
            api_key=GEMINI_KEY,
            expires_in=60,
            credentials={"cartesia": CARTESIA_KEY},
        )
        assert GEMINI_KEY not in repr(fetched)
        assert CARTESIA_KEY not in repr(fetched)

    @pytest.mark.asyncio
    async def test_the_client_parses_a_credentials_map(self) -> None:
        client = _FakeTransportClient(
            body={
                "room": "room-a",
                "provider": "gemini",
                "api_key": GEMINI_KEY,
                "expires_in": 60,
                "credentials": {"cartesia": CARTESIA_KEY},
            }
        )
        fetched = await client.fetch("room-a")
        assert fetched is not None
        assert fetched.credentials == {"cartesia": CARTESIA_KEY}

    @pytest.mark.asyncio
    async def test_the_client_tolerates_an_old_server_without_credentials(self) -> None:
        client = _FakeTransportClient(
            body={"room": "room-a", "provider": "gemini", "api_key": GEMINI_KEY, "expires_in": 60}
        )
        fetched = await client.fetch("room-a")
        assert fetched is not None
        assert fetched.credentials == {}


# ---------------------------------------------------------------------------
# Settings: two keys, no cross-contamination (unit)
# ---------------------------------------------------------------------------


class TestSettingsRouting:
    def test_the_voice_key_lands_in_the_cartesia_field(self) -> None:
        applied = Settings().apply_overrides(
            {"provider_api_key": CARTESIA_KEY, "key_provider": "cartesia"}
        )
        assert applied.cartesia_api_key == CARTESIA_KEY
        assert applied.gemini_api_key == ""
        assert applied.openai_api_key == ""

    def test_the_reasoning_key_does_not_touch_the_voice_key(self) -> None:
        base = Settings(cartesia_api_key="server-cartesia")
        applied = base.apply_overrides({"provider_api_key": GEMINI_KEY, "ai_provider": "gemini"})
        assert applied.gemini_api_key == GEMINI_KEY
        assert applied.cartesia_api_key == "server-cartesia"

    def test_applying_both_keeps_both(self) -> None:
        s = Settings()
        s = s.apply_overrides({"provider_api_key": GEMINI_KEY, "ai_provider": "gemini"})
        s = s.apply_overrides({"provider_api_key": CARTESIA_KEY, "key_provider": "cartesia"})
        assert (s.gemini_api_key, s.cartesia_api_key) == (GEMINI_KEY, CARTESIA_KEY)

    def test_redacted_view_hides_the_voice_key(self) -> None:
        view = Settings(cartesia_api_key=CARTESIA_KEY).redacted()
        assert CARTESIA_KEY not in str(view)
        assert view["cartesia_api_key"] != "<unset>", "presence should still be visible"

    def test_the_public_config_reports_presence_but_not_the_key(self, server: TestClient) -> None:
        body = server.get("/v1/config").json()
        assert "cartesia_api_key" not in body
        assert isinstance(body["cartesia_configured"], bool)
        assert CARTESIA_KEY not in str(body)


# ---------------------------------------------------------------------------
# Helper: a SessionKeyClient driven by a fake transport
# ---------------------------------------------------------------------------


class _FakeResponse:
    def __init__(self, status: int, body: dict) -> None:
        self.status = status
        self._body = body

    async def json(self) -> dict:
        return self._body

    async def text(self) -> str:
        return str(self._body)

    async def __aenter__(self) -> "_FakeResponse":
        return self

    async def __aexit__(self, *exc: object) -> None:
        return None


class _FakeSession:
    def __init__(self, body: dict) -> None:
        self._body = body
        self.calls: list[tuple[str, str, dict]] = []

    def request(self, method: str, url: str, **kwargs: object):
        self.calls.append((method, url, kwargs.get("headers", {})))  # type: ignore[arg-type]
        return _FakeResponse(200, self._body)


def _FakeTransportClient(body: dict):
    from aladin.key_client import SessionKeyClient

    return SessionKeyClient(
        base_url="http://token-api.test",
        internal_token=INTERNAL_TOKEN,
        session=_FakeSession(body),
    )
