"""
Logging configuration for Aladin.

Logger names are namespaced under ``aladin.*`` so the backend can be dialled up
or down without drowning in LiveKit's own very chatty agents framework, which
logs at DEBUG by default.

A filter scrubs anything that looks like an API key from log records. This is
belt-and-braces: the code should never log a secret in the first place, but a
future plugin or a stray ``repr()`` of a config object would otherwise leak one.
"""

from __future__ import annotations

import logging
import re

__all__ = ["configure_logging", "redact_secrets_in_text"]

#: Patterns that should never appear in logs.
_SECRET_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    # Google / Gemini API keys
    (re.compile(r"AIza[0-9A-Za-z_\-]{10,}"), "AIza<redacted>"),
    # OpenAI keys, incl. project-scoped
    (re.compile(r"sk-[A-Za-z0-9_\-]{12,}"), "sk-<redacted>"),
    # LiveKit API keys
    (re.compile(r"\bAPI[0-9A-Za-z]{8,}\b"), "API<redacted>"),
    # Bearer / token style headers
    (re.compile(r"(?i)\b(bearer|token|api[_-]?key)\s*[=:]\s*[^\s,;\"']{8,}"), r"\1=<redacted>"),
)


def redact_secrets_in_text(text: str) -> str:
    """Replace anything that looks like a credential with a placeholder."""
    for pattern, replacement in _SECRET_PATTERNS:
        text = pattern.sub(replacement, text)
    return text


class _SecretRedactingFilter(logging.Filter):
    """Scrub credentials out of formatted log messages and arguments."""

    def filter(self, record: logging.LogRecord) -> bool:  # noqa: A003
        try:
            if isinstance(record.msg, str):
                record.msg = redact_secrets_in_text(record.msg)
            if record.args:
                if isinstance(record.args, dict):
                    record.args = {
                        key: (
                            redact_secrets_in_text(value)
                            if isinstance(value, str)
                            else value
                        )
                        for key, value in record.args.items()
                    }
                elif isinstance(record.args, tuple):
                    record.args = tuple(
                        redact_secrets_in_text(arg) if isinstance(arg, str) else arg
                        for arg in record.args
                    )
        except Exception:  # pragma: no cover - logging must never raise
            pass
        return True


def configure_logging(level: str = "INFO") -> None:
    """Configure root logging with secret redaction applied."""
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)-8s %(name)s — %(message)s",
        datefmt="%H:%M:%S",
    )
    _install_redaction()

    # LiveKit's agents framework is extremely verbose at DEBUG.
    if level.upper() != "DEBUG":
        logging.getLogger("livekit.agents").setLevel(logging.INFO)


def _install_redaction() -> None:
    filter_instance = _SecretRedactingFilter()
    root = logging.getLogger()
    if not any(isinstance(f, _SecretRedactingFilter) for f in root.filters):
        root.addFilter(filter_instance)
    # Handlers created before this call also need the filter.
    for handler in root.handlers:
        if not any(isinstance(f, _SecretRedactingFilter) for f in handler.filters):
            handler.addFilter(filter_instance)
