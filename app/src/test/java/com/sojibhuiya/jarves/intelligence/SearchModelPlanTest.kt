package com.sojibhuiya.jarves.intelligence

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.5: the model plan. Everything here is about *which* model is asked, in what order, what is
 * remembered between searches, and what the user is told when nothing works.
 *
 * The list itself is verified against the official docs (see the KDoc in [SearchModels]); these
 * tests pin the behaviour that the device log exposed as broken in 0.2.4.
 */
class SearchModelPlanTest {

    private val day = 24 * 60 * 60 * 1000L
    private val t0 = 1_700_000_000_000L

    // ---- the list ------------------------------------------------------------------------

    @Test
    fun theOrderIsExactlyTheVerifiedFreeTierList() {
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.6-flash"), SearchModels.ORDER)
        assertEquals("gemini-3.5-flash-lite", SearchModels.PREFERRED)
    }

    @Test
    fun noTwoPointFiveAndNoDeprecatedThreeFiveFlash() {
        // 2.5 is retired for new users; gemini-3.5-flash is deprecated and auto-routed to 3.6.
        SearchModels.ORDER.forEach { model ->
            assertFalse("2.5 must never be used: $model", model.startsWith("gemini-2.5"))
            assertFalse("3.5-flash auto-routes to 3.6-flash", model == "gemini-3.5-flash")
        }
    }

    @Test
    fun theTwentyRequestModelIsNeverFirst() {
        // gemini-3.6-flash is 20 RPD on the free tier: it must only ever be a fallback.
        assertEquals("gemini-3.6-flash", SearchModels.ORDER.last())
        assertTrue(SearchModels.isFreeTierModel("gemini-3.5-flash-lite"))
        assertFalse(SearchModels.isFreeTierModel("gemini-3.1-pro-preview"))
    }

    // ---- 404 / deprecated ---------------------------------------------------------------

    @Test
    fun a404IsModelUnavailableAndTriggersTheNextModel() {
        val seen = mutableListOf<String>()
        val health = SearchModelHealth(InMemoryModelStateStore(), { t0 })
        val answer = runBlocking {
            client(
                transport = { req ->
                    seen += req.modelName()
                    if (req.modelName() == "gemini-3.5-flash-lite") {
                        RestResponse(404, error(404, "NOT_FOUND", "models/gemini-3.5-flash-lite is no longer available to new users"))
                    } else {
                        RestResponse(200, groundedBody("Dhaka is 32C."))
                    }
                },
                health = health
            ).search("weather")
        }

        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.6-flash"), seen)
        assertTrue(answer.isSuccess)
        assertEquals("gemini-3.6-flash", answer.model)
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.6-flash"), answer.attempted)
        assertTrue(answer.skipped.any { it.contains("gemini-3.5-flash-lite") })
        // The cached model is the one that answered.
        assertEquals("gemini-3.6-flash", health.cachedModel(t0))
    }

    @Test
    fun theDeprecationWordingIsReportedPlainlyInTheBubble() {
        val body = error(404, "NOT_FOUND", "models/gemini-2.5-flash-lite is no longer available to new users. Please update your code to use models/gemini-3.5-flash-lite")
        val answer = GroundedSearchClient.parseResponse("q", 404, body, "gemini-2.5-flash-lite")
        val report = answer.failureReport()

        assertEquals(GroundedSearchFailure.MODEL_UNAVAILABLE, answer.failure)
        assertTrue(report.contains("type=MODEL_UNAVAILABLE"))
        assertTrue(report.contains("HTTP=404"))
        assertTrue(report.contains("no longer available"))
        assertTrue(report.contains("deprecated"))
    }

    // ---- 429 quota, skipped for the day --------------------------------------------------

    @Test
    fun a429SkipsThatModelForTheRestOfTheDay() = runBlocking {
        val seen = mutableListOf<String>()
        val store = InMemoryModelStateStore()
        val health = SearchModelHealth(store, { t0 })
        val transport: (RestRequest) -> RestResponse = { req ->
            seen += req.modelName()
            when (req.modelName()) {
                "gemini-3.5-flash-lite" -> RestResponse(429, error(429, "RESOURCE_EXHAUSTED", "Quota exceeded"))
                else -> RestResponse(200, groundedBody("Dhaka is warm."))
            }
        }

        client(transport, health).search("weather")
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.6-flash"), seen)

        // Same day, same health: the exhausted model is not asked again.
        seen.clear()
        client(transport, health).search("weather again")
        assertEquals(listOf("gemini-3.6-flash"), seen)
        assertTrue(health.isExhaustedToday("gemini-3.5-flash-lite", t0 + 60_000))
    }

    @Test
    fun anExhaustedModelComesBackTheNextDay() {
        val health = SearchModelHealth(InMemoryModelStateStore(), { t0 })
        health.onDailyQuotaExhausted("gemini-3.5-flash-lite", t0)
        assertTrue(health.isExhaustedToday("gemini-3.5-flash-lite", t0 + 60_000))

        val tomorrow = SearchModelHealth.nextLocalMidnight(t0) + 60_000
        assertFalse(health.isExhaustedToday("gemini-3.5-flash-lite", tomorrow))
        assertEquals(
            listOf("gemini-3.5-flash-lite", "gemini-3.6-flash"),
            health.usableModels(tomorrow)
        )
    }

    @Test
    fun everyModelOutOfQuotaSaysSoInsteadOfInventingAnAnswer() = runBlocking {
        val health = SearchModelHealth(InMemoryModelStateStore(), { t0 })
        val answer = client({ RestResponse(429, error(429, "RESOURCE_EXHAUSTED", "Quota exceeded")) }, health)
            .search("weather")

        assertFalse(answer.isSuccess)
        assertEquals(GroundedSearchFailure.QUOTA, answer.failure)
        assertEquals(429, answer.statusCode)
        // Second call, same health: nothing is even attempted, and it still fails honestly.
        val second = client({ RestResponse(200, groundedBody("should not be reached")) }, health).search("weather")
        assertFalse(second.isSuccess)
        assertEquals(GroundedSearchFailure.QUOTA, second.failure)
        assertTrue(second.asToolResult().contains("Tell the user"))
        assertTrue(second.asToolResult().contains("could not search"))
    }

    // ---- auth / internal never cycle -----------------------------------------------------

    @Test
    fun a401DoesNotCycleModels() = runBlocking {
        val seen = mutableListOf<String>()
        val answer = client(
            { req -> seen += req.modelName(); RestResponse(401, error(401, "UNAUTHENTICATED", "API key not valid.")) },
            SearchModelHealth(InMemoryModelStateStore(), { t0 })
        ).search("weather")

        assertEquals(1, seen.size)
        assertEquals(GroundedSearchFailure.AUTH, answer.failure)
        assertTrue(answer.failureReport().contains("HTTP=401"))
    }

    @Test
    fun anInternalErrorDoesNotCycleModels() = runBlocking {
        val seen = mutableListOf<String>()
        val answer = client(
            { req -> seen += req.modelName(); throw java.io.EOFException("body ended early") },
            SearchModelHealth(InMemoryModelStateStore(), { t0 })
        ).search("weather")

        assertEquals(1, seen.size)
        assertEquals(GroundedSearchFailure.INTERNAL, answer.failure)
    }

    @Test
    fun aPlainServerErrorAlsoStopsInsteadOfBurningTheSecondModel() = runBlocking {
        val seen = mutableListOf<String>()
        val answer = client(
            { req -> seen += req.modelName(); RestResponse(503, "<html>Service Unavailable</html>") },
            SearchModelHealth(InMemoryModelStateStore(), { t0 })
        ).search("weather")

        assertEquals(1, seen.size)
        assertEquals(GroundedSearchFailure.HTTP, answer.failure)
        assertEquals(503, answer.statusCode)
    }

    // ---- cache ---------------------------------------------------------------------------

    @Test
    fun aWorkingModelIsCachedAndUsedFirst() {
        val store = InMemoryModelStateStore()
        val health = SearchModelHealth(store, { t0 })
        health.onSuccess("gemini-3.6-flash", t0)

        assertEquals("gemini-3.6-flash", health.cachedModel(t0 + 1000))
        // The cached model is tried first, so a search starts with it rather than with the list head.
        assertEquals(listOf("gemini-3.6-flash", "gemini-3.5-flash-lite"), health.usableModels(t0 + 1000))
    }

    @Test
    fun theCacheExpiresAfterTwentyFourHours() {
        val health = SearchModelHealth(InMemoryModelStateStore(), { t0 })
        health.onSuccess("gemini-3.6-flash", t0)

        assertNotNull(health.cachedModel(t0 + day - 1))
        assertNull(health.cachedModel(t0 + day))
        assertNull(health.cachedModel(t0 + day + 60_000))
    }

    @Test
    fun theCacheSurvivesARestartBecauseItIsPersisted() {
        val store = InMemoryModelStateStore()
        SearchModelHealth(store, { t0 }).onSuccess("gemini-3.6-flash", t0)

        // A brand new instance over the same store is what an app restart looks like.
        val restarted = SearchModelHealth(store, { t0 + 60_000 })
        assertEquals("gemini-3.6-flash", restarted.cachedModel(t0 + 60_000))
    }

    @Test
    fun aFailingModelLosesTheCacheImmediately() {
        val health = SearchModelHealth(InMemoryModelStateStore(), { t0 })
        health.onSuccess("gemini-3.5-flash-lite", t0)
        assertEquals("gemini-3.5-flash-lite", health.cachedModel(t0))

        health.onModelUnavailable("gemini-3.5-flash-lite", t0 + 1000)
        assertNull(health.cachedModel(t0 + 1000))
    }

    // ---- request shape for the 3.x family ------------------------------------------------

    @Test
    fun theRequestBodyIsValidForTheThreeXFamily() {
        val body = client({ RestResponse(200, groundedBody("x")) }, SearchModelHealth(InMemoryModelStateStore(), { t0 }))
            .requestBody("dhaka weather")

        assertTrue(body.contains("\"google_search\""))
        assertTrue("output budget must not truncate a grounded answer", body.contains("\"maxOutputTokens\":1024"))
        assertTrue("thinking level must be explicit", body.contains("\"thinkingLevel\":\"MINIMAL\""))
        // temperature is documented as silently ignored on 3.6 Flash and newer, so it must not be sent.
        assertFalse(body.contains("temperature"))
        assertFalse("the key must never be in the body", body.contains("AIza"))
    }

    @Test
    fun theDiagnosticsLineShowsCacheAndSkips() {
        val health = SearchModelHealth(InMemoryModelStateStore(), { t0 })
        health.onSuccess("gemini-3.5-flash-lite", t0)
        health.onDailyQuotaExhausted("gemini-3.6-flash", t0)

        val line = client({ RestResponse(200, groundedBody("x")) }, health).diagnosticsLine()
        assertTrue(line.contains("gemini-3.5-flash-lite>gemini-3.6-flash"))
        assertTrue(line.contains("cached=gemini-3.5-flash-lite"))
        assertTrue(line.contains("skipped=gemini-3.6-flash(quota"))
        assertFalse("no key in diagnostics", line.contains("AIzaS"))
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun client(transport: (RestRequest) -> RestResponse, health: SearchModelHealth): GroundedSearchClient =
        GroundedSearchClient(
            apiKey = "AIzaUNIT-TEST-PLACEHOLDER-000000000000",
            transport = RestTransport { transport(it) },
            health = health,
            models = SearchModels.ORDER
        )

    private fun RestRequest.modelName(): String =
        url.substringAfter("/models/").substringBefore(":generateContent")

    private fun error(code: Int, status: String, message: String): String =
        """{"error":{"code":$code,"status":"$status","message":"$message"}}"""

    /** A 3.x-shaped grounded response: candidate parts plus groundingMetadata chunks. */
    private fun groundedBody(text: String): String = """
        {
          "candidates": [{
            "content": {"role": "model", "parts": [{"text": "$text"}]},
            "finishReason": "STOP",
            "groundingMetadata": {
              "webSearchQueries": ["dhaka weather"],
              "groundingChunks": [
                {"web": {"uri": "https://weather.example.com/dhaka", "title": "Example Weather"}},
                {"web": {"uri": "https://news.example.com/bd", "title": "Example News"}}
              ]
            }
          }],
          "usageMetadata": {"promptTokenCount": 12, "candidatesTokenCount": 40, "totalTokenCount": 52},
          "modelVersion": "gemini-3.5-flash-lite"
        }
    """.trimIndent()
}
