"""
Provider factories for every stage of a conversation.

Aladin has **two voice architectures**, and this module builds the components
for both:

``realtime`` (speech-to-speech)
    One realtime model owns the whole turn — Gemini Live by default, OpenAI's
    Realtime API optionally. :func:`build_realtime_model` builds it.

``modular`` (the primary path)
    Three independent stages: **STT -> LLM -> TTS**, with Cartesia speaking.
    :func:`build_llm`, :func:`build_stt` and :func:`build_tts` build them, and
    :mod:`aladin.voice_pipeline` assembles them into an ``AgentSession``.

Why both live here
------------------
Provider-specific knowledge — which SDK class, which parameter names, which
credential — belongs in exactly one place. ``agent_worker.py`` asks for a
component by *name* and never imports a provider SDK, so adding a provider is a
registration here rather than a new branch in the session callback.

Every factory raises :class:`~aladin.config.SettingsError` with an actionable
message when its credential is missing, and none of them ever logs a key.

This module also owns a small **capability registry**. Real provider
differences are the single biggest source of confusing runtime failures in
voice agents, and they are worth encoding explicitly rather than discovering
mid-call:

* Gemini's ``generate_reply()`` is unsupported on ``gemini-3.1-flash-live-preview``.
  Aladin's greeting falls back gracefully (see :mod:`aladin.agent_worker`).
* Gemini cannot combine Google Search grounding with function-calling tools in
  one session — it is one or the other. :func:`tool_mode_for` decides which.
* OpenAI's Realtime API has no Google Search grounding, so web search there
  needs an external search API instead.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from .config import (
    DEFAULT_CARTESIA_LANGUAGE,
    GEMINI_API_MODELS,
    GEMINI_VERTEX_MODELS,
    STT_PROVIDERS,
    TTS_PROVIDERS,
    Settings,
    SettingsError,
)

logger = logging.getLogger("aladin.providers")

__all__ = [
    "Capabilities",
    "ComponentFactory",
    "available_providers",
    "build_llm",
    "build_realtime_model",
    "build_stt",
    "build_tts",
    "capabilities_for",
    "describe_provider",
    "describe_voice_pipeline",
    "factory_for",
    "tool_mode_for",
    "validate_model",
]


# --------------------------------------------------------------------------
# Capability registry
# --------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Capabilities:
    """What a given provider/model combination actually supports."""

    provider: str
    model: str
    supports_generate_reply: bool
    supports_search_grounding: bool
    supports_function_tools: bool
    supports_session_resumption: bool
    supports_affective_dialog: bool
    supports_proactivity: bool
    supports_temperature: bool

    @property
    def supports_grounding_with_tools(self) -> bool:
        """Whether grounding and function tools can coexist in one session."""
        return self.supports_search_grounding and self.supports_function_tools


#: Gemini Live models where the LiveKit wrapper rejects ``generate_reply()``.
#: Plugin 1.8.3 handles the 3.1/3.8 family internally via a reply placeholder,
#: but the JS SDK had a hard failure here and the Python behaviour has changed
#: across releases. Aladin keeps a spoken fallback for these models so a
#: greeting can never abort a session, whichever plugin version is installed.
_GEMINI_NO_GENERATE_REPLY = frozenset({"gemini-3.1-flash-live-preview"})

#: Models built on Google's streaming speech-to-speech engine.
_GEMINI_NATIVE_AUDIO = frozenset(GEMINI_API_MODELS) | frozenset(GEMINI_VERTEX_MODELS)


def validate_model(provider: str, model: str) -> None:
    """
    Reject provider/model pairings that cannot work.

    Gemini Live model names are bound to a specific Google API surface:
    ``gemini-live-2.5-flash-native-audio`` only exists on Vertex AI, while the
    ``*-preview-12-2025`` and ``3.x`` models only exist on the Gemini API. The
    LiveKit plugin raises a ValueError deep inside model construction when they
    are mixed, which is a confusing way to find out, so Aladin checks up front
    and explains the fix.

    Raises:
        SettingsError: if the model does not belong to the selected provider.
    """
    if provider != "gemini":
        return

    if model in GEMINI_VERTEX_MODELS:
        raise SettingsError(
            f"Model {model!r} is a Vertex AI model and cannot be used with a "
            "Google AI Studio API key.\n"
            f"Use one of: {', '.join(GEMINI_API_MODELS)}\n"
            f"(Leave ALADIN_MODEL empty to use the default.)"
        )


def capabilities_for(provider: str, model: str) -> Capabilities:
    """Look up the capabilities of a provider/model pair."""
    validate_model(provider, model)

    if provider == "gemini":
        return Capabilities(
            provider=provider,
            model=model,
            supports_generate_reply=model not in _GEMINI_NO_GENERATE_REPLY,
            supports_search_grounding=True,
            # Gemini Live accepts function tools, but not alongside grounding.
            supports_function_tools=True,
            supports_session_resumption=True,
            supports_affective_dialog=model in _GEMINI_NATIVE_AUDIO,
            supports_proactivity=model in _GEMINI_NATIVE_AUDIO,
            supports_temperature=True,
        )

    if provider == "openai":
        return Capabilities(
            provider=provider,
            model=model,
            supports_generate_reply=True,
            supports_search_grounding=False,
            supports_function_tools=True,
            supports_session_resumption=False,  # handled internally by the plugin
            supports_affective_dialog=False,
            supports_proactivity=False,
            supports_temperature=True,
        )

    raise SettingsError(f"unknown AI provider: {provider!r}")


def tool_mode_for(settings: Settings) -> str:
    """
    Decide which **search** strategy this session should use.

    Returns one of:

    ``"grounding"``
        Built-in Google Search grounding. No function tools are registered,
        because Gemini Live rejects that combination.
    ``"function"``
        Regular function tools (e.g. an external search API).
    ``"none"``
        No search. Also the answer when the user turned search *off*: the mode
        describes how this session searches, and a session that must not search
        has no strategy rather than a dormant one.

    History — why the ``not settings.web_search`` case is not "function":
    it used to be, on the reasoning that *other* function tools should stay
    available. Nothing else registers function tools, so the only effect was
    that a session with search switched off still got a tool named
    ``web_search`` with a description inviting the model to call it — the exact
    behaviour the switch exists to prevent. Honouring the switch is also the
    privacy-correct answer: a silent search still sends the user's question to
    a third party.
    """
    caps = capabilities_for(settings.ai_provider, settings.resolved_model)

    if not settings.web_search:
        return "none"

    if not caps.supports_function_tools and not caps.supports_search_grounding:
        # A provider that can do neither cannot search at all.
        return "none"

    if settings.ai_provider == "gemini":
        # Grounding wins: it is lower-latency and needs no extra API key.
        return "grounding" if caps.supports_search_grounding else "none"

    # OpenAI: search must go through an external function tool.
    if settings.tavily_api_key or settings.brave_search_api_key:
        return "function"
    return "none"


def describe_provider(settings: Settings) -> dict[str, Any]:
    """Human-readable provider summary, used in logs and the health endpoint."""
    caps = capabilities_for(settings.ai_provider, settings.resolved_model)
    return {
        "provider": caps.provider,
        "model": caps.model,
        "voice": settings.resolved_voice,
        "tool_mode": tool_mode_for(settings),
        "web_search_requested": settings.web_search,
        "web_search_active": settings.has_web_search_credentials,
        "generate_reply": caps.supports_generate_reply,
        "session_resumption": caps.supports_session_resumption
        and settings.session_resumption,
        # Which architecture this session will run, and which components it
        # will use. A *new* key rather than a reinterpretation of ``voice``:
        # ``voice`` above stays the realtime voice name it has always been, so
        # nothing that already reads this summary changes meaning.
        "voice_pipeline": describe_voice_pipeline(settings),
    }


# --------------------------------------------------------------------------
# Model construction
# --------------------------------------------------------------------------


def build_realtime_model(settings: Settings) -> Any:
    """
    Build the realtime model for the configured provider.

    Returns an object suitable for ``AgentSession(llm=...)``.

    Raises:
        SettingsError: if the required API key for the selected provider is
            missing, or the provider name is unknown.
    """
    if settings.ai_provider == "gemini":
        return _build_gemini(settings)
    if settings.ai_provider == "openai":
        return _build_openai(settings)
    raise SettingsError(f"unknown AI provider: {settings.ai_provider!r}")


def _build_gemini(settings: Settings) -> Any:
    """Construct a Gemini Live realtime model."""
    if not settings.gemini_api_key:
        raise SettingsError(
            "Gemini provider selected but GEMINI_API_KEY is not set. "
            "Get a key at https://aistudio.google.com/apikey"
        )

    from livekit.plugins import google

    caps = capabilities_for("gemini", settings.resolved_model)

    kwargs: dict[str, Any] = {
        "model": settings.resolved_model,
        "api_key": settings.gemini_api_key,
        "voice": settings.resolved_voice,
    }

    # Text-only bridge probes do not render captions. Avoid paying for
    # realtime transcription setup there; normal app sessions keep captions.
    if settings.captions:
        kwargs["input_audio_transcription"] = _gemini_transcription_config()
        kwargs["output_audio_transcription"] = _gemini_transcription_config()

    if settings.temperature is not None and caps.supports_temperature:
        kwargs["temperature"] = settings.temperature

    # Session resumption keeps a long conversation alive across brief network
    # drops instead of starting over from silence.
    if settings.session_resumption and caps.supports_session_resumption:
        resumption = _gemini_session_resumption()
        if resumption is not None:
            kwargs["session_resumption"] = resumption

    if settings.affective_dialog and caps.supports_affective_dialog:
        kwargs["enable_affective_dialog"] = True

    if settings.proactivity and caps.supports_proactivity:
        kwargs["proactivity"] = True

    if not settings.thinking:
        # Voice turns are latency-critical; disable reasoning unless asked for.
        thinking_config = _gemini_thinking_off()
        if thinking_config is not None:
            kwargs["thinking_config"] = thinking_config

    logger.info(
        "Gemini realtime model: model=%s voice=%s resumption=%s",
        settings.resolved_model,
        settings.resolved_voice,
        bool(kwargs.get("session_resumption")),
    )
    return google.realtime.RealtimeModel(**kwargs)


def _build_openai(settings: Settings) -> Any:
    """Construct an OpenAI Realtime model."""
    if not settings.openai_api_key:
        raise SettingsError(
            "OpenAI provider selected but OPENAI_API_KEY is not set. "
            "Get a key at https://platform.openai.com/api-keys"
        )

    from livekit.plugins import openai

    kwargs: dict[str, Any] = {
        "model": settings.resolved_model,
        "api_key": settings.openai_api_key,
        "voice": settings.resolved_voice,
    }

    if settings.temperature is not None:
        kwargs["temperature"] = settings.temperature

    logger.info(
        "OpenAI realtime model: model=%s voice=%s",
        settings.resolved_model,
        settings.resolved_voice,
    )
    return openai.realtime.RealtimeModel(**kwargs)


# --------------------------------------------------------------------------
# Optional Google config helpers
#
# These are defensive on purpose: if a future google-genai release changes
# these config classes, we log and carry on with the parameter omitted rather
# than crashing the whole worker at startup.
# --------------------------------------------------------------------------


def _gemini_transcription_config() -> Any:
    try:
        from google.genai import types as genai_types

        return genai_types.AudioTranscriptionConfig()
    except Exception as exc:  # pragma: no cover - depends on google-genai version
        logger.debug("audio transcription config unavailable: %s", exc)
        return None


def _gemini_session_resumption() -> Any:
    try:
        from google.genai import types as genai_types

        return genai_types.SessionResumptionConfig()
    except Exception as exc:  # pragma: no cover
        logger.debug("session resumption config unavailable: %s", exc)
        return None


def _gemini_thinking_off() -> Any:
    try:
        from google.genai import types as genai_types

        return genai_types.ThinkingConfig(thinking_budget=0)
    except Exception as exc:  # pragma: no cover
        logger.debug("thinking config unavailable: %s", exc)
        return None


# --------------------------------------------------------------------------
# Modular pipeline: the component registry
# --------------------------------------------------------------------------
#
# A "component" is one stage of the modular pipeline: the text LLM, the STT, or
# the TTS. Each provider registers a factory for the stage(s) it can serve, and
# the rest of the codebase asks for a stage by name:
#
#     build_tts(settings)          # whoever settings.tts_provider names
#     build_component("tts", "gemini", settings)   # an explicit choice
#
# That indirection is what keeps provider specifics out of agent_worker.py and
# out of the fallback logic, which only ever deals in names.


@dataclass(frozen=True, slots=True)
class ComponentFactory:
    """One provider's factory for one stage of the modular pipeline."""

    provider: str
    #: ``"llm"``, ``"stt"`` or ``"tts"``.
    kind: str
    #: Builds the component. Raises :class:`SettingsError` if its key is absent.
    build: Callable[[Settings], Any]


#: ``(kind, provider) -> factory``. Populated by the ``_register_*`` calls at
#: the bottom of this module, so the table is readable in one place.
_REGISTRY: dict[tuple[str, str], ComponentFactory] = {}


def _register(kind: str, provider: str) -> Callable[[Callable[[Settings], Any]], Callable[[Settings], Any]]:
    """Decorator: register ``provider`` as a builder for ``kind``."""

    def decorate(func: Callable[[Settings], Any]) -> Callable[[Settings], Any]:
        _REGISTRY[(kind, provider)] = ComponentFactory(
            provider=provider, kind=kind, build=func
        )
        return func

    return decorate


def available_providers(kind: str) -> tuple[str, ...]:
    """Names of every provider that can serve ``kind``, in a stable order."""
    names = {provider for (registered_kind, provider) in _REGISTRY if registered_kind == kind}
    # Prefer the configured order so the UI lists providers predictably
    # (Cartesia first for TTS, which mirrors the default).
    preferred = TTS_PROVIDERS if kind == "tts" else STT_PROVIDERS
    ordered = [name for name in preferred if name in names and name != "auto"]
    ordered += sorted(names - set(ordered))
    return tuple(ordered)


def factory_for(kind: str, provider: str) -> ComponentFactory:
    """
    Look up a factory, or explain why there is none.

    Raises:
        SettingsError: for an unknown stage or an unsupported provider.
    """
    factory = _REGISTRY.get((kind, provider))
    if factory is None:
        known = ", ".join(available_providers(kind)) or "none"
        raise SettingsError(
            f"no {kind.upper()} provider named {provider!r}. Available: {known}"
        )
    return factory


def _build_component(kind: str, provider: str, settings: Settings) -> Any:
    return factory_for(kind, provider).build(settings)


def build_llm(settings: Settings) -> Any:
    """Build the modular pipeline's **text** LLM for the selected provider."""
    return _build_component("llm", settings.ai_provider, settings)


def build_stt(settings: Settings) -> Any:
    """Build the modular pipeline's STT for the resolved provider."""
    provider = settings.resolved_stt_provider
    if not provider:
        raise SettingsError(
            "No speech-to-text provider is available: the modular pipeline "
            "needs a Cartesia key or a key for the selected AI provider "
            f"({settings.ai_provider}). Add one in the app's API settings, or "
            "set ALADIN_STT_PROVIDER explicitly."
        )
    return _build_component("stt", provider, settings)


def build_tts(settings: Settings) -> Any:
    """Build the modular pipeline's TTS for the selected provider."""
    return _build_component("tts", settings.tts_provider, settings)


def describe_voice_pipeline(settings: Settings) -> dict[str, Any]:
    """
    A log- and health-safe description of the pipeline that will run.

    Contains provider *names* and model/voice settings only. A key is never
    present, and the phrase "configured" is never inferred from a value that
    might be a secret — the caller can ask ``settings.redacted()`` for that.
    """
    if settings.voice_mode == "realtime":
        return {
            "voice_mode": "realtime",
            "architecture": "speech-to-speech realtime model",
            "provider": settings.ai_provider,
            "model": settings.resolved_model,
            "voice": settings.resolved_voice,
        }
    return {
        "voice_mode": "modular",
        "architecture": "STT -> LLM -> TTS",
        "llm_provider": settings.ai_provider,
        "llm_model": settings.resolved_llm_model,
        "stt_provider": settings.resolved_stt_provider or "<unavailable>",
        "tts_provider": settings.tts_provider,
        "tts_model": settings.resolved_cartesia_model
        if settings.tts_provider == "cartesia"
        else settings.resolved_model,
        "tts_voice": settings.resolved_cartesia_voice
        if settings.tts_provider == "cartesia"
        else settings.resolved_voice,
    }


# --------------------------------------------------------------------------
# LLM adapters (text, not audio)
# --------------------------------------------------------------------------


@_register("llm", "gemini")
def _build_gemini_llm(settings: Settings) -> Any:
    """
    Gemini as a **text** model.

    ``google.LLM`` is the text GenerateContent API. It is deliberately not the
    realtime model used in ``realtime`` mode: the modular pipeline's LLM must
    emit text, which is then spoken by the TTS stage, so the audio-native
    realtime model would be the wrong component even though it is the same
    provider and the same credential.
    """
    if not settings.gemini_api_key:
        raise SettingsError(
            "Gemini LLM selected but GEMINI_API_KEY is not set. "
            "Get a key at https://aistudio.google.com/apikey"
        )

    from livekit.plugins import google

    kwargs: dict[str, Any] = {
        "model": settings.resolved_llm_model,
        "api_key": settings.gemini_api_key,
    }
    if settings.temperature is not None:
        kwargs["temperature"] = settings.temperature

    logger.info("Modular LLM: provider=gemini model=%s", settings.resolved_llm_model)
    return google.LLM(**kwargs)


@_register("llm", "openai")
def _build_openai_llm(settings: Settings) -> Any:
    """OpenAI's chat model for the modular pipeline's LLM stage."""
    if not settings.openai_api_key:
        raise SettingsError(
            "OpenAI LLM selected but OPENAI_API_KEY is not set. "
            "Get a key at https://platform.openai.com/api-keys"
        )

    from livekit.plugins import openai

    kwargs: dict[str, Any] = {
        "model": settings.resolved_llm_model,
        "api_key": settings.openai_api_key,
    }
    if settings.temperature is not None:
        kwargs["temperature"] = settings.temperature

    logger.info("Modular LLM: provider=openai model=%s", settings.resolved_llm_model)
    return openai.LLM(**kwargs)


# --------------------------------------------------------------------------
# STT adapters
# --------------------------------------------------------------------------


@_register("stt", "cartesia")
def _build_cartesia_stt(settings: Settings) -> Any:
    """
    Cartesia's streaming STT (Ink).

    Chosen as the default transcriber because it is the one provider whose key
    the modular pipeline already needs in order to *speak*, so a deployment does
    not have to source a second credential just to be heard. It is also
    streaming, which is what keeps transcription latency off the response path.
    """
    if not settings.cartesia_api_key:
        raise SettingsError(
            "Cartesia STT selected but no Cartesia API key is configured."
        )

    from livekit.plugins import cartesia

    kwargs: dict[str, Any] = {"api_key": settings.cartesia_api_key}
    # Only pin a language when the session has exactly one. Aladin switches
    # between bn/en/hi mid-conversation, and a pinned language would fight that.
    language = _single_session_language(settings)
    if language:
        kwargs["language"] = language

    logger.info(
        "Modular STT: provider=cartesia language=%s", language or "auto"
    )
    return cartesia.STT(**kwargs)


@_register("stt", "gemini")
def _build_gemini_stt(settings: Settings) -> Any:
    """Gemini's speech-to-text, reusing the existing Gemini API key."""
    if not settings.gemini_api_key:
        raise SettingsError(
            "Gemini STT selected but GEMINI_API_KEY is not set."
        )

    from livekit.plugins.google import beta

    logger.info("Modular STT: provider=gemini")
    return beta.GeminiSTT(api_key=settings.gemini_api_key)


@_register("stt", "openai")
def _build_openai_stt(settings: Settings) -> Any:
    """OpenAI's speech-to-text, reusing the existing OpenAI API key."""
    if not settings.openai_api_key:
        raise SettingsError("OpenAI STT selected but OPENAI_API_KEY is not set.")

    from livekit.plugins import openai

    logger.info("Modular STT: provider=openai")
    return openai.STT(api_key=settings.openai_api_key)


# --------------------------------------------------------------------------
# TTS adapters
# --------------------------------------------------------------------------


@_register("tts", "cartesia")
def _build_cartesia_tts(settings: Settings) -> Any:
    """
    Cartesia's streaming TTS — Aladin's primary voice.

    Streaming is the point: LiveKit feeds text into this component *as the LLM
    emits it*, so Aladin starts speaking on the first clause instead of waiting
    for the complete response. Nothing here buffers the text.

    The key comes from the session's authenticated provider configuration (the
    server-side env fallback, or the key this app registered for this room). It
    is never hard-coded, never read from room metadata and never logged.
    """
    if not settings.cartesia_api_key:
        raise SettingsError(
            "Cartesia is the selected TTS provider but no Cartesia API key is "
            "configured. Save one in the app's API settings (it is handed to "
            "the agent per session), or set CARTESIA_API_KEY on the server."
        )

    from livekit.plugins import cartesia

    kwargs: dict[str, Any] = {
        "api_key": settings.cartesia_api_key,
        "model": settings.resolved_cartesia_model,
        "voice": settings.resolved_cartesia_voice,
    }
    language = _cartesia_language(settings)
    if language:
        kwargs["language"] = language
    if settings.cartesia_speed is not None:
        kwargs["speed"] = settings.cartesia_speed

    logger.info(
        "Modular TTS: provider=cartesia model=%s voice=%s language=%s speed=%s",
        settings.resolved_cartesia_model,
        _voice_label(settings.resolved_cartesia_voice),
        language or "auto",
        settings.cartesia_speed if settings.cartesia_speed is not None else "default",
    )
    return cartesia.TTS(**kwargs)


@_register("tts", "gemini")
def _build_gemini_tts(settings: Settings) -> Any:
    """
    Gemini's own TTS — the fallback voice when Cartesia is unavailable.

    Used only when Gemini is what the operator selected as the TTS provider, or
    when :mod:`aladin.voice_pipeline` explicitly falls back. Selecting Cartesia
    and then silently hearing Gemini is exactly the outcome the pipeline's
    fallback logic reports rather than hides.
    """
    if not settings.gemini_api_key:
        raise SettingsError("Gemini TTS selected but GEMINI_API_KEY is not set.")

    from livekit.plugins.google import beta

    logger.info("Modular TTS: provider=gemini voice=%s", settings.resolved_voice)
    return beta.GeminiTTS(
        api_key=settings.gemini_api_key,
        voice_name=settings.resolved_voice,
    )


@_register("tts", "openai")
def _build_openai_tts(settings: Settings) -> Any:
    """OpenAI's TTS, reusing the existing OpenAI API key."""
    if not settings.openai_api_key:
        raise SettingsError("OpenAI TTS selected but OPENAI_API_KEY is not set.")

    from livekit.plugins import openai

    logger.info("Modular TTS: provider=openai voice=%s", settings.resolved_voice)
    return openai.TTS(api_key=settings.openai_api_key, voice=settings.resolved_voice)


# --------------------------------------------------------------------------
# Language helpers
# --------------------------------------------------------------------------

#: Languages Cartesia's STT accepts, taken from the installed plugin's own
#: ``STTLanguages`` literal rather than copied by hand. Aladin's three
#: languages (bn/hi/en) are all present.
def _cartesia_supported_languages() -> frozenset[str]:
    try:
        from typing import get_args

        from livekit.plugins.cartesia.models import STTLanguages

        return frozenset(str(item) for item in get_args(STTLanguages))
    except Exception as exc:  # pragma: no cover - depends on the plugin build
        logger.debug("could not read Cartesia language list: %s", exc)
        return frozenset({"en"})


def _single_session_language(settings: Settings) -> str | None:
    """The session's language, when there is exactly one and it is supported."""
    if len(settings.languages) != 1:
        return None
    language = settings.languages[0]
    return language if language in _cartesia_supported_languages() else None


def _cartesia_language(settings: Settings) -> str | None:
    """
    Which ``language`` to hand Cartesia, or ``None`` to let it detect.

    Precedence:

    1. An explicit ``ALADIN_CARTESIA_LANGUAGE`` choice always wins — an operator
       who names a language means it.
    2. Otherwise a single-language session uses that language.
    3. Otherwise ``None``: Aladin switches between Bangla, English and Hindi
       inside one conversation, and Cartesia's multilingual model handles that
       better than a pinned code would. ``None`` is an explicitly supported
       value in the plugin (it omits the field), not a guess.
    """
    if settings.cartesia_language and settings.cartesia_language != DEFAULT_CARTESIA_LANGUAGE:
        return settings.cartesia_language
    return _single_session_language(settings)


def _voice_label(voice: str) -> str:
    """Voice ids are not secrets, but they are long; keep logs readable."""
    return voice if len(voice) <= 12 else f"{voice[:8]}…"
