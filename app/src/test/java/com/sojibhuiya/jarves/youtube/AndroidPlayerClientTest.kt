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
          "videoDetails": {"videoId": "abc123", "title": "Test Song", "lengthSeconds": "346"}
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
    fun `player response for a different video fails instead of playing the wrong video`() {
        // 0.2.26: YouTube sometimes substitutes another video's response (NewPipe
        // documents this for third-party clients) — the phone's signed-in 0.2.25
        // test hit it on visionOS. The Android client must reject it too.
        val json = """
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
              "videoDetails": {"videoId": "other999", "title": "Not available", "lengthSeconds": "30"}
            }
        """.trimIndent()
        val streams = AndroidPlayerClient.parsePlayerResponse("abc123", json)
        assertEquals(YouTubeFailure.INTERNAL, streams.failure)
        assertEquals("response videoId mismatch", streams.detail)
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
    fun `gapis urls are the endpoints`() {
        // 0.2.28: the Android client moved from www.youtube.com to GAPIS —
        // GAPIS answers the anonymous Android player request with HTTP 200,
        // while www answers the phone's signed-in request with 400 and its
        // anonymous retry with LOGIN_REQUIRED (0.2.27 phone test).
        assertEquals(
            "https://youtubei.googleapis.com/youtubei/v1/player",
            AndroidPlayerClient.PLAYER_URL_BASE
        )
        assertEquals(
            "https://youtubei.googleapis.com/youtubei/v1/visitor_id?prettyPrint=false",
            AndroidPlayerClient.VISITOR_ID_URL
        )
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
          "videoDetails": {"videoId": "abc123", "title": "Test Song", "lengthSeconds": "346"}
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

    @Test
    fun `anonymous GAPIS attempt plays first without a session`() {
        // 0.2.28: the anonymous attempt is PRIMARY — it is tried first, with no
        // session at all, because GAPIS answers the anonymous Android player
        // request with HTTP 200 (verified live 2026-11-28) while the phone's
        // signed-in www request got HTTP 400 (0.2.27 test). The signed-in
        // attempt must not run at all when the anonymous one plays.
        server.enqueue(
            MockResponse().setBody("""{"responseContext": {"visitorData": "CgtfSmVjVmlnUEVTZg"}}""")
        )
        server.enqueue(MockResponse().setBody(playerJson))
        val signedIn = OkHttpNewPipeDownloader(
            authCookiesProvider = { "SID=dummySid; SAPISID=AbC123xYz" }
        )
        val client = AndroidPlayerClient(
            signedIn,
            visitorIdUrl = server.url("/visitor_id").toString(),
            playerUrlBase = server.url("/player").toString(),
            anonymousDownloader = OkHttpNewPipeDownloader()
        )
        val streams = client.fetch("abc123")
        assertNull(streams.failure)
        assertEquals("https://example.test/v18.mp4", streams.videoUrl)
        assertEquals("android", streams.source)

        assertEquals(2, server.requestCount)
        server.takeRequest() // visitor_id, anonymous
        val playerRequest = server.takeRequest()
        assertNull(playerRequest.getHeader("Cookie"))
        assertNull(playerRequest.getHeader("Authorization"))
    }

    @Test
    fun `anonymous fails but signed-in succeeds plays`() {
        // 0.2.28: the signed-in attempt is the fallback — it runs only when the
        // anonymous GAPIS attempt fails, and it can still play.
        server.enqueue(
            MockResponse().setBody("""{"responseContext": {"visitorData": "CgtfSmVjVmlnUEVTZg"}}""")
        )
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(
            MockResponse().setBody("""{"responseContext": {"visitorData": "CgtfSmVjVmlnUEVTZg"}}""")
        )
        server.enqueue(MockResponse().setBody(playerJson))
        val signedIn = OkHttpNewPipeDownloader(
            authCookiesProvider = { "SID=dummySid; SAPISID=AbC123xYz" }
        )
        val client = AndroidPlayerClient(
            signedIn,
            visitorIdUrl = server.url("/visitor_id").toString(),
            playerUrlBase = server.url("/player").toString(),
            anonymousDownloader = OkHttpNewPipeDownloader()
        )
        val streams = client.fetch("abc123")
        assertNull(streams.failure)
        assertEquals("https://example.test/v18.mp4", streams.videoUrl)
        assertEquals("android", streams.source)

        assertEquals(4, server.requestCount)
        server.takeRequest() // visitor_id, anonymous
        server.takeRequest() // player, anonymous, 400
        server.takeRequest() // visitor_id, signed in
        val fallbackPlayer = server.takeRequest()
        assertTrue(fallbackPlayer.getHeader("Cookie")!!.contains("SAPISID=AbC123xYz"))
    }

    @Test
    fun `visitor_id failure still tries the player`() {
        // 0.2.28 (Arena's minor gap): a failed visitor_id must not block the
        // player attempt — the GAPIS player answers 200 without visitorData
        // (verified live 2026-11-28 for dQw4w9WgXcQ).
        server.enqueue(MockResponse().setResponseCode(500))
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
    }

    @Test
    fun `both attempts fail returns combined detail`() {
        // 0.2.28: when the anonymous GAPIS attempt and the signed-in fallback
        // both fail, the detail must carry both results, so the next diagnosis
        // is not blind. (0.2.27's "400; anonymous HTTP (400)" shape, with the
        // anonymous attempt now first.)
        server.enqueue(
            MockResponse().setBody("""{"responseContext": {"visitorData": "CgtfSmVjVmlnUEVTZg"}}""")
        )
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(
            MockResponse().setBody("""{"responseContext": {"visitorData": "CgtfSmVjVmlnUEVTZg"}}""")
        )
        server.enqueue(MockResponse().setResponseCode(400))
        val signedIn = OkHttpNewPipeDownloader(
            authCookiesProvider = { "SID=dummySid; SAPISID=AbC123xYz" }
        )
        val client = AndroidPlayerClient(
            signedIn,
            visitorIdUrl = server.url("/visitor_id").toString(),
            playerUrlBase = server.url("/player").toString(),
            anonymousDownloader = OkHttpNewPipeDownloader()
        )
        val streams = client.fetch("abc123")
        assertEquals(YouTubeFailure.HTTP, streams.failure)
        assertEquals("anonymous HTTP (400); signed-in HTTP (400)", streams.detail)
        assertEquals("android", streams.source)
        assertEquals(4, server.requestCount)
    }
}
