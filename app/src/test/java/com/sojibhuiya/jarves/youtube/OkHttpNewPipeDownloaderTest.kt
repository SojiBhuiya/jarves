package com.sojibhuiya.jarves.youtube

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request

/**
 * 0.2.29: the signed-in origin follows the request host. The fake OkHttp client
 * below never touches the network — its interceptor records the request the
 * downloader built and answers 200 — so the tests can use the real
 * youtubei.googleapis.com / www.youtube.com URLs and read the exact headers
 * that would go out on the phone.
 */
class OkHttpNewPipeDownloaderOriginTest {

    private val cookies = "SID=dummySid; SAPISID=AbC123xYz"

    private fun capturingClient(captured: MutableList<okhttp3.Request>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured.add(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }
            .build()

    private fun signedInDownloader(captured: MutableList<okhttp3.Request>) =
        OkHttpNewPipeDownloader(
            client = capturingClient(captured),
            authCookiesProvider = { cookies }
        )

    private fun hashTimestamp(authorization: String): Long =
        authorization.removePrefix("SAPISIDHASH ").substringBefore("_").toLong()

    private fun post(url: String) =
        Request.newBuilder()
            .post(url, "{}".toByteArray(Charsets.UTF_8))
            .build()

    @Test
    fun `signed-in GAPIS request carries GAPIS origin and hash`() {
        // 0.2.29: a signed-in request to youtubei.googleapis.com must carry
        // Origin/X-Origin = https://youtubei.googleapis.com, and the
        // SAPISIDHASH must be the one computed for that origin — the www
        // origin is what YouTube rejects with HTTP 400 ("Origin doesn't
        // match Host for XD3", proven by VM replay 2026-11-29).
        val captured = mutableListOf<okhttp3.Request>()
        val response = signedInDownloader(captured).execute(
            post("https://youtubei.googleapis.com/youtubei/v1/player")
        )
        assertEquals(200, response.responseCode())
        assertEquals(1, captured.size)
        val request = captured[0]
        assertEquals("https://youtubei.googleapis.com", request.header("Origin"))
        assertEquals("https://youtubei.googleapis.com", request.header("X-Origin"))
        assertTrue(request.header("Cookie")!!.contains("SAPISID=AbC123xYz"))
        val authorization = request.header("Authorization")!!
        assertEquals(
            YouTubeAuth.sapisidHash(
                sapisid = "AbC123xYz",
                origin = "https://youtubei.googleapis.com",
                timestampSeconds = hashTimestamp(authorization)
            ),
            authorization
        )
    }

    @Test
    fun `signed-in www request still carries www origin`() {
        // 0.2.29: the GAPIS origin applies only to the GAPIS host — a
        // signed-in request to www.youtube.com keeps the www origin and the
        // www-origin hash, exactly as in 0.2.25–0.2.28.
        val captured = mutableListOf<okhttp3.Request>()
        val response = signedInDownloader(captured).execute(
            post("https://www.youtube.com/youtubei/v1/player")
        )
        assertEquals(200, response.responseCode())
        assertEquals(1, captured.size)
        val request = captured[0]
        assertEquals("https://www.youtube.com", request.header("Origin"))
        assertEquals("https://www.youtube.com", request.header("X-Origin"))
        val authorization = request.header("Authorization")!!
        assertEquals(
            YouTubeAuth.sapisidHash(
                sapisid = "AbC123xYz",
                origin = "https://www.youtube.com",
                timestampSeconds = hashTimestamp(authorization)
            ),
            authorization
        )
    }

    @Test
    fun `request without a session carries no auth headers`() {
        // 0.2.29: the anonymous attempt is unchanged — no session, no Cookie,
        // no SAPISIDHASH, no Origin headers at all.
        val captured = mutableListOf<okhttp3.Request>()
        val downloader = OkHttpNewPipeDownloader(client = capturingClient(captured))
        val response = downloader.execute(
            post("https://youtubei.googleapis.com/youtubei/v1/player")
        )
        assertEquals(200, response.responseCode())
        assertEquals(1, captured.size)
        val request = captured[0]
        assertNull(request.header("Cookie"))
        assertNull(request.header("Authorization"))
        assertNull(request.header("Origin"))
        assertNull(request.header("X-Origin"))
    }

    @Test
    fun `originForUrl is GAPIS only for the GAPIS host`() {
        assertEquals(
            "https://youtubei.googleapis.com",
            OkHttpNewPipeDownloader.originForUrl(
                "https://youtubei.googleapis.com/youtubei/v1/player"
            )
        )
        assertEquals(
            "https://www.youtube.com",
            OkHttpNewPipeDownloader.originForUrl(
                "https://www.youtube.com/youtubei/v1/player"
            )
        )
        assertEquals(
            "https://www.youtube.com",
            OkHttpNewPipeDownloader.originForUrl(
                "https://music.youtube.com/youtubei/v1/player"
            )
        )
        assertEquals(
            "https://www.youtube.com",
            OkHttpNewPipeDownloader.originForUrl("https://example.test/x")
        )
        assertEquals(
            "https://www.youtube.com",
            OkHttpNewPipeDownloader.originForUrl("not a url")
        )
    }
}
