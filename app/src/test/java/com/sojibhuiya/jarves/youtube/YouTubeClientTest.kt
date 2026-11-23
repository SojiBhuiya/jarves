package com.sojibhuiya.jarves.youtube

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YouTubeSearchParserTest {

    private val searchJson = """
        {
          "items": [
            {"id": {"videoId": "abc123"}, "snippet": {
              "title": "Old Song",
              "channelTitle": "Music Channel",
              "publishedAt": "1991-01-01T00:00:00Z",
              "thumbnails": {"medium": {"url": "https://i.ytimg.com/vi/abc123/mqdefault.jpg"}}
            }},
            {"id": {"videoId": "def456"}, "snippet": {
              "title": "New Song",
              "channelTitle": "Covers",
              "publishedAt": "2022-05-01T00:00:00Z",
              "thumbnails": {"default": {"url": "https://i.ytimg.com/vi/def456/default.jpg"}}
            }},
            {"id": {"kind": "youtube#channel"}, "snippet": {"title": "Not a video"}}
          ]
        }
    """.trimIndent()

    private val durationsJson = """
        {"items": [
          {"id": "abc123", "contentDetails": {"duration": "PT4M12S"}},
          {"id": "def456", "contentDetails": {"duration": "PT3M5S"}}
        ]}
    """.trimIndent()

    @Test
    fun `search response parses videos and skips non-video items`() {
        val videos = YouTubeSearchClient.parseSearchResponse(searchJson)
        assertEquals(2, videos.size)
        assertEquals("abc123", videos[0].videoId)
        assertEquals("Old Song", videos[0].title)
        assertEquals("Music Channel", videos[0].channelTitle)
        assertEquals("1991-01-01T00:00:00Z", videos[0].publishedAt)
        assertEquals("https://i.ytimg.com/vi/abc123/mqdefault.jpg", videos[0].thumbnailUrl)
        assertEquals("def456", videos[1].videoId)
        assertEquals("https://i.ytimg.com/vi/def456/default.jpg", videos[1].thumbnailUrl)
    }

    @Test
    fun `garbage search response parses to empty`() {
        assertTrue(YouTubeSearchClient.parseSearchResponse("not json").isEmpty())
        assertTrue(YouTubeSearchClient.parseSearchResponse("{}").isEmpty())
        assertTrue(YouTubeSearchClient.parseSearchResponse("").isEmpty())
    }

    @Test
    fun `durations parse from videos list`() {
        val durations = YouTubeSearchClient.parseDurations(durationsJson)
        assertEquals(252L, durations["abc123"])
        assertEquals(185L, durations["def456"])
    }

    @Test
    fun `iso8601 durations`() {
        assertEquals(252L, YouTubeSearchClient.parseIso8601Duration("PT4M12S"))
        assertEquals(3723L, YouTubeSearchClient.parseIso8601Duration("PT1H2M3S"))
        assertEquals(45L, YouTubeSearchClient.parseIso8601Duration("PT45S"))
        assertEquals(93_600L, YouTubeSearchClient.parseIso8601Duration("P1DT2H"))
        assertEquals(0L, YouTubeSearchClient.parseIso8601Duration("PT0S"))
        assertNull(YouTubeSearchClient.parseIso8601Duration("garbage"))
        assertNull(YouTubeSearchClient.parseIso8601Duration(""))
        assertNull(YouTubeSearchClient.parseIso8601Duration("PT"))
    }

    @Test
    fun `failure mapping separates auth quota and http`() {
        assertEquals(
            YouTubeFailure.AUTH,
            YouTubeSearchClient.failureFor(401, null)
        )
        assertEquals(
            YouTubeFailure.QUOTA,
            YouTubeSearchClient.failureFor(403, """{"error":{"errors":[{"reason":"quotaExceeded"}]}}""")
        )
        assertEquals(
            YouTubeFailure.AUTH,
            YouTubeSearchClient.failureFor(403, """{"error":{"errors":[{"reason":"keyInvalid"}]}}""")
        )
        assertEquals(YouTubeFailure.QUOTA, YouTubeSearchClient.failureFor(429, null))
        assertEquals(YouTubeFailure.HTTP, YouTubeSearchClient.failureFor(500, null))
    }
}

class YouTubeSearchClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
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

    @Test
    fun `search returns videos with durations`() = runBlocking {
        server.enqueue(MockResponse().setBody(searchJson))
        server.enqueue(MockResponse().setBody(durationsJson))
        val client = YouTubeSearchClient(baseUrl = server.url("/").toString().dropLast(1))
        val result = client.search("old song", apiKey = "test-key")
        assertNull(result.failure)
        assertEquals(2, result.videos.size)
        assertEquals(252L, result.videos[0].durationSeconds)
        assertEquals(185L, result.videos[1].durationSeconds)
        val firstRequest = server.takeRequest()
        assertTrue(firstRequest.path!!.contains("/search"))
        assertTrue(firstRequest.path!!.contains("q=old%20song"))
    }

    @Test
    fun `search without a key reports NO_KEY without a request`() = runBlocking {
        val client = YouTubeSearchClient(baseUrl = server.url("/").toString().dropLast(1))
        val result = client.search("old song", apiKey = "")
        assertEquals(YouTubeFailure.NO_KEY, result.failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `quota error maps to QUOTA`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"errors":[{"reason":"quotaExceeded"}]}}""")
        )
        val client = YouTubeSearchClient(baseUrl = server.url("/").toString().dropLast(1))
        val result = client.search("old song", apiKey = "test-key")
        assertEquals(YouTubeFailure.QUOTA, result.failure)
        assertEquals(403, result.statusCode)
    }

    @Test
    fun `empty search reports EMPTY`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items": []}"""))
        val client = YouTubeSearchClient(baseUrl = server.url("/").toString().dropLast(1))
        val result = client.search("zzz-no-such-song", apiKey = "test-key")
        assertEquals(YouTubeFailure.EMPTY, result.failure)
        assertTrue(result.videos.isEmpty())
    }
}

class YouTubeStreamPickerTest {

    @Test
    fun `best muxed video at most 1080p wins`() {
        val videos = listOf(
            StreamCandidate(url = "v720", height = 720, bitrate = 1_000, hasAudio = true),
            StreamCandidate(url = "v1080", height = 1080, bitrate = 2_000, hasAudio = true),
            StreamCandidate(url = "v2160-only", height = 2160, bitrate = 5_000, hasAudio = false)
        )
        val audios = listOf(
            StreamCandidate(url = "a-low", bitrate = 128, hasAudio = true),
            StreamCandidate(url = "a-high", bitrate = 256, hasAudio = true)
        )
        val (video, audio) = pickPlaybackStreams(videos, audios)
        assertEquals("v1080", video)
        assertEquals("a-high", audio)
    }

    @Test
    fun `no muxed video falls back to video-only plus audio`() {
        val videos = listOf(
            StreamCandidate(url = "v2160-only", height = 2160, bitrate = 5_000, hasAudio = false)
        )
        val audios = listOf(StreamCandidate(url = "a-only", bitrate = 128, hasAudio = true))
        val (video, audio) = pickPlaybackStreams(videos, audios)
        assertEquals("v2160-only", video)
        assertEquals("a-only", audio)
    }

    @Test
    fun `empty lists pick nothing`() {
        val (video, audio) = pickPlaybackStreams(emptyList(), emptyList())
        assertNull(video)
        assertNull(audio)
    }

    @Test
    fun `resolution heights parse`() {
        assertEquals(720, parseHeight("720p"))
        assertEquals(720, parseHeight("720p60"))
        assertEquals(1080, parseHeight("1080p"))
        assertEquals(0, parseHeight(""))
        assertEquals(0, parseHeight(null))
        assertEquals(0, parseHeight("abc"))
    }
}

class OkHttpNewPipeDownloaderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `get passes code body headers and url through`() {
        server.enqueue(
            MockResponse().setBody("hello").addHeader("X-Test", "1")
        )
        val downloader = OkHttpNewPipeDownloader()
        val url = server.url("/watch?v=abc123").toString()
        val request = org.schabi.newpipe.extractor.downloader.Request(
            "GET", url, emptyMap(), null, null
        )
        val response = downloader.execute(request)
        assertEquals(200, response.responseCode)
        assertEquals("hello", response.responseBody)
        assertEquals(listOf("1"), response.getHeader("X-Test"))
        assertEquals(url, response.latestUrl)
    }
}
