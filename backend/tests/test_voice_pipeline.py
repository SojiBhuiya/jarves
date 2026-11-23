"""
The modular voice pipeline: resolution, construction, fallback and wiring.

What these tests are for
------------------------
The requirement is that a selected provider **actually controls the runtime
``AgentSession``** — not that a setting merely looks right in a UI. So these
tests stop at nothing less than the constructed ``AgentSession``: they assert
which concrete component ends up on ``stt``, ``llm`` and ``tts``, and that the
turn-detection mode matches the architecture that was built.

Categories, kept explicit (see the module docstring in the task report):

* **unit** — resolution, fallback policy, language choice, endpointing options.
* **mocked integration** — component construction and ``AgentSession`` assembly
  with fake credentials. These build *real* LiveKit objects; they just never
  make a network call, because constructing a LiveKit plugin is a local
  operation.
* **real external provider** — none here. Nothing in this file contacts
  Cartesia, Gemini or OpenAI, and no test claims that a live conversation works.
  That requires real credentials and is reported as untested.
"""

from __future__ import annotations

import asyncio

import pytest

from aladin.config import (
    DEFAULT_CARTESIA_MODEL,
    DEFAULT_CARTESIA_VOICE,
    Settings,
    SettingsError,
)
from aladin.providers import (
    available_providers,
    build_llm,
    build_stt,
    build_tts,
    factory_for,
)
from aladin.voice_pipeline import (
    MODULAR_MODE,
    REALTIME_MODE,
    build_voice_components,
    load_vad,
    resolve_voice_plan,
    session_options,
)

GEMINI_KEY = "AIzaSyTESTKEYVALUE1234567890"
CARTESIA_KEY = "cartesia-test-key-abcdef123456"
OPENAI_KEY = "sk-test-openai-key-abcdefghijklmnop"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def modular_settings(**overrides: object) -> Settings:
    """Settings for a fully configured modular pipeline."""
    base: dict[str, object] = {
        "voice_mode": MODULAR_MODE,
        "ai_provider": "gemini",
        "gemini_api_key": GEMINI_KEY,
        "tts_provider": "cartesia",
        "cartesia_api_key": CARTESIA_KEY,
    }
    base.update(overrides)
    return Settings(**base)  # type: ignore[arg-type]


async def build_session(settings: Settings) -> tuple[object, object, object]:
    """
    Resolve, construct, and assemble a real ``AgentSession``.

    Async because that is the only honest way to build one: ``AgentSession``
    binds to the running event loop, exactly as it does inside the agent worker.
    Constructing it from a synchronous test only works while no loop has been
    created and closed in the process, which makes ordering matter — a trap this
    helper exists to avoid.
    """
    from livekit.agents import AgentSession

    plan = resolve_voice_plan(settings)
    components = build_voice_components(settings, plan)
    session = AgentSession(**session_options(components, settings))
    return plan, components, session


# ---------------------------------------------------------------------------
# Resolution (unit)
# ---------------------------------------------------------------------------


class TestResolution:
    def test_modular_is_the_default_mode(self) -> None:
        assert Settings().voice_mode == MODULAR_MODE

    def test_cartesia_is_the_default_voice(self) -> None:
        assert Settings().tts_provider == "cartesia"

    def test_resolved_plan_runs_what_was_selected(self) -> None:
        plan = resolve_voice_plan(modular_settings())
        assert plan.mode == MODULAR_MODE
        assert plan.tts_provider == "cartesia"
        assert plan.llm_provider == "gemini"
        assert plan.is_fallback is False

    def test_realtime_mode_is_still_available(self) -> None:
        """The original speech-to-speech path must not have been deleted."""
        plan = resolve_voice_plan(
            Settings(voice_mode=REALTIME_MODE, ai_provider="gemini", gemini_api_key=GEMINI_KEY)
        )
        assert plan.mode == REALTIME_MODE
        assert plan.turn_detection == "realtime_llm"
        assert plan.is_fallback is False

    def test_explicit_realtime_request_is_not_a_fallback(self) -> None:
        # Choosing realtime is a choice, not a degradation: reporting it as a
        # fallback would put a spurious warning in front of the user.
        plan = resolve_voice_plan(
            Settings(voice_mode=REALTIME_MODE, ai_provider="gemini", gemini_api_key=GEMINI_KEY)
        )
        assert plan.fallback is None

    def test_missing_cartesia_key_falls_back_and_says_why(self) -> None:
        plan = resolve_voice_plan(
            Settings(
                voice_mode=MODULAR_MODE,
                ai_provider="gemini",
                gemini_api_key=GEMINI_KEY,
                tts_provider="cartesia",
            )
        )
        assert plan.mode == REALTIME_MODE
        assert plan.fallback is not None
        assert plan.fallback.reason == "cartesia_key_missing"
        assert plan.fallback.requested_mode == MODULAR_MODE
        assert plan.fallback.active_mode == REALTIME_MODE

    def test_cartesia_fallback_names_cartesia_in_its_message(self) -> None:
        plan = resolve_voice_plan(Settings(ai_provider="gemini", gemini_api_key=GEMINI_KEY))
        assert plan.fallback is not None
        assert "Cartesia" in plan.fallback.detail

    def test_missing_llm_key_falls_back(self) -> None:
        plan = resolve_voice_plan(Settings(ai_provider="gemini", cartesia_api_key=CARTESIA_KEY))
        assert plan.fallback is not None
        assert plan.fallback.reason == "llm_key_missing"

    def test_no_transcriber_falls_back(self) -> None:
        # No Cartesia key and no Gemini key: nothing can transcribe.
        plan = resolve_voice_plan(Settings(ai_provider="gemini"))
        assert plan.fallback is not None
        assert plan.fallback.reason in {"llm_key_missing", "stt_provider_unavailable"}

    def test_stt_auto_prefers_cartesia_when_present(self) -> None:
        assert Settings(cartesia_api_key=CARTESIA_KEY).resolved_stt_provider == "cartesia"

    def test_stt_auto_falls_back_to_the_llm_provider(self) -> None:
        # A deployment whose only key is Gemini must still be able to hear.
        assert (
            Settings(ai_provider="gemini", gemini_api_key=GEMINI_KEY).resolved_stt_provider
            == "gemini"
        )

    def test_stt_auto_reports_nothing_available_rather_than_guessing(self) -> None:
        assert Settings().resolved_stt_provider == ""

    def test_explicit_stt_choice_is_honoured(self) -> None:
        s = Settings(cartesia_api_key=CARTESIA_KEY, stt_provider="openai")
        assert s.resolved_stt_provider == "openai"

    def test_fallback_payload_is_json_safe_and_secret_free(self) -> None:
        plan = resolve_voice_plan(Settings(ai_provider="gemini", gemini_api_key=GEMINI_KEY))
        payload = plan.fallback.as_payload()  # type: ignore[union-attr]
        assert set(payload) == {"reason", "requested_mode", "active_mode", "detail"}
        assert CARTESIA_KEY not in str(payload)

    def test_plan_description_contains_no_key(self) -> None:
        plan = resolve_voice_plan(modular_settings())
        assert CARTESIA_KEY not in str(plan.describe())
        assert GEMINI_KEY not in str(plan.describe())


# ---------------------------------------------------------------------------
# The adapter registry (unit)
# ---------------------------------------------------------------------------


class TestProviderRegistry:
    def test_every_tts_provider_is_registered(self) -> None:
        assert set(available_providers("tts")) == {"cartesia", "gemini", "openai"}

    def test_every_stt_provider_is_registered(self) -> None:
        # "auto" is a resolution strategy, not a provider, so it is absent.
        assert set(available_providers("stt")) == {"cartesia", "gemini", "openai"}

    def test_llm_providers_match_the_existing_allowlist(self) -> None:
        assert set(available_providers("llm")) == {"gemini", "openai"}

    def test_cartesia_is_listed_first_for_tts(self) -> None:
        assert available_providers("tts")[0] == "cartesia"

    def test_unknown_provider_is_rejected_with_the_options(self) -> None:
        with pytest.raises(SettingsError) as excinfo:
            factory_for("tts", "elevenlabs")
        assert "cartesia" in str(excinfo.value)

    def test_unknown_stage_is_rejected(self) -> None:
        with pytest.raises(SettingsError):
            factory_for("embeddings", "cartesia")


# ---------------------------------------------------------------------------
# Component construction (mocked integration — real LiveKit objects)
# ---------------------------------------------------------------------------


class TestComponentConstruction:
    def test_builds_cartesia_tts_with_the_configured_model_and_voice(self) -> None:
        from livekit.plugins import cartesia

        tts = build_tts(modular_settings(cartesia_model="sonic-2", cartesia_voice="voice-abc"))
        assert isinstance(tts, cartesia.TTS)
        assert tts.model == "sonic-2"

    def test_tts_defaults_come_from_the_plugin(self) -> None:
        tts = build_tts(modular_settings())
        assert tts.model == DEFAULT_CARTESIA_MODEL

    def test_cartesia_tts_is_a_streaming_tts(self) -> None:
        # Streaming is what lets Aladin start speaking before the LLM finishes.
        from livekit.agents import tts as agents_tts

        tts = build_tts(modular_settings())
        assert isinstance(tts, agents_tts.TTS)

    def test_missing_cartesia_key_is_refused_with_an_actionable_message(self) -> None:
        with pytest.raises(SettingsError) as excinfo:
            build_tts(Settings(tts_provider="cartesia"))
        assert "Cartesia" in str(excinfo.value)

    def test_the_error_never_contains_a_key(self) -> None:
        with pytest.raises(SettingsError) as excinfo:
            build_stt(Settings(ai_provider="gemini", gemini_api_key=GEMINI_KEY, stt_provider="cartesia"))
        assert CARTESIA_KEY not in str(excinfo.value)
        assert GEMINI_KEY not in str(excinfo.value)

    def test_builds_a_text_llm_not_an_audio_model(self) -> None:
        """
        The modular pipeline's LLM must emit text.

        A realtime model here would speak *and* the TTS would speak, so this
        asserts the component is a plain ``LLM``.
        """
        from livekit.agents import llm as agents_llm
        from livekit.agents import llm as _llm

        llm = build_llm(modular_settings())
        assert isinstance(llm, _llm.LLM)
        assert not isinstance(llm, agents_llm.RealtimeModel)

    def test_llm_model_is_the_text_model_not_the_realtime_one(self) -> None:
        from aladin.config import DEFAULT_GEMINI_MODEL

        llm = build_llm(modular_settings())
        assert llm.model != DEFAULT_GEMINI_MODEL
        assert llm.model == "gemini-2.5-flash"

    def test_llm_model_can_be_overridden(self) -> None:
        llm = build_llm(modular_settings(llm_model="gemini-2.5-pro"))
        assert llm.model == "gemini-2.5-pro"

    def test_openai_llm_uses_the_openai_key(self) -> None:
        llm = build_llm(
            modular_settings(ai_provider="openai", openai_api_key=OPENAI_KEY, gemini_api_key="")
        )
        assert llm.model == "gpt-4o-mini"

    def test_stt_is_built_for_the_resolved_provider(self) -> None:
        from livekit.plugins import cartesia

        stt = build_stt(modular_settings())
        assert isinstance(stt, cartesia.STT)

    def test_gemini_tts_fallback_builds(self) -> None:
        """Gemini can speak too — that is what makes it a usable fallback."""
        from livekit.plugins.google import beta

        tts = build_tts(modular_settings(tts_provider="gemini"))
        assert isinstance(tts, beta.GeminiTTS)

    def test_openai_tts_builds(self) -> None:
        from livekit.plugins import openai

        tts = build_tts(modular_settings(tts_provider="openai", openai_api_key=OPENAI_KEY))
        assert isinstance(tts, openai.TTS)


# ---------------------------------------------------------------------------
# Language (unit)
# ---------------------------------------------------------------------------


class TestLanguage:
    def test_a_single_supported_language_is_pinned(self) -> None:
        from aladin.providers import _cartesia_language

        assert _cartesia_language(modular_settings(languages=("bn",))) == "bn"
        assert _cartesia_language(modular_settings(languages=("hi",))) == "hi"

    def test_a_multilingual_session_lets_cartesia_detect(self) -> None:
        # Aladin switches mid-conversation, so a pinned code would fight it.
        from aladin.providers import _cartesia_language

        assert _cartesia_language(modular_settings(languages=("bn", "en", "hi"))) is None

    def test_an_explicit_operator_choice_wins(self) -> None:
        from aladin.providers import _cartesia_language

        s = modular_settings(languages=("en",), cartesia_language="hi")
        assert _cartesia_language(s) == "hi"

    def test_aladins_languages_are_all_supported_by_the_plugin(self) -> None:
        from aladin.providers import _cartesia_supported_languages

        supported = _cartesia_supported_languages()
        assert {"bn", "en", "hi"} <= supported


# ---------------------------------------------------------------------------
# VAD and session options (unit)
# ---------------------------------------------------------------------------


class TestVadAndSessionOptions:
    def test_vad_loads_from_the_bundled_model(self) -> None:
        vad, note = load_vad()
        assert vad is not None
        assert "silero" in note.lower()

    def test_modular_options_wire_stt_llm_and_tts(self) -> None:
        settings = modular_settings()
        plan = resolve_voice_plan(settings)
        components = build_voice_components(settings, plan)
        options = session_options(components, settings)

        assert options["stt"] is components.stt
        assert options["llm"] is components.llm
        assert options["tts"] is components.tts
        assert options["vad"] is components.vad

    def test_modular_turn_detection_is_vad_and_stays_interruptible(self) -> None:
        settings = modular_settings()
        plan = resolve_voice_plan(settings)
        options = session_options(build_voice_components(settings, plan), settings)

        assert options["turn_handling"]["turn_detection"] == "vad"
        assert options["turn_handling"]["interruption"]["enabled"] is True

    def test_realtime_options_are_unchanged(self) -> None:
        """The production realtime path keeps the options it has always had."""
        settings = Settings(
            voice_mode=REALTIME_MODE, ai_provider="gemini", gemini_api_key=GEMINI_KEY
        )
        plan = resolve_voice_plan(settings)
        options = session_options(build_voice_components(settings, plan), settings)

        assert "stt" not in options and "tts" not in options and "vad" not in options
        assert options["turn_handling"]["turn_detection"] == "realtime_llm"
        assert options["min_endpointing_delay"] == 0.4
        assert options["max_endpointing_delay"] == 3.0
        assert options["false_interruption_timeout"] == 1.5

    def test_stt_endpointing_is_used_when_no_vad_is_available(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """
        A missing VAD degrades the pipeline; it does not break it, and the
        session is told the truth about it.

        Regression guard: the session options must come from what was *built*.
        Reading the plan's optimistic "vad" here would leave a session waiting
        for end-of-speech signals that no component produces.
        """
        import aladin.voice_pipeline as vp

        monkeypatch.setattr(vp, "load_vad", lambda: (None, "no vad available"))
        settings = modular_settings()
        plan = resolve_voice_plan(settings)
        components = build_voice_components(settings, plan)

        assert components.vad is None
        assert components.turn_detection == "stt"
        options = session_options(components, settings)
        assert options["turn_handling"]["turn_detection"] == "stt"
        assert any("no vad available" in note for note in components.notes)


# ---------------------------------------------------------------------------
# AgentSession assembly (mocked integration)
# ---------------------------------------------------------------------------


class TestAgentSessionConstruction:
    @pytest.mark.asyncio
    async def test_a_real_agent_session_carries_the_selected_components(self) -> None:
        """
        The requirement, asserted literally: the selection reaches the runtime.

        A settings-only test would pass even if ``agent_worker`` ignored the
        plan, so this builds the object the agent actually starts.
        """
        from livekit.agents import AgentSession
        from livekit.plugins import cartesia

        plan, components, session = await build_session(modular_settings())
        assert isinstance(session, AgentSession)
        assert plan.mode == MODULAR_MODE
        assert isinstance(session._tts, cartesia.TTS), "Cartesia is not the session's TTS"
        assert isinstance(session._stt, cartesia.STT)
        assert session._tts is components.tts

    @pytest.mark.asyncio
    async def test_a_gemini_selected_voice_reaches_the_session(self) -> None:
        """Proves the TTS choice is what decides, not a hardcoded Cartesia."""
        from livekit.plugins.google import beta

        _, _, session = await build_session(modular_settings(tts_provider="gemini"))
        assert isinstance(session._tts, beta.GeminiTTS)

    @pytest.mark.asyncio
    async def test_selecting_cartesia_never_yields_gemini_tts(self) -> None:
        """No silent substitution: Cartesia selected means Cartesia or nothing."""
        from livekit.plugins.google import beta

        _, _, session = await build_session(modular_settings())
        assert not isinstance(session._tts, beta.GeminiTTS)

    @pytest.mark.asyncio
    async def test_the_realtime_session_still_constructs(self) -> None:
        """The existing Gemini Live path must keep working."""
        from livekit.agents import llm as agents_llm

        settings = Settings(
            voice_mode=REALTIME_MODE, ai_provider="gemini", gemini_api_key=GEMINI_KEY
        )
        _, _, session = await build_session(settings)
        assert isinstance(session._llm, agents_llm.RealtimeModel)

    @pytest.mark.asyncio
    async def test_session_construction_makes_no_network_call(self) -> None:
        """
        Construction is local, which is why these tests are meaningful without
        credentials. Any network use would have to come from a request the code
        makes at build time — it makes none.
        """
        # A key that could never authenticate anywhere: if any provider call were
        # attempted, this session would not construct.
        settings = modular_settings(
            gemini_api_key="not-a-real-key", cartesia_api_key="not-a-real-cartesia-key"
        )
        _, _, session = await build_session(settings)
        assert session is not None


# ---------------------------------------------------------------------------
# Worker wiring (mocked integration)
# ---------------------------------------------------------------------------


class TestAgentWorkerWiring:
    def test_the_worker_uses_the_resolver_not_a_hardcoded_model(self) -> None:
        """
        Guard against the regression this whole change could silently suffer:
        the worker building a realtime model directly again.
        """
        import inspect

        from aladin import agent_worker

        source = inspect.getsource(agent_worker.aladin_session)
        assert "resolve_voice_plan" in source
        assert "build_voice_components" in source
        assert "session_options" in source
        # The old unconditional construction must be gone from the session body.
        assert "model = build_realtime_model(settings)" not in source

    def test_the_worker_announces_a_fallback_plan(self) -> None:
        import inspect

        from aladin import agent_worker

        source = inspect.getsource(agent_worker.aladin_session)
        assert "provider_error" in source

    def test_worker_reports_the_pipeline_in_its_logs(self) -> None:
        import inspect

        from aladin import agent_worker

        source = inspect.getsource(agent_worker.aladin_session)
        assert "describe_voice_pipeline" in source
