"""
Tests for web search: the tool the model calls, the two backends behind it, and
the two things it publishes back to the app.

Written around the questions that decide whether search is *usable* rather than
whether it returns a list:

1. **Does a search actually happen, and does the user see that it did?**
   (success path, the ``searching`` state, the sources payload)
2. **What happens when it does not?** (HTTP error, timeout, malformed body,
   empty query, no results) — every one of these must produce something
   truthful for the model to say, and must never raise into the voice loop.
3. **Can the API key escape?** The key travels in a request body for Tavily and
   a header for Brave, so a provider error message is the realistic leak path.
   The key must not appear in the exception, in a log line, or in the payload
   sent to the app.
4. **Is the switch honoured?** Search off must mean no tool, no request, and no
   data published — the setting is a promise about privacy as much as cost.

Nothing here makes a network call: ``aiohttp.ClientSession`` is replaced with a
fake that returns canned responses, and the tool's own search function is
patched where the point of the test is the tool's behaviour rather than the
HTTP layer.
"""

from __future__ import annotations

import asyncio
import json
import logging

import pytest

from aladin.agent_worker import STATE_TOPIC, _search_events
from aladin.config import Settings
from aladin.tools import (
    MAX_RESULTS,
    SEARCH_EMPTY_MESSAGE,
    SEARCH_FAILED_MESSAGE,
    SOURCES_TOPIC,
    SearchError,
    SearchEvents,
    SearchResult,
    build_tools,
    display_host,
    format_for_speech,
    publish_sources,
    redact,
    run_search,
    sources_payload,
)

#: Fake credentials. Shaped like the real thing so length checks behave.
TAVILY_KEY = "tvly-FAKEKEYVALUE1234567890"
BRAVE_KEY = "BSAFAKEKEYVALUE1234567890"


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
        "livekit_url": "wss://test.livekit.cloud",
        "livekit_api_key": "APIabcdefghij",
        "livekit_api_secret": "s" * 32,
    }
    base.update(kwargs)
    return Settings(**base)


# ---------------------------------------------------------------------------
# A fake aiohttp, so the HTTP layer can be exercised without a network
# ---------------------------------------------------------------------------


class FakeResponse:
    def __init__(self, *, status: int = 200, payload: object = None, text: str = "") -> None:
        self.status = status
        self._payload = payload
        self._text = text

    async def text(self) -> str:
        return self._text

    async def json(self) -> object:
        if isinstance(self._payload, Exception):
            raise self._payload
        return self._payload

    async def __aenter__(self) -> "FakeResponse":
        return self

    async def __aexit__(self, *exc: object) -> bool:
        return False


class FakeSession:
    """Stands in for ``aiohttp.ClientSession``."""

    #: Set by the test: the response to return, or an exception to raise.
    outcome: object = FakeResponse(payload={})
    calls: list[dict[str, object]] = []

    def __init__(self, *args: object, **kwargs: object) -> None:
        pass

    async def __aenter__(self) -> "FakeSession":
        return self

    async def __aexit__(self, *exc: object) -> bool:
        return False

    def _record(self, method: str, url: str, kwargs: dict[str, object]) -> FakeResponse:
        FakeSession.calls.append({"method": method, "url": url, **kwargs})
        if isinstance(FakeSession.outcome, Exception):
            raise FakeSession.outcome
        assert isinstance(FakeSession.outcome, FakeResponse)
        return FakeSession.outcome

    def post(self, url: str, **kwargs: object) -> FakeResponse:
        return self._record("post", url, kwargs)

    def get(self, url: str, **kwargs: object) -> FakeResponse:
        return self._record("get", url, kwargs)


@pytest.fixture
def fake_http(monkeypatch: pytest.MonkeyPatch) -> type[FakeSession]:
    FakeSession.calls = []
    FakeSession.outcome = FakeResponse(payload={})
    monkeypatch.setattr("aiohttp.ClientSession", FakeSession)
    return FakeSession


# ---------------------------------------------------------------------------
# Success
# ---------------------------------------------------------------------------


class TestTavily:
    @pytest.mark.asyncio
    async def test_parses_results(self, fake_http: type[FakeSession]) -> None:
        fake_http.outcome = FakeResponse(
            payload={
                "results": [
                    {"title": "First", "url": "https://a.example/x", "content": "one"},
                    {"title": "Second", "url": "https://b.example/y", "content": "two"},
                ]
            }
        )

        results = await run_search("anything", backend="tavily", api_key=TAVILY_KEY)

        assert [r.title for r in results] == ["First", "Second"]
        assert results[0].url == "https://a.example/x"
        assert results[0].snippet == "one"

    @pytest.mark.asyncio
    async def test_sends_the_key_in_the_body_not_the_url(
        self, fake_http: type[FakeSession]
    ) -> None:
        # The key must not end up in a URL, where it would be logged by every
        # proxy on the way and stored in the provider's access logs.
        fake_http.outcome = FakeResponse(payload={"results": []})

        await run_search("q", backend="tavily", api_key=TAVILY_KEY)

        call = fake_http.calls[0]
        assert "tvly" not in str(call["url"])
        assert call["json"]["api_key"] == TAVILY_KEY  # type: ignore[index]

    @pytest.mark.asyncio
    async def test_caps_results_at_the_requested_count(
        self, fake_http: type[FakeSession]
    ) -> None:
        fake_http.outcome = FakeResponse(
            payload={
                "results": [
                    {"title": f"t{i}", "url": f"https://e{i}.example", "content": ""}
                    for i in range(20)
                ]
            }
        )

        results = await run_search("q", backend="tavily", api_key=TAVILY_KEY)

        assert len(results) == MAX_RESULTS

    @pytest.mark.asyncio
    async def test_results_without_a_url_are_dropped(
        self, fake_http: type[FakeSession]
    ) -> None:
        # A source the user cannot open is not a source.
        fake_http.outcome = FakeResponse(
            payload={
                "results": [
                    {"title": "no url", "content": "x"},
                    {"title": "", "url": "https://real.example", "content": ""},
                ]
            }
        )

        results = await run_search("q", backend="tavily", api_key=TAVILY_KEY)

        assert len(results) == 1
        assert results[0].title == "https://real.example", "falls back to the URL"

    @pytest.mark.asyncio
    async def test_empty_result_list_is_not_an_error(
        self, fake_http: type[FakeSession]
    ) -> None:
        fake_http.outcome = FakeResponse(payload={"results": []})
        assert await run_search("q", backend="tavily", api_key=TAVILY_KEY) == []

    @pytest.mark.asyncio
    async def test_null_results_field(self, fake_http: type[FakeSession]) -> None:
        # Providers do return null where a list is documented.
        fake_http.outcome = FakeResponse(payload={"results": None})
        assert await run_search("q", backend="tavily", api_key=TAVILY_KEY) == []


class TestBrave:
    @pytest.mark.asyncio
    async def test_parses_the_nested_shape(self, fake_http: type[FakeSession]) -> None:
        fake_http.outcome = FakeResponse(
            payload={"web": {"results": [{"title": "T", "url": "https://b.example", "description": "d"}]}}
        )

        results = await run_search("anything", backend="brave", api_key=BRAVE_KEY)

        assert len(results) == 1
        assert results[0].snippet == "d"

    @pytest.mark.asyncio
    async def test_sends_the_key_in_a_header(self, fake_http: type[FakeSession]) -> None:
        fake_http.outcome = FakeResponse(payload={"web": {"results": []}})

        await run_search("q", backend="brave", api_key=BRAVE_KEY)

        call = fake_http.calls[0]
        assert call["headers"]["X-Subscription-Token"] == BRAVE_KEY  # type: ignore[index]
        assert BRAVE_KEY not in str(call["params"])

    @pytest.mark.asyncio
    async def test_missing_web_key_does_not_crash(
        self, fake_http: type[FakeSession]
    ) -> None:
        fake_http.outcome = FakeResponse(payload={"query": "q"})
        assert await run_search("q", backend="brave", api_key=BRAVE_KEY) == []


# ---------------------------------------------------------------------------
# Failure
# ---------------------------------------------------------------------------


class TestFailures:
    @pytest.mark.asyncio
    async def test_http_error_raises_search_error(
        self, fake_http: type[FakeSession]
    ) -> None:
        fake_http.outcome = FakeResponse(status=429, text="rate limited")

        with pytest.raises(SearchError) as caught:
            await run_search("q", backend="tavily", api_key=TAVILY_KEY)

        assert "429" in str(caught.value)
        assert "rate limited" in str(caught.value)

    @pytest.mark.asyncio
    async def test_error_body_is_truncated(self, fake_http: type[FakeSession]) -> None:
        # A provider that returns a novel must not fill the log.
        fake_http.outcome = FakeResponse(status=500, text="x" * 5_000)

        with pytest.raises(SearchError) as caught:
            await run_search("q", backend="brave", api_key=BRAVE_KEY)

        assert len(str(caught.value)) < 400

    @pytest.mark.asyncio
    async def test_timeout_becomes_a_search_error(
        self, fake_http: type[FakeSession]
    ) -> None:
        fake_http.outcome = asyncio.TimeoutError()

        with pytest.raises(SearchError) as caught:
            await run_search("q", backend="tavily", api_key=TAVILY_KEY)

        assert "Tavily request failed" in str(caught.value)

    @pytest.mark.asyncio
    async def test_connection_error_names_the_exception_type(
        self, fake_http: type[FakeSession]
    ) -> None:
        # The type is what a reader needs; the message alone is often empty.
        fake_http.outcome = ConnectionResetError("connection reset by peer")

        with pytest.raises(SearchError) as caught:
            await run_search("q", backend="brave", api_key=BRAVE_KEY)

        assert "ConnectionResetError" in str(caught.value)

    @pytest.mark.asyncio
    async def test_a_malformed_json_body_is_reported(
        self, fake_http: type[FakeSession]
    ) -> None:
        fake_http.outcome = FakeResponse(payload=ValueError("Expecting value: line 1 column 1"))

        with pytest.raises(SearchError) as caught:
            await run_search("q", backend="tavily", api_key=TAVILY_KEY)

        assert "Tavily request failed" in str(caught.value)

    @pytest.mark.asyncio
    async def test_an_unexpected_payload_shape_is_reported(
        self, fake_http: type[FakeSession]
    ) -> None:
        fake_http.outcome = FakeResponse(payload=["not", "a", "dict"])

        with pytest.raises(SearchError, match="unexpected payload"):
            await run_search("q", backend="tavily", api_key=TAVILY_KEY)

    @pytest.mark.asyncio
    async def test_an_empty_query_needs_no_network(
        self, fake_http: type[FakeSession]
    ) -> None:
        assert await run_search("   ", backend="tavily", api_key=TAVILY_KEY) == []
        assert fake_http.calls == [], "an empty query must not leave the machine"

    @pytest.mark.asyncio
    async def test_an_unknown_backend_is_refused(
        self, fake_http: type[FakeSession]
    ) -> None:
        with pytest.raises(SearchError, match="unknown search backend"):
            await run_search("q", backend="altavista", api_key=TAVILY_KEY)


# ---------------------------------------------------------------------------
# Secret hygiene
# ---------------------------------------------------------------------------


class TestSecretHygiene:
    @pytest.mark.asyncio
    async def test_a_key_echoed_by_a_provider_error_is_redacted(
        self, fake_http: type[FakeSession]
    ) -> None:
        # The realistic leak: a provider or HTTP library quotes the request
        # back, key and all, and that string goes straight into a log line.
        fake_http.outcome = FakeResponse(
            status=400, text=f'invalid api_key: "{TAVILY_KEY}"'
        )

        with pytest.raises(SearchError) as caught:
            await run_search("q", backend="tavily", api_key=TAVILY_KEY)

        assert TAVILY_KEY not in str(caught.value)
        assert "<redacted>" in str(caught.value)

    @pytest.mark.asyncio
    async def test_a_key_in_a_raised_exception_is_redacted(
        self, fake_http: type[FakeSession]
    ) -> None:
        fake_http.outcome = RuntimeError(f"header X-Subscription-Token: {BRAVE_KEY} rejected")

        with pytest.raises(SearchError) as caught:
            await run_search("q", backend="brave", api_key=BRAVE_KEY)

        assert BRAVE_KEY not in str(caught.value)

    @pytest.mark.asyncio
    async def test_a_failed_search_logs_no_key(
        self, fake_http: type[FakeSession], caplog: pytest.LogCaptureFixture
    ) -> None:
        fake_http.outcome = FakeResponse(status=401, text=f"denied for {TAVILY_KEY}")
        tools = build_tools(openai_settings(tavily_api_key=TAVILY_KEY))
        assert tools

        with caplog.at_level(logging.DEBUG):
            await tools[0](query="today's news")

        logged = "\n".join(record.getMessage() for record in caplog.records)
        assert TAVILY_KEY not in logged
        assert "denied" in logged, "the useful part of the error still gets through"

    def test_redact_leaves_short_values_alone(self) -> None:
        # Replacing a two-character "secret" would shred the message.
        assert redact("a normal message", "ab") == "a normal message"

    def test_redact_handles_an_empty_secret(self) -> None:
        assert redact("message", "") == "message"

    def test_redact_replaces_every_occurrence(self) -> None:
        assert redact("k and k", "secret-key") == "k and k"
        assert redact("secret-key and secret-key", "secret-key") == (
            "<redacted> and <redacted>"
        )


# ---------------------------------------------------------------------------
# What the model hears, and what the app sees
# ---------------------------------------------------------------------------


class TestFormatForSpeech:
    def test_urls_are_replaced_by_the_domain(self) -> None:
        # The model cannot read out a URL it never sees.
        text = format_for_speech(
            "q", [SearchResult(title="T", url="https://www.bbc.com/news/world-1", snippet="s")]
        )
        assert "bbc.com" in text
        assert "https://" not in text
        assert "news/world-1" not in text

    def test_it_asks_for_a_conversational_answer(self) -> None:
        text = format_for_speech("q", [SearchResult(title="T", url="https://e.com")])
        assert "own words" in text
        assert "never read a URL aloud" in text

    def test_it_forbids_filling_gaps(self) -> None:
        text = format_for_speech("q", [SearchResult(title="T", url="https://e.com")])
        assert "do not answer the question" in text

    def test_it_includes_the_snippets(self) -> None:
        text = format_for_speech("q", [SearchResult(title="T", url="https://e.com", snippet="fact")])
        assert "fact" in text

    def test_every_result_is_numbered(self) -> None:
        text = format_for_speech(
            "q",
            [
                SearchResult(title="A", url="https://a.com"),
                SearchResult(title="B", url="https://b.com"),
            ],
        )
        assert "1. A" in text
        assert "2. B" in text

    def test_a_url_without_a_host_does_not_leak_the_raw_string(self) -> None:
        text = format_for_speech("q", [SearchResult(title="T", url="not a url")])
        assert "not a url" not in text
        assert "Source: unknown" in text


class TestDisplayHost:
    @pytest.mark.parametrize(
        ("url", "expected"),
        [
            ("https://www.bbc.com/news/world-1", "bbc.com"),
            ("http://example.com", "example.com"),
            ("https://sub.domain.example.co.uk/path", "sub.domain.example.co.uk"),
            ("https://user:pass@private.example/x", "private.example"),
            ("https://example.com:8443/x", "example.com"),
            ("ftp://files.example/x", "files.example"),
            ("not a url", ""),
            ("", ""),
            ("https://", ""),
        ],
    )
    def test_it_extracts_just_the_host(self, url: str, expected: str) -> None:
        assert display_host(url) == expected

    def test_a_credential_in_a_url_cannot_leak(self) -> None:
        # A URL with embedded credentials must not put the password in the
        # prompt sent to the model.
        assert display_host("https://user:hunter2@example.com/x") == "example.com"


class TestSourcesPayload:
    def test_shape_matches_what_the_app_parses(self) -> None:
        payload = sources_payload(
            [SearchResult(title="T", url="https://e.com", snippet="s")]
        )
        assert payload == {
            "type": "sources",
            "sources": [{"title": "T", "url": "https://e.com", "snippet": "s"}],
        }

    def test_it_survives_json_encoding(self) -> None:
        payload = sources_payload([SearchResult(title="T", url="https://e.com")])
        assert json.loads(json.dumps(payload))["sources"][0]["url"] == "https://e.com"

    def test_it_is_capped(self) -> None:
        payload = sources_payload(
            [SearchResult(title=str(i), url=f"https://e{i}.com") for i in range(50)]
        )
        assert len(payload["sources"]) == MAX_RESULTS

    def test_it_carries_no_key_field(self) -> None:
        payload = sources_payload([SearchResult(title="T", url="https://e.com")])
        assert "api_key" not in json.dumps(payload)

    def test_publishing_uses_the_sources_topic(self) -> None:
        room = _FakeRoom()
        asyncio.run(publish_sources(room, [SearchResult(title="T", url="https://e.com")]))

        assert room.published[0][0] == SOURCES_TOPIC
        assert json.loads(room.published[0][1])["sources"][0]["url"] == "https://e.com"

    def test_publishing_nothing_sends_nothing(self) -> None:
        room = _FakeRoom()
        asyncio.run(publish_sources(room, []))
        assert room.published == []

    def test_publishing_without_a_room_is_a_no_op(self) -> None:
        asyncio.run(publish_sources(None, [SearchResult(title="T", url="https://e.com")]))

    def test_a_transport_failure_is_swallowed(self) -> None:
        # Sources are a nice-to-have; losing them must not break the answer.
        room = _FakeRoom(raise_on_publish=True)
        asyncio.run(publish_sources(room, [SearchResult(title="T", url="https://e.com")]))


class _FakeRoom:
    """Just enough room to observe what gets published."""

    def __init__(self, *, raise_on_publish: bool = False) -> None:
        self.published: list[tuple[str, str]] = []
        self._raise = raise_on_publish
        room = self

        class _Participant:
            async def publish_data(self, data: bytes, **kwargs: object) -> None:
                if room._raise:
                    raise RuntimeError("transport gone")
                room.published.append((str(kwargs.get("topic")), data.decode("utf-8")))

        self.local_participant = _Participant()


# ---------------------------------------------------------------------------
# The tool the model calls
# ---------------------------------------------------------------------------


class TestSearchTool:
    """The tool's behaviour with the HTTP layer replaced."""

    @pytest.fixture
    def recorded(self, monkeypatch: pytest.MonkeyPatch) -> dict[str, object]:
        """Capture what the tool asks for and what it reports back."""
        events: dict[str, object] = {"started": 0, "results": None}

        async def fake_run_search(query: str, *, backend: str, api_key: str, **_: object):
            events["query"] = query
            events["backend"] = backend
            outcome = events.get("outcome")
            if isinstance(outcome, Exception):
                raise outcome
            return outcome if outcome is not None else []

        monkeypatch.setattr("aladin.tools.run_search", fake_run_search)
        return events

    def _tool(self, events: SearchEvents):
        tools = build_tools(
            openai_settings(tavily_api_key=TAVILY_KEY, web_search=True),
            events=events,
        )
        assert len(tools) == 1
        return tools[0]

    @pytest.mark.asyncio
    async def test_a_successful_search_reports_both_events(
        self, recorded: dict[str, object]
    ) -> None:
        recorded["outcome"] = [SearchResult(title="T", url="https://e.com", snippet="s")]
        seen: list[object] = []

        async def on_started() -> None:
            seen.append("started")

        async def on_results(results: object) -> None:
            seen.append(results)

        tool = self._tool(SearchEvents(on_search_started=on_started, on_results=on_results))
        text = await tool(query="today's news in Dhaka")

        assert seen[0] == "started", "the UI must know a search is in flight"
        assert isinstance(seen[1], list)
        assert "e.com" in text

    @pytest.mark.asyncio
    async def test_a_failed_search_reports_no_sources(
        self, recorded: dict[str, object]
    ) -> None:
        recorded["outcome"] = SearchError("Tavily HTTP 500: boom")
        seen: list[str] = []

        async def on_results(results: object) -> None:
            seen.append("results")

        tool = self._tool(SearchEvents(on_results=on_results))
        text = await tool(query="q")

        assert seen == [], "a failed search must not publish stale or empty sources"
        assert text == SEARCH_FAILED_MESSAGE

    @pytest.mark.asyncio
    async def test_an_empty_result_set_says_so(
        self, recorded: dict[str, object]
    ) -> None:
        recorded["outcome"] = []
        tool = self._tool(SearchEvents())
        assert await tool(query="q") == SEARCH_EMPTY_MESSAGE

    @pytest.mark.asyncio
    async def test_an_empty_query_does_not_search(
        self, recorded: dict[str, object]
    ) -> None:
        tool = self._tool(SearchEvents())
        text = await tool(query="   ")

        assert "query" not in recorded, "no search was requested"
        assert "empty" in text.lower()

    @pytest.mark.asyncio
    async def test_an_unexpected_error_never_escapes_the_tool(
        self, recorded: dict[str, object]
    ) -> None:
        # Anything raised here would reach the voice loop mid-sentence.
        recorded["outcome"] = RuntimeError("something unexpected")
        tool = self._tool(SearchEvents())

        assert await tool(query="q") == SEARCH_FAILED_MESSAGE

    @pytest.mark.asyncio
    async def test_a_failing_ui_callback_still_returns_the_answer(
        self, recorded: dict[str, object]
    ) -> None:
        recorded["outcome"] = [SearchResult(title="T", url="https://e.com")]

        async def broken() -> None:
            raise RuntimeError("no room")

        async def broken_results(results: object) -> None:
            raise RuntimeError("no room")

        tool = self._tool(
            SearchEvents(on_search_started=broken, on_results=broken_results)
        )
        text = await tool(query="q")

        assert "e.com" in text

    @pytest.mark.asyncio
    async def test_the_tool_works_without_any_events(self, recorded: dict[str, object]) -> None:
        recorded["outcome"] = [SearchResult(title="T", url="https://e.com")]
        tool = self._tool(SearchEvents())
        assert "e.com" in await tool(query="q")

    @pytest.mark.asyncio
    async def test_the_query_reaches_the_backend_unchanged(
        self, recorded: dict[str, object]
    ) -> None:
        # The persona asks for the query in the user's language; mangling it
        # (lower-casing, stripping diacritics) would search for the wrong thing.
        recorded["outcome"] = [SearchResult(title="T", url="https://e.com")]
        tool = self._tool(SearchEvents())
        await tool(query="আজকের খবর কী?")

        assert recorded["query"] == "আজকের খবর কী?"

    @pytest.mark.asyncio
    async def test_the_tool_description_discourages_pointless_searches(self) -> None:
        tool = self._tool(SearchEvents())
        description = tool.info.description.lower()
        assert "do not call it" in description or "do not" in description
        assert "arithmetic" in description
        assert "small talk" in description

    @pytest.mark.asyncio
    async def test_the_tool_description_asks_for_honesty_on_failure(self) -> None:
        tool = self._tool(SearchEvents())
        assert "instead of guessing" in tool.info.description.lower()


class TestToolSelectionPerProvider:
    def test_gemini_gets_grounding_and_no_function_tool(self) -> None:
        tools = build_tools(gemini_settings(), events=SearchEvents())
        assert [type(t).__name__ for t in tools] == ["GoogleSearch"]

    @pytest.mark.asyncio
    async def test_grounding_never_claims_to_know_the_sources(self) -> None:
        # The Google plugin does not expose Gemini's grounding metadata, so a
        # grounding session has no source list to publish — and inventing one
        # would be worse than showing none. This asserts the shape of that
        # decision: grounding yields a provider tool, never a function tool that
        # could report results back.
        published: list[object] = []

        async def on_results(results: object) -> None:
            published.append(results)

        tools = build_tools(gemini_settings(), events=SearchEvents(on_results=on_results))

        assert [type(t).__name__ for t in tools] == ["GoogleSearch"]
        assert published == [], "no hook is called for a grounding session"

    def test_openai_gets_the_function_tool(self) -> None:
        tools = build_tools(
            openai_settings(web_search=True, brave_search_api_key=BRAVE_KEY),
            events=SearchEvents(),
        )
        assert [type(t).__name__ for t in tools] == ["FunctionTool"]

    def test_openai_without_a_search_key_gets_nothing(self) -> None:
        assert build_tools(openai_settings(web_search=True), events=SearchEvents()) == []

    def test_search_disabled_returns_no_tools_at_all(self) -> None:
        # Even with a key sitting in the environment: the switch wins.
        for settings in (
            gemini_settings(web_search=False),
            openai_settings(web_search=False, tavily_api_key=TAVILY_KEY),
        ):
            assert build_tools(settings, events=SearchEvents()) == []

    @pytest.mark.asyncio
    async def test_search_disabled_makes_no_request(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        called = False

        async def fake_run_search(*args: object, **kwargs: object):
            nonlocal called
            called = True
            return []

        monkeypatch.setattr("aladin.tools.run_search", fake_run_search)
        tools = build_tools(
            openai_settings(web_search=False, tavily_api_key=TAVILY_KEY),
            events=SearchEvents(),
        )

        assert tools == []
        assert called is False

    def test_brave_is_used_when_tavily_is_absent(self) -> None:
        tools = build_tools(
            openai_settings(web_search=True, brave_search_api_key=BRAVE_KEY),
            events=SearchEvents(),
        )
        assert tools, "a Brave key alone must be enough"


# ---------------------------------------------------------------------------
# The agent side: what a real session publishes
# ---------------------------------------------------------------------------


class _FakeContext:
    """The only part of a JobContext `_search_events` touches."""

    def __init__(self, *, fail: bool = False) -> None:
        self.published: list[tuple[str, dict]] = []
        self._fail = fail
        outer = self

        class _Participant:
            async def publish_data(self, data: bytes, **kwargs: object) -> None:
                if outer._fail:
                    raise RuntimeError("transport gone")
                outer.published.append((str(kwargs.get("topic")), json.loads(data)))

        self.room = type("Room", (), {"local_participant": _Participant()})()


class TestAgentSearchWiring:
    """
    The agent half of "reliable search invocation": a search that runs must be
    visible while it runs, and its sources must reach the app.
    """

    def test_events_are_off_when_ui_updates_are_off(self) -> None:
        # One switch for the whole UI channel: with captions off, a search does
        # no extra publishing at all.
        assert _search_events(_FakeContext(), gemini_settings(captions=False)) is None

    def test_events_are_on_by_default(self) -> None:
        assert _search_events(_FakeContext(), gemini_settings()) is not None

    @pytest.mark.asyncio
    async def test_starting_a_search_publishes_the_searching_state(self) -> None:
        ctx = _FakeContext()
        events = _search_events(ctx, gemini_settings())
        assert events is not None

        await events.on_search_started()  # type: ignore[misc]

        assert ctx.published == [(STATE_TOPIC, {"state": "searching"})]

    @pytest.mark.asyncio
    async def test_results_are_published_on_the_sources_topic(self) -> None:
        ctx = _FakeContext()
        events = _search_events(ctx, gemini_settings())
        assert events is not None

        await events.on_results(  # type: ignore[misc]
            [SearchResult(title="T", url="https://e.com", snippet="s")]
        )

        topic, payload = ctx.published[0]
        assert topic == SOURCES_TOPIC
        assert payload["type"] == "sources"
        assert payload["sources"][0]["url"] == "https://e.com"

    @pytest.mark.asyncio
    async def test_a_publishing_failure_does_not_raise(self) -> None:
        # If the room is gone, the answer is still owed to the user.
        events = _search_events(_FakeContext(fail=True), gemini_settings())
        assert events is not None

        await events.on_search_started()  # type: ignore[misc]
        await events.on_results([])  # type: ignore[misc]

    @pytest.mark.asyncio
    async def test_the_search_tool_and_the_app_agree_on_the_payload(self) -> None:
        # The app parses {type, sources:[{title,url,snippet}]}; this is the
        # end-to-end shape as it actually leaves the agent.
        ctx = _FakeContext()
        events = _search_events(ctx, gemini_settings())
        assert events is not None
        results = [SearchResult(title="T", url="https://e.com", snippet="s")]

        await events.on_results(results)  # type: ignore[misc]

        assert ctx.published[0][1] == sources_payload(results)
