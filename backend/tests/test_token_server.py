"""
Tests for the token API.

These are end-to-end in the way that matters: a real LiveKit JWT is minted with
the same code path production uses, then **cryptographically verified** with the
API secret. Signing is local, so this proves the whole token flow works without
touching the network.

The security assertions here are the important ones — the token API is the only
place where the LiveKit signing secret is used, and the only place the app can
influence agent configuration.
"""

from __future__ import annotations

import json
import os

# The token server reads its configuration at import time, so the environment
# must be populated before the module is imported below.
TEST_API_KEY = "APItestkey123456789"
TEST_API_SECRET = "test-secret-value-at-least-32-characters-long"
TEST_DEVICE_TOKEN = "device-token-for-tests"
TEST_LIVEKIT_URL = "wss://test-project.livekit.cloud"
GEMINI_KEY = "AIzaSyFAKEKEYVALUE1234567"

os.environ.update(
    {
        "LIVEKIT_URL": TEST_LIVEKIT_URL,
        "LIVEKIT_API_KEY": TEST_API_KEY,
        "LIVEKIT_API_SECRET": TEST_API_SECRET,
        "ALADIN_DEVICE_TOKEN": TEST_DEVICE_TOKEN,
        "GEMINI_API_KEY": GEMINI_KEY,
        "ALADIN_AI_PROVIDER": "gemini",
        "ALADIN_TOKEN_TTL_SECONDS": "600",
        "ALADIN_LOG_LEVEL": "WARNING",
    }
)

import jwt  # noqa: E402
import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from aladin.token_server import (  # noqa: E402
    APP_CONFIGURABLE_KEYS,
    _RateLimiter,
    app,
)

AUTH = {"Authorization": f"Bearer {TEST_DEVICE_TOKEN}"}


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def decode(token: str) -> dict:
    """Verify a minted token's signature and return its claims."""
    return jwt.decode(token, TEST_API_SECRET, algorithms=["HS256"])


# ---------------------------------------------------------------------------
# public endpoints
# ---------------------------------------------------------------------------


class TestHealth:
    def test_ok_without_auth(self, client: TestClient) -> None:
        response = client.get("/health")
        assert response.status_code == 200
        assert response.json()["status"] == "ok"

    def test_leaks_no_secrets(self, client: TestClient) -> None:
        body = client.get("/health").text
        for secret in (TEST_API_SECRET, TEST_DEVICE_TOKEN, GEMINI_KEY):
            assert secret not in body


class TestPublicConfig:
    def test_ok_without_auth(self, client: TestClient) -> None:
        assert client.get("/v1/config").status_code == 200

    def test_leaks_no_secrets(self, client: TestClient) -> None:
        body = client.get("/v1/config").text
        for secret in (TEST_API_SECRET, TEST_DEVICE_TOKEN, GEMINI_KEY):
            assert secret not in body

    def test_reports_available_options(self, client: TestClient) -> None:
        data = client.get("/v1/config").json()
        assert data["available_providers"] == ["gemini", "openai"]
        assert data["available_languages"] == ["bn", "en", "hi"]
        assert data["languages"] == ["bn", "en", "hi"]

    def test_byo_key_not_advertised_when_unconfigured(self, client: TestClient) -> None:
        # This server has no ALADIN_INTERNAL_TOKEN, so the agent would have no
        # way to fetch a stored key. The flag must stay false rather than
        # promising a feature that cannot work.
        #
        # The configured case lives in test_byok_endpoints.py, which replaces
        # the module's settings object.
        assert client.get("/v1/config").json()["supports_bring_your_own_key"] is False


# ---------------------------------------------------------------------------
# authentication
# ---------------------------------------------------------------------------


class TestAuthentication:
    def test_missing_token_is_401(self, client: TestClient) -> None:
        assert client.post("/v1/token", json={}).status_code == 401

    def test_wrong_token_is_401(self, client: TestClient) -> None:
        response = client.post(
            "/v1/token", json={}, headers={"Authorization": "Bearer wrong-token"}
        )
        assert response.status_code == 401

    def test_header_variant_works(self, client: TestClient) -> None:
        response = client.post(
            "/v1/token", json={}, headers={"X-Aladin-Device-Token": TEST_DEVICE_TOKEN}
        )
        assert response.status_code == 200

    def test_no_token_is_ever_issued_unauthenticated(self, client: TestClient) -> None:
        for headers in ({}, {"Authorization": "Bearer nope"}):
            body = client.post("/v1/token", json={}, headers=headers).text
            assert "token" not in body.lower() or "detail" in body.lower()

    def test_401_carries_www_authenticate(self, client: TestClient) -> None:
        response = client.post("/v1/token", json={})
        assert "WWW-Authenticate" in response.headers


# ---------------------------------------------------------------------------
# token minting
# ---------------------------------------------------------------------------


class TestTokenMinting:
    def test_returns_valid_signed_token(self, client: TestClient) -> None:
        response = client.post("/v1/token", json={}, headers=AUTH)
        assert response.status_code == 200

        claims = decode(response.json()["token"])
        assert claims["iss"] == TEST_API_KEY
        assert claims["sub"]  # identity

    def test_returns_connection_details(self, client: TestClient) -> None:
        data = client.post("/v1/token", json={}, headers=AUTH).json()
        assert data["url"] == TEST_LIVEKIT_URL
        assert data["room"]
        assert data["identity"]
        assert data["expires_in"] == 600

    def test_grants_are_minimal_but_sufficient(self, client: TestClient) -> None:
        claims = decode(client.post("/v1/token", json={}, headers=AUTH).json()["token"])
        video = claims["video"]

        # The app needs to join, publish its mic, hear the agent, and exchange
        # captions over data messages.
        assert video["roomJoin"] is True
        assert video["canPublish"] is True
        assert video["canSubscribe"] is True
        assert video["canPublishData"] is True

        # It must not be able to administer the server.
        assert video.get("roomAdmin") is not True
        assert video.get("roomCreate") is not True
        assert video.get("roomList") is not True

    def test_grant_is_scoped_to_one_room(self, client: TestClient) -> None:
        data = client.post("/v1/token", json={"room": "my-room"}, headers=AUTH).json()
        assert decode(data["token"])["video"]["room"] == "my-room"

    def test_token_expiry_matches_ttl(self, client: TestClient) -> None:
        claims = decode(client.post("/v1/token", json={}, headers=AUTH).json()["token"])
        # Short-lived by design: a leaked token should expire quickly.
        assert claims["exp"] - claims["nbf"] == 600

    def test_signature_from_a_different_secret_is_rejected(self, client: TestClient) -> None:
        token = client.post("/v1/token", json={}, headers=AUTH).json()["token"]
        with pytest.raises(jwt.InvalidSignatureError):
            jwt.decode(token, "a-completely-different-secret-value", algorithms=["HS256"])

    def test_identity_is_echoed(self, client: TestClient) -> None:
        data = client.post(
            "/v1/token", json={"identity": "my-phone"}, headers=AUTH
        ).json()
        assert data["identity"] == "my-phone"
        assert decode(data["token"])["sub"] == "my-phone"


# ---------------------------------------------------------------------------
# input sanitisation
# ---------------------------------------------------------------------------


class TestSanitisation:
    def test_room_name_is_generated_when_omitted(self, client: TestClient) -> None:
        room = client.post("/v1/token", json={}, headers=AUTH).json()["room"]
        assert room.startswith("aladin-room-")

    def test_unsafe_characters_stripped_from_room(self, client: TestClient) -> None:
        data = client.post(
            "/v1/token", json={"room": "../../etc/passwd; DROP TABLE"}, headers=AUTH
        ).json()
        assert "/" not in data["room"]
        assert ";" not in data["room"]
        assert " " not in data["room"]

    def test_unsafe_characters_stripped_from_identity(self, client: TestClient) -> None:
        data = client.post(
            "/v1/token", json={"identity": "user@example.com<script>"}, headers=AUTH
        ).json()
        assert "@" not in data["identity"]
        assert "<" not in data["identity"]

    def test_blank_room_falls_back_to_generated(self, client: TestClient) -> None:
        data = client.post("/v1/token", json={"room": "   "}, headers=AUTH).json()
        assert data["room"].startswith("aladin-room-")

    def test_malformed_body_is_422(self, client: TestClient) -> None:
        response = client.post("/v1/token", json={"room": "x" * 5000}, headers=AUTH)
        assert response.status_code == 422


# ---------------------------------------------------------------------------
# config -> room metadata (and the secret boundary)
# ---------------------------------------------------------------------------


class TestAgentConfig:
    def test_accepted_preferences_are_validated(self, client: TestClient) -> None:
        response = client.post(
            "/v1/token",
            json={
                "config": {
                    "ai_provider": "openai",
                    "voice": "cedar",
                    "languages": ["bn", "en"],
                    "web_search": False,
                }
            },
            headers=AUTH,
        )
        assert response.status_code == 200

    def test_unsupported_language_is_rejected(self, client: TestClient) -> None:
        response = client.post(
            "/v1/token",
            json={"config": {"languages": ["bn", "fr"]}},
            headers=AUTH,
        )
        assert response.status_code == 422

    def test_unsupported_provider_is_rejected(self, client: TestClient) -> None:
        response = client.post(
            "/v1/token", json={"config": {"ai_provider": "mistral"}}, headers=AUTH
        )
        assert response.status_code == 422

    def test_temperature_out_of_range_rejected(self, client: TestClient) -> None:
        response = client.post(
            "/v1/token", json={"config": {"temperature": 9.5}}, headers=AUTH
        )
        assert response.status_code == 422

    def test_secret_keys_are_not_configurable(self) -> None:
        # The single most important invariant in this file: a client must never
        # be able to push an AI key through room metadata, because room
        # metadata is visible to every participant in the room.
        for forbidden in (
            "provider_api_key",
            "gemini_api_key",
            "openai_api_key",
            "api_key",
            "device_token",
        ):
            assert forbidden not in APP_CONFIGURABLE_KEYS

    def test_secret_key_in_payload_is_rejected_not_ignored(
        self, client: TestClient
    ) -> None:
        # extra="forbid" means this is a 422 rather than a silent drop — the
        # client learns it sent something it should not have.
        response = client.post(
            "/v1/token",
            json={"config": {"provider_api_key": GEMINI_KEY}},
            headers=AUTH,
        )
        assert response.status_code == 422
        # The key must not be reflected back to the caller...
        assert GEMINI_KEY not in response.text
        # ...but the caller must still learn that credentials are unwelcome.
        assert "credentials are not accepted here" in response.text

    def test_invalid_body_values_are_not_echoed(self, client: TestClient) -> None:
        # A 422 must not turn into an echo service for arbitrary input.
        response = client.post(
            "/v1/token",
            json={"config": {"ai_provider": "AIzaSyFAKEKEYVALUE1234567"}},
            headers=AUTH,
        )
        assert response.status_code == 422
        assert "AIzaSyFAKEKEYVALUE1234567" not in response.text

    def test_extra_unknown_field_is_rejected(self, client: TestClient) -> None:
        response = client.post(
            "/v1/token", json={"config": {"inject": "something"}}, headers=AUTH
        )
        assert response.status_code == 422


# ---------------------------------------------------------------------------
# rate limiting
# ---------------------------------------------------------------------------


class TestRateLimiter:
    def test_allows_up_to_limit(self) -> None:
        limiter = _RateLimiter(max_requests=3, window_seconds=60)
        assert all(limiter.check("client") for _ in range(3))

    def test_blocks_beyond_limit(self) -> None:
        limiter = _RateLimiter(max_requests=3, window_seconds=60)
        for _ in range(3):
            limiter.check("client")
        assert limiter.check("client") is False

    def test_clients_are_isolated(self) -> None:
        limiter = _RateLimiter(max_requests=1, window_seconds=60)
        assert limiter.check("a") is True
        assert limiter.check("b") is True
        assert limiter.check("a") is False

    def test_window_expiry_frees_capacity(self) -> None:
        limiter = _RateLimiter(max_requests=1, window_seconds=0.0)
        assert limiter.check("client") is True
        assert limiter.check("client") is True
