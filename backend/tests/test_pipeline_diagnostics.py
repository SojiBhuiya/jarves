"""
Pipeline diagnostics: stage timings and provider-failure reporting.

The point of these tests is the *meaning* of each published number. The
requirement was explicit that a text event must never be presented as audio
latency, so the assertions here check both the values **and** the labels that
accompany them: ``tts_first_audio_ms`` must come from the TTS provider's
time-to-first-audio-byte, and ``llm_first_token_ms`` must be labelled as text.

The other half is failure reporting: when a component fails, the client must be
told *which provider* failed. A session that quietly stops using Cartesia and
carries on is the outcome the product spec forbids.

Everything here is **unit** testing of the recorder plus **mocked integration**
through the data topic: a fake room captures what would be published. No test
asserts that audio was heard.

Events are emitted from inside a running loop, because that is where LiveKit
invokes them and where :meth:`_publish` schedules its task. Emitting from a
synchronous test would exercise a path that never happens in production.
"""

from __future__ import annotations

import asyncio
import json

import pytest
from livekit.agents.metrics import EOUMetrics, LLMMetrics, TTSMetrics

from aladin import pipeline_diagnostics as pd
from aladin.config import Settings
from aladin.voice_pipeline import build_voice_components, resolve_voice_plan

GEMINI_KEY = "AIzaSyTESTKEYVALUE1234567890"
CARTESIA_KEY = "cartesia-test-key-abcdef123456"


# ---------------------------------------------------------------------------
# Doubles
# ---------------------------------------------------------------------------


class FakeParticipant:
    """Captures published packets instead of sending them over a data channel."""

    def __init__(self) -> None:
        self.published: list[tuple[str, dict]] = []

    async def publish_data(self, data: bytes, reliable: bool = True, topic: str = "") -> None:
        self.published.append((topic, json.loads(data.decode("utf-8"))))

    def packets(self, kind: str) -> list[dict]:
        return [payload for _, payload in self.published if payload.get("type") == kind]


class FakeRoom:
    def __init__(self) -> None:
        self.local_participant = FakeParticipant()


class FakeSession:
    """Records the handlers ``attach`` registers, so tests can fire events."""

    def __init__(self) -> None:
        self.handlers: dict[str, object] = {}

    def on(self, name: str):
        def decorate(func):
            self.handlers[name] = func
            return func

        return decorate

    def emit(self, name: str, event: object) -> None:
        handler = self.handlers[name]
        handler(event)  # type: ignore[operator]


class FakeClock:
    """A monotonic clock the test advances by hand."""

    def __init__(self, start: float = 100.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def event(**kwargs: object) -> object:
    """A stand-in for a LiveKit event object with arbitrary attributes."""
    return type("Event", (), kwargs)()


def modular_settings(**overrides: object) -> Settings:
    base: dict[str, object] = {
        "voice_mode": "modular",
        "ai_provider": "gemini",
        "gemini_api_key": GEMINI_KEY,
        "cartesia_api_key": CARTESIA_KEY,
    }
    base.update(overrides)
    return Settings(**base)  # type: ignore[arg-type]


def make_recorder(settings: Settings | None = None, clock: FakeClock | None = None):
    settings = settings or modular_settings()
    clock = clock or FakeClock()
    plan = resolve_voice_plan(settings)
    components = build_voice_components(settings, plan)
    room = FakeRoom()
    recorder = pd.PipelineDiagnostics(
        room=room, settings=settings, plan=plan, components=components, clock=clock
    )
    session = FakeSession()
    recorder.attach(session)
    return recorder, session, room, clock


# ---------------------------------------------------------------------------
# Wiring
# ---------------------------------------------------------------------------


class TestWiring:
    def test_it_subscribes_to_every_event_it_needs(self) -> None:
        _, session, _, _ = make_recorder()
        assert set(session.handlers) == {
            "metrics_collected",
            "user_input_transcribed",
            "conversation_item_added",
            "agent_state_changed",
            "error",
        }

    def test_it_publishes_on_its_own_topic(self) -> None:
        # Separate from aladin.state so an older app ignores it rather than
        # trying to render a diagnostics payload as agent state.
        assert pd.DIAGNOSTICS_TOPIC == "aladin.diagnostics"
        assert pd.DIAGNOSTICS_TOPIC != "aladin.state"


# ---------------------------------------------------------------------------
# Session ready
# ---------------------------------------------------------------------------


class TestSessionReady:
    @pytest.mark.asyncio
    async def test_it_announces_the_architecture_and_providers(self) -> None:
        recorder, _, room, clock = make_recorder()
        recorder.mark_session_started()
        clock.advance(1.5)
        recorder.mark_session_ready()
        await asyncio.sleep(0.01)

        packet = room.local_participant.packets("session_ready")[0]
        assert packet["voice_mode"] == "modular"
        assert packet["tts_provider"] == "cartesia"
        assert packet["stt_provider"] == "cartesia"
        assert packet["llm_provider"] == "gemini"
        assert packet["session_startup_ms"] == 1500
        assert packet["timing_version"] == pd.TIMING_VERSION

    @pytest.mark.asyncio
    async def test_a_fallback_session_says_so(self) -> None:
        """A user must be able to tell they are not hearing Cartesia."""
        settings = modular_settings(cartesia_api_key="")
        recorder, _, room, clock = make_recorder(settings)
        recorder.mark_session_started()
        clock.advance(1.0)
        recorder.mark_session_ready()
        await asyncio.sleep(0.01)

        packet = room.local_participant.packets("session_ready")[0]
        assert packet["voice_mode"] == "realtime"
        assert packet["fallback"]["reason"] == "cartesia_key_missing"
        assert packet["fallback"]["active_mode"] == "realtime"

    @pytest.mark.asyncio
    async def test_a_normal_session_reports_no_fallback(self) -> None:
        recorder, _, room, clock = make_recorder()
        recorder.mark_session_started()
        clock.advance(0.5)
        recorder.mark_session_ready()
        await asyncio.sleep(0.01)
        assert room.local_participant.packets("session_ready")[0]["fallback"] is None


# ---------------------------------------------------------------------------
# Stage timings
# ---------------------------------------------------------------------------


class TestStageTimings:
    def test_stt_partial_and_final_are_measured_from_the_turn_start(self) -> None:
        recorder, session, _, clock = make_recorder()
        recorder.mark_user_turn_started()
        clock.advance(0.28)
        session.emit("user_input_transcribed", event(is_final=False))
        clock.advance(0.22)
        session.emit("user_input_transcribed", event(is_final=True))

        stages = recorder.snapshot()
        assert stages["stt_first_partial_ms"] == 280
        assert stages["stt_final_ms"] == 500

    def test_the_first_partial_is_not_overwritten_by_a_later_one(self) -> None:
        recorder, session, _, clock = make_recorder()
        recorder.mark_user_turn_started()
        clock.advance(0.10)
        session.emit("user_input_transcribed", event(is_final=False))
        clock.advance(0.40)
        session.emit("user_input_transcribed", event(is_final=False))
        assert recorder.snapshot()["stt_first_partial_ms"] == 100

    def test_llm_first_token_comes_from_the_provider_metric(self) -> None:
        recorder, session, _, _ = make_recorder()
        metrics = LLMMetrics.model_construct(
            type="llm_metrics", ttft=0.42, duration=1.1, timestamp=1.0
        )
        session.emit("metrics_collected", event(metrics=metrics))
        stages = recorder.snapshot()
        assert stages["llm_first_token_ms"] == 420
        assert stages["llm_total_ms"] == 1100

    def test_tts_first_audio_comes_from_the_audio_byte_metric(self) -> None:
        """
        The accuracy requirement: "first audio" is the TTS provider's own
        time-to-first-byte, not a text event wearing an audio label.
        """
        recorder, session, _, _ = make_recorder()
        metrics = TTSMetrics.model_construct(
            type="tts_metrics", ttfb=0.17, duration=0.8, timestamp=1.0
        )
        session.emit("metrics_collected", event(metrics=metrics))
        assert recorder.snapshot()["tts_first_audio_ms"] == 170

    @pytest.mark.asyncio
    async def test_the_published_packet_labels_which_numbers_are_audio(self) -> None:
        recorder, session, room, clock = make_recorder()
        recorder.mark_user_turn_started()
        session.emit(
            "metrics_collected",
            event(
                metrics=EOUMetrics.model_construct(
                    timestamp=1.0,
                    end_of_utterance_delay=0.4,
                    transcription_delay=0.1,
                    on_user_turn_completed_delay=0.0,
                )
            ),
        )
        session.emit(
            "metrics_collected",
            event(metrics=LLMMetrics.model_construct(type="llm_metrics", ttft=0.5, duration=1.0, timestamp=2.0)),
        )
        session.emit(
            "metrics_collected",
            event(metrics=TTSMetrics.model_construct(type="tts_metrics", ttfb=0.2, duration=0.9, timestamp=3.0)),
        )
        await asyncio.sleep(0.01)

        packet = room.local_participant.packets("pipeline_timings")[0]
        sources = packet["sources"]
        # Audio, and says so.
        assert "audio" in sources["tts_first_audio_ms"]
        # Text, and says so.
        assert "text" in sources["llm_first_token_ms"]
        # Derived, and says so.
        assert "derived" in sources["end_of_speech_to_first_audio_ms"]

    def test_the_total_is_the_sum_of_measured_parts(self) -> None:
        recorder, session, _, _ = make_recorder()
        for metrics in (
            EOUMetrics.model_construct(
                timestamp=1.0,
                end_of_utterance_delay=0.4,
                transcription_delay=0.1,
                on_user_turn_completed_delay=0.0,
            ),
            LLMMetrics.model_construct(type="llm_metrics", ttft=0.5, duration=1.0, timestamp=2.0),
            TTSMetrics.model_construct(type="tts_metrics", ttfb=0.2, duration=0.9, timestamp=3.0),
        ):
            session.emit("metrics_collected", event(metrics=metrics))
        assert recorder.snapshot()["end_of_speech_to_first_audio_ms"] == 400 + 500 + 200

    def test_the_total_is_absent_rather_than_guessed(self) -> None:
        """With a component unmeasured, no total is published at all."""
        recorder, session, _, _ = make_recorder()
        session.emit(
            "metrics_collected",
            event(
                metrics=EOUMetrics.model_construct(
                    timestamp=1.0,
                    end_of_utterance_delay=0.4,
                    transcription_delay=0.1,
                    on_user_turn_completed_delay=0.0,
                )
            ),
        )
        assert "end_of_speech_to_first_audio_ms" not in recorder.snapshot()

    def test_a_new_turn_resets_the_stage_marks(self) -> None:
        recorder, session, _, clock = make_recorder()
        recorder.mark_user_turn_started()
        clock.advance(0.3)
        session.emit("user_input_transcribed", event(is_final=False))
        assert "stt_first_partial_ms" in recorder.snapshot()

        recorder.mark_user_turn_started()
        assert recorder.snapshot() == {}

    def test_the_assistant_reply_is_counted_not_captured(self) -> None:
        """Diagnostics must not become a transcript store."""
        recorder, session, _, _ = make_recorder()
        item = type("Item", (), {"role": "assistant", "text_content": "hello there"})()
        session.emit("conversation_item_added", event(item=item))
        stages = recorder.snapshot()
        assert stages["assistant_chars"] == len("hello there")
        assert "hello there" not in json.dumps(stages)

    def test_a_listening_state_starts_a_turn(self) -> None:
        recorder, session, _, _ = make_recorder()
        session.emit("agent_state_changed", event(new_state="listening"))
        # A turn has started, so a transcript now has something to measure from.
        session.emit("user_input_transcribed", event(is_final=False))
        assert "stt_first_partial_ms" in recorder.snapshot()


# ---------------------------------------------------------------------------
# Failures
# ---------------------------------------------------------------------------


class TestProviderFailures:
    @pytest.mark.asyncio
    async def test_a_cartesia_failure_names_cartesia(self) -> None:
        recorder, session, room, _ = make_recorder()
        source = type("TTS", (), {"provider": "Cartesia", "model": "sonic-3"})()
        session.emit("error", event(source=source, error=RuntimeError("boom")))
        await asyncio.sleep(0.01)

        packet = room.local_participant.packets("provider_error")[0]
        assert packet["provider"] == "Cartesia"
        assert packet["selected_tts_provider"] == "cartesia"
        assert packet["stage"] == "TTS"

    @pytest.mark.asyncio
    async def test_the_failure_message_does_not_relay_the_provider_error_body(self) -> None:
        """
        A provider's error text is outside our control and could echo a key, so
        only the exception *class* is published.
        """
        recorder, session, room, _ = make_recorder()
        source = type("TTS", (), {"provider": "Cartesia", "model": "sonic-3"})()
        leaky = RuntimeError(f"401 unauthorized for key {CARTESIA_KEY}")
        session.emit("error", event(source=source, error=leaky))
        await asyncio.sleep(0.01)

        packet = room.local_participant.packets("provider_error")[0]
        assert CARTESIA_KEY not in json.dumps(packet)
        assert packet["error_type"] == "RuntimeError"
        assert "Cartesia" in packet["detail"]

    def test_the_failure_is_still_logged_in_full_for_operators(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        """The detail has to go *somewhere* or the failure is undebuggable."""
        recorder, session, _, _ = make_recorder()
        source = type("TTS", (), {"provider": "Cartesia", "model": "sonic-3"})()
        with caplog.at_level("WARNING"):
            session.emit("error", event(source=source, error=RuntimeError("upstream said no")))
        assert "upstream said no" in caplog.text
        assert "Cartesia" in caplog.text

    def test_the_failure_detail_says_which_stage_in_words(self) -> None:
        from aladin.pipeline_diagnostics import _failure_detail

        assert "speech synthesis" in _failure_detail("Cartesia", "TTS")
        assert "transcription" in _failure_detail("Cartesia", "STT")
        assert "reasoning" in _failure_detail("Gemini", "LLM")


class TestResilience:
    """Diagnostics must never be able to break the conversation."""

    def test_a_handler_raising_does_not_propagate(self) -> None:
        recorder, session, _, _ = make_recorder()
        # An event whose attributes explode when read.
        class Exploding:
            @property
            def is_final(self) -> bool:
                raise RuntimeError("attribute exploded")

        session.emit("user_input_transcribed", Exploding())

    def test_publishing_without_a_room_participant_is_survivable(self) -> None:
        settings = modular_settings()
        plan = resolve_voice_plan(settings)
        components = build_voice_components(settings, plan)

        class NoParticipantRoom:
            local_participant = None

        recorder = pd.PipelineDiagnostics(
            room=NoParticipantRoom(), settings=settings, plan=plan, components=components
        )
        recorder._publish({"type": "test"})

    def test_returning_to_the_loop_is_required_not_assumed(self) -> None:
        """
        Called with no running loop (as a bare synchronous call would be), the
        packet is dropped rather than left as a never-awaited coroutine.
        """
        recorder, _, room, _ = make_recorder()
        recorder._publish({"type": "sync_call"})
        assert room.local_participant.published == []
