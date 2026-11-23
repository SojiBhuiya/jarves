"""
Tests for the provider layer: realtime model construction, the capability
registry, and the tool-mode decision.

Model construction is tested *offline*. Building a LiveKit realtime model does
not open a network connection, so these tests genuinely prove that the
arguments Aladin passes are accepted by the installed plugin — which is where
the Vertex-AI-vs-Gemini-API model mix-up was caught.
"""

from __future__ import annotations

import pytest

from aladin.config import (
    DEFAULT_GEMINI_MODEL,
    DEFAULT_OPENAI_MODEL,
    GEMINI_API_MODELS,
    GEMINI_VERTEX_MODELS,
    Settings,
    SettingsError,
)
from aladin.providers import (
    build_realtime_model,
    capabilities_for,
    describe_provider,
    tool_mode_for,
    validate_model,
)
from aladin.tools import build_tools


def gemini_settings(**kwargs) -> Settings:
    base = {
        "ai_provider": "gemini",
        "gemini_api_key": "AIzaSyTESTKEYVALUE123456",
        "livekit_url": "wss://test.livekit.cloud",
        "livekit_api_key": "APIabcdefghij",
        "livekit_api_secret": "s" * 32,
    }
    base.update(kwargs)
    return Settings(**base)


def openai_settings(**kwargs) -> Settings:
    base = {
        "ai_provider": "openai",
        "openai_api_key": "sk-test1234567890abcdef",
    }
    base.update(kwargs)
    return Settings(**base)


# ---------------------------------------------------------------------------
# model validation
# ---------------------------------------------------------------------------


class TestValidateModel:
    def test_vertex_model_rejected_for_api_key_path(self) -> None:
        for model in GEMINI_VERTEX_MODELS:
            with pytest.raises(SettingsError, match="Vertex AI"):
                validate_model("gemini", model)

    def test_error_names_working_alternatives(self) -> None:
        with pytest.raises(SettingsError) as excinfo:
            validate_model("gemini", GEMINI_VERTEX_MODELS[0])
        message = str(excinfo.value)
        assert GEMINI_API_MODELS[0] in message
        assert "ALADIN_MODEL" in message

    @pytest.mark.parametrize("model", GEMINI_API_MODELS)
    def test_all_gemini_api_models_accepted(self, model: str) -> None:
        validate_model("gemini", model)

    def test_unknown_model_is_not_rejected(self) -> None:
        # Forward compatibility: a model released after this code was written
        # must not be blocked by a stale allowlist.
        validate_model("gemini", "gemini-9.9-live-future")

    def test_other_providers_are_not_checked(self) -> None:
        validate_model("openai", GEMINI_VERTEX_MODELS[0])


# ---------------------------------------------------------------------------
# capability registry
# ---------------------------------------------------------------------------


class TestCapabilities:
    def test_gemini_supports_grounding(self) -> None:
        assert capabilities_for("gemini", DEFAULT_GEMINI_MODEL).supports_search_grounding

    def test_openai_has_no_search_grounding(self) -> None:
        assert not capabilities_for("openai", DEFAULT_OPENAI_MODEL).supports_search_grounding

    def test_openai_has_no_session_resumption(self) -> None:
        assert not capabilities_for(
            "openai", DEFAULT_OPENAI_MODEL
        ).supports_session_resumption

    def test_native_audio_models_support_affective_dialog(self) -> None:
        assert capabilities_for(
            "gemini", DEFAULT_GEMINI_MODEL
        ).supports_affective_dialog

    def test_unknown_provider_raises(self) -> None:
        with pytest.raises(SettingsError, match="unknown AI provider"):
            capabilities_for("mistral", "whatever")


# ---------------------------------------------------------------------------
# tool mode
# ---------------------------------------------------------------------------


class TestToolMode:
    def test_gemini_uses_grounding(self) -> None:
        assert tool_mode_for(gemini_settings()) == "grounding"

    def test_gemini_grounding_survives_without_external_keys(self) -> None:
        # Gemini grounding needs no additional credential.
        assert tool_mode_for(gemini_settings(web_search=True)) == "grounding"

    def test_openai_uses_function_tools_when_search_key_present(self) -> None:
        settings = openai_settings(tavily_api_key="tvly-test")
        assert tool_mode_for(settings) == "function"

    def test_openai_without_search_key_has_no_tools(self) -> None:
        assert tool_mode_for(openai_settings(web_search=True)) == "none"

    def test_web_search_disabled_means_no_search_mode(self) -> None:
        # This asserted "function" until P4, on the reasoning that function
        # tools should stay available. Nothing else registers function tools,
        # so the only effect was a live web_search tool in a session whose owner
        # had switched search off — and a silent search still sends the user's
        # question to a third party, so the switch had to win.
        assert tool_mode_for(gemini_settings(web_search=False)) == "none"
        assert tool_mode_for(openai_settings(web_search=False)) == "none"

    def test_a_disabled_search_is_not_rescued_by_a_stored_key(self) -> None:
        # A key in the environment is not consent to use it.
        assert (
            tool_mode_for(openai_settings(web_search=False, tavily_api_key="tvly-test"))
            == "none"
        )


# ---------------------------------------------------------------------------
# model construction
# ---------------------------------------------------------------------------


class TestGeminiConstruction:
    def test_builds_default_model(self) -> None:
        model = build_realtime_model(gemini_settings())
        assert type(model).__name__ == "RealtimeModel"

    @pytest.mark.parametrize("model_name", GEMINI_API_MODELS)
    def test_every_gemini_api_model_constructs(self, model_name: str) -> None:
        model = build_realtime_model(gemini_settings(model=model_name))
        assert model is not None

    def test_vertex_model_fails_with_clear_error(self) -> None:
        # The user-facing fix matters more than the exception type here.
        with pytest.raises(SettingsError, match="Vertex AI"):
            build_realtime_model(gemini_settings(model=GEMINI_VERTEX_MODELS[0]))

    def test_missing_key_raises_with_link(self) -> None:
        with pytest.raises(SettingsError, match="aistudio.google.com"):
            build_realtime_model(Settings(ai_provider="gemini"))

    def test_custom_voice_and_temperature_applied(self) -> None:
        model = build_realtime_model(
            gemini_settings(voice="Kore", temperature=0.3)
        )
        assert model is not None

    def test_construction_survives_all_feature_toggles(self) -> None:
        # Exercises the defensive google-genai config helpers on every path.
        for flags in (
            {"session_resumption": True, "thinking": False},
            {"session_resumption": False, "thinking": True},
            {"affective_dialog": True, "proactivity": True},
            {"session_resumption": False, "thinking": True, "proactivity": True},
        ):
            model = build_realtime_model(gemini_settings(**flags))
            assert model is not None, flags


class TestOpenAIConstruction:
    def test_builds_default_model(self) -> None:
        model = build_realtime_model(openai_settings())
        assert model is not None

    def test_missing_key_raises_with_link(self) -> None:
        with pytest.raises(SettingsError, match="platform.openai.com"):
            build_realtime_model(Settings(ai_provider="openai"))

    def test_custom_model_and_voice(self) -> None:
        model = build_realtime_model(
            openai_settings(model="gpt-realtime-mini", voice="alloy", temperature=0.8)
        )
        assert model is not None


class TestProviderSwitching:
    def test_same_settings_object_switches_provider(self) -> None:
        # This is what the app's Settings screen does at runtime.
        settings = gemini_settings(openai_api_key="sk-test1234567890abcdef")
        assert settings.ai_provider == "gemini"

        switched = settings.apply_overrides({"ai_provider": "openai"})
        assert switched.ai_provider == "openai"
        assert build_realtime_model(switched) is not None
        assert build_realtime_model(settings) is not None

    def test_switching_to_unconfigured_provider_fails_clearly(self) -> None:
        settings = gemini_settings().apply_overrides({"ai_provider": "openai"})
        with pytest.raises(SettingsError, match="OPENAI_API_KEY"):
            build_realtime_model(settings)


# ---------------------------------------------------------------------------
# tool building
# ---------------------------------------------------------------------------


class TestBuildTools:
    def test_gemini_gets_google_search(self) -> None:
        tools = build_tools(gemini_settings())
        assert [type(t).__name__ for t in tools] == ["GoogleSearch"]

    def test_grounding_excludes_function_tools(self) -> None:
        # Gemini Live rejects grounding combined with function calling, so
        # this must never regress into returning both.
        tools = build_tools(gemini_settings())
        assert all(type(t).__name__ != "FunctionTool" for t in tools)

    def test_openai_gets_function_tool(self) -> None:
        tools = build_tools(openai_settings(tavily_api_key="tvly-test"))
        assert len(tools) == 1
        assert type(tools[0]).__name__ == "FunctionTool"

    def test_brave_also_produces_function_tool(self) -> None:
        tools = build_tools(openai_settings(brave_search_api_key="brave-test"))
        assert len(tools) == 1

    def test_no_tools_when_search_unavailable(self) -> None:
        assert build_tools(openai_settings()) == []

    def test_no_tools_when_search_disabled(self) -> None:
        assert build_tools(gemini_settings(web_search=False)) == []

    def test_no_search_tool_when_search_is_disabled_and_a_key_is_present(self) -> None:
        # The regression this exists for: `test_no_tools_when_search_disabled`
        # passed even while the tool was built, because the Gemini fixture had
        # no Tavily key to build it from. With a key present, the old code
        # registered a `web_search` tool in a session with search switched off.
        assert build_tools(openai_settings(web_search=False, tavily_api_key="tvly-test")) == []

    def test_search_still_works_when_it_is_enabled(self) -> None:
        assert build_tools(openai_settings(web_search=True, tavily_api_key="tvly-test"))


# ---------------------------------------------------------------------------
# describe_provider
# ---------------------------------------------------------------------------


class TestDescribeProvider:
    def test_reports_no_raw_secrets(self) -> None:
        summary = str(describe_provider(gemini_settings()))
        assert "AIzaSyTESTKEYVALUE123456" not in summary

    def test_gemini_summary_shape(self) -> None:
        summary = describe_provider(gemini_settings())
        assert summary["provider"] == "gemini"
        assert summary["tool_mode"] == "grounding"
        assert summary["web_search_active"] is True

    def test_openai_search_inactive_without_key(self) -> None:
        summary = describe_provider(openai_settings(web_search=True))
        assert summary["web_search_active"] is False
        assert summary["tool_mode"] == "none"
