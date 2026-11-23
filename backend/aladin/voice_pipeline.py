"""
The modular voice pipeline: **STT -> LLM -> Cartesia TTS**.

This module is the resolver between "what the user selected" and "what the
agent actually runs". It exists so that ``agent_worker.py`` never has to know
which provider is which:

.. code-block:: text

   Settings (provider names + credentials)
        │
        ▼
   resolve_voice_plan()      ← decides the architecture, and whether a fallback
        │                       is needed *before* anything is constructed
        ▼
   build_voice_components()  ← asks aladin.providers for the components
        │
        ▼
   session_options()         ← turn detection, endpointing, interruption rules
        │
        ▼
   AgentSession(stt=…, llm=…, tts=…)

Two architectures, one entry point
----------------------------------
``modular``
    The primary path. Independent STT, LLM and TTS stages, so the transcriber,
    the reasoner and the voice can each be swapped without touching the others.
    Cartesia is the default voice.
``realtime``
    The original speech-to-speech model (Gemini Live). Unchanged, and still
    reachable by selecting it — and used as the *fallback* when the modular
    pipeline cannot be constructed.

Fallback policy, stated precisely
---------------------------------
The one thing this module refuses to do is lie about which voice is speaking.

* **Before construction.** If the modular pipeline cannot be built at all (no
  transcriber available, or the LLM credential is missing), the plan falls back
  to the realtime pipeline and carries a :class:`PipelineFallback` explaining
  exactly why. The caller publishes that as a diagnostic event.
* **Never a silent TTS swap.** If Cartesia is the selected voice and its key is
  missing, this module does **not** quietly build Gemini's TTS and let the user
  believe they heard Cartesia. It reports ``tts_provider_unavailable`` and falls
  back to the realtime pipeline, which is audible *and* labelled.
* **Mid-session.** A provider failing while the session is live is handled by
  :mod:`aladin.pipeline_diagnostics`, which records the failure and tells the
  client which provider failed. The session itself stays up when LiveKit can
  keep it up.

Why the fallback is Gemini Live and not "Gemini TTS"
---------------------------------------------------
Because that is what was specified, and because it is the honest option: a
different TTS provider is a different voice, and swapping it invisibly makes
"Cartesia is configured" untrue. Gemini Live is a distinct, self-describing
mode, so a session that fell back is visibly a session that fell back.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any

from .config import Settings, SettingsError
from .providers import (
    build_llm,
    build_realtime_model,
    build_stt,
    build_tts,
    describe_voice_pipeline,
)

logger = logging.getLogger("aladin.voice_pipeline")

__all__ = [
    "MODULAR_MODE",
    "REALTIME_MODE",
    "PipelineFallback",
    "VoiceComponents",
    "VoicePlan",
    "build_voice_components",
    "load_vad",
    "resolve_voice_plan",
    "session_options",
]

MODULAR_MODE = "modular"
REALTIME_MODE = "realtime"

#: Turn-detection modes the modular pipeline can use, best first. ``vad`` gives
#: the snappiest endpointing and lets a barge-in interrupt before the transcript
#: is final; ``stt`` uses the transcriber's own endpointing and needs no VAD at
#: all. The pipeline picks the first one its installed plugins can support.
_VAD_TURN_DETECTION = "vad"
_STT_TURN_DETECTION = "stt"


@dataclass(frozen=True, slots=True)
class PipelineFallback:
    """
    Why a session is not running the pipeline that was selected.

    Carried in the plan rather than logged and forgotten, so the reason reaches
    (a) the log, (b) the client as a diagnostic event, and (c) any test that
    wants to assert the fallback was *reported* rather than silently taken.
    """

    #: Machine-readable cause, e.g. ``cartesia_key_missing``.
    reason: str
    #: The mode that was requested but could not run.
    requested_mode: str
    #: The mode that will run instead.
    active_mode: str
    #: One sentence a human can act on. Never contains a credential.
    detail: str

    def as_payload(self) -> dict[str, str]:
        """JSON-safe form for the diagnostics topic."""
        return {
            "reason": self.reason,
            "requested_mode": self.requested_mode,
            "active_mode": self.active_mode,
            "detail": self.detail,
        }


@dataclass(frozen=True, slots=True)
class VoicePlan:
    """
    The architecture a session will actually run, before anything is built.

    Resolving first and constructing second is what makes the fallback
    *decidable*: a missing credential is a fact about settings, so it can be
    named exactly ("cartesia_api_key_missing") instead of being inferred from a
    provider SDK's stack trace.
    """

    #: ``modular`` or ``realtime``.
    mode: str
    #: Provider names, for logs and diagnostics. In realtime mode ``llm`` is the
    #: realtime provider; ``stt``/``tts`` are empty because the realtime model
    #: owns both halves.
    llm_provider: str
    stt_provider: str
    tts_provider: str
    #: How turn detection will run, once the components are known.
    turn_detection: str
    #: Set when the requested pipeline could not run. ``None`` when the session
    #: runs exactly what was asked for.
    fallback: PipelineFallback | None = None

    @property
    def is_fallback(self) -> bool:
        """Whether this plan differs from what the settings selected."""
        return self.fallback is not None

    def describe(self) -> dict[str, Any]:
        """Log-safe summary. Contains no credential values."""
        return {
            "mode": self.mode,
            "llm_provider": self.llm_provider,
            "stt_provider": self.stt_provider or "<none>",
            "tts_provider": self.tts_provider or "<none>",
            "turn_detection": self.turn_detection,
            "fallback": self.fallback.as_payload() if self.fallback else None,
        }


@dataclass(slots=True)
class VoiceComponents:
    """
    The constructed pieces of one session, plus what they ended up being.

    The provider names are recorded next to the objects because the two can
    differ: ``stt_provider`` is what ``auto`` resolved to, which is only known
    after construction.
    """

    mode: str
    llm: Any = None
    stt: Any = None
    tts: Any = None
    vad: Any = None
    stt_provider: str = ""
    tts_provider: str = ""
    plan: VoicePlan | None = None
    #: The turn-detection mode these components can actually run. It lives here
    #: rather than only on the plan because it is not knowable until the VAD
    #: question is answered: the plan says what was intended, this says what was
    #: built, and ``session_options`` must use the latter.
    turn_detection: str = ""
    #: Extra facts worth reporting (e.g. why a VAD was unavailable).
    notes: list[str] = field(default_factory=list)

    def describe(self) -> dict[str, Any]:
        """Log-safe summary of what was actually built."""
        return {
            "mode": self.mode,
            "llm": type(self.llm).__name__ if self.llm is not None else None,
            "stt_provider": self.stt_provider or None,
            "tts_provider": self.tts_provider or None,
            "vad": type(self.vad).__name__ if self.vad is not None else None,
            "turn_detection": self.turn_detection or None,
            "notes": list(self.notes),
        }


# --------------------------------------------------------------------------
# VAD
# --------------------------------------------------------------------------


def load_vad() -> tuple[Any | None, str]:
    """
    Load a local voice-activity detector, or explain why there is none.

    Returns ``(vad_or_none, note)``. A missing VAD is not an error: the modular
    pipeline can run on the transcriber's endpointing alone, and this function
    reports that so the session can choose ``turn_detection="stt"`` instead of
    failing.

    The Silero plugin ships its ONNX model inside the wheel, so this loads
    without a network fetch or a separate ``download-files`` step. It is only
    *used* when it is importable — the same deliberate laziness the rest of this
    module applies to provider SDKs, so importing the pipeline never costs a
    model load.
    """
    try:
        from livekit.plugins import silero
    except ImportError as exc:
        note = (
            "silero VAD plugin is not installed; using the transcriber's own "
            f"endpointing for turn detection ({exc})"
        )
        logger.info("Modular pipeline: %s", note)
        return None, note

    try:
        vad = silero.VAD.load()
    except Exception as exc:  # pragma: no cover - depends on the ONNX runtime
        note = f"silero VAD could not be loaded ({exc}); using STT endpointing"
        logger.warning("Modular pipeline: %s", note)
        return None, note

    logger.info("Modular pipeline: silero VAD loaded")
    return vad, "silero VAD active"


# --------------------------------------------------------------------------
# Resolution
# --------------------------------------------------------------------------


def _missing_key_reason(settings: Settings) -> str | None:
    """
    Why the modular pipeline cannot be built, or ``None`` if it can.

    Checked *before* construction so the reason is a statement about the
    configuration rather than a reprised exception from a provider SDK. The
    wording is deliberately about what is missing, never about a value.
    """
    if not settings.provider_api_key:
        return "llm_key_missing"
    if not settings.resolved_stt_provider:
        return "stt_provider_unavailable"
    if settings.tts_provider == "cartesia" and not settings.cartesia_api_key:
        # The headline case: Cartesia is the selected voice but there is no key.
        return "cartesia_key_missing"
    return None


def resolve_voice_plan(settings: Settings) -> VoicePlan:
    """
    Decide which architecture this session runs. Never raises.

    A configuration problem is reported as a fallback, not an exception, because
    the agent must still be able to talk: refusing to start a session because a
    *voice* setting is wrong would be worse than speaking in the fallback voice
    and saying so.
    """
    if settings.voice_mode == REALTIME_MODE:
        return VoicePlan(
            mode=REALTIME_MODE,
            llm_provider=settings.ai_provider,
            stt_provider="",
            tts_provider="",
            # The realtime model owns turn detection.
            turn_detection="realtime_llm",
        )

    missing = _missing_key_reason(settings)
    if missing is None:
        return VoicePlan(
            mode=MODULAR_MODE,
            llm_provider=settings.ai_provider,
            stt_provider=settings.resolved_stt_provider,
            tts_provider=settings.tts_provider,
            # Refined once the VAD question is answered; the placeholder is the
            # better of the two modes so a caller that never asks is sane.
            turn_detection=_VAD_TURN_DETECTION,
        )

    detail = _fallback_detail(missing, settings)
    logger.warning(
        "Modular pipeline cannot start (%s); falling back to the realtime "
        "pipeline. %s",
        missing,
        detail,
    )
    return VoicePlan(
        mode=REALTIME_MODE,
        llm_provider=settings.ai_provider,
        stt_provider="",
        tts_provider="",
        turn_detection="realtime_llm",
        fallback=PipelineFallback(
            reason=missing,
            requested_mode=MODULAR_MODE,
            active_mode=REALTIME_MODE,
            detail=detail,
        ),
    )


def _fallback_detail(reason: str, settings: Settings) -> str:
    """A sentence explaining a fallback, in terms the user can act on."""
    if reason == "cartesia_key_missing":
        return (
            "Cartesia is selected as the voice provider but no Cartesia API key "
            "is configured, and Aladin will not substitute a different voice "
            "silently. Add a Cartesia key in the app's API settings, or set "
            "ALADIN_TTS_PROVIDER to a provider that is configured."
        )
    if reason == "llm_key_missing":
        return (
            f"The modular pipeline needs a {settings.ai_provider} key for its "
            "reasoning stage and none is configured."
        )
    if reason == "stt_provider_unavailable":
        return (
            "The modular pipeline needs a speech-to-text provider and none is "
            "available: it uses the Cartesia key or the selected AI provider's "
            "key, and neither is configured."
        )
    return "The modular pipeline could not be constructed."


# --------------------------------------------------------------------------
# Construction
# --------------------------------------------------------------------------


def build_voice_components(settings: Settings, plan: VoicePlan) -> VoiceComponents:
    """
    Construct the components ``plan`` calls for.

    Raises:
        SettingsError: if a component cannot be built. The caller decides
            whether to fall back; this function never does it silently, because
            a construction failure *after* the plan was resolved is a different
            thing from the configuration problem the plan already reasoned about.
    """
    if plan.mode == REALTIME_MODE:
        model = build_realtime_model(settings)
        logger.info("Voice pipeline ready: %s", describe_voice_pipeline(settings))
        return VoiceComponents(
            mode=REALTIME_MODE,
            llm=model,
            plan=plan,
            turn_detection="realtime_llm",
        )

    llm = build_llm(settings)
    stt = build_stt(settings)
    tts = build_tts(settings)

    vad, note = load_vad()
    turn_detection = _VAD_TURN_DETECTION if vad is not None else _STT_TURN_DETECTION

    components = VoiceComponents(
        mode=MODULAR_MODE,
        llm=llm,
        stt=stt,
        tts=tts,
        vad=vad,
        stt_provider=settings.resolved_stt_provider,
        tts_provider=settings.tts_provider,
        plan=plan,
        turn_detection=turn_detection,
        notes=[note],
    )
    logger.info(
        "Voice pipeline ready: mode=modular stt=%s llm=%s(%s) tts=%s(%s/%s) "
        "turn_detection=%s",
        components.stt_provider,
        settings.ai_provider,
        settings.resolved_llm_model,
        components.tts_provider,
        settings.resolved_cartesia_model
        if components.tts_provider == "cartesia"
        else settings.resolved_model,
        settings.resolved_cartesia_voice
        if components.tts_provider == "cartesia"
        else settings.resolved_voice,
        turn_detection,
    )
    return components


# --------------------------------------------------------------------------
# Session wiring
# --------------------------------------------------------------------------


def session_options(
    components: VoiceComponents,
    settings: Settings,
) -> dict[str, Any]:
    """
    ``AgentSession`` keyword arguments for these components.

    Kept here, next to the resolution logic, so the endpointing numbers and the
    turn-detection mode are chosen by the same code that chose the architecture.
    The realtime path keeps the exact options it has always used — this function
    is additive for it, not a rewrite.

    Note on the two endpointing vocabularies: livekit-agents 1.8 deprecates
    ``min_endpointing_delay``/``max_endpointing_delay`` in favour of
    ``turn_handling["endpointing"]``. The realtime branch keeps the deprecated
    form on purpose: that is the code that is running in production today, and
    changing it is not part of adding a pipeline. The modular branch uses the
    current form.
    """
    if components.mode == REALTIME_MODE:
        return {
            "llm": components.llm,
            "turn_handling": {
                "turn_detection": components.turn_detection or "realtime_llm",
                "interruption": {"enabled": True},
            },
            "min_endpointing_delay": 0.4,
            "max_endpointing_delay": 3.0,
            "false_interruption_timeout": 1.5,
            "user_away_timeout": None,
        }

    return {
        "stt": components.stt,
        "llm": components.llm,
        "tts": components.tts,
        "vad": components.vad,
        "turn_handling": {
            # From the components, not the plan: when no VAD could be loaded the
            # built pipeline runs on STT endpointing, and telling the session
            # "vad" would leave it waiting for end-of-speech signals that will
            # never come.
            "turn_detection": components.turn_detection or _VAD_TURN_DETECTION,
            "interruption": {"enabled": True},
            # Slightly tighter than the realtime path: a modular turn has more
            # steps, so the endpointing budget is where responsiveness is won
            # or lost. The upper bound still allows a thinking pause.
            "endpointing": {"min_delay": 0.35, "max_delay": 2.5},
        },
        "user_away_timeout": None,
    }
