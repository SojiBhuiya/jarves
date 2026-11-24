package com.sojibhuiya.jarves.youtube

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 0.2.24: the Android Innertube client — the fallback when YouTube bot-checks the
 * visionOS client. Parsing is pure (no network); one MockWebServer test proves the
 * visitor_id → player wiring and that the requests carry the Android identity.
 */
class AndroidPlayerClientTest {

    private val playerJson = """
        {
          "playabilityStatus": {"status": "OK"},
          "streamingData": {
            "formats": [
              {"itag": 18, "url": "https://example.test/v18.mp4",
               "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
               "qualityLabel": "360p", "bitrate": 500000}
            ],
            "adaptiveFormats": [
              {"itag": 137, "mimeType": "video/mp4; codecs=\"avc1.640028\"",
               "qualityLabel": "1080p", "bitrate": 4000000,
               "signatureCipher": "s=abc&url=https://example.test/v137.mp4"},
              {"itag": 140, "url": "https://example.test/a140.m4a",
               "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000}
            ]
          },
          "videoDetails": {"title": "Test Song", "lengthSeconds": "346"}
        }
    """.trimIndent()

    @Test
    fun `player response parses direct urls and skips cipher entries`() {
        val streams = AndroidPlayerClient.parsePlayerResponse("abc123", playerJson)
        assertNull(streams.failure)
        assertEquals("https://example.test/v18.mp4", streams.videoUrl)
        assertEquals("https://example.test/a140.m4a", streams.audioUrl)
        assertEquals("Test Song", streams.title)
        assertEquals(346L, streams.durationSeconds)
        assertEquals("android", streams.source)
        assertEquals(1, streams.videoOptions.size)
    }

    @Test
    fun `player response with bot-check playability returns the bot failure`() {
        val json = """
            {"playabilityStatus": {
              "status": "login_required",
              "reason": "Sign in to confirm you're not a bot"
            }}
        """.trimIndent()
        val streams = AndroidPlayerClient.parsePlayerResponse("abc123", json)
        assertEquals(YouTubeFailure.INTERNAL, streams.failure)
        assertEquals("SignInConfirmNotBotException", streams.detail)
        assertEquals("android", streams.source)
    }

    @Test
    fun `garbage player response fails internal`() {
        val streams = AndroidPlayerClient.parsePlayerResponse("abc123", "not json")
        assertEquals(YouTubeFailure.INTERNAL, streams.failure)
        assertEquals("bad player json", streams.detail)
    }

    @Test
    fun `visitorData parses`() {
        assertEquals(
            "CgtfSmVjVmlnUEVTZg",
            AndroidPlayerClient.parseVisitorData(
                """{"responseContext": {"visitorData": "CgtfSmVjVmlnUEVTZg"}}"""
            )
        )
        assertNull(AndroidPlayerClient.parseVisitorData("not json"))
        assertNull(AndroidPlayerClient.parseVisitorData("{}"))
    }

    @Test
    fun `android client constants match the verified values`() {
        assertEquals("21.26.364", AndroidPlayerClient.ANDROID_CLIENT_VERSION)
        assertEquals(
            "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
            AndroidPlayerClient.ANDROID_USER_AGENT
        )
    }
}

class AndroidPlayerClientFetchTest {

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

    private val playerJson = """
        {
          "playabilityStatus": {"status": "OK"},
          "streamingData": {
            "formats": [
              {"itag": 18, "url": "https://example.test/v18.mp4",
               "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
               "qualityLabel": "360p", "bitrate": 500000}
            ],
            "adaptiveFormats": []
          },
          "videoDetails": {"title": "Test Song", "lengthSeconds": "346"}
        }
    """.trimIndent()

    @Test
    fun `fetch posts visitor_id then player and returns streams`() {
        server.enqueue(
            MockResponse().setBody("""{"responseContext": {"visitorData": "CgtfSmVjVmlnUEVTZg"}}""")
        )
        server.enqueue(MockResponse().setBody(playerJson))
        val client = AndroidPlayerClient(
            OkHttpNewPipeDownloader(),
            visitorIdUrl = server.url("/visitor_id").toString(),
            playerUrlBase = server.url("/player").toString()
        )
        val streams = client.fetch("abc123")
        assertNull(streams.failure)
        assertEquals("https://example.test/v18.mp4", streams.videoUrl)
        assertEquals("android", streams.source)

        assertEquals(2, server.requestCount)
        val visitorRequest = server.takeRequest()
        assertEquals("/visitor_id", visitorRequest.path)
        assertEquals("POST", visitorRequest.method)
        val playerRequest = server.takeRequest()
        assertTrue(playerRequest.path!!.startsWith("/player?prettyPrint=false&t="))
        assertTrue(playerRequest.path!!.contains("id=abc123"))
        assertEquals("application/json", playerRequest.getHeader("Content-Type"))
        assertEquals(
            AndroidPlayerClient.ANDROID_USER_AGENT,
            playerRequest.getHeader("User-Agent")
        )
        assertEquals("3", playerRequest.getHeader("X-YouTube-Client-Name"))
    }
}
