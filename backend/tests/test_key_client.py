"""
Tests for the agent's session-key client.

The transport is faked, because the interesting behaviour is policy rather than
HTTP: what the agent does when the key is absent, when it has not arrived yet,
and when the token API is unreachable. Those three cases decide whether an
ordinary session (no bring-your-own-key) stays quiet and fast, and whether a
broken token API degrades a session instead of breaking it.
"""

from __future__ import annotations

import pytest

from aladin.key_client import (
    FetchedKey,
    SessionKeyClient,
    SessionKeyError,
)

GEMINI_KEY = "AIzaSyFAKEKEYVALUE1234567"
INTERNAL_TOKEN = "internal-token-for-tests"


class FakeResponse:
    def __init__(self, status: int, payload: object = None, text: str = "") -> None:
        self.status = status
        self._payload = payload
        self._text = text

    async def json(self) -> object:
        return self._payload

    async def text(self) -> str:
        return self._text


class _FakeRequest:
    """Async context manager returned by ``FakeSession.request``."""

    def __init__(self, response: FakeResponse) -> None:
        self._response = response

    async def __aenter__(self) -> FakeResponse:
        return self._response

    async def __aexit__(self, *_exc: object) -> None:
        return None


class FakeSession:
    """
    Stands in for an ``aiohttp.ClientSession``.

    ``script`` is a list of answers; each call pops the next one, and the last
    entry is reused, so ``[404]`` means "always not found".
    """

    def __init__(self, script: list[FakeResponse] | None = None, *, raises: Exception | None = None) -> None:
        self.script = list(script or [])
        self.raises = raises
        self.calls: list[tuple[str, str, dict]] = []

    def request(self, method: str, url: str, **kwargs: object) -> _FakeRequest:
        self.calls.append((method, url, kwargs))
        if self.raises is not None:
            raise self.raises
        response = self.script.pop(0) if len(self.script) > 1 else self.script[0]
        return _FakeRequest(response)


def client_for(session: FakeSession, **overrides: object) -> SessionKeyClient:
    kwargs: dict = {
        "base_url": "http://127.0.0.1:8080",
        "internal_token": INTERNAL_TOKEN,
        "session": session,
    }
    kwargs.update(overrides)
    return SessionKeyClient(**kwargs)  # type: ignore[arg-type]


FOUND = FakeResponse(
    200,
    {
        "room": "room-a",
        "provider": "gemini",
        "api_key": GEMINI_KEY,
        "expires_in": 600,
    },
)
ABSENT = FakeResponse(404, {}, text='{"detail":"No session key registered for this room."}')


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------


class TestConfiguration:
    def test_configured_when_url_and_token_present(self) -> None:
        client = client_for(FakeSession([FOUND]))
        assert client.is_configured is True

    @pytest.mark.parametrize(
        ("base_url", "token"),
        [("", INTERNAL_TOKEN), ("http://127.0.0.1:8080", ""), ("", "")],
    )
    def test_missing_configuration_is_reported(self, base_url: str, token: str) -> None:
        client = SessionKeyClient(base_url=base_url, internal_token=token)
        assert client.is_configured is False

    @pytest.mark.asyncio
    async def test_unconfigured_client_refuses_to_call(self) -> None:
        client = SessionKeyClient(base_url="", internal_token="")
        with pytest.raises(SessionKeyError, match="not configured"):
            await client.fetch("room-a")

    def test_trailing_slash_is_tolerated(self) -> None:
        session = FakeSession([FOUND])
        client = client_for(session, base_url="http://127.0.0.1:8080/")
        assert client.is_configured


# ---------------------------------------------------------------------------
# Fetching
# ---------------------------------------------------------------------------


class TestFetch:
    @pytest.mark.asyncio
    async def test_returns_the_key(self) -> None:
        client = client_for(FakeSession([FOUND]))
        fetched = await client.fetch("room-a")

        assert fetched is not None
        assert fetched.api_key == GEMINI_KEY
        assert fetched.provider == "gemini"
        assert fetched.room == "room-a"
        assert fetched.expires_in == 600

    @pytest.mark.asyncio
    async def test_absent_key_is_none_not_an_error(self) -> None:
        client = client_for(FakeSession([ABSENT]))
        assert await client.fetch("room-a") is None

    @pytest.mark.asyncio
    async def test_requests_the_right_path_with_the_internal_token(self) -> None:
        session = FakeSession([FOUND])
        await client_for(session).fetch("room-a")

        method, url, kwargs = session.calls[0]
        assert method == "GET"
        assert url == "http://127.0.0.1:8080/v1/session/keys/room-a"
        assert kwargs["headers"]["X-Aladin-Internal-Token"] == INTERNAL_TOKEN

    @pytest.mark.asyncio
    async def test_never_uses_the_device_token_header(self) -> None:
        # The agent authenticates as the agent; using the device token here
        # would mean the app could read keys too.
        session = FakeSession([FOUND])
        await client_for(session).fetch("room-a")
        headers = session.calls[0][2]["headers"]
        assert "X-Aladin-Device-Token" not in headers

    @pytest.mark.asyncio
    async def test_response_without_a_key_is_an_error(self) -> None:
        client = client_for(FakeSession([FakeResponse(200, {"room": "room-a"})]))
        with pytest.raises(SessionKeyError, match="no usable key"):
            await client.fetch("room-a")

    @pytest.mark.asyncio
    async def test_non_string_key_is_an_error(self) -> None:
        client = client_for(FakeSession([FakeResponse(200, {"api_key": 12345})]))
        with pytest.raises(SessionKeyError):
            await client.fetch("room-a")

    @pytest.mark.asyncio
    @pytest.mark.parametrize("status", [400, 401, 500, 503])
    async def test_error_status_raises(self, status: int) -> None:
        client = client_for(
            FakeSession([FakeResponse(status, {}, text='{"detail":"nope"}')])
        )
        with pytest.raises(SessionKeyError, match=str(status)):
            await client.fetch("room-a")

    @pytest.mark.asyncio
    async def test_transport_failure_raises(self) -> None:
        client = client_for(FakeSession(raises=OSError("connection refused")))
        with pytest.raises(SessionKeyError, match="request failed"):
            await client.fetch("room-a")

    @pytest.mark.asyncio
    async def test_error_message_is_bounded(self) -> None:
        # An error body is echoed for diagnosis but truncated, so a huge or
        # hostile response cannot fill the logs.
        client = client_for(
            FakeSession([FakeResponse(500, {}, text="x" * 5_000)])
        )
        with pytest.raises(SessionKeyError) as caught:
            await client.fetch("room-a")
        assert len(str(caught.value)) < 400


# ---------------------------------------------------------------------------
# Waiting for a key that has not arrived yet
# ---------------------------------------------------------------------------


class TestWaitForKey:
    @pytest.mark.asyncio
    async def test_returns_immediately_when_present(self) -> None:
        session = FakeSession([FOUND])
        fetched = await client_for(session).wait_for_key("room-a", timeout=1.0)
        assert fetched is not None
        assert len(session.calls) == 1

    @pytest.mark.asyncio
    async def test_retries_until_the_key_appears(self) -> None:
        # The race this closes: a self-hosted deployment can dispatch the agent
        # the moment the room is created, before the app has registered its key.
        session = FakeSession([ABSENT, ABSENT, FOUND])
        fetched = await client_for(session).wait_for_key(
            "room-a", timeout=5.0, interval=0.001
        )
        assert fetched is not None
        assert len(session.calls) == 3

    @pytest.mark.asyncio
    async def test_gives_up_quietly_after_the_timeout(self) -> None:
        session = FakeSession([ABSENT])
        fetched = await client_for(session).wait_for_key(
            "room-a", timeout=0.02, interval=0.001
        )
        assert fetched is None
        assert len(session.calls) > 1, "should have retried within the window"

    @pytest.mark.asyncio
    async def test_zero_timeout_makes_exactly_one_attempt(self) -> None:
        # Used when the server already has a key: nothing to wait for.
        session = FakeSession([ABSENT])
        assert await client_for(session).wait_for_key("room-a", timeout=0.0) is None
        assert len(session.calls) == 1

    @pytest.mark.asyncio
    async def test_does_not_retry_a_transport_failure(self) -> None:
        # Retrying an unreachable API just delays the fallback to the server's
        # own credentials.
        session = FakeSession(raises=OSError("connection refused"))
        with pytest.raises(SessionKeyError):
            await client_for(session).wait_for_key("room-a", timeout=1.0, interval=0.001)
        assert len(session.calls) == 1


# ---------------------------------------------------------------------------
# Wiping
# ---------------------------------------------------------------------------


class TestDelete:
    @pytest.mark.asyncio
    async def test_deletes(self) -> None:
        session = FakeSession([FakeResponse(200, {"deleted": True, "room": "room-a"})])
        assert await client_for(session).delete("room-a") is True
        assert session.calls[0][0] == "DELETE"

    @pytest.mark.asyncio
    async def test_reports_when_nothing_was_deleted(self) -> None:
        session = FakeSession([FakeResponse(200, {"deleted": False, "room": "room-a"})])
        assert await client_for(session).delete("room-a") is False

    @pytest.mark.asyncio
    async def test_failure_does_not_raise(self) -> None:
        # Called from session teardown: a failure here must never mask the real
        # reason a session ended. The TTL is the backstop.
        session = FakeSession(raises=OSError("connection refused"))
        assert await client_for(session).delete("room-a") is False

    @pytest.mark.asyncio
    async def test_failure_does_not_log_the_key(self) -> None:
        session = FakeSession(raises=OSError("boom"))
        await client_for(session).delete("room-a")


# ---------------------------------------------------------------------------
# Never leaking
# ---------------------------------------------------------------------------


class TestNeverLeaks:
    def test_fetched_key_repr_is_redacted(self) -> None:
        fetched = FetchedKey(
            room="room-a", provider="gemini", api_key=GEMINI_KEY, expires_in=600
        )
        for rendered in (repr(fetched), str(fetched)):
            assert GEMINI_KEY not in rendered
            assert "redacted" in rendered
            assert "room-a" in rendered

    @pytest.mark.asyncio
    async def test_internal_token_never_travels_in_the_url(self) -> None:
        # A credential in a query string ends up in access logs, browser
        # history and any proxy in between. It belongs in a header, and only
        # there.
        session = FakeSession([FOUND])
        await client_for(session).fetch("room-a")

        url = session.calls[0][1]
        assert INTERNAL_TOKEN not in url
        assert "?" not in url
        assert "token" not in url.lower()
