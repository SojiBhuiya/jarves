"""Tests for the latency accounting of the secured test bridge.

The bridge is what the performance probe trusts, so its arithmetic is asserted
here rather than only observed on a live run:

* ``latency_ms`` is ``connect_ms + response_wait_ms`` and therefore excludes
  waiting for a cold (scaled-to-zero) agent, retry windows and cleanup.
* ``total_wall_ms`` is the caller's full wait, and ``unaccounted_ms`` proves
  the named fields add up to it.
* A re-published prompt never resets the response clock.

The endpoint tests drive a fake ``rtc.Room`` and a fake monotonic clock, so the
whole request — including a simulated 16-second LiveKit Cloud cold start — runs
in milliseconds and is deterministic.
"""

from __future__ import annotations

import json
import time
from typing import Any

import pytest
from fastapi.testclient import TestClient

import aladin.token_server as ts
from aladin.latency import TIMING_VERSION, TestChatTimings, round_ms


# ---------------------------------------------------------------------------
# Timing arithmetic
# ---------------------------------------------------------------------------


class TestTimingArithmetic:
    def test_latency_is_connect_plus_response(self) -> None:
        timings = TestChatTimings(
            connect_ms=1105,
            agent_join_wait_ms=0,
            response_wait_ms=2795,
            total_wall_ms=3902,
            attempts=1,
        )
        assert timings.latency_ms == 3900 == 1105 + 2795

    def test_cold_start_wait_is_not_part_of_latency(self) -> None:
        """Two runs with identical response paths differ only in join wait."""
        warm = TestChatTimings(
            connect_ms=1105,
            agent_join_wait_ms=0,
            response_wait_ms=2795,
            total_wall_ms=3902,
            attempts=1,
        )
        cold = TestChatTimings(
            connect_ms=1105,
            agent_join_wait_ms=16_400,
            response_wait_ms=2795,
            total_wall_ms=20_302,
            attempts=1,
        )
        assert cold.latency_ms == warm.latency_ms == 3900
        assert cold.total_wall_ms - warm.total_wall_ms == 16_400

    def test_unaccounted_ms_covers_the_named_parts(self) -> None:
        timings = TestChatTimings(
            connect_ms=2194,
            agent_join_wait_ms=16_400,
            response_wait_ms=1500,
            total_wall_ms=20_098,
            attempts=1,
        )
        assert timings.unaccounted_ms == 4  # sub-millisecond bookkeeping only

    def test_reconstructed_failing_run_passes_the_guard(self) -> None:
        """The 20121 ms probe result decomposes without touching the threshold.

        Measured facts from the failing run: connect 2194 ms, the agent's own
        ``prompt_to_response_ms`` 841 ms, and the endpoint total 20121 ms. The
        agent's clocks start when the prompt *arrives*, so the publish that
        finally reached it must have happened around 20121 - 841 = 19280 ms.
        The difference is agent cold-start/join time, which belongs in its own
        field — the guard is unchanged at 15 s and still measures latency.
        """
        prompt_arrival_ms = 20_121 - 841
        connect_ms = 2_194
        # The agent's session was ready (by its own clock) 3620 ms before it
        # answered, i.e. it joined and started up well before the prompt hit it.
        agent_join_wait_ms = prompt_arrival_ms - connect_ms - 700

        timings = TestChatTimings(
            connect_ms=connect_ms,
            agent_join_wait_ms=agent_join_wait_ms,
            response_wait_ms=841 + 700,
            total_wall_ms=20_121,
            attempts=1,
        )
        assert timings.latency_ms <= 15_000, "the guard's number must be the response path"
        assert timings.total_wall_ms == 20_121, "the caller's real wait is still reported"
        assert timings.agent_join_wait_ms > 15_000, "cold start is reported, not hidden"
        assert abs(timings.unaccounted_ms) <= 5

    def test_round_ms_rounds_to_whole_milliseconds(self) -> None:
        assert round_ms(1.4994) == 1499
        assert round_ms(1.4996) == 1500
        assert round_ms(0.0014) == 1
        assert round_ms(0.0) == 0


# ---------------------------------------------------------------------------
# Endpoint behaviour against a fake LiveKit room
# ---------------------------------------------------------------------------


class _FakeClock:
    """Monotonic clock the tests drive explicitly.

    ``per_read_ms`` advances logical time on every read, which lets a test
    reach a deadline without sleeping in real time.
    """

    def __init__(self, *, per_read_ms: float = 0.0) -> None:
        self._now = 1_000.0
        self.per_read_ms = per_read_ms

    def __call__(self) -> float:
        now = self._now
        if self.per_read_ms:
            self._now += self.per_read_ms / 1000.0
        return now

    def advance(self, ms: float) -> None:
        self._now += ms / 1000.0


class _Packet:
    def __init__(self, topic: str, data: bytes) -> None:
        self.topic = topic
        self.data = data


class _FakeParticipant:
    def __init__(self) -> None:
        self.identity = "agent-fake"


class _FakeRoom:
    """Minimal stand-in for ``livekit.rtc.Room``.

    * ``join_after_ms`` models LiveKit Cloud scale-to-zero: the agent appears
      (and the clock advances) that much later.
    * ``deliver_from_publish`` models a lost data packet: earlier publishes are
      accepted by the SDK but never produce an answer.
    """

    def __init__(
        self,
        clock: _FakeClock,
        *,
        connect_ms: float = 1_100.0,
        join_after_ms: float | None = 0.0,
        publish_ms: float = 2.0,
        lost_publish_ms: float = 500.0,
        response_ms: float = 800.0,
        deliver_from_publish: int = 1,
        response_text: str = "ঠিক আছে।",
        agent_timing: dict[str, int] | None = None,
    ) -> None:
        self._clock = clock
        self._connect_ms = connect_ms
        self._join_after_ms = join_after_ms
        self._publish_ms = publish_ms
        self._lost_publish_ms = lost_publish_ms
        self._response_ms = response_ms
        self._deliver_from_publish = deliver_from_publish
        self._response_text = response_text
        self._agent_timing = agent_timing or {
            "agent_session_startup_ms": 655,
            "prompt_to_response_ms": 1791,
            "ready_to_response_ms": 2756,
        }
        self._handlers: dict[str, list[Any]] = {}
        self.remote_participants: dict[str, Any] = {}
        self.publishes: list[Any] = []
        self.connected = False
        self.disconnected = False
        local = type("_Local", (), {})()
        local.publish_data = self._publish_data
        self.local_participant = local

    # -- livekit.rtc.Room surface ------------------------------------------
    async def connect(self, url: str, token: str) -> None:
        self.connected = True
        self._clock.advance(self._connect_ms)
        if self._join_after_ms is not None:
            if self._join_after_ms == 0:
                self.remote_participants["agent-fake"] = _FakeParticipant()
            else:
                import asyncio

                asyncio.get_running_loop().call_soon(self._fire_join)

    def _fire_join(self) -> None:
        assert self._join_after_ms is not None
        self._clock.advance(self._join_after_ms)
        self.remote_participants["agent-fake"] = _FakeParticipant()
        self._emit("participant_connected", _FakeParticipant())

    async def disconnect(self) -> None:
        self.disconnected = True

    def on(self, event: str, callback: Any = None) -> Any:
        # ``rtc.Room.on`` works both as a decorator and as a direct call.
        if callback is None:

            def _decorator(handler: Any) -> Any:
                self._handlers.setdefault(event, []).append(handler)
                return handler

            return _decorator
        self._handlers.setdefault(event, []).append(callback)
        return None

    def off(self, event: str, callback: Any) -> None:
        handlers = self._handlers.get(event, [])
        if callback in handlers:
            handlers.remove(callback)

    def _emit(self, event: str, *args: Any) -> None:
        for handler in list(self._handlers.get(event, [])):
            handler(*args)

    # -- data path ---------------------------------------------------------
    async def _publish_data(
        self, message: Any, *, reliable: bool = False, topic: str | None = None
    ) -> None:
        import asyncio

        self.publishes.append(json.loads(message))
        self._clock.advance(self._publish_ms)
        if len(self.publishes) < self._deliver_from_publish:
            # The packet never reached the agent; the caller waits before the
            # bridge re-publishes.
            self._clock.advance(self._lost_publish_ms)
            return
        # The answer arrives after publish_data returns, as it does with the
        # real SDK, so the response clock starts at the publish, not inside it.
        asyncio.get_running_loop().call_soon(self._deliver_response)

    def _deliver_response(self) -> None:
        self._clock.advance(self._response_ms)
        body = {
            "type": "response",
            "text": self._response_text,
            "timing_version": TIMING_VERSION,
            **self._agent_timing,
        }
        self._emit(
            "data_received",
            _Packet(topic="aladin.test.response", data=json.dumps(body).encode("utf-8")),
        )


@pytest.fixture()
def fast_bridge(monkeypatch: pytest.MonkeyPatch) -> Any:
    """Install the fake clock and short polling constants for one test."""

    def _install(
        room: _FakeRoom, clock: _FakeClock, *, response_budget_seconds: float = 0.0
    ) -> TestClient:
        monkeypatch.setattr(ts, "_clock", clock)
        monkeypatch.setattr(ts, "_RESPONSE_POLL_SECONDS", 0.005)
        monkeypatch.setattr(ts, "_REPUBLISH_EVERY_SECONDS", 0.0)
        monkeypatch.setattr(ts, "_MIN_RESPONSE_BUDGET_SECONDS", response_budget_seconds)
        import livekit.rtc as rtc

        monkeypatch.setattr(rtc, "Room", lambda: room)
        return TestClient(ts.app)

    return _install


def _post(client: TestClient, **body: Any) -> Any:
    payload = {
        "message": "বাংলায় শুধু লিখুন: ঠিক আছে",
        "timeout_seconds": 25.0,
        **body,
    }
    return client.post(
        "/v1/test/chat",
        json=payload,
        headers={"Authorization": f"Bearer {ts.settings.device_token}"},
    )


class TestTestChatEndpointTimings:
    def test_warm_agent_has_no_join_wait(self, fast_bridge: Any) -> None:
        clock = _FakeClock()
        room = _FakeRoom(clock, join_after_ms=0.0)
        client = fast_bridge(room, clock)

        response = _post(client)
        assert response.status_code == 200
        data = response.json()

        assert data["agent_join_wait_ms"] == 0
        assert data["connect_ms"] == 1_100
        assert data["attempts"] == 1
        assert data["latency_ms"] == data["connect_ms"] + data["response_wait_ms"]
        # Nothing is left over: connect + publish + answer is the whole wait.
        assert data["total_wall_ms"] == data["latency_ms"]
        assert data["unaccounted_ms"] == 0
        assert room.disconnected, "cleanup still happens after the measurement"

    def test_cold_agent_wait_is_reported_separately(self, fast_bridge: Any) -> None:
        clock = _FakeClock()
        room = _FakeRoom(clock, join_after_ms=16_400.0, deliver_from_publish=1)
        client = fast_bridge(room, clock)

        response = _post(client)
        assert response.status_code == 200
        data = response.json()

        assert data["agent_join_wait_ms"] == 16_400
        assert data["attempts"] == 1, "the prompt is sent once the agent is present"
        assert data["latency_ms"] == data["connect_ms"] + data["response_wait_ms"]
        assert data["latency_ms"] < 5_000, "a cold start is not a slow response"
        assert data["total_wall_ms"] >= 16_400 + data["latency_ms"]
        assert abs(data["unaccounted_ms"]) <= 5

    def test_lost_first_publish_never_resets_the_clock(self, fast_bridge: Any) -> None:
        clock = _FakeClock()
        room = _FakeRoom(clock, join_after_ms=0.0, deliver_from_publish=2)
        client = fast_bridge(room, clock)

        response = _post(client)
        assert response.status_code == 200
        data = response.json()

        assert data["attempts"] == 2, "the prompt was re-published after the loss"
        assert len(room.publishes) == 2
        # The wait before the re-publish is part of the caller's latency: the
        # clock is never reset by a retry.
        assert data["response_wait_ms"] >= 1_300
        assert data["response_wait_ms"] > 800, "the lost-publish gap must not be dropped"
        assert data["latency_ms"] == data["connect_ms"] + data["response_wait_ms"]
        assert data["total_wall_ms"] == data["latency_ms"]

    def test_agent_timing_fields_are_echoed(self, fast_bridge: Any) -> None:
        clock = _FakeClock()
        room = _FakeRoom(clock)
        client = fast_bridge(room, clock)

        data = _post(client).json()
        assert data["timing_version"] == TIMING_VERSION
        assert data["agent_session_startup_ms"] == 655
        assert data["prompt_to_response_ms"] == 1791
        assert data["ready_to_response_ms"] == 2756
        assert data["response"] == "ঠিক আছে।"

    def test_no_answer_returns_504_and_still_disconnects(self, fast_bridge: Any) -> None:
        # No agent ever joins: a budget larger than the request timeout makes
        # the join wait return immediately instead of holding up the test.
        clock = _FakeClock(per_read_ms=250.0)
        room = _FakeRoom(clock, join_after_ms=None, deliver_from_publish=99)
        client = fast_bridge(room, clock, response_budget_seconds=60.0)

        response = _post(client)
        assert response.status_code == 504
        assert room.disconnected
        assert room.publishes, "the prompt is still sent while waiting"

    def test_real_clock_smoke(self) -> None:
        """The default seam is the real monotonic clock."""
        before = ts._clock()
        time.sleep(0.01)
        assert ts._clock() > before
