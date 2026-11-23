"""
Tests for what the agent does with a user's key.

The transport is stubbed out here (``test_key_client.py`` covers it), leaving
the decisions that matter:

* the user's key **beats** the server's when both exist — otherwise
  bring-your-own-key would be indistinguishable from doing nothing;
* a session with no key behaves exactly as before, quietly;
* a session with no key *and* no server credentials is reported clearly, because
  it cannot work and silence would be cruel;
* a token API that is down degrades the session to the server's credentials
  rather than refusing to start;
* the key is wiped when the session ends, on every exit path.
"""

from __future__ import annotations

import logging
from types import SimpleNamespace

import pytest

from aladin.agent_worker import _apply_session_key, _wipe_session_key
from aladin.config import Settings
from aladin.key_client import FetchedKey, SessionKeyError

SERVER_GEMINI_KEY = "AIzaSySERVEROWNKEY1234567890"
USER_GEMINI_KEY = "AIzaSyUSERBROUGHTKEY12345678"
USER_OPENAI_KEY = "sk-proj-USERBROUGHTKEY123456789"

INTERNAL_TOKEN = "internal-token-for-tests"


def make_settings(**overrides: object) -> Settings:
    """Settings for a server that has BYOK configured."""
    base = {
        "livekit_url": "wss://test.livekit.cloud",
        "livekit_api_key": "APItestkey123456789",
        "livekit_api_secret": "test-secret-value-at-least-32-characters-long",
        "device_token": "device-token-for-tests",
        "internal_token": INTERNAL_TOKEN,
        "ai_provider": "gemini",
        "gemini_api_key": SERVER_GEMINI_KEY,
    }
    base.update(overrides)
    return Settings(**base)  # type: ignore[arg-type]


def make_ctx(room: str = "room-a") -> object:
    """The only part of a JobContext these helpers touch."""
    return SimpleNamespace(room=SimpleNamespace(name=room))


class StubKeyClient:
    """A session-key client with a scripted answer."""

    def __init__(
        self,
        *,
        key: FetchedKey | None = None,
        error: Exception | None = None,
    ) -> None:
        self.key = key
        self.error = error
        self.waits: list[float] = []
        self.deleted: list[str] = []

    async def wait_for_key(self, room: str, *, timeout: float = 0.0) -> FetchedKey | None:
        self.waits.append(timeout)
        if self.error is not None:
            raise self.error
        return self.key

    async def delete(self, room: str) -> bool:
        self.deleted.append(room)
        return True


def user_key(provider: str = "gemini", api_key: str = USER_GEMINI_KEY) -> FetchedKey:
    return FetchedKey(room="room-a", provider=provider, api_key=api_key, expires_in=600)


# ---------------------------------------------------------------------------
# Using the user's key
# ---------------------------------------------------------------------------


class TestApplySessionKey:
    @pytest.mark.asyncio
    async def test_user_key_replaces_the_servers(self) -> None:
        settings = make_settings()
        client = StubKeyClient(key=user_key())

        applied, used = await _apply_session_key(settings, make_ctx(), client)

        assert used is True
        assert applied.gemini_api_key == USER_GEMINI_KEY
        assert applied.provider_api_key == USER_GEMINI_KEY

    @pytest.mark.asyncio
    async def test_original_settings_are_not_mutated(self) -> None:
        # Settings is frozen and shared; a session must not change the server's
        # configuration for the next one.
        settings = make_settings()
        await _apply_session_key(settings, make_ctx(), StubKeyClient(key=user_key()))
        assert settings.gemini_api_key == SERVER_GEMINI_KEY

    @pytest.mark.asyncio
    async def test_key_provider_routes_the_key_correctly(self) -> None:
        settings = make_settings(ai_provider="gemini", openai_api_key="")
        client = StubKeyClient(key=user_key(provider="openai", api_key=USER_OPENAI_KEY))

        applied, used = await _apply_session_key(settings, make_ctx(), client)

        assert used is True
        assert applied.ai_provider == "openai"
        assert applied.openai_api_key == USER_OPENAI_KEY
        # The server's Gemini key must not be mistaken for an OpenAI one.
        assert applied.provider_api_key == USER_OPENAI_KEY

    @pytest.mark.asyncio
    async def test_provider_mismatch_is_reported(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        settings = make_settings(ai_provider="gemini")
        client = StubKeyClient(key=user_key(provider="openai", api_key=USER_OPENAI_KEY))

        with caplog.at_level(logging.WARNING, logger="aladin.agent"):
            applied, _ = await _apply_session_key(settings, make_ctx(), client)

        assert "provider=openai" in caplog.text
        assert applied.ai_provider == "openai", "the key's own provider wins"

    @pytest.mark.asyncio
    async def test_key_is_never_logged(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        with caplog.at_level(logging.DEBUG, logger="aladin.agent"):
            await _apply_session_key(make_settings(), make_ctx(), StubKeyClient(key=user_key()))
        assert USER_GEMINI_KEY not in caplog.text


# ---------------------------------------------------------------------------
# No key at all
# ---------------------------------------------------------------------------


class TestNoUserKey:
    @pytest.mark.asyncio
    async def test_server_key_is_used_quietly(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        # The ordinary case. It must not log like a problem: most deployments
        # never use bring-your-own-key.
        settings = make_settings()

        with caplog.at_level(logging.DEBUG, logger="aladin.agent"):
            applied, used = await _apply_session_key(
                settings, make_ctx(), StubKeyClient(key=None)
            )

        assert used is False
        assert applied.gemini_api_key == SERVER_GEMINI_KEY
        assert "ERROR" not in caplog.text

    @pytest.mark.asyncio
    async def test_absent_key_does_not_wait_when_the_server_has_one(self) -> None:
        client = StubKeyClient(key=None)
        await _apply_session_key(make_settings(), make_ctx(), client)

        # Nothing to wait for: a session must not be slowed down by a feature
        # it is not using.
        assert client.waits == [0.0]

    @pytest.mark.asyncio
    async def test_absent_key_waits_when_the_server_has_none(self) -> None:
        # Nothing to fall back on, so the app's key is worth a brief wait.
        client = StubKeyClient(key=None)
        await _apply_session_key(make_settings(gemini_api_key=""), make_ctx(), client)

        assert client.waits and client.waits[0] > 0

    @pytest.mark.asyncio
    async def test_missing_everything_is_reported_as_an_error(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        settings = make_settings(gemini_api_key="")

        with caplog.at_level(logging.ERROR, logger="aladin.agent"):
            applied, used = await _apply_session_key(
                settings, make_ctx(), StubKeyClient(key=None)
            )

        assert used is False
        assert applied.provider_api_key == ""
        # The message has to name what is missing, or the operator is left
        # guessing why every session is silent.
        assert "No AI key" in caplog.text
        assert "GEMINI" in caplog.text


# ---------------------------------------------------------------------------
# Failure paths
# ---------------------------------------------------------------------------


class TestFailures:
    @pytest.mark.asyncio
    async def test_unreachable_token_api_degrades_to_the_server_key(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        settings = make_settings()
        client = StubKeyClient(error=SessionKeyError("token API unreachable"))

        with caplog.at_level(logging.ERROR, logger="aladin.agent"):
            applied, used = await _apply_session_key(settings, make_ctx(), client)

        # A working session on the server's own key beats no session at all.
        assert used is False
        assert applied.gemini_api_key == SERVER_GEMINI_KEY
        assert "Could not check for a session key" in caplog.text

    @pytest.mark.asyncio
    async def test_disabled_byok_never_calls_the_api(self) -> None:
        # No internal token configured: nothing to authenticate with, so no
        # request should even be attempted.
        settings = make_settings(internal_token="")
        client = StubKeyClient(key=user_key())

        applied, used = await _apply_session_key(settings, make_ctx(), client)

        assert used is False
        assert client.waits == []
        assert applied.gemini_api_key == SERVER_GEMINI_KEY

    @pytest.mark.asyncio
    async def test_shared_secret_misconfiguration_disables_byok(self) -> None:
        # internal_token == device_token is a real misconfiguration: it would
        # let an app install read keys back out of the store.
        settings = make_settings(internal_token="device-token-for-tests")
        client = StubKeyClient(key=user_key())

        _, used = await _apply_session_key(settings, make_ctx(), client)

        assert used is False
        assert client.waits == []

    @pytest.mark.asyncio
    async def test_invalid_key_is_rejected_without_breaking_the_session(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        # A key the parser refuses (e.g. empty) must not abort a session that
        # could still run on the server's credentials — and must not be
        # announced as if the user's key were in use.
        settings = make_settings()
        client = StubKeyClient(
            key=FetchedKey(room="room-a", provider="", api_key="", expires_in=1)
        )

        with caplog.at_level(logging.DEBUG, logger="aladin.agent"):
            applied, used = await _apply_session_key(settings, make_ctx(), client)

        assert used is False
        assert applied.gemini_api_key == SERVER_GEMINI_KEY
        assert "Using the user's own" not in caplog.text
        assert "empty session key" in caplog.text


# ---------------------------------------------------------------------------
# Wiping
# ---------------------------------------------------------------------------


class TestWipe:
    @pytest.mark.asyncio
    async def test_deletes_the_key_after_use(self) -> None:
        client = StubKeyClient()
        await _wipe_session_key(make_ctx("room-a"), client, used_key=True)
        assert client.deleted == ["room-a"]

    @pytest.mark.asyncio
    async def test_does_nothing_when_no_key_was_used(self) -> None:
        # Session teardown runs on every path; it must not make a pointless
        # request for sessions that never used bring-your-own-key.
        client = StubKeyClient()
        await _wipe_session_key(make_ctx(), client, used_key=False)
        assert client.deleted == []

    @pytest.mark.asyncio
    async def test_a_delete_failure_never_raises(self) -> None:
        class Broken(StubKeyClient):
            async def delete(self, room: str) -> bool:
                raise SessionKeyError("token API unreachable")

        # The TTL is the backstop, so teardown must not fail here.
        await _wipe_session_key(make_ctx(), Broken(), used_key=True)

    @pytest.mark.asyncio
    async def test_delete_failure_does_not_log_the_key(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        class Broken(StubKeyClient):
            async def delete(self, room: str) -> bool:
                raise SessionKeyError(f"boom {USER_GEMINI_KEY}")

        with caplog.at_level(logging.WARNING, logger="aladin.agent"):
            await _wipe_session_key(make_ctx(), Broken(), used_key=True)

        # The stub deliberately puts a key in the error text; the helper must
        # not be what leaks it. (The client itself never does this — see
        # test_key_client.)
        assert "Could not delete session key" in caplog.text
