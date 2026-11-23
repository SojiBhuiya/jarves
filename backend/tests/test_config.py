"""
Tests for configuration loading, overrides and validation.

These are the tests that matter most: almost every way Aladin can fail in
production is a configuration mistake, and the goal here is that each one fails
loudly and with a useful message rather than mid-conversation.
"""

from __future__ import annotations

import pytest

from aladin.config import (
    DEFAULT_GEMINI_MODEL,
    DEFAULT_OPENAI_MODEL,
    Settings,
    SettingsError,
    clamp_temperature,
    parse_languages,
    redact,
)


# ---------------------------------------------------------------------------
# redaction
# ---------------------------------------------------------------------------


class TestRedaction:
    def test_unset_is_distinguishable_from_hidden(self) -> None:
        # "not configured" and "configured but hidden" are different problems,
        # and conflating them wastes debugging time.
        assert redact(None) == "<unset>"
        assert redact("") == "<unset>"
        assert redact("AIzaSyABCDEFGHIJKLMNOP") != "<unset>"

    def test_short_secrets_are_fully_masked(self) -> None:
        assert redact("abc123") == "******"

    def test_long_secrets_leak_neither_prefix_nor_suffix_fully(self) -> None:
        secret = "AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rendered = redact(secret)
        assert secret not in rendered
        assert len(rendered) < len(secret)


# ---------------------------------------------------------------------------
# languages
# ---------------------------------------------------------------------------


class TestLanguages:
    def test_parses_comma_separated(self) -> None:
        assert parse_languages("bn,en,hi") == ("bn", "en", "hi")

    def test_parses_space_separated(self) -> None:
        assert parse_languages("bn en hi") == ("bn", "en", "hi")

    def test_parses_list(self) -> None:
        assert parse_languages(["bn", "hi"]) == ("bn", "hi")

    def test_strips_region_subtags(self) -> None:
        assert parse_languages("bn-BD,hi-IN") == ("bn", "hi")

    def test_deduplicates_preserving_order(self) -> None:
        assert parse_languages("hi,bn,hi") == ("hi", "bn")

    def test_empty_falls_back_to_default(self) -> None:
        assert parse_languages("") == ("bn", "en", "hi")

    def test_unknown_language_raises(self) -> None:
        # Silently dropping a language is a bug the user only notices
        # mid-conversation, so this must be fatal.
        with pytest.raises(SettingsError, match="unsupported language"):
            parse_languages("bn,fr")

    def test_all_unsupported_raises(self) -> None:
        with pytest.raises(SettingsError, match="unsupported language"):
            parse_languages("fr,de")


# ---------------------------------------------------------------------------
# temperature
# ---------------------------------------------------------------------------


class TestTemperature:
    def test_none_passes_through(self) -> None:
        assert clamp_temperature(None) is None

    def test_in_range_unchanged(self) -> None:
        assert clamp_temperature(0.7) == 0.7

    def test_clamped_both_ends(self) -> None:
        assert clamp_temperature(5.0) == 1.0
        assert clamp_temperature(-3.0) == 0.0


# ---------------------------------------------------------------------------
# from_env
# ---------------------------------------------------------------------------


@pytest.fixture
def base_env() -> dict[str, str]:
    return {
        "LIVEKIT_URL": "wss://test.livekit.cloud",
        "LIVEKIT_API_KEY": "APIabcdefghij",
        "LIVEKIT_API_SECRET": "s" * 32,
        "GEMINI_API_KEY": "AIzaSyTESTKEYVALUE123456",
        "ALADIN_DEVICE_TOKEN": "device-token-value",
    }


class TestFromEnv:
    def test_defaults(self, base_env: dict[str, str]) -> None:
        settings = Settings.from_env(base_env)
        assert settings.ai_provider == "gemini"
        assert settings.languages == ("bn", "en", "hi")
        assert settings.web_search is True
        assert settings.noise_cancellation is True

    def test_google_api_key_alias(self, base_env: dict[str, str]) -> None:
        # LiveKit's own docs use GOOGLE_API_KEY; accept both spellings.
        env = dict(base_env)
        del env["GEMINI_API_KEY"]
        env["GOOGLE_API_KEY"] = "AIzaSyVIAALIAS1234567"
        assert Settings.from_env(env).gemini_api_key == "AIzaSyVIAALIAS1234567"

    def test_boolean_parsing(self, base_env: dict[str, str]) -> None:
        for raw, expected in [
            ("true", True),
            ("TRUE", True),
            ("1", True),
            ("yes", True),
            ("on", True),
            ("false", False),
            ("0", False),
            ("off", False),
            ("", False),
        ]:
            env = dict(base_env, ALADIN_WEB_SEARCH=raw)
            assert Settings.from_env(env).web_search is expected, raw

    def test_unknown_boolean_keeps_safe_default(self, base_env: dict[str, str]) -> None:
        env = dict(base_env, ALADIN_WEB_SEARCH="maybe")
        # Must not raise, and must not flip a capability on by accident.
        assert Settings.from_env(env).web_search is True

    def test_invalid_provider_raises(self, base_env: dict[str, str]) -> None:
        with pytest.raises(SettingsError, match="ALADIN_AI_PROVIDER"):
            Settings.from_env(dict(base_env, ALADIN_AI_PROVIDER="claude"))

    def test_invalid_int_raises(self, base_env: dict[str, str]) -> None:
        with pytest.raises(SettingsError, match="must be an integer"):
            Settings.from_env(dict(base_env, ALADIN_TOKEN_API_PORT="eight thousand"))

    def test_invalid_temperature_raises(self, base_env: dict[str, str]) -> None:
        with pytest.raises(SettingsError, match="must be a number"):
            Settings.from_env(dict(base_env, ALADIN_TEMPERATURE="hot"))


# ---------------------------------------------------------------------------
# resolved values
# ---------------------------------------------------------------------------


class TestResolvedValues:
    def test_gemini_defaults(self) -> None:
        settings = Settings(ai_provider="gemini")
        assert settings.resolved_model == DEFAULT_GEMINI_MODEL
        assert settings.resolved_voice == "Puck"

    def test_openai_defaults(self) -> None:
        settings = Settings(ai_provider="openai")
        assert settings.resolved_model == DEFAULT_OPENAI_MODEL
        assert settings.resolved_voice == "marin"

    def test_explicit_values_win(self) -> None:
        settings = Settings(ai_provider="gemini", model="custom", voice="Kore")
        assert settings.resolved_model == "custom"
        assert settings.resolved_voice == "Kore"

    def test_provider_api_key_follows_provider(self) -> None:
        settings = Settings(
            ai_provider="gemini", gemini_api_key="g-key", openai_api_key="o-key"
        )
        assert settings.provider_api_key == "g-key"
        assert settings.apply_overrides({"ai_provider": "openai"}).provider_api_key == (
            "o-key"
        )


# ---------------------------------------------------------------------------
# web search capability
# ---------------------------------------------------------------------------


class TestWebSearchCapability:
    def test_gemini_needs_no_extra_key(self) -> None:
        # Gemini searches via built-in Google Search grounding.
        settings = Settings(ai_provider="gemini", gemini_api_key="k", web_search=True)
        assert settings.has_web_search_credentials is True

    def test_gemini_disabled(self) -> None:
        settings = Settings(ai_provider="gemini", gemini_api_key="k", web_search=False)
        assert settings.has_web_search_credentials is False

    def test_openai_needs_external_key(self) -> None:
        settings = Settings(ai_provider="openai", openai_api_key="k", web_search=True)
        assert settings.has_web_search_credentials is False

    def test_openai_with_tavily(self) -> None:
        settings = Settings(
            ai_provider="openai", openai_api_key="k", web_search=True, tavily_api_key="t"
        )
        assert settings.has_web_search_credentials is True

    def test_openai_with_brave(self) -> None:
        settings = Settings(
            ai_provider="openai",
            openai_api_key="k",
            web_search=True,
            brave_search_api_key="b",
        )
        assert settings.has_web_search_credentials is True


# ---------------------------------------------------------------------------
# overrides
# ---------------------------------------------------------------------------


class TestOverrides:
    def test_none_is_a_no_op(self) -> None:
        settings = Settings()
        assert settings.apply_overrides(None) is settings
        assert settings.apply_overrides({}) is settings

    def test_applies_supported_keys(self) -> None:
        settings = Settings().apply_overrides(
            {
                "ai_provider": "openai",
                "voice": "cedar",
                "languages": ["bn", "en"],
                "web_search": False,
                "temperature": 0.4,
            }
        )
        assert settings.ai_provider == "openai"
        assert settings.voice == "cedar"
        assert settings.languages == ("bn", "en")
        assert settings.web_search is False
        assert settings.temperature == 0.4

    def test_unknown_key_raises(self) -> None:
        # A rejected push is visible; a half-applied one is not.
        with pytest.raises(SettingsError, match="unknown override keys"):
            Settings().apply_overrides({"rm_rf": "everything"})

    def test_invalid_provider_raises(self) -> None:
        with pytest.raises(SettingsError, match="ai_provider"):
            Settings().apply_overrides({"ai_provider": "gpt5-turbo-ultra"})

    def test_original_is_not_mutated(self) -> None:
        original = Settings(ai_provider="gemini")
        original.apply_overrides({"ai_provider": "openai"})
        assert original.ai_provider == "gemini"

    def test_byo_key_routes_to_selected_provider(self) -> None:
        settings = Settings(ai_provider="openai").apply_overrides(
            {"provider_api_key": "sk-user-supplied"}
        )
        assert settings.openai_api_key == "sk-user-supplied"
        assert settings.gemini_api_key == ""

    def test_byo_key_routes_to_gemini(self) -> None:
        settings = Settings(ai_provider="gemini").apply_overrides(
            {"provider_api_key": "AIza-user-supplied"}
        )
        assert settings.gemini_api_key == "AIza-user-supplied"

    def test_blank_values_ignored(self) -> None:
        settings = Settings(voice="Kore").apply_overrides({"voice": ""})
        assert settings.voice == "Kore"


# ---------------------------------------------------------------------------
# required-config reporting
# ---------------------------------------------------------------------------


class TestRequiredConfig:
    def test_agent_lists_all_missing(self) -> None:
        missing = Settings().missing_for_agent()
        assert "LIVEKIT_URL" in missing
        assert "GEMINI_API_KEY" in missing

    def test_agent_reports_openai_key_when_selected(self) -> None:
        settings = Settings(
            ai_provider="openai",
            livekit_url="wss://x",
            livekit_api_key="k",
            livekit_api_secret="s",
        )
        assert settings.missing_for_agent() == ["OPENAI_API_KEY"]

    def test_agent_ok_when_complete(self) -> None:
        settings = Settings(
            livekit_url="wss://x",
            livekit_api_key="k",
            livekit_api_secret="s",
            gemini_api_key="g",
        )
        assert settings.missing_for_agent() == []

    def test_require_for_agent_raises_with_guidance(self) -> None:
        with pytest.raises(SettingsError, match=r"\.env\.example"):
            Settings().require_for_agent()

    def test_token_api_needs_device_token(self) -> None:
        settings = Settings(
            livekit_url="wss://x", livekit_api_key="k", livekit_api_secret="s"
        )
        assert any("DEVICE_TOKEN" in m for m in settings.missing_for_token_api())

    def test_token_api_does_not_need_ai_key(self) -> None:
        # The token API must be able to run without any AI credential: it holds
        # no AI capability, only the LiveKit signing secret.
        settings = Settings(
            livekit_url="wss://x",
            livekit_api_key="k",
            livekit_api_secret="s",
            device_token="d",
        )
        assert settings.missing_for_token_api() == []


# ---------------------------------------------------------------------------
# log safety
# ---------------------------------------------------------------------------


class TestRedactedView:
    def test_contains_no_raw_secrets(self) -> None:
        settings = Settings(
            livekit_api_key="APIabcdefghijklmnop",
            livekit_api_secret="SUPERSECRETVALUE1234567890",
            gemini_api_key="AIzaSyREALKEYVALUE123456789",
            openai_api_key="sk-REALKEYVALUE1234567890",
            device_token="REALDEVICETOKEN1234567890",
        )
        dumped = str(settings.redacted())
        for secret in (
            "SUPERSECRETVALUE1234567890",
            "AIzaSyREALKEYVALUE123456789",
            "sk-REALKEYVALUE1234567890",
            "REALDEVICETOKEN1234567890",
        ):
            assert secret not in dumped

    def test_marks_unset_secrets(self) -> None:
        assert Settings().redacted()["gemini_api_key"] == "<unset>"


class TestBringYourOwnKey:
    """
    When the feature is available, and — more importantly — when it is not.

    BYOK needs a second shared secret so the agent can fetch a key over a
    server-to-server channel. Getting this wrong either exposes keys to app
    installs or silently disables a feature, so each refusal has a reason.
    """

    def test_enabled_when_an_internal_token_is_configured(self) -> None:
        settings = Settings(
            allow_byok=True,
            internal_token="internal-token-value-32-characters",
            device_token="device-token-value-32-characters",
        )
        assert settings.byok_enabled is True
        assert settings.byok_disabled_reason is None

    def test_disabled_without_an_internal_token(self) -> None:
        settings = Settings(allow_byok=True, internal_token="", device_token="d" * 32)
        assert settings.byok_enabled is False
        assert "ALADIN_INTERNAL_TOKEN" in (settings.byok_disabled_reason or "")

    def test_disabled_when_switched_off(self) -> None:
        settings = Settings(
            allow_byok=False,
            internal_token="internal-token-value-32-characters",
            device_token="d" * 32,
        )
        assert settings.byok_enabled is False
        assert "ALADIN_ALLOW_BYOK" in (settings.byok_disabled_reason or "")

    def test_disabled_when_the_two_secrets_are_the_same(self) -> None:
        # Distinct secrets, distinct powers: the device token ships in every
        # app install, so reusing it here would let an app read keys back out.
        shared = "one-secret-used-for-both-purposes"
        settings = Settings(allow_byok=True, internal_token=shared, device_token=shared)
        assert settings.byok_enabled is False
        assert "must differ" in (settings.byok_disabled_reason or "")

    def test_default_is_disabled(self) -> None:
        # Nothing is enabled by accident: a fresh deployment has to opt in.
        assert Settings().byok_enabled is False

    def test_session_key_defaults_are_bounded(self) -> None:
        settings = Settings()
        assert settings.session_key_ttl_seconds > 0
        assert settings.session_key_ttl_seconds <= 3600
        assert settings.session_key_max_entries > 0

    def test_env_vars_are_read(self) -> None:
        settings = Settings.from_env(
            {
                "ALADIN_INTERNAL_TOKEN": "internal-token-value-32-characters",
                "ALADIN_DEVICE_TOKEN": "device-token-value-32-characters",
                "ALADIN_ALLOW_BYOK": "true",
                "ALADIN_SESSION_KEY_TTL_SECONDS": "120",
                "ALADIN_SESSION_KEY_MAX_ENTRIES": "25",
                "ALADIN_TOKEN_API_URL": "http://token-api.internal:8080",
            }
        )
        assert settings.byok_enabled is True
        assert settings.session_key_ttl_seconds == 120
        assert settings.session_key_max_entries == 25
        assert settings.token_api_url == "http://token-api.internal:8080"

    def test_allow_byok_false_from_env(self) -> None:
        settings = Settings.from_env(
            {
                "ALADIN_INTERNAL_TOKEN": "internal-token-value-32-characters",
                "ALADIN_ALLOW_BYOK": "false",
            }
        )
        assert settings.byok_enabled is False

    def test_bad_integer_is_reported(self) -> None:
        with pytest.raises(SettingsError):
            Settings.from_env({"ALADIN_SESSION_KEY_TTL_SECONDS": "soon"})

    def test_internal_token_is_redacted_in_the_log_view(self) -> None:
        secret = "internal-token-value-that-must-not-be-logged"
        settings = Settings(internal_token=secret, device_token="d" * 32)
        dumped = str(settings.redacted())
        assert secret not in dumped
        assert settings.redacted()["byok_enabled"] is True

    def test_disabled_reason_is_visible_in_the_log_view(self) -> None:
        settings = Settings(allow_byok=False)
        assert settings.redacted()["byok_disabled_reason"]
        assert settings.redacted()["byok_enabled"] is False

    def test_token_api_does_not_require_byok(self) -> None:
        # BYOK is optional: a deployment without it must still serve tokens.
        settings = Settings(
            livekit_url="wss://x.livekit.cloud",
            livekit_api_key="APIabcdefghijklmnop",
            livekit_api_secret="s" * 32,
            device_token="d" * 32,
        )
        assert settings.byok_enabled is False
        assert settings.missing_for_token_api() == []
