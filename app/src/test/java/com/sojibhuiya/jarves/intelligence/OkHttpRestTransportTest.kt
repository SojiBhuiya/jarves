package com.sojibhuiya.jarves.intelligence

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the REAL [OkHttpRestTransport] — never the [RestTransport] fake.
 *
 * This file exists because of the 0.2.2 field failure: on the phone every search ended with
 * "I have no internet connection". All unit tests passed because they replaced the transport with
 * a fake, so the only code that actually talks to Google was never executed.
 *
 * The real bug: the body was read with `readByteArray(minOf(maxBodyBytes, contentLength()))` and,
 * when `contentLength()` is unknown (`-1`, which is what a chunked **or gzip** response reports),
 * the fallback demanded a full **1 MiB** from a body of a few hundred bytes. okio's
 * `readByteArray(count)` requires exactly `count` bytes, so it threw `EOFException`, and the catch
 * block labelled that "no network connection".
 *
 * MockWebServer reproduces the exact condition here, so the fix is verified rather than asserted.
 */
class OkHttpRestTransportTest {

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

    private fun request(body: String = "{}") = RestRequest(
        url = server.url("/v1beta/models/gemini-2.5-flash:generateContent").toString(),
        headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
        body = body
    )

    private fun gzip(text: String): Buffer {
        val raw = Buffer().writeUtf8(text)
        val compressed = Buffer()
        GzipSink(compressed).use { it.write(raw, raw.size) }
        return compressed
    }

    // ---- the regression: a body of unknown length ---------------------------------------

    @Test
    fun readsAChunkedResponseBodyWithoutThrowing() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"Dhaka is 32C"}]}}]}"""
        // A chunked body has no Content-Length, i.e. contentLength() == -1: the exact condition
        // that made the old reader demand 1 MiB and throw EOFException.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setChunkedBody(json, 16)
        )

        val response = OkHttpRestTransport().post(request())

        assertEquals(200, response.code)
        assertEquals("the whole body must be returned, not a truncated or empty one", json, response.body)
    }

    @Test
    fun theOldReadPatternIsProvenToThrowOnTheSameFixture() {
        // Demonstrates why this test catches the bug: with an unknown length the old code fell back
        // to a 1 MiB read, and okio requires exactly that many bytes.
        val json = """{"candidates":[]}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setChunkedBody(json, 8)
        )

        var threw: Throwable? = null
        try {
            okhttp3.OkHttpClient().newCall(
                okhttp3.Request.Builder().url(server.url("/x").toString()).build()
            ).execute().use { r ->
                val declared = r.body!!.contentLength()          // -1 for a chunked body
                val count = if (declared >= 0) declared else (1L shl 20)   // the old fallback
                r.body!!.source().readByteArray(count)
            }
        } catch (t: Throwable) {
            threw = t
        }

        assertTrue("the old read pattern must fail here", threw != null)
        assertTrue(
            "and it fails with EOFException, which is why it looked like a network fault: $threw",
            threw is java.io.EOFException
        )
    }

    // ---- gzip, the way Google actually answers ------------------------------------------

    @Test
    fun readsAGzippedResponseBodyWithoutThrowing() {
        // Long enough that gzip genuinely shrinks it (tiny bodies grow: header overhead).
        val json = """{"candidates":[{"content":{"parts":[{"text":"${
            "Dhaka is hot and humid today with a chance of afternoon showers. ".repeat(40)
        }"}]}}]}"""
        val compressed = gzip(json)
        assertTrue("fixture must really be compressed", compressed.size < json.length)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip")
                .setHeader("Content-Length", compressed.size)
                .setBody(compressed)
        )

        val response = OkHttpRestTransport().post(request())
        assertEquals(200, response.code)
        assertEquals("OkHttp decompresses transparently; we must return the decoded JSON", json, response.body)
    }

    // ---- the ordinary cases --------------------------------------------------------------

    @Test
    fun readsAPlainUncompressedBody() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}""")
        )
        assertEquals("""{"ok":true}""", OkHttpRestTransport().post(request()).body)
    }

    @Test
    fun passesThroughAnErrorStatusAndItsBody() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":400,"message":"API key not valid."}}""")
        )
        val response = OkHttpRestTransport().post(request())
        assertEquals(400, response.code)
        assertTrue(response.body.contains("API key not valid"))
    }

    @Test
    fun sendsExactlyTheHeadersAndBodyItWasGiven() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        OkHttpRestTransport().post(
            RestRequest(
                url = server.url("/m").toString(),
                headers = mapOf("x-goog-api-key" to "UNIT-TEST-key", "Content-Type" to "application/json"),
                body = """{"a":1}"""
            )
        )
        val recorded = server.takeRequest()
        assertEquals("UNIT-TEST-key", recorded.getHeader("x-goog-api-key"))
        assertEquals("POST", recorded.method)
        assertEquals("""{"a":1}""", recorded.body.readUtf8())
    }

    @Test
    fun anEmptyBodyIsReturnedAsEmptyRatherThanThrowing() {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("Content-Length", 0))
        val response = OkHttpRestTransport().post(request())
        assertEquals(204, response.code)
        assertEquals("", response.body)
    }
}
