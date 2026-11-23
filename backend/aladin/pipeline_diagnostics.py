"""
Latency diagnostics and provider-failure reporting for the voice pipeline.

What this module is for
-----------------------
The modular pipeline (STT -> LLM -> Cartesia TTS) has more moving parts than
the realtime one, so "the reply felt slow" needs to be attributable to a stage
rather than to the whole session. This module turns the metrics LiveKit already
emits into a per-turn stage breakdown and publishes it on its own data topic.

Accuracy rules — what each number is allowed to claim
----------------------------------------------------
The requirement was explicit that a *text* event must never be labelled as audio
latency, so every mark says where it came from:

``tts_first_audio_ms``
    ``TTSMetrics.ttfb`` — the TTS provider's time to **first byte of audio**.
    This is a real audio measurement, and it is the only field here that may be
    called "first audio".
``llm_first_token_ms``
    ``LLMMetrics.ttft`` — the LLM's time to first **token**. That is text, not
    sound, and it is labelled as text.
``stt_first_partial_ms`` / ``stt_final_ms``
    The agent's own monotonic clock from the start of the user's turn to the
    first interim / the final transcript event. These are *agent* clocks: they
    say when the agent was told about speech, which is strictly later than the
    sound arriving.
``end_of_speech_to_first_audio_ms``
    A **derived** total, and reported as such: the end-of-utterance delay
    (``EOUMetrics``) plus the LLM's first token plus the TTS's first audio byte.
    It is a sum of measured components — not an independent stopwatch reading —
    so it is published under a name that describes exactly that composition, and
    never as "total latency".

The payload carries no transcript text and no credential: counts, durations,
provider names and a ``timing_version``. Anything unknown to a client is
ignored by the app, which is what lets this topic be additive.

Failures
--------
When a component raises, the error is *classified and named* — which provider,
which stage — and published as a ``provider_error`` event, because a silent
switch away from the selected voice is precisely what the product must not do.
The exception's own message is logged (through the same redaction filter the
rest of the backend uses) but never published, since a provider's error body is
outside our control.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any

from .config import Settings
from .logging_setup import redact_secrets_in_text
from .voice_pipeline import VoiceComponents, VoicePlan

logger = logging.getLogger("aladin.pipeline_diagnostics")

__all__ = [
    "DIAGNOSTICS_TOPIC",
    "TIMING_VERSION",
    "PipelineDiagnostics",
]

#: Where pipeline timings and provider failures are published. A separate topic
#: from ``aladin.state`` on purpose: the app renders state every turn, and a
#: diagnostics payload arriving there would either break an older client or
#: force state and telemetry to share a schema.
DIAGNOSTICS_TOPIC = "aladin.diagnostics"

#: Bumped when the *meaning* of a published field changes, so a stored sample
#: can never be compared against one produced by different semantics.
TIMING_VERSION = 1

#: Stages as they are named to the client.
STAGE_STT_FIRST_PARTIAL = "stt_first_partial_ms"
STAGE_STT_FINAL = "stt_final_ms"
STAGE_LLM_REQUEST = "llm_request_start_ms"
STAGE_LLM_FIRST_TOKEN = "llm_first_token_ms"
STAGE_LLM_TOTAL = "llm_total_ms"
STAGE_TTS_REQUEST = "tts_request_start_ms"
STAGE_TTS_FIRST_AUDIO = "tts_first_audio_ms"
STAGE_TTS_TOTAL = "tts_total_ms"
STAGE_END_OF_SPEECH = "end_of_utterance_ms"
STAGE_RESPONSE = "end_of_speech_to_first_audio_ms"


def _ms(seconds: float | None) -> int | None:
    """Seconds to whole milliseconds, preserving ``None`` as "not measured"."""
    if seconds is None:
        return None
    return int(round(float(seconds) * 1000))


class PipelineDiagnostics:
    """
    Subscribes to one ``AgentSession`` and publishes its stage timings.

    One instance per session. Every method is safe to call on a session that has
    already ended: publishing is best-effort and never raises into the voice
    loop, which is the same rule the caption publisher follows.
    """

    def __init__(
        self,
        *,
        room: Any,
        settings: Settings,
        plan: VoicePlan,
        components: VoiceComponents,
        clock: Any | None = None,
    ) -> None:
        self._room = room
        self._settings = settings
        self._plan = plan
        self._components = components
        # Injectable so tests can drive the agent-side clocks deterministically.
        self._clock = clock or time.perf_counter

        #: Stage marks for the interaction currently in flight.
        self._stages: dict[str, Any] = {}
        #: When this session became ready, for the startup figure.
        self._session_started_at: float | None = None
        self._session_ready_at: float | None = None
        #: When the user's current turn began, per the agent's own clock.
        self._turn_started_at: float | None = None
        #: Set once any TTS audio has been measured for this session, so the
        #: "first audio" field is a fact about this session, not about a
        #: particular turn's ordering.
        self._tts_first_audio_seen = False

    # ------------------------------------------------------------------ api ---

    def mark_session_started(self) -> None:
        """Record that the session is being started (before ``session.start``)."""
        self._session_started_at = self._clock()

    def mark_session_ready(self) -> None:
        """Record that the session finished starting."""
        self._session_ready_at = self._clock()
        self._publish_session_ready()

    def mark_user_turn_started(self) -> None:
        """Record the start of a new user turn, resetting per-turn stages."""
        self._turn_started_at = self._clock()
        self._stages = {}

    def attach(self, session: Any) -> None:
        """
        Wire this recorder to ``session``.

        Every handler is defensive: a diagnostics callback that raises would
        take down the voice session it is measuring, which would be a
        spectacularly bad trade.
        """

        @session.on("metrics_collected")
        def _on_metrics(event: Any) -> None:
            self._handle_metrics(getattr(event, "metrics", event))

        @session.on("user_input_transcribed")
        def _on_transcript(event: Any) -> None:
            self._handle_transcript(event)

        @session.on("conversation_item_added")
        def _on_item(event: Any) -> None:
            self._handle_item(event)

        @session.on("agent_state_changed")
        def _on_state(event: Any) -> None:
            self._handle_state(event)

        @session.on("error")
        def _on_error(event: Any) -> None:
            self._handle_error(event)

    def snapshot(self) -> dict[str, Any]:
        """The current stage marks, as published (JSON-safe, no secrets)."""
        return dict(self._stages)

    # -------------------------------------------------------------- handlers ---

    def _handle_transcript(self, event: Any) -> None:
        """
        STT marks.

        ``is_final`` distinguishes the two, and the first partial is recorded
        only once per turn so a later interim does not overwrite the earliest
        evidence that speech was recognised.
        """
        try:
            if getattr(event, "is_final", False):
                if self._turn_started_at is not None:
                    self._stages.setdefault(
                        STAGE_STT_FINAL,
                        _ms(self._clock() - self._turn_started_at),
                    )
            elif STAGE_STT_FIRST_PARTIAL not in self._stages:
                if self._turn_started_at is not None:
                    self._stages[STAGE_STT_FIRST_PARTIAL] = _ms(
                        self._clock() - self._turn_started_at
                    )
        except Exception as exc:  # pragma: no cover - diagnostics must not raise
            logger.debug("diagnostics: transcript handling failed: %s", exc)

    def _handle_metrics(self, metrics: Any) -> None:
        """Map LiveKit's per-component metrics onto the stage breakdown."""
        try:
            kind = getattr(metrics, "type", None)
            if kind == "llm_metrics":
                # ttft is the provider's own time-to-first-token: text, and
                # labelled as text.
                self._stages.setdefault(STAGE_LLM_FIRST_TOKEN, _ms(getattr(metrics, "ttft", None)))
                self._stages.setdefault(STAGE_LLM_TOTAL, _ms(getattr(metrics, "duration", None)))
            elif kind == "tts_metrics":
                # ttfb is time to first byte of *audio*: the only mark that may
                # be presented as "first audio".
                ttfb = _ms(getattr(metrics, "ttfb", None))
                self._stages.setdefault(STAGE_TTS_FIRST_AUDIO, ttfb)
                self._stages.setdefault(STAGE_TTS_TOTAL, _ms(getattr(metrics, "duration", None)))
                if ttfb is not None and not self._tts_first_audio_seen:
                    self._tts_first_audio_seen = True
                    self._publish_turn()
            elif kind == "eou_metrics":
                delay = getattr(metrics, "end_of_utterance_delay", None)
                self._stages.setdefault(STAGE_END_OF_SPEECH, _ms(delay))
                self._recompute_total()
        except Exception as exc:  # pragma: no cover
            logger.debug("diagnostics: metrics handling failed: %s", exc)

    def _recompute_total(self) -> None:
        """
        Compose the derived end-of-speech → first-audio figure.

        Only computed from parts that were actually measured: if the LLM or TTS
        mark is missing, the total is left absent rather than guessed at.
        """
        parts = [
            self._stages.get(STAGE_END_OF_SPEECH),
            self._stages.get(STAGE_LLM_FIRST_TOKEN),
            self._stages.get(STAGE_TTS_FIRST_AUDIO),
        ]
        if any(part is None for part in parts):
            return
        self._stages[STAGE_RESPONSE] = sum(int(part) for part in parts)

    def _handle_item(self, event: Any) -> None:
        """Record that the assistant produced a reply (counts, never text)."""
        try:
            item = getattr(event, "item", None)
            if item is None or getattr(item, "role", None) != "assistant":
                return
            text = getattr(item, "text_content", None) or ""
            self._stages.setdefault("assistant_chars", len(str(text)))
        except Exception as exc:  # pragma: no cover
            logger.debug("diagnostics: item handling failed: %s", exc)

    def _handle_state(self, event: Any) -> None:
        """A new user turn begins when the agent starts listening."""
        try:
            if getattr(event, "new_state", None) == "listening":
                self.mark_user_turn_started()
        except Exception as exc:  # pragma: no cover
            logger.debug("diagnostics: state handling failed: %s", exc)

    def _handle_error(self, event: Any) -> None:
        """
        Classify a component failure and report *which provider* failed.

        The source object on LiveKit's error event knows its own provider, so
        this does not have to guess from the message. Nothing from the
        exception's body is published; the full text goes to the log through the
        standard redaction filter.
        """
        try:
            source = getattr(event, "source", None)
            error = getattr(event, "error", None)
            provider = str(getattr(source, "provider", "") or "")
            stage = type(source).__name__ if source is not None else "unknown"
            error_type = type(error).__name__ if error is not None else "Error"

            logger.warning(
                "Voice pipeline component failed: stage=%s provider=%s error=%s: %s",
                stage,
                provider or "<unknown>",
                error_type,
                redact_secrets_in_text(str(error))[:300],
            )

            payload = {
                "type": "provider_error",
                "stage": stage,
                "provider": provider,
                # The exception *class*, never its message: a provider's error
                # body is outside our control and could echo a credential.
                "error_type": error_type,
                "selected_tts_provider": self._plan.tts_provider,
                "voice_mode": self._plan.mode,
                "timing_version": TIMING_VERSION,
                "detail": _failure_detail(provider, stage),
            }
            self._publish(payload)
        except Exception as exc:  # pragma: no cover
            logger.debug("diagnostics: error handling failed: %s", exc)

    # ------------------------------------------------------------- publishing ---

    def _publish_session_ready(self) -> None:
        """Announce the session's architecture and startup cost."""
        startup = None
        if self._session_started_at is not None and self._session_ready_at is not None:
            startup = _ms(self._session_ready_at - self._session_started_at)
        self._publish(
            {
                "type": "session_ready",
                "voice_mode": self._plan.mode,
                "llm_provider": self._plan.llm_provider,
                "stt_provider": self._components.stt_provider,
                "tts_provider": self._components.tts_provider,
                "turn_detection": self._plan.turn_detection,
                "session_startup_ms": startup,
                "timing_version": TIMING_VERSION,
                # Reported so a client can say "Cartesia is speaking" honestly,
                # and so a fallback session is visibly a fallback session.
                "fallback": self._plan.fallback.as_payload()
                if self._plan.fallback
                else None,
            }
        )

    def _publish_turn(self) -> None:
        """Publish the completed stage breakdown for the turn just finished."""
        self._recompute_total()
        self._publish(
            {
                "type": "pipeline_timings",
                "voice_mode": self._plan.mode,
                "stt_provider": self._components.stt_provider,
                "tts_provider": self._components.tts_provider,
                "stages": dict(self._stages),
                # States plainly which numbers are audio and which are not, so
                # a client never has to infer it.
                "sources": {
                    STAGE_TTS_FIRST_AUDIO: "tts provider time to first audio byte (measured)",
                    STAGE_LLM_FIRST_TOKEN: "llm provider time to first token (text)",
                    STAGE_END_OF_SPEECH: "livekit end-of-utterance delay",
                    STAGE_RESPONSE: "derived: end_of_utterance + llm_first_token + tts_first_audio",
                },
                "timing_version": TIMING_VERSION,
            }
        )

    def _publish(self, payload: dict[str, Any]) -> None:
        """
        Publish one diagnostics packet. Never raises.

        Same contract as the caption publisher in ``agent_worker``: telemetry is
        never allowed to break the conversation, so a failure here is logged and
        dropped. The packet is a JSON string and the transport call runs as a
        task, so a slow data channel cannot stall the voice loop either.

        Requires a running event loop, which every real caller has: these
        handlers are invoked by LiveKit from inside the session's loop. When one
        is not running (a synchronous test calling a handler directly) the packet
        is dropped rather than half-published — hence the explicit check instead
        of a bare ``ensure_future``, which would leave a never-awaited coroutine
        behind.
        """
        try:
            data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            local_participant = getattr(self._room, "local_participant", None)
            if local_participant is None:
                return
            try:
                loop = asyncio.get_running_loop()
            except RuntimeError:
                logger.debug(
                    "diagnostics: no event loop; dropped a %s packet",
                    payload.get("type"),
                )
                return
            loop.create_task(
                local_participant.publish_data(
                    data, reliable=True, topic=DIAGNOSTICS_TOPIC
                )
            )
        except Exception as exc:  # pragma: no cover
            logger.debug("diagnostics: publish failed: %s", exc)


#: Stage class name -> the thing a user would call it. LiveKit's error event
#: names the source by class (``TTS``, ``STT``, ``LLM``, ``RealtimeModel``), and
#: "the TTS provider failed" is less clear than "speech synthesis failed".
_STAGE_WORDS: dict[str, str] = {
    "TTS": "speech synthesis",
    "STT": "transcription",
    "LLM": "reasoning",
    "RealtimeModel": "realtime voice",
}


def _failure_detail(provider: str, stage: str) -> str:
    """
    A short, safe explanation of a component failure.

    Deliberately generic: the goal is to tell the user *which* provider failed
    and that the failure was recorded, not to relay a provider's raw error text.
    """
    what = _STAGE_WORDS.get(stage, "voice")
    if provider:
        return (
            f"The {provider} provider failed at {what} during this session. "
            "The failure was recorded; the session continues on the configured "
            "fallback where one exists."
        )
    return (
        f"A {what} component failed during this session. The failure was "
        "recorded; the session continues where possible."
    )
