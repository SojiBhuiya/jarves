package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 4 retrieval: the REST call that actually performs the search, and the honest mapping of
 * every failure to something the voice assistant can say.
 *
 * No socket is involved: [RestTransport] is the seam, so the request body, the credential header
 * placement and every response branch are checked for real.
 */
class GroundedSearchClientTest {

    private fun client(transport: RestTransport) =
        GroundedSearchClient(apiKey = "UNIT-TEST-key-placeholder", transport = transport)

    /** A minimal successful grounded response, shaped like `generateContent` returns it. */
    private fun successBody(): String = """
        {
          "candidates": [{
            "content": {"role": "model", "parts": [
              {"text": "Dhaka is 32°C and humid today."},
              {"thought": true, "text": "internal reasoning that must never be spoken"}
            ]},
            "groundingMetadata": {
              "groundingChunks": [
                {"web": {"uri": "https://weather.example.com/dhaka", "title": "Example Weather"}},
                {"web": {"uri": "https://news.example.com/bd", "title": "Example News"}}
              ],
              "groundingSupports": [
                {"segment": {"startIndex": 0, "endIndex": 29, "text": "Dhaka is 32°C and humid today."},
                 "groundingChunkIndices": [0, 1]}
              ]
            },
            "finishReason": "STOP"
          }]
        }
    """.trimIndent()

    // ---- the request -------------------------------------------------------------------

    @Test
    fun requestUsesTheRestSearchToolInSnakeCase() {
        val body = client { RestResponse(200, successBody()) }.requestBody("ঢাকার আবহাওয়া")
        val root = MiniJson.parse(body) as Map<*, *>
        val tools = root["tools"] as List<*>
        assertEquals(1, tools.size)
        val tool = tools.single() as Map<*, *>

        // REST spells it `google_search`; the Live API spells it `googleSearch`. Mixing them up
        // fails with an opaque INVALID_ARGUMENT, so this is asserted exactly.
        assertEquals(setOf("google_search"), tool.keys)
        assertTrue(tool.containsKey("google_search"))
        assertFalse(tool.containsKey("googleSearch"))
        assertFalse(tool.containsKey("functionDeclarations"))
    }

    @Test
    fun requestGoesToTheNonLiveModelAndCarriesTheQuestion() {
        val c = client { RestResponse(200, successBody()) }
        val request = c.buildRequest("আজকের সোনার দাম")
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            request.url
        )
        assertTrue(request.body.contains("আজকের সোনার দাম"))
        assertTrue(request.body.contains("sources", ignoreCase = true))
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
    }

    @Test
    fun theKeyTravelsOnlyInTheHeaderAndNeverInTheUrlOrBody() {
        val request = client { RestResponse(200, successBody()) }.buildRequest("x")
        assertEquals("UNIT-TEST-key-placeholder", request.headers["x-goog-api-key"])
        assertFalse("the key must never be in the URL", request.url.contains("UNIT-TEST-key-placeholder"))
        assertFalse("the key must never be in the body", request.body.contains("UNIT-TEST-key-placeholder"))
        assertFalse("no query-string auth", request.url.contains("key="))
        assertEquals(setOf("Content-Type", "x-goog-api-key"), request.headers.keys)
    }

    @Test
    fun describeNeverPrintsTheKey() {
        val described = client { RestResponse(200, successBody()) }.describe()
        assertFalse(described.contains("UNIT-TEST-key-placeholder"))
        assertTrue(described.contains("AIza****"))
        assertTrue(described.contains("gemini-2.5-flash"))
    }

    // ---- the answer --------------------------------------------------------------------

    @Test
    fun successReturnsTheSpokenTextAndTheRealSourceUrls() {
        val answer = GroundedSearchClient.parseResponse("q", 200, successBody())
        assertTrue(answer.isSuccess)
        assertEquals("Dhaka is 32°C and humid today.", answer.text)
        assertEquals(
            listOf("https://weather.example.com/dhaka", "https://news.example.com/bd"),
            answer.sources.map { it.url }
        )
        assertFalse(answer.text.contains("internal reasoning"))
    }

    @Test
    fun theToolResultIsReadableAndNamesItsSources() {
        val result = GroundedSearchClient.parseResponse("q", 200, successBody()).asToolResult()
        assertTrue(result.contains("Dhaka is 32°C and humid today."))
        assertTrue(result.contains("https://weather.example.com/dhaka"))
        assertTrue(result.contains("Example Weather"))
    }

    @Test
    fun aSuccessfulAnswerWithNoSourcesSaysSoInsteadOfInventingALink() {
        val body = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"No numbers found."}]}}]}
        """.trimIndent()
        val answer = GroundedSearchClient.parseResponse("q", 200, body)
        assertTrue(answer.isSuccess)
        assertTrue(answer.sources.isEmpty())
        val result = answer.asToolResult()
        assertTrue(result.contains("No numbers found."))
        assertTrue("the model must be told not to cite anything", result.contains("do not cite a link"))
        assertFalse(result.contains("http"))
    }

    // ---- the failures ------------------------------------------------------------------

    @Test
    fun httpFailuresAreClassifiedAndNeverLookLikeFacts() {
        val cases = listOf(
            Triple(401, """{"error":{"code":401,"message":"API key not valid."}}""", GroundedSearchFailure.AUTH),
            Triple(403, """{"error":{"code":403,"message":"Permission denied."}}""", GroundedSearchFailure.AUTH),
            Triple(429, """{"error":{"code":429,"message":"Resource has been exhausted."}}""", GroundedSearchFailure.QUOTA),
            Triple(400, """{"error":{"code":400,"message":"Unknown name \\"google_search\\""}}""", GroundedSearchFailure.UNSUPPORTED),
            Triple(404, """{"error":{"code":404,"message":"models/x is not found for API version v1beta"}}""", GroundedSearchFailure.UNSUPPORTED),
            Triple(500, "boom", GroundedSearchFailure.HTTP)
        )
        cases.forEach { (code, body, expected) ->
            val answer = GroundedSearchClient.parseResponse("q", code, body)
            assertEquals("status $code", expected, answer.failure)
            assertEquals(code, answer.statusCode)
            assertFalse("status $code must not be a success", answer.isSuccess)
            val result = answer.asToolResult()
            assertFalse("status $code must not leak http", result.contains("http://"))
            assertTrue("status $code must instruct the model", result.contains("Tell the user"))
        }
    }

    @Test
    fun a200ThatCarriesAnErrorObjectIsTreatedAsAFailure() {
        val body = """{"error":{"code":429,"message":"quota exceeded"}}"""
        val answer = GroundedSearchClient.parseResponse("q", 200, body)
        assertEquals(GroundedSearchFailure.QUOTA, answer.failure)
        assertFalse(answer.isSuccess)
    }

    @Test
    fun anEmptyOrGarbledBodyIsAFailureNotAnEmptyAnswer() {
        listOf("", "   ", "not json", "{}", """{"candidates":[]}""").forEach { body ->
            val answer = GroundedSearchClient.parseResponse("q", 200, body)
            assertEquals("body: '$body'", GroundedSearchFailure.EMPTY, answer.failure)
            assertFalse(answer.isSuccess)
        }
    }

    @Test
    fun everyFailureHasItsOwnHonestWordingAndNeverInventsAFact() {
        val messages = GroundedSearchFailure.entries.associateWith {
            GroundedAnswer("q", "", failure = it).asToolResult()
        }
        assertEquals("each failure must be distinguishable", messages.size, messages.values.toSet().size)

        messages.forEach { (failure, text) ->
            assertTrue("$failure must say something", text.isNotBlank())
            assertFalse("$failure must not fabricate a link", text.contains("http://"))
            assertFalse("$failure must not fabricate a link", text.contains("https://"))
            assertFalse("$failure must not claim a result", text.contains("Dhaka"))
            assertTrue("$failure must instruct the model", text.contains("Tell the user"))
        }

        assertTrue(messages.getValue(GroundedSearchFailure.TIMEOUT).contains("do not invent facts or links"))
        assertTrue(messages.getValue(GroundedSearchFailure.QUOTA).contains("do not invent facts or links"))
        // BUSY is not an error for the user: it promises the other question comes next.
        assertTrue(messages.getValue(GroundedSearchFailure.BUSY).contains("next"))
    }

    // ---- through the transport ---------------------------------------------------------

    @Test
    fun searchSendsTheBuiltRequestAndParsesTheResponse() {
        var seen: RestRequest? = null
        val c = client { request ->
            seen = request
            RestResponse(200, successBody())
        }
        val answer = kotlinx.coroutines.runBlocking { c.search("  ঢাকার আবহাওয়া  ") }

        assertNotNull(seen)
        assertEquals("UNIT-TEST-key-placeholder", seen!!.headers["x-goog-api-key"])
        assertTrue(seen!!.body.contains("ঢাকার আবহাওয়া"))
        assertTrue(answer.isSuccess)
        assertEquals(2, answer.sources.size)
    }

    @Test
    fun aThrowingTransportBecomesANetworkFailure() {
        val c = client { throw java.io.IOException("no route to host") }
        val answer = kotlinx.coroutines.runBlocking { c.search("q") }
        assertEquals(GroundedSearchFailure.NETWORK, answer.failure)
        assertFalse(answer.isSuccess)
    }

    @Test
    fun aBlankQueryNeverReachesTheNetwork() {
        var calls = 0
        val c = client { calls++; RestResponse(200, successBody()) }
        val answer = kotlinx.coroutines.runBlocking { c.search("   ") }
        assertEquals(0, calls)
        assertEquals(GroundedSearchFailure.EMPTY, answer.failure)
    }

    @Test
    fun duplicateSourceUrlsAreCollapsed() {
        val body = """
            {"candidates":[{"content":{"parts":[{"text":"ok"}]},
             "groundingMetadata":{"groundingChunks":[
               {"web":{"uri":"https://a.example","title":"A"}},
               {"web":{"uri":"https://a.example","title":"A again"}},
               {"web":{"uri":"https://b.example","title":"B"}}]}}]}
        """.trimIndent()
        val answer = GroundedSearchClient.parseResponse("q", 200, body)
        assertEquals(listOf("https://a.example", "https://b.example"), answer.sources.map { it.url })
        assertNull(answer.failure)
    }
}
