"""
The Cartesia dependency contract for the **deployed** agent.

Why this test exists
--------------------
Cartesia is now the modular pipeline's primary voice (see
:mod:`aladin.voice_pipeline`), so the deployed image must be able to import it:
without the dependency, `from livekit.plugins import cartesia` raises
ImportError inside the deployed agent, and every modular session would fall back
to the realtime pipeline. That is a loud failure in the logs but a silent one
for the user, who would simply hear the wrong voice.

The same applies to Silero, which provides the modular pipeline's turn
detection. Its absence is survivable (the pipeline degrades to the transcriber's
own endpointing) but it is still a deployment fact worth pinning, because the
degradation is otherwise invisible.

So these tests pin the two things that would otherwise regress quietly:

1. **Both** packaging files declare the `cartesia` extra. They mirror each
   other (``pyproject.toml`` says so itself), and a change to one that misses
   the other is exactly how a dependency disappears from a build.
2. The plugin actually imports, and exposes the ``TTS``/``STT`` classes an
   adapter would construct — the import path the deployment relies on.
3. No Cartesia key was introduced into the deployment: the key must keep
   arriving through the app-managed provider path (a session key fetched with
   the internal token), never as a workflow secret or a hardcoded value.

The third check is deliberately about *configuration*, not about the SDK: if a
future change adds a Cartesia key to the deploy workflow's SECRET_LIST, the
provider path above has been bypassed and this test should fail loudly.
"""

from __future__ import annotations

import pathlib
import re

import pytest

#: `<repo root>/backend/tests/this_file` -> `<repo root>`
REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
REQUIREMENTS = REPO_ROOT / "backend" / "requirements.txt"
PYPROJECT = REPO_ROOT / "backend" / "pyproject.toml"
DEPLOY_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "deploy-livekit-agent.yml"


def _livekit_agents_line(text: str, pattern: str) -> str:
    """Return the ``livekit-agents[...]`` requirement line from *text*."""
    match = re.search(pattern, text)
    assert match is not None, (
        "no livekit-agents requirement found; the agent image would have no "
        "voice SDK at all"
    )
    return match.group(0)


@pytest.mark.parametrize(
    ("path", "pattern"),
    [
        (REQUIREMENTS, r"livekit-agents\[[^\]]*\][^\n]*"),
        (PYPROJECT, r'"livekit-agents\[[^\]]*\][^"]*"'),
    ],
    ids=["requirements.txt", "pyproject.toml"],
)
def test_the_cartesia_extra_is_declared(path: pathlib.Path, pattern: str) -> None:
    """The deployed image must install livekit-plugins-cartesia."""
    requirement = _livekit_agents_line(path.read_text(encoding="utf-8"), pattern)
    assert "cartesia" in requirement, (
        f"{path.name} does not request the cartesia extra: {requirement!r}. "
        "The deployed agent could not import livekit.plugins.cartesia."
    )


@pytest.mark.parametrize(
    "path",
    [REQUIREMENTS, PYPROJECT],
    ids=["requirements.txt", "pyproject.toml"],
)
def test_the_vad_dependency_is_declared(path: pathlib.Path) -> None:
    """The modular pipeline's turn detection needs Silero in the image."""
    text = path.read_text(encoding="utf-8")
    assert "livekit-plugins-silero" in text, (
        f"{path.name} does not install livekit-plugins-silero; the modular "
        "pipeline would silently fall back to STT endpointing."
    )


def test_gemini_stays_the_default_provider() -> None:
    """
    Adding Cartesia must not have disturbed the existing default.

    The default lives in the settings module, so this reads it from there
    rather than from a comment: if the default provider changed, every new
    session would change provider.
    """
    from aladin.config import Settings

    default = Settings.from_env({})
    assert default.ai_provider == "gemini"


def test_cartesia_plugin_imports_with_the_classes_an_adapter_needs() -> None:
    """The import path the deployment relies on, exercised for real."""
    from livekit.plugins import cartesia

    assert hasattr(cartesia, "TTS"), "cartesia.TTS is missing"
    assert hasattr(cartesia, "STT"), "cartesia.STT is missing"


def test_no_cartesia_key_is_configured_by_the_deployment() -> None:
    """
    A Cartesia key must not enter through the deployment.

    Requirement: keys keep coming from the app-managed provider configuration
    (fetched by the agent over the internal-token API). A `CARTESIA_API_KEY`
    in the workflow would be a second, conflicting source of truth — and would
    be exactly the hardcoding this contract forbids.
    """
    workflow = DEPLOY_WORKFLOW.read_text(encoding="utf-8")
    assert "CARTESIA" not in workflow.upper(), (
        "the deploy workflow mentions Cartesia. Keys must arrive through the "
        "app-managed provider path, not as a deployment secret."
    )


def test_the_internal_token_still_reaches_the_agent() -> None:
    """
    Cartesia's key travels over the internal-token channel, so that channel
    must still be configured for the deployed agent.

    This is the one line that makes the whole provider flow possible: without
    ALADIN_INTERNAL_TOKEN in the agent's environment, the agent cannot ask the
    token API for any app-saved provider key.
    """
    workflow = DEPLOY_WORKFLOW.read_text(encoding="utf-8")
    assert "ALADIN_INTERNAL_TOKEN=${{ secrets.ALADIN_INTERNAL_TOKEN }}" in workflow, (
        "the deploy workflow no longer passes ALADIN_INTERNAL_TOKEN to the "
        "agent; app-managed provider keys could not be fetched."
    )
