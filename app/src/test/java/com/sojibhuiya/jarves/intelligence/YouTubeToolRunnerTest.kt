package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.youtube.YouTubeFailure
import com.sojibhuiya.jarves.youtube.YouTubeSearchClient
import com.sojibhuiya.jarves.youtube.YouTubeStreamResolver
import com.sojibhuiya.jarves.youtube.YouTubeStreams
import com.sojibhuiya.jarves.youtube.YouTubeVideo
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The video runner: search formatting, play wiring, control validation, and the same
 * duplicate/capacity guarantees as [SearchToolRunner]. Nothing here touches a player.
 */
class YouTubeToolRunnerTest {

    private lateinit var server: MockWebServer
    private var runner: YouTubeToolRunner? = null

    private val results = LinkedBlockingQueue<Triple<String, String, String>>()
    private val played = LinkedBlockingQueue<Pair<YouTubeVideo, YouTubeStreams>>()
    private val controlled = LinkedBlockingQueue<String>()
    private val searched = LinkedBlockingQueue<List<YouTubeVideo>>()

    private class FakeResolver(var streams: YouTubeStreams? = null) : YouTubeStreamResolver {
        val resolvedIds = mutableListOf<String>()
        override suspend fun resolve(videoId: String): YouTubeStreams {
            resolvedIds += videoId
            return streams ?: YouTubeStreams(videoId = videoId, failure = YouTubeFailure.EMPTY)
        }
    }

    private fun runner(
        key: String? = "test-key",
        resolver: YouTubeStreamResolver = FakeResolver(),
        client: YouTubeSearchClient? = null
    ): YouTubeToolRunner {
        val searchClient = client ?: YouTubeSearchClient(
            baseUrl = server.url("/").toString().dropLast(1)
        )
        return YouTubeToolRunner(
            searchClient = searchClient,
            keyProvider = { key },
            resolver = resolver,
            onResult = { callId, functionName, result, _ ->
                results.add(Triple(callId, functionName, result))
            },
            onPlayVideo = { video, streams -> played.add(video to streams) },
            onControl = { action -> controlled.add(action) },
            onSearchResults = { videos -> searched.add(videos) }
        ).also { runner = it }
    }

    private val searchJson = """
        {"items": [
          {"id": {"videoId": "abc123"}, "snippet": {
            "title": "Old Song", "channelTitle": "Music Channel",
            "publishedAt": "1991-01-01T00:00:00Z",
            "thumbnails": {"medium": {"url": "https://i.ytimg.com/vi/abc123/mqdefault.jpg"}}
          }},
          {"id": {"videoId": "def456"}, "snippet": {
            "title": "New Song", "channelTitle": "Covers",
            "publishedAt": "2022-05-01T00:00:00Z",
            "thumbnails": {"default": {"url": "https://i.ytimg.com/vi/def456/default.jpg"}}
          }}
        ]}
    """.trimIndent()

    private val durationsJson = """
        {"items": [
          {"id": "abc123", "contentDetails": {"duration": "PT4M12S"}},
          {"id": "def456", "contentDetails": {"duration": "PT3M5S"}}
        ]}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runner?.shutdown()
        server.shutdown()
    }

    private fun awaitResult(): Triple<String, String, String> =
        results.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("no tool result arrived within 5 s")

    @Test
    fun searchFormatsANumberedListWithIdsAndDurations() {
        server.enqueue(MockResponse().setBody(searchJson))
        server.enqueue(MockResponse().setBody(durationsJson))
        runner().handleSearch(YouTubeSearchCall("c1", "old song"))
        val (_, name, result) = awaitResult()
        assertEquals(LiveSessionConfig.FUNCTION_SEARCH_VIDEOS, name)
        assertTrue(result.contains("1. Old Song — Music Channel (4:12) [videoId: abc123]"))
        assertTrue(result.contains("2. New Song — Covers (3:05) [videoId: def456]"))
        assertTrue(result.contains("Do not play the first one by itself"))
    }

    @Test
    fun searchWithoutAKeySaysSoPlainly() {
        runner(key = null).handleSearch(YouTubeSearchCall("c1", "old song"))
        val (_, _, result) = awaitResult()
        assertTrue(result.contains("No YouTube API key is saved"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun playResolvesStreamsAndStartsPlayback() {
        val streams = YouTubeStreams(
            videoId = "abc123", title = "Old Song",
            videoUrl = "https://example.test/v.mp4", audioUrl = "https://example.test/a.m4a"
        )
        val resolver = FakeResolver(streams)
        runner(resolver = resolver).handlePlay(YouTubePlayCall("c1", "abc123", "Old Song"))
        val (_, name, result) = awaitResult()
        assertEquals(LiveSessionConfig.FUNCTION_PLAY_VIDEO, name)
        assertTrue(result.contains("Playing Old Song."))
        val (video, playedStreams) = played.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("onPlayVideo was not called")
        assertEquals("abc123", video.videoId)
        assertEquals("https://example.test/v.mp4", playedStreams.videoUrl)
        assertEquals(listOf("abc123"), resolver.resolvedIds)
    }

    @Test
    fun playWithNoStreamsReportsFailureAndDoesNotStartPlayback() {
        runner().handlePlay(YouTubePlayCall("c1", "gone123", "Gone Song"))
        val (_, _, result) = awaitResult()
        assertTrue(result.contains("no playable streams"))
        assertNull(played.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun playWithInternalFailureNamesTheCause() {
        // 0.2.22: the spoken failure must name the cause, or the next fix is a guess.
        val resolver = FakeResolver(
            YouTubeStreams(videoId = "bad123", failure = YouTubeFailure.INTERNAL, detail = "ExtractionException")
        )
        runner(resolver = resolver).handlePlay(YouTubePlayCall("c1", "bad123", "Bad Song"))
        val (_, _, result) = awaitResult()
        assertTrue(result.contains("ExtractionException"))
        assertNull(played.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun controlAcceptsKnownActionsAndRefusesUnknownOnes() {
        val r = runner()
        r.handleControl(YouTubeControlCall("c1", "pause"))
        assertEquals("pause", controlled.poll(5, TimeUnit.SECONDS))
        val (_, _, ok) = awaitResult()
        assertTrue(ok.contains("Playback: pause."))
        r.handleControl(YouTubeControlCall("c2", "dance"))
        val (_, _, refused) = awaitResult()
        assertTrue(refused.contains("Unknown playback action"))
        assertNull(controlled.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun duplicateCallIdIsAnsweredOnce() {
        server.enqueue(MockResponse().setBody(searchJson))
        server.enqueue(MockResponse().setBody(durationsJson))
        val r = runner()
        r.handleSearch(YouTubeSearchCall("c1", "old song"))
        r.handleSearch(YouTubeSearchCall("c1", "old song"))
        awaitResult()
        assertNull(results.poll(300, TimeUnit.MILLISECONDS))
        // One search = search.list + videos.list; the duplicate must not add more.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun searchHandsTheVideoListToTheUiForTappableCards() {
        server.enqueue(MockResponse().setBody(searchJson))
        server.enqueue(MockResponse().setBody(durationsJson))
        runner().handleSearch(YouTubeSearchCall("c1", "old song"))
        awaitResult()
        val videos = searched.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("onSearchResults was not called")
        assertEquals(listOf("abc123", "def456"), videos.map { it.videoId })
    }

    @Test
    fun durationsFormatAsMinutesAndSeconds() {
        assertEquals("4:12", YouTubeToolRunner.formatDuration(252))
        assertEquals("0:05", YouTubeToolRunner.formatDuration(5))
        assertEquals("62:03", YouTubeToolRunner.formatDuration(3723))
    }
}
