"""
"Test Connection" for a Cartesia key.

This endpoint exists so a user finds out that a key is wrong *before* saving it
and starting a session. Two properties matter more than the happy path:

* it must **not store anything** — testing a key cannot leave a copy behind;
* it must never leak the key into a response or a log, including on failure.

Everything is **mocked integration**: a real FastAPI app driven by TestClient,
with the outbound Cartesia call replaced by a fake. No test here contacts
Cartesia, and none claims a real key was verified.
"""

from __future__ import annotations

import json
import logging

import pytest
from fastapi.testclient import TestClient

from aladin.config import Settings

CARTESIA_KEY = "cartesia-test-key-abcdef123456"
DEVICE_TOKEN = "device-token-for-tests"
INTERNAL_TOKEN = "internal-token-for-tests"
AUTH = {"X-Aladin-Device-Token": DEVICE_TOKEN}

#: A voice id shaped like a real one, so the "voice not found" path is exercised.
VOICE_ID = "f786b574-daa5-4673-aa0c-cbe3e8534c02"


@pytest.fixture
def server(monkeypatch: pytest.MonkeyPatch):
    """A token API with both tokens configured and a working key limiter."""
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
        ),
    )
    monkeypatch.setattr(ts, "_key_limiter", ts._RateLimiter(max_requests=50, window_seconds=60.0))
    with TestClient(ts.app) as client:
        yield client


def fake_cartesia(monkeypatch: pytest.MonkeyPatch, *, status: int, voices: list[str] | None = None):
    """Replace the outbound Cartesia call with a canned response."""
    from aladin import token_server as ts

    captured: dict[str, object] = {}

    async def _verify(api_key: str, *, timeout: float = 10.0):
        captured["api_key"] = api_key
        if status in (401, 403):
            return False, "Cartesia rejected this API key.", None, []
        if status >= 400:
            return None, f"Cartesia returned HTTP {status}; the key could not be verified right now.", None, []
        ids = voices if voices is not None else [VOICE_ID]
        return True, "Cartesia accepted this API key.", len(ids), ids

    monkeypatch.setattr(ts, "_verify_cartesia_key", _verify)
    return captured


class TestAuthAndShape:
    def test_it_requires_the_device_token(self, server: TestClient) -> None:
        response = server.post(
            "/v1/providers/test", json={"provider": "cartesia", "api_key": CARTESIA_KEY}
        )
        assert response.status_code == 401

    def test_the_internal_token_is_not_accepted(self, server: TestClient) -> None:
        """The agent must not be able to use the app's endpoint."""
        response = server.post(
            "/v1/providers/test",
            headers={"X-Aladin-Internal-Token": INTERNAL_TOKEN},
            json={"provider": "cartesia", "api_key": CARTESIA_KEY},
        )
        assert response.status_code == 401

    def test_a_short_key_is_rejected_before_any_network_call(
        self, server: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        captured = fake_cartesia(monkeypatch, status=200)
        response = server.post(
            "/v1/providers/test", headers=AUTH, json={"provider": "cartesia", "api_key": "tiny"}
        )
        # A 422 from the request schema, and nothing was sent anywhere.
        assert response.status_code == 422
        assert "api_key" not in captured

    def test_a_transport_secret_is_refused_without_a_network_call(
        self, server: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        captured = fake_cartesia(monkeypatch, status=200)
        response = server.post(
            "/v1/providers/test",
            headers=AUTH,
            json={"provider": "cartesia", "api_key": INTERNAL_TOKEN},
        )
        assert response.status_code == 200
        assert response.json()["ok"] is False
        assert INTERNAL_TOKEN not in response.text
        assert "api_key" not in captured

    def test_an_unknown_provider_is_refused_by_the_schema(self, server: TestClient) -> None:
        response = server.post(
            "/v1/providers/test", headers=AUTH, json={"provider": "elevenlabs", "api_key": CARTESIA_KEY}
        )
        assert response.status_code == 422


class TestOutcomes:
    def test_a_working_key_reports_success(self, server: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
        fake_cartesia(monkeypatch, status=200)
        response = server.post(
            "/v1/providers/test",
            headers=AUTH,
            json={"provider": "cartesia", "api_key": CARTESIA_KEY},
        )
        body = response.json()
        assert body["ok"] is True
        assert body["key_valid"] is True
        assert body["voices_available"] == 1

    def test_a_rejected_key_reports_failure(self, server: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
        fake_cartesia(monkeypatch, status=401)
        response = server.post(
            "/v1/providers/test",
            headers=AUTH,
            json={"provider": "cartesia", "api_key": CARTESIA_KEY},
        )
        body = response.json()
        assert body["ok"] is False
        assert body["key_valid"] is False
        assert "rejected" in body["message"]

    def test_an_unreachable_provider_is_not_reported_as_a_bad_key(
        self, server: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """A network failure must not accuse the user's key of being invalid."""
        fake_cartesia(monkeypatch, status=503)
        response = server.post(
            "/v1/providers/test",
            headers=AUTH,
            json={"provider": "cartesia", "api_key": CARTESIA_KEY},
        )
        body = response.json()
        assert body["ok"] is False
        assert body["key_valid"] is None, "an unverifiable key must not be called invalid"

    def test_a_known_voice_is_confirmed(self, server: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
        fake_cartesia(monkeypatch, status=200)
        response = server.post(
            "/v1/providers/test",
            headers=AUTH,
            json={"provider": "cartesia", "api_key": CARTESIA_KEY, "voice": VOICE_ID},
        )
        body = response.json()
        assert body["ok"] is True
        assert body["voice_checked"] is True
        assert body["voice_valid"] is True

    def test_an_unknown_voice_is_reported_without_failing_the_key(
        self, server: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        fake_cartesia(monkeypatch, status=200)
        response = server.post(
            "/v1/providers/test",
            headers=AUTH,
            json={"provider": "cartesia", "api_key": CARTESIA_KEY, "voice": "not-a-real-voice"},
        )
        body = response.json()
        assert body["key_valid"] is True
        assert body["voice_valid"] is False
        assert "voice" in body["message"]

    def test_an_unsupported_model_is_reported(self, server: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
        fake_cartesia(monkeypatch, status=200)
        response = server.post(
            "/v1/providers/test",
            headers=AUTH,
            json={"provider": "cartesia", "api_key": CARTESIA_KEY, "model": "sonic-99"},
        )
        body = response.json()
        assert body["model_checked"] is True
        assert body["model_valid"] is False

    def test_a_supported_model_passes(self, server: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
        """The model check reads the installed plugin's own list, not a copy."""
        fake_cartesia(monkeypatch, status=200)
        response = server.post(
            "/v1/providers/test",
            headers=AUTH,
            json={"provider": "cartesia", "api_key": CARTESIA_KEY, "model": "sonic-3"},
        )
        assert response.json()["model_valid"] is True


class TestNothingIsStoredOrLeaked:
    def test_the_endpoint_stores_no_key(
        self, server: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        fake_cartesia(monkeypatch, status=200)
        server.post(
            "/v1/providers/test",
            headers=AUTH,
            json={"provider": "cartesia", "api_key": CARTESIA_KEY},
        )
        # Nothing was registered for any room: a tested key leaves no trace.
        from aladin import token_server as ts

        assert len(ts.session_keys) == 0

    def test_no_response_contains_the_key(
        self, server: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        for status in (200, 401, 403, 500):
            fake_cartesia(monkeypatch, status=status)
            response = server.post(
                "/v1/providers/test",
                headers=AUTH,
                json={"provider": "cartesia", "api_key": CARTESIA_KEY},
            )
            assert CARTESIA_KEY not in response.text, f"leaked on HTTP {status}"

    def test_the_key_reaches_the_provider_and_nowhere_else(
        self, server: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        captured = fake_cartesia(monkeypatch, status=200)
        server.post(
            "/v1/providers/test",
            headers=AUTH,
            json={"provider": "cartesia", "api_key": CARTESIA_KEY},
        )
        assert captured["api_key"] == CARTESIA_KEY

    def test_a_validation_error_does_not_echo_the_key(
        self, server: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        fake_cartesia(monkeypatch, status=200)
        response = server.post(
            "/v1/providers/test",
            headers=AUTH,
            json={"provider": "cartesia", "api_key": CARTESIA_KEY, "surprise": "x"},
        )
        assert response.status_code == 422
        assert CARTESIA_KEY not in response.text


class TestCartesiaClientSettings:
    def test_the_auth_header_and_version_come_from_the_plugin(self) -> None:
        """
        The test endpoint must speak to Cartesia exactly as the agent's TTS does.
        Reading these from the installed plugin is what guarantees that.
        """
        from aladin.token_server import _cartesia_client_settings

        base_url, auth_header, api_version = _cartesia_client_settings()
        assert base_url == "https://api.cartesia.ai"
        assert auth_header == "X-API-Key"
        assert api_version, "the plugin should declare an API version"

    def test_the_endpoint_mentions_no_cartesia_key_env_var(self) -> None:
        """
        The key is app-managed. A server-side env var exists only as a
        self-hosted fallback for the *agent*, so the test endpoint must not read
        the environment at all — it uses the key from the request body.
        """
        import inspect

        from aladin import token_server as ts

        source = inspect.getsource(ts.test_provider_connection)
        assert "settings.cartesia_api_key" not in source
        assert "os.environ" not in source
