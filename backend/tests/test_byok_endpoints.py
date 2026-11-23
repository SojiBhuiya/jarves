"""
Tests for the bring-your-own-key endpoints.

The interesting assertions are not "does it store a value" but the security
properties around it:

* the app can register a key but **cannot read one back**, even its own;
* the agent can, but only with a *different* secret;
* a key never appears in a response body, in an error, or in a log;
* the server's own transport secrets are refused if pasted into the key field;
* a session with no key behaves exactly as before (404, and the agent falls
  back) rather than failing.

The token API reads its configuration at import time, so these tests replace
the module-level ``settings`` object instead of re-importing the module. The
endpoints resolve ``settings`` at call time, which is what makes that safe.
"""

from __future__ import annotations

import dataclasses
import logging

# The environment comes from tests/conftest.py, which pytest imports first: the
# token server reads its configuration at import time, so the values cannot be
# decided by whichever test module happens to import it first.
TEST_API_SECRET = "test-secret-value-at-least-32-characters-long"
TEST_DEVICE_TOKEN = "device-token-for-tests"
TEST_INTERNAL_TOKEN = "internal-token-for-tests"
GEMINI_KEY = "AIzaSyFAKEKEYVALUE1234567"

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import aladin.token_server as ts  # noqa: E402

DEVICE_AUTH = {"X-Aladin-Device-Token": TEST_DEVICE_TOKEN}
INTERNAL_AUTH = {"X-Aladin-Internal-Token": TEST_INTERNAL_TOKEN}


@pytest.fixture(autouse=True)
def _clean_store() -> None:
    """No test may see another test's key."""
    ts.session_keys.clear()


@pytest.fixture
def byok(monkeypatch: pytest.MonkeyPatch) -> TestClient:
    """A client for a server that has bring-your-own-key properly configured."""
    enabled = dataclasses.replace(
        ts.settings,
        allow_byok=True,
        internal_token=TEST_INTERNAL_TOKEN,
        token_api_url="http://127.0.0.1:8080",
    )
    monkeypatch.setattr(ts, "settings", enabled)
    # Generous limiters by default: rate limiting has its own test below, and
    # the module-level limiters are process-wide and would otherwise leak
    # between tests.
    monkeypatch.setattr(
        ts, "_key_limiter", ts._RateLimiter(max_requests=1_000, window_seconds=60.0)
    )
    monkeypatch.setattr(
        ts, "_token_limiter", ts._RateLimiter(max_requests=1_000, window_seconds=60.0)
    )
    return TestClient(ts.app)


@pytest.fixture
def no_byok(monkeypatch: pytest.MonkeyPatch) -> TestClient:
    """A client for a server where the feature is *not* configured."""
    disabled = dataclasses.replace(
        ts.settings, allow_byok=True, internal_token="", device_token=TEST_DEVICE_TOKEN
    )
    monkeypatch.setattr(ts, "settings", disabled)
    monkeypatch.setattr(
        ts, "_key_limiter", ts._RateLimiter(max_requests=1_000, window_seconds=60.0)
    )
    return TestClient(ts.app)


def register(client: TestClient, **overrides: object) -> object:
    """Register a key, with sensible defaults."""
    payload = {
        "room": "aladin-room-1",
        "provider": "gemini",
        "api_key": GEMINI_KEY,
    }
    payload.update(overrides)
    return client.post("/v1/session/keys", json=payload, headers=DEVICE_AUTH)


# ---------------------------------------------------------------------------
# Registering
# ---------------------------------------------------------------------------


class TestRegister:
    def test_requires_device_token(self, byok: TestClient) -> None:
        response = byok.post(
            "/v1/session/keys",
            json={"room": "room-a", "provider": "gemini", "api_key": GEMINI_KEY},
        )
        assert response.status_code == 401

    def test_wrong_device_token_is_refused(self, byok: TestClient) -> None:
        response = byok.post(
            "/v1/session/keys",
            json={"room": "room-a", "provider": "gemini", "api_key": GEMINI_KEY},
            headers={"X-Aladin-Device-Token": "not-the-token"},
        )
        assert response.status_code == 401

    def test_stores_the_key(self, byok: TestClient) -> None:
        response = register(byok)
        assert response.status_code == 200

        body = response.json()
        assert body["stored"] is True
        assert body["room"] == "aladin-room-1"
        assert body["provider"] == "gemini"
        assert body["expires_in"] > 0

        # ...and the agent can now fetch it.
        fetched = byok.get("/v1/session/keys/aladin-room-1", headers=INTERNAL_AUTH)
        assert fetched.json()["api_key"] == GEMINI_KEY

    def test_response_never_contains_the_key(self, byok: TestClient) -> None:
        response = register(byok)
        assert GEMINI_KEY not in response.text

    def test_openai_key_is_accepted_and_tagged(self, byok: TestClient) -> None:
        response = register(byok, provider="openai", api_key="sk-proj-abcdefghijklmnop")
        assert response.status_code == 200
        assert response.json()["provider"] == "openai"

    def test_room_name_is_normalised(self, byok: TestClient) -> None:
        # The agent looks the key up by the LiveKit room name, which is
        # sanitised by the token endpoint; registering under a raw name must
        # still land on the name the agent will use.
        response = register(byok, room="aladin-room-1!! extra")
        assert response.json()["room"] == "aladin-room-1extra"

    def test_disabled_server_refuses_with_a_reason(self, no_byok: TestClient) -> None:
        response = register(no_byok)
        assert response.status_code == 503

        detail = response.json()["detail"]
        assert "ALADIN_INTERNAL_TOKEN" in detail, "the reason must be actionable"
        assert GEMINI_KEY not in response.text

    def test_transport_secret_is_refused(self, byok: TestClient) -> None:
        response = register(byok, api_key=TEST_API_SECRET)
        assert response.status_code == 400
        assert "transport secret" in response.json()["detail"]
        # Neither the value nor a hash of it may come back.
        assert TEST_API_SECRET not in response.text
        assert ts.session_keys.get("aladin-room-1") is None

    def test_device_token_as_key_is_refused(self, byok: TestClient) -> None:
        response = register(byok, api_key=TEST_DEVICE_TOKEN)
        assert response.status_code == 400
        assert TEST_DEVICE_TOKEN not in response.text

    def test_short_key_is_refused(self, byok: TestClient) -> None:
        response = register(byok, api_key="abc")
        assert response.status_code == 400

    def test_unknown_provider_is_refused_by_the_schema(self, byok: TestClient) -> None:
        response = register(byok, provider="anthropic")
        assert response.status_code == 422

    def test_extra_fields_are_refused(self, byok: TestClient) -> None:
        response = register(byok, livekit_api_secret="nope")
        assert response.status_code == 422

    def test_validation_errors_do_not_echo_the_key(self, byok: TestClient) -> None:
        # A 422 is generated by FastAPI from the submitted body; the global
        # handler must scrub the input, or a mistyped request would reflect the
        # credential straight back to the caller.
        response = register(byok, provider="anthropic", api_key=GEMINI_KEY)
        assert response.status_code == 422
        assert GEMINI_KEY not in response.text

    def test_overlong_room_is_refused(self, byok: TestClient) -> None:
        response = register(byok, room="r" * 200)
        assert response.status_code == 422

    def test_replacing_a_key_keeps_one_entry(self, byok: TestClient) -> None:
        register(byok, api_key=GEMINI_KEY)
        register(byok, provider="openai", api_key="sk-proj-abcdefghijklmnop")

        assert len(ts.session_keys) == 1
        fetched = byok.get("/v1/session/keys/aladin-room-1", headers=INTERNAL_AUTH)
        assert fetched.json()["provider"] == "openai"

    def test_rate_limit_applies(
        self, byok: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        monkeypatch.setattr(
            ts, "_key_limiter", ts._RateLimiter(max_requests=2, window_seconds=60.0)
        )
        assert register(byok).status_code == 200
        assert register(byok).status_code == 200
        assert register(byok).status_code == 429


# ---------------------------------------------------------------------------
# Fetching (agent only)
# ---------------------------------------------------------------------------


class TestFetch:
    def test_requires_a_token(self, byok: TestClient) -> None:
        register(byok)
        assert byok.get("/v1/session/keys/aladin-room-1").status_code == 401

    def test_the_device_token_cannot_read_a_key(self, byok: TestClient) -> None:
        # The central property of this design: every app install holds the
        # device token, so holding it must not grant read access to keys.
        register(byok)
        response = byok.get("/v1/session/keys/aladin-room-1", headers=DEVICE_AUTH)
        assert response.status_code == 401
        assert GEMINI_KEY not in response.text

    def test_wrong_internal_token_is_refused(self, byok: TestClient) -> None:
        register(byok)
        response = byok.get(
            "/v1/session/keys/aladin-room-1",
            headers={"X-Aladin-Internal-Token": "wrong"},
        )
        assert response.status_code == 401

    def test_internal_token_returns_the_key(self, byok: TestClient) -> None:
        register(byok)
        response = byok.get("/v1/session/keys/aladin-room-1", headers=INTERNAL_AUTH)

        assert response.status_code == 200
        body = response.json()
        assert body["api_key"] == GEMINI_KEY
        assert body["room"] == "aladin-room-1"
        assert body["provider"] == "gemini"
        assert body["expires_in"] > 0

    def test_missing_key_is_404_not_500(self, byok: TestClient) -> None:
        # The ordinary case for a session that uses server credentials: the
        # agent must be able to tell "no key" apart from "something broke".
        response = byok.get("/v1/session/keys/never-registered", headers=INTERNAL_AUTH)
        assert response.status_code == 404

    def test_disabled_server_gives_a_reason(self, no_byok: TestClient) -> None:
        response = no_byok.get("/v1/session/keys/room-a", headers=INTERNAL_AUTH)
        assert response.status_code == 503
        assert "ALADIN_INTERNAL_TOKEN" in response.json()["detail"]

    def test_bearer_header_also_works(self, byok: TestClient) -> None:
        register(byok)
        response = byok.get(
            "/v1/session/keys/aladin-room-1",
            headers={"Authorization": f"Bearer {TEST_INTERNAL_TOKEN}"},
        )
        assert response.status_code == 200

    def test_fetch_does_not_consume_the_key(self, byok: TestClient) -> None:
        # A retry (reconnect, worker restart) must still find the key.
        register(byok)
        first = byok.get("/v1/session/keys/aladin-room-1", headers=INTERNAL_AUTH)
        second = byok.get("/v1/session/keys/aladin-room-1", headers=INTERNAL_AUTH)
        assert first.json()["api_key"] == second.json()["api_key"] == GEMINI_KEY


# ---------------------------------------------------------------------------
# Deleting
# ---------------------------------------------------------------------------


class TestDelete:
    def test_wipes_the_key(self, byok: TestClient) -> None:
        register(byok)

        deleted = byok.delete("/v1/session/keys/aladin-room-1", headers=INTERNAL_AUTH)
        assert deleted.status_code == 200
        assert deleted.json()["deleted"] is True

        after = byok.get("/v1/session/keys/aladin-room-1", headers=INTERNAL_AUTH)
        assert after.status_code == 404

    def test_deleting_an_absent_key_is_not_an_error(self, byok: TestClient) -> None:
        # Session teardown must be idempotent: it runs on both the normal and
        # the failure path.
        response = byok.delete("/v1/session/keys/never-registered", headers=INTERNAL_AUTH)
        assert response.status_code == 200
        assert response.json()["deleted"] is False

    def test_requires_a_token(self, byok: TestClient) -> None:
        register(byok)
        assert byok.delete("/v1/session/keys/aladin-room-1").status_code == 401

    def test_the_device_token_cannot_delete(self, byok: TestClient) -> None:
        # Deliberately agent-only: a client that could delete another room's
        # key could deny service to a session it does not own.
        register(byok)
        response = byok.delete("/v1/session/keys/aladin-room-1", headers=DEVICE_AUTH)
        assert response.status_code == 401
        assert ts.session_keys.get("aladin-room-1") is not None


# ---------------------------------------------------------------------------
# What the app is told
# ---------------------------------------------------------------------------


class TestConfigFlag:
    def test_advertised_when_configured(self, byok: TestClient) -> None:
        body = byok.get("/v1/config").json()
        assert body["supports_bring_your_own_key"] is True

    def test_not_advertised_when_unconfigured(self, no_byok: TestClient) -> None:
        body = no_byok.get("/v1/config").json()
        assert body["supports_bring_your_own_key"] is False

    def test_public_config_carries_no_secret(self, byok: TestClient) -> None:
        register(byok)
        body = byok.get("/v1/config").text
        assert GEMINI_KEY not in body
        assert TEST_INTERNAL_TOKEN not in body


# ---------------------------------------------------------------------------
# The whole flow
# ---------------------------------------------------------------------------


class TestEndToEndFlow:
    def test_app_registers_then_agent_fetches_then_wipes(self, byok: TestClient) -> None:
        # 1. The app asks for a room token (with its provider preference).
        token = byok.post(
            "/v1/token",
            json={"config": {"ai_provider": "gemini", "languages": ["bn"]}},
            headers=DEVICE_AUTH,
        )
        assert token.status_code == 200
        room = token.json()["room"]

        # 2. Before joining, it registers its key for that room.
        registered = byok.post(
            "/v1/session/keys",
            json={"room": room, "provider": "gemini", "api_key": GEMINI_KEY},
            headers=DEVICE_AUTH,
        )
        assert registered.status_code == 200
        assert registered.json()["room"] == room

        # 3. The agent fetches it privately and uses it instead of the server's.
        fetched = byok.get(f"/v1/session/keys/{room}", headers=INTERNAL_AUTH)
        assert fetched.status_code == 200
        assert fetched.json()["api_key"] == GEMINI_KEY

        # 4. Session ends; the key is gone.
        byok.delete(f"/v1/session/keys/{room}", headers=INTERNAL_AUTH)
        assert byok.get(f"/v1/session/keys/{room}", headers=INTERNAL_AUTH).status_code == 404

    def test_the_key_never_enters_room_metadata(self, byok: TestClient) -> None:
        # The reason this design exists: metadata reaches every participant.
        # A client that tries to pass a key as config must still be refused,
        # *after* the BYOK endpoints exist.
        response = byok.post(
            "/v1/token",
            json={"config": {"provider_api_key": GEMINI_KEY}},
            headers=DEVICE_AUTH,
        )
        assert response.status_code == 422
        assert GEMINI_KEY not in response.text

    def test_the_key_never_reaches_a_log(
        self, byok: TestClient, caplog: pytest.LogCaptureFixture
    ) -> None:
        with caplog.at_level(logging.DEBUG):
            register(byok)
            byok.get("/v1/session/keys/aladin-room-1", headers=INTERNAL_AUTH)
            byok.delete("/v1/session/keys/aladin-room-1", headers=INTERNAL_AUTH)

        assert GEMINI_KEY not in caplog.text
