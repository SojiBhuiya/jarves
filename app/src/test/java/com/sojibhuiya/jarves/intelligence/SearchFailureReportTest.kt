package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.4: when a search fails, the real HTTP status and Google's own error text have to reach the
 * chat, and the API key must never reach it. These tests pin both halves of that promise.
 */
class SearchFailureReportTest {

    private fun client(transport: RestTransport, models: List<String>) =
        GroundedSearchClient(
            apiKey = "AIzaUNIT-TEST-PLACEHOLDER-000000000000",
            transport = transport,
            models = models
        )

    private fun errorBody(code: Int, status: String, message: String) = """
        {"error": {"code": $code, "message": "$message", "status": "$status"}}
    """.trimIndent()

    @Test
    fun `google's error message is carried into the answer`() {
        val body = errorBody(429, "RESOURCE_EXHAUSTED", "Quota exceeded for quota metric 'GenerateRequestsPerDayPerProjectPerModel-FreeTier'")
        val answer = GroundedSearchClient.parseResponse("q", 429, body, "gemini-2.5-flash")

        assertEquals(429, answer.statusCode)
        assertEquals(GroundedSearchFailure.QUOTA, answer.failure)
        assertEquals("gemini-2.5-flash", answer.model)
        assertTrue(answer.serverMessage.contains("Quota exceeded"))
    }

    @Test
    fun `the report shows type, real status, model and google's text`() {
        val body = errorBody(429, "RESOURCE_EXHAUSTED", "Quota exceeded")
        val report = GroundedSearchClient.parseResponse("q", 429, body, "gemini-2.5-flash").failureReport()

        assertTrue(report.contains("type=QUOTA"))
        assertTrue(report.contains("HTTP=429"))
        assertTrue(report.contains("model=gemini-2.5-flash"))
        assertTrue(report.contains("Google: Quota exceeded"))
    }

    @Test
    fun `a non-json body still reports the status honestly`() {
        val answer = GroundedSearchClient.parseResponse("q", 502, "<html>Bad Gateway</html>", "gemini-2.5-flash")

        assertEquals(502, answer.statusCode)
        assertEquals(GroundedSearchFailure.HTTP, answer.failure)
        assertEquals("", answer.serverMessage)
        assertTrue(answer.failureReport().contains("HTTP=502"))
    }

    @Test
    fun `a failure failure never shows a key`() {
        // Google echoes the request back in some error payloads; the key must not survive.
        val body = errorBody(400, "INVALID_ARGUMENT", "API key not valid: AIzaUNIT-TEST-PLACEHOLDER-000000000000")
        val answer = GroundedSearchClient.parseResponse("q", 400, body, "gemini-2.5-flash")
        val report = answer.failureReport()

        assertFalse(report.contains("AIzaUNIT-TEST"))
        assertTrue(report.contains("AIza****"))
        assertEquals(GroundedSearchFailure.AUTH, answer.failure)
    }

    @Test
    fun `a key-shaped string inside any server text is scrubbed`() {
        val scrubbed = GroundedSearchClient.scrubSecret("sent AIzaSyD-1234567890abcdefg to the server")

        assertFalse(scrubbed.contains("AIzaSyD-1234567890abcdefg"))
        assertTrue(scrubbed.contains("AIza****"))
    }

    @Test
    fun `server message is clipped so a bubble stays readable`() {
        val long = "x".repeat(900)
        val clipped = GroundedSearchClient.serverMessage(errorBody(500, "INTERNAL", long))

        assertTrue(clipped.length <= 301)
        assertTrue(clipped.endsWith("…"))
    }

    @Test
    fun `the documented fallback model is the free-tier one`() {
        // Pricing table: only gemini-2.5-flash and gemini-2.5-flash-lite have free-tier grounding.
        assertEquals("gemini-2.5-flash", GroundedSearchClient.MODEL)
        assertEquals("gemini-2.5-flash-lite", GroundedSearchClient.FALLBACK_MODEL)
    }

    @Test
    fun `an unsupported model is retried on the fallback and the fallback answer wins`() {
        val seen = mutableListOf<String>()
        val transport = RestTransport { request ->
            val model = request.url.substringAfter("/models/").substringBefore(":generateContent")
            seen += model
            if (model == GroundedSearchClient.MODEL) {
                RestResponse(404, errorBody(404, "NOT_FOUND", "models/gemini-2.5-flash is not found for API version v1beta"))
            } else {
                RestResponse(200, """{"candidates":[{"content":{"parts":[{"text":"Dhaka is warm."}]}}]}""")
            }
        }
        val answer = kotlinx.coroutines.runBlocking {
            client(transport, listOf(GroundedSearchClient.MODEL, GroundedSearchClient.FALLBACK_MODEL)).search("weather")
        }

        assertEquals(listOf(GroundedSearchClient.MODEL, GroundedSearchClient.FALLBACK_MODEL), seen)
        assertTrue(answer.isSuccess)
        assertEquals(GroundedSearchClient.FALLBACK_MODEL, answer.model)
    }

    @Test
    fun `an auth failure is not retried on the fallback`() {
        val seen = mutableListOf<String>()
        val transport = RestTransport { request ->
            seen += request.url
            RestResponse(401, errorBody(401, "UNAUTHENTICATED", "API key not valid. Please pass a valid API key."))
        }
        val answer = kotlinx.coroutines.runBlocking {
            client(transport, listOf(GroundedSearchClient.MODEL, GroundedSearchClient.FALLBACK_MODEL)).search("weather")
        }

        assertEquals(1, seen.size)
        assertEquals(GroundedSearchFailure.AUTH, answer.failure)
        assertTrue(answer.failureReport().contains("HTTP=401"))
    }

    @Test
    fun `a quota failure is not retried either`() {
        val seen = mutableListOf<String>()
        val transport = RestTransport { request ->
            seen += request.url
            RestResponse(429, errorBody(429, "RESOURCE_EXHAUSTED", "Quota exceeded"))
        }
        val answer = kotlinx.coroutines.runBlocking {
            client(transport, listOf(GroundedSearchClient.MODEL, GroundedSearchClient.FALLBACK_MODEL)).search("weather")
        }

        assertEquals(1, seen.size)
        assertEquals(GroundedSearchFailure.QUOTA, answer.failure)
        assertEquals(429, answer.statusCode)
    }

    @Test
    fun `a blocked candidate explains itself through finishReason`() {
        val body = """{"candidates":[{"finishReason":"SAFETY","content":{"parts":[]}}]}"""
        val answer = GroundedSearchClient.parseResponse("q", 200, body, "gemini-2.5-flash")

        assertEquals(GroundedSearchFailure.EMPTY, answer.failure)
        assertEquals("finishReason=SAFETY", answer.detail)
    }

    @Test
    fun `the request still declares google_search once`() {
        val request = client(RestTransport { RestResponse(200, "{}") }, listOf(GroundedSearchClient.MODEL))
            .buildRequest("dhaka weather", GroundedSearchClient.MODEL)

        assertTrue(request.url.endsWith("/models/gemini-2.5-flash:generateContent"))
        assertTrue(request.body.contains("\"google_search\""))
        assertEquals(request.body.split("\"google_search\"").size, 2)
        // The key travels only in the header, never in the URL or the body.
        assertFalse(request.url.contains("AIza"))
        assertFalse(request.body.contains("AIza"))
        assertTrue(request.headers.getValue("x-goog-api-key").startsWith("AIza"))
    }
}
