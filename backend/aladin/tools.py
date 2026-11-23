"""
Web search for Aladin.

Two strategies, chosen automatically by :func:`aladin.providers.tool_mode_for`:

``grounding`` (Gemini, default)
    Gemini's built-in Google Search grounding, exposed by the LiveKit Google
    plugin as :class:`livekit.plugins.google.tools.GoogleSearch`. No extra API
    key, and search happens server-side, so it adds very little latency.

    Gemini Live **cannot** combine grounding with function-calling tools in the
    same session, so when this mode is active no function tools are registered.

``function`` (OpenAI, or Gemini with grounding disabled)
    A real function tool the model can call. Backed by Tavily or Brave, both of
    which have free tiers.

Results are also formatted for the *ear*: the model sees the source's **domain**
so it can attribute an answer ("according to bbc.com") but never a full URL it
could read out, while the app receives the complete links to display. That split
is deliberate — the acceptance criterion for search is a spoken answer with
sources visible in the app and *none of them read aloud*, and a URL the model
never sees cannot be read aloud.

Where the sources come from, per provider
-----------------------------------------
``function`` mode knows exactly what was searched, so the results are published
to the app. ``grounding`` mode does **not**: the search happens inside Google's
servers, and the LiveKit Google plugin does not surface Gemini's
``grounding_metadata`` (verified against livekit-plugins-google 1.8.3 — nothing
in the plugin reads it). Rather than invent a source list for grounding
sessions, Aladin publishes none and the app shows none. A fabricated citation
would be worse than a missing one.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Sequence
from urllib.parse import urlparse

from .config import Settings
from .providers import tool_mode_for

logger = logging.getLogger("aladin.tools")

__all__ = [
    "SearchResult",
    "SearchError",
    "SearchEvents",
    "build_tools",
    "format_for_speech",
    "display_host",
    "publish_sources",
    "sources_payload",
    "run_search",
    "redact",
    "MAX_RESULTS",
    "SOURCES_TOPIC",
]

#: Data topic used to push source links to the app.
SOURCES_TOPIC = "aladin.sources"

#: How many results are requested from a backend, and published to the app.
MAX_RESULTS = 5

#: Per-request timeout. A voice assistant that waits longer than this has
#: already broken the conversation, so the search is abandoned instead.
SEARCH_TIMEOUT_SECONDS = 8.0

#: Said when a search could not run. Phrased as an instruction to the model,
#: because that is what it is: the tool returns text, and the model speaks it.
SEARCH_FAILED_MESSAGE = (
    "The web search failed, so you could not check this. Tell the user plainly "
    "that you could not search right now, answer from what you already know if "
    "you are confident, and say which parts you could not verify. Do not guess "
    "at anything current."
)

#: Said when the search ran but found nothing worth using.
SEARCH_EMPTY_MESSAGE = (
    "The search returned no usable results. Say that you could not find "
    "anything, rather than filling the gap with a guess."
)


class SearchError(RuntimeError):
    """Raised when a search provider fails."""


@dataclass(frozen=True, slots=True)
class SearchResult:
    """One web result."""

    title: str
    url: str
    snippet: str = ""

    def to_dict(self) -> dict[str, str]:
        return {"title": self.title, "url": self.url, "snippet": self.snippet}


# --------------------------------------------------------------------------
# Search events
# --------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class SearchEvents:
    """
    Hooks the voice loop uses to reflect search activity in the app.

    Passed in rather than reached for, for two reasons: it keeps topic names and
    publishing inside the agent worker, where the LiveKit room lives, and it
    means the search tool can be tested in full without a room, a transport or
    an event loop full of LiveKit objects.

    Both hooks are optional and **best-effort**. A UI that fails to update must
    never cost the user their answer, so failures are logged at debug and
    swallowed.
    """

    #: Called immediately before a search leaves the machine.
    on_search_started: Callable[[], Awaitable[None]] | None = None

    #: Called with the results, so the app can show them.
    on_results: Callable[[Sequence[SearchResult]], Awaitable[None]] | None = None


async def _notify(callback: Callable[..., Awaitable[None]] | None, *args: Any) -> None:
    """Run a UI callback, swallowing anything it throws."""
    if callback is None:
        return
    try:
        await callback(*args)
    except Exception as exc:  # pragma: no cover - transport dependent
        logger.debug("search event callback failed: %s", exc)


# --------------------------------------------------------------------------
# Tool wiring
# --------------------------------------------------------------------------


def build_tools(
    settings: Settings,
    *,
    events: SearchEvents | None = None,
) -> list[Any]:
    """
    Build the tool list for an :class:`~livekit.agents.AgentSession`.

    Returns an empty list when no tools should be registered — which is the
    correct behaviour for Gemini when grounding is active, not an error.
    """
    mode = tool_mode_for(settings)

    if mode == "none":
        if settings.web_search:
            logger.warning(
                "Web search was requested but is not available for provider=%s "
                "(no grounding support and no TAVILY_API_KEY / BRAVE_SEARCH_API_KEY). "
                "Continuing without web search.",
                settings.ai_provider,
            )
        return []

    if mode == "grounding":
        try:
            from livekit.plugins.google import tools as google_tools

            logger.info("Web search: Gemini Google Search grounding enabled")
            # No `events` here on purpose: grounding searches happen inside
            # Google's servers and the plugin does not report what was searched
            # or which pages were used, so there is nothing truthful to send.
            # See this module's docstring.
            return [google_tools.GoogleSearch()]
        except Exception as exc:
            logger.error(
                "Failed to enable Google Search grounding (%s); "
                "continuing without web search.",
                exc,
            )
            return []

    # mode == "function"
    tool = _make_external_search_tool(settings, events=events)
    return [tool] if tool is not None else []


def _make_external_search_tool(settings: Settings, *, events: SearchEvents | None) -> Any | None:
    """Create a function tool backed by Tavily or Brave."""
    backend: str
    api_key: str

    if settings.tavily_api_key:
        backend, api_key = "tavily", settings.tavily_api_key
    elif settings.brave_search_api_key:
        backend, api_key = "brave", settings.brave_search_api_key
    else:
        return None

    from livekit.agents import function_tool

    @function_tool(
        name="web_search",
        description=(
            "Search the web for current, real-time or verifiable information. "
            "Call this for news, weather, prices, sports results, recent events, "
            "opening hours, travel details, or any factual question you are not "
            "certain about. "
            "Do NOT call it for things you already know or that cannot change: "
            "arithmetic, definitions, translations, opinions, small talk, or "
            "anything the user is clearly just chatting about. Search is slow, "
            "and using it for those makes you feel slower without being better. "
            "If a search fails, say so instead of guessing. Returns result "
            "snippets to summarise aloud."
        ),
    )
    async def web_search(query: str) -> str:
        """Search the web and return a short summary of the top results.

        Args:
            query: A concise search query in the user's language.
        """
        # A model that calls a tool with an empty query is confused rather than
        # searching. Spending a request on it would be pointless, and would
        # send nothing useful to a third party, so it never leaves the machine.
        if not (query or "").strip():
            logger.debug("web_search called with an empty query; skipping")
            return (
                "No search was run because the query was empty. Ask the user to "
                "repeat what they want to know, or answer without searching."
            )

        await _notify(
            events.on_search_started if events else None,
        )

        try:
            results = await run_search(query, backend=backend, api_key=api_key)
        except SearchError as exc:
            # `exc` is already redacted by the providers.
            logger.warning("web_search failed: %s", exc)
            return SEARCH_FAILED_MESSAGE
        except Exception as exc:
            # An unexpected error must not reach the voice loop mid-sentence.
            # LiveKit would surface it to the model as a tool failure, which is
            # this same outcome — but said in our words, with our logging, and
            # with the provider's text redacted.
            logger.warning(
                "web_search failed unexpectedly: %s: %s",
                type(exc).__name__,
                redact(str(exc), api_key),
            )
            return SEARCH_FAILED_MESSAGE

        if not results:
            logger.info(
                "web_search found nothing backend=%s query_length=%d",
                backend,
                len(query),
            )
            return SEARCH_EMPTY_MESSAGE

        await _notify(events.on_results if events else None, results)
        return format_for_speech(query, results)

    logger.info("Web search: external provider %s enabled", backend)
    return web_search


# --------------------------------------------------------------------------
# Providers
# --------------------------------------------------------------------------


def redact(message: str, *secrets: str) -> str:
    """
    Remove any occurrence of ``secrets`` from ``message``.

    Error text from an HTTP client is not under our control: a library is free
    to include the request body (which carries the API key) or a header in a
    message, and that message ends up in a log line. Redacting the known secrets
    on the way out is cheap insurance against a provider's diagnostics becoming
    Aladin's credential leak.

    Only reasonably long values are replaced, so a one-character "secret" cannot
    turn every message into confetti.
    """
    for secret in secrets:
        if secret and len(secret) >= 6:
            message = message.replace(secret, "<redacted>")
    return message


async def run_search(
    query: str,
    *,
    backend: str,
    api_key: str,
    max_results: int = MAX_RESULTS,
) -> list[SearchResult]:
    """
    Run a web search against the given backend.

    Raises :class:`SearchError` for anything that goes wrong, with a message
    that has the API key redacted out of it.
    """
    query = (query or "").strip()
    if not query:
        return []

    if backend == "tavily":
        return await _tavily(query, api_key, max_results)
    if backend == "brave":
        return await _brave(query, api_key, max_results)
    raise SearchError(f"unknown search backend: {backend!r}")


def _clean(
    items: Any,
    *,
    url_key: str,
    title_key: str,
    body_key: str,
    max_results: int,
) -> list[SearchResult]:
    """
    Turn a provider's result list into :class:`SearchResult` objects.

    Providers change shapes and occasionally return `null` where a list is
    documented, so every field is coerced, anything that is not an object is
    skipped, and anything without a usable URL is dropped rather than passed on
    as a source the user cannot open.

    The cap is applied here rather than at the call sites, because
    ``data.get("results")[:5]`` on a null-but-present field raises
    ``TypeError`` — found by the test that feeds it exactly that.
    """
    if not isinstance(items, list):
        return []

    results: list[SearchResult] = []
    for item in items[:max_results]:
        if not isinstance(item, dict):
            continue
        url = str(item.get(url_key) or "").strip()
        if not url:
            continue
        results.append(
            SearchResult(
                title=str(item.get(title_key) or "").strip() or url,
                url=url,
                snippet=str(item.get(body_key) or "").strip(),
            )
        )
    return results


async def _tavily(query: str, api_key: str, max_results: int) -> list[SearchResult]:
    import aiohttp

    payload = {
        "api_key": api_key,
        "query": query,
        "max_results": max_results,
        "include_answer": False,
        "search_depth": "basic",
    }

    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(
                "https://api.tavily.com/search",
                json=payload,
                timeout=aiohttp.ClientTimeout(total=SEARCH_TIMEOUT_SECONDS),
            ) as response:
                if response.status != 200:
                    body = await response.text()
                    raise SearchError(
                        redact(
                            f"Tavily HTTP {response.status}: {body[:200]}",
                            api_key,
                        )
                    )
                data = await response.json()
    except SearchError:
        raise
    except Exception as exc:
        raise SearchError(
            redact(f"Tavily request failed: {type(exc).__name__}: {exc}", api_key)
        ) from exc

    if not isinstance(data, dict):
        raise SearchError("Tavily returned an unexpected payload")

    return _clean(
        data.get("results"),
        url_key="url",
        title_key="title",
        body_key="content",
        max_results=max_results,
    )


async def _brave(query: str, api_key: str, max_results: int) -> list[SearchResult]:
    import aiohttp

    headers = {
        "Accept": "application/json",
        "X-Subscription-Token": api_key,
    }
    params = {"q": query, "count": max_results}

    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(
                "https://api.search.brave.com/res/v1/web/search",
                headers=headers,
                params=params,
                timeout=aiohttp.ClientTimeout(total=SEARCH_TIMEOUT_SECONDS),
            ) as response:
                if response.status != 200:
                    body = await response.text()
                    raise SearchError(
                        redact(
                            f"Brave HTTP {response.status}: {body[:200]}",
                            api_key,
                        )
                    )
                data = await response.json()
    except SearchError:
        raise
    except Exception as exc:
        raise SearchError(
            redact(f"Brave request failed: {type(exc).__name__}: {exc}", api_key)
        ) from exc

    if not isinstance(data, dict):
        raise SearchError("Brave returned an unexpected payload")

    web = data.get("web")
    results = web.get("results") if isinstance(web, dict) else None
    return _clean(
        results,
        url_key="url",
        title_key="title",
        body_key="description",
        max_results=max_results,
    )


# --------------------------------------------------------------------------
# Formatting
# --------------------------------------------------------------------------


def display_host(url: str) -> str:
    """
    The part of a URL that is safe to say out loud: just the domain.

    ``https://www.bbc.com/news/world-12345`` becomes ``bbc.com``. Returns an
    empty string for anything without a host, because the alternative is
    handing the model a raw string that might itself be a URL to read aloud.
    """
    try:
        host = urlparse(url).netloc
    except ValueError:
        return ""
    host = host.split("@")[-1].split(":")[0].strip().lower()
    if host.startswith("www."):
        host = host[4:]
    return host


def format_for_speech(query: str, results: Sequence[SearchResult]) -> str:
    """
    Render results as text for the model to summarise.

    Only the **domain** of each source is included. The model can therefore
    attribute what it says ("according to bbc.com") but has no URL to read out
    letter by letter, which is the failure this formatting exists to prevent.
    Full URLs go to the app in :func:`publish_sources`, where the user can read
    them at their own pace.
    """
    lines = [f"Web search results for: {query}", ""]
    for index, result in enumerate(results, start=1):
        lines.append(f"{index}. {result.title}")
        if result.snippet:
            lines.append(f"   {result.snippet}")
        host = display_host(result.url)
        lines.append(f"   Source: {host}" if host else "   Source: unknown")
        lines.append("")
    lines.append(
        "Summarise these conversationally and answer the user's question in "
        "your own words. You may say which site you saw it on, but never read a "
        "URL aloud or spell out a web address — the full links are already "
        "shown in the app. If the results do not answer the question, say so "
        "instead of filling the gap."
    )
    return "\n".join(lines)


def sources_payload(results: Sequence[SearchResult]) -> dict[str, Any]:
    """
    Build the payload the app parses.

    Kept as its own function because the shape is a contract with the Android
    app (`SearchSource.fromJson`): ``{type, sources: [{title, url, snippet}]}``.
    A test asserts this exact shape, so a refactor that changes it fails here
    rather than surfacing as an empty sheet on a phone.
    """
    return {
        "type": "sources",
        "sources": [result.to_dict() for result in results[:MAX_RESULTS]],
    }


async def publish_sources(
    room: Any,
    results: Sequence[SearchResult],
    *,
    topic: str = SOURCES_TOPIC,
) -> None:
    """
    Push source links to the app over a LiveKit data topic.

    This is best-effort: caption/source delivery is a nice-to-have and must
    never break the voice loop, so failures are logged and swallowed.
    """
    if not results or room is None:
        return

    try:
        await room.local_participant.publish_data(
            json.dumps(sources_payload(results)).encode("utf-8"),
            reliable=True,
            topic=topic,
        )
    except Exception as exc:  # pragma: no cover - transport dependent
        logger.debug("could not publish sources: %s", exc)
