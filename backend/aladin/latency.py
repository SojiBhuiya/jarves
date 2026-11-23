"""Latency accounting for the secured text bridge (``POST /v1/test/chat``).

The test bridge is the only place where Aladin's responsiveness is measured, so
every number it reports has to mean exactly one thing:

``connect_ms``
    LiveKit room connection only (``rtc.Room.connect``).
``agent_join_wait_ms``
    Time spent waiting for the dispatched Aladin agent to appear in the room.
    On LiveKit Cloud the agent scales to zero, so a cold start shows up *here*
    and is never folded into ``latency_ms``.
``response_wait_ms``
    Successful prompt publish -> assistant response received. Measured from the
    first publish, so a re-publish after a lost packet never resets the clock.
``latency_ms``
    ``connect_ms + response_wait_ms`` — the part of the caller's wait Aladin is
    responsible for. Waiting for a cold agent, retrying a publish and
    disconnecting are deliberately excluded.
``total_wall_ms``
    The endpoint's full wall clock: what the caller actually waited. It equals
    ``latency_ms + agent_join_wait_ms`` plus sub-millisecond bookkeeping; that
    difference is exposed as ``unaccounted_ms`` so nothing can hide in it.

The agent reports its own clocks alongside these (``agent_session_startup_ms``,
``prompt_to_response_ms``, ``ready_to_response_ms``); they are measured inside
the agent and are labelled as such in the response.
"""

from __future__ import annotations

from dataclasses import dataclass

#: Bumped whenever the meaning of a reported timing field changes, so a probe
#: result can never be compared against one produced by different semantics.
TIMING_VERSION = 4


def round_ms(seconds: float) -> int:
    """Round a seconds delta to whole milliseconds."""
    return int(round(seconds * 1000))



@dataclass(frozen=True)
class TestChatTimings:
    """Immutable snapshot of one measured test-bridge request."""

    connect_ms: int
    agent_join_wait_ms: int
    response_wait_ms: int
    total_wall_ms: int
    attempts: int

    @property
    def latency_ms(self) -> int:
        """Connect + response: excludes cold-start waits and cleanup."""
        return self.connect_ms + self.response_wait_ms

    @property
    def unaccounted_ms(self) -> int:
        """``total_wall_ms`` minus the parts we can name; normally ~0 ms."""
        return self.total_wall_ms - self.agent_join_wait_ms - self.latency_ms

    def as_payload_fields(self) -> dict[str, int]:
        """Flatten into the JSON fields :class:`TestChatResponse` exposes."""
        return {
            "latency_ms": self.latency_ms,
            "connect_ms": self.connect_ms,
            "agent_join_wait_ms": self.agent_join_wait_ms,
            "response_wait_ms": self.response_wait_ms,
            "total_wall_ms": self.total_wall_ms,
            "unaccounted_ms": self.unaccounted_ms,
            "attempts": self.attempts,
        }
