"""
Shared test environment.

``aladin.token_server`` reads its configuration from the environment **at import
time** (``settings = _load_settings()``), so whichever test module imports it
first decides the configuration for the whole session. That made the suite
order-dependent: a module that forgot to set ``ALADIN_TOKEN_TTL_SECONDS`` would
silently give every later test the default TTL instead of the value they assert
on.

Setting the environment here — in ``conftest.py``, which pytest imports before
any test module — removes the ordering question entirely. Modules that need to
exercise a *different* configuration should replace
``aladin.token_server.settings`` with a fixture (see
``test_byok_endpoints.py``) rather than relying on import order.
"""

from __future__ import annotations

import os

#: Canonical values for the suite. These are obviously fake, and deliberately
#: shaped like the real thing (a 32-character secret, an ``AIza``-prefixed
#: Gemini key) so that length-dependent behaviour is exercised.
TEST_LIVEKIT_URL = "wss://test-project.livekit.cloud"
TEST_LIVEKIT_API_KEY = "APItestkey123456789"
TEST_LIVEKIT_API_SECRET = "test-secret-value-at-least-32-characters-long"
TEST_DEVICE_TOKEN = "device-token-for-tests"
TEST_GEMINI_KEY = "AIzaSyFAKEKEYVALUE1234567"
TEST_TOKEN_TTL_SECONDS = "600"

os.environ.update(
    {
        "LIVEKIT_URL": TEST_LIVEKIT_URL,
        "LIVEKIT_API_KEY": TEST_LIVEKIT_API_KEY,
        "LIVEKIT_API_SECRET": TEST_LIVEKIT_API_SECRET,
        "ALADIN_DEVICE_TOKEN": TEST_DEVICE_TOKEN,
        "GEMINI_API_KEY": TEST_GEMINI_KEY,
        "ALADIN_AI_PROVIDER": "gemini",
        "ALADIN_TOKEN_TTL_SECONDS": TEST_TOKEN_TTL_SECONDS,
        "ALADIN_LOG_LEVEL": "WARNING",
        # Bring-your-own-key is deliberately left *unconfigured* here: the
        # default deployment has no ALADIN_INTERNAL_TOKEN, and tests that need
        # it enabled patch the settings object instead.
        "ALADIN_INTERNAL_TOKEN": "",
    }
)
