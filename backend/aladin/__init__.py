"""
Aladin — Android-first personal AI voice assistant.

Backend package: a LiveKit Agents worker that runs the realtime voice
conversation, plus a small FastAPI service that mints room tokens for the
Android app.

Public surface:

* :mod:`aladin.config`       — environment-driven settings
* :mod:`aladin.prompts`      — persona / multilingual system instructions
* :mod:`aladin.providers`    — Gemini and OpenAI realtime model factory
* :mod:`aladin.tools`        — web search
* :mod:`aladin.agent_worker` — the LiveKit Agents worker
* :mod:`aladin.token_server` — the token-minting API
"""

from __future__ import annotations

__version__ = "0.1.0"
__all__ = ["__version__"]
