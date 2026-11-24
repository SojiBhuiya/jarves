package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 4, wire level: what the app reads out of a `toolCall` frame and what it writes back.
 *
 * The frames below are the shapes documented at ai.google.dev/api/live — `toolCall.functionCalls[]`
 * with `id` / `name` / `args`, and `toolCallCancellation.ids[]`. Nothing here touches a network.
 */
class LiveToolProtocolTest {

    private fun toolCallFrame(id: String = "call-1", query: String = "আজ ঢাকার তাপমাত্রা") =
        """
        {"toolCall":{"functionCalls":[{"id":"$id","name":"search_web","args":{"query":"$query"}}]}}
        """.trimIndent()

    // ---- parsing -----------------------------------------------------------------------

    @Test
    fun readsTheQueryAndCallIdFromAToolCall() {
        val calls = LiveToolProtocol.parseSearchCalls(toolCallFrame())
        assertEquals(1, calls.size)
        assertEquals("call-1", calls.single().callId)
        assertEquals("আজ ঢাকার তাপমাত্রা", calls.single().query)
    }

    @Test
    fun readsEveryCallInAMultiCallFrame() {
        val frame = """
            {"toolCall":{"functionCalls":[
              {"id":"a","name":"search_web","args":{"query":"first"}},
              {"id":"b","name":"search_web","args":{"query":"second"}}
            ]}}
        """.trimIndent()
        val calls = LiveToolProtocol.parseSearchCalls(frame)
        assertEquals(listOf("a", "b"), calls.map { it.callId })
        assertEquals(listOf("first", "second"), calls.map { it.query })
    }

    @Test
    fun ignoresFunctionsTheAppDoesNotImplement() {
        val frame = """
            {"toolCall":{"functionCalls":[
              {"id":"x","name":"play_music","args":{"track":"x"}},
              {"id":"y","name":"search_web","args":{"query":"ok"}}
            ]}}
        """.trimIndent()
        assertEquals(listOf("y"), LiveToolProtocol.parseSearchCalls(frame).map { it.callId })
    }

    @Test
    fun dropsCallsWithoutAUsableQueryOrId() {
        val frame = """
            {"toolCall":{"functionCalls":[
              {"id":"blank","name":"search_web","args":{"query":"   "}},
              {"id":"","name":"search_web","args":{"query":"no id"}},
              {"id":"noparams","name":"search_web"}
            ]}}
        """.trimIndent()
        assertTrue(
            "unusable calls must be dropped, not guessed at",
            LiveToolProtocol.parseSearchCalls(frame).isEmpty()
        )
    }

    @Test
    fun truncatesAnAbsurdlyLongQueryInsteadOfSendingIt() {
        val long = "ক".repeat(LiveToolProtocol.MAX_QUERY_CHARS + 200)
        val calls = LiveToolProtocol.parseSearchCalls(toolCallFrame(query = long))
        assertEquals(LiveToolProtocol.MAX_QUERY_CHARS, calls.single().query.length)
    }

    @Test
    fun readsTheForceRefreshFlagFromAToolCall() {
        val fresh = """{"toolCall":{"functionCalls":[{"id":"f","name":"search_web","args":{"query":"দাম কত?","forceRefresh":true}}]}}"""
        assertTrue(LiveToolProtocol.parseSearchCalls(fresh).single().forceRefresh)

        val normal = """{"toolCall":{"functionCalls":[{"id":"n","name":"search_web","args":{"query":"দাম কত?","forceRefresh":false}}]}}"""
        assertFalse(LiveToolProtocol.parseSearchCalls(normal).single().forceRefresh)

        // Absent means an ordinary search — older frames carry no flag at all.
        assertFalse(LiveToolProtocol.parseSearchCalls(toolCallFrame()).single().forceRefresh)
    }

    @Test
    fun readsTheForceRefreshFlagFromStringEncodedArgs() {
        val frame = """{"toolCall":{"functionCalls":[{"id":"s","name":"search_web","args":"{\"query\":\"দাম কত?\",\"forceRefresh\":true}"}]}}"""
        assertTrue(LiveToolProtocol.parseSearchCalls(frame).single().forceRefresh)
    }

    @Test
    fun readsCancellationIds() {
        val frame = """{"toolCallCancellation":{"ids":["call-1","call-9"]}}"""
        assertEquals(listOf("call-1", "call-9"), LiveToolProtocol.parseCancellations(frame))
    }

    @Test
    fun malformedAndUnrelatedFramesAreSafe() {
        val junk = listOf(
            "", "not json", "{}", "{\"serverContent\":{}}", "{\"toolCall\":null}",
            "{\"toolCall\":{\"functionCalls\":\"nope\"}}", "[1,2,3]"
        )
        junk.forEach { frame ->
            assertTrue("must not throw on: $frame", LiveToolProtocol.parseSearchCalls(frame).isEmpty())
            assertTrue("must not throw on: $frame", LiveToolProtocol.parseCancellations(frame).isEmpty())
        }
    }

    @Test
    fun theCheapPreFilterIsNotFooledByOrdinaryAudioFrames() {
        assertFalse(LiveToolProtocol.mightContainToolCall("""{"serverContent":{"turnComplete":true}}"""))
        assertTrue(LiveToolProtocol.mightContainToolCall(toolCallFrame()))
        assertTrue(LiveToolProtocol.mightContainToolCall("""{"toolCallCancellation":{"ids":[]}}"""))
    }

    @Test
    fun parsesArgsThatArriveAsAJsonString() {
        val frame = """{"toolCall":{"functionCalls":[{"id":"s","name":"search_web","args":"{\"query\":\"encoded\"}"}]}}"""
        assertEquals("encoded", LiveToolProtocol.parseSearchCalls(frame).single().query)
    }

    // ---- the reply ---------------------------------------------------------------------

    @Test
    fun toolResponseHasTheExactDocumentedShape() {
        val json = LiveToolProtocol.toolResponseJson(
            "call-1", "উত্তর: ৩২°C", LiveToolProtocol.SCHEDULING_WHEN_IDLE
        )
        val root = MiniJson.parse(json) as Map<*, *>

        // Nothing but toolResponse at the top level: the server rejects unknown siblings.
        assertEquals(setOf("toolResponse"), root.keys)
        val responses = (root["toolResponse"] as Map<*, *>)["functionResponses"] as List<*>
        assertEquals(1, responses.size)
        val response = responses.single() as Map<*, *>

        assertEquals("call-1", response["id"])                       // matched by id, not order
        assertEquals("search_web", response["name"])
        assertEquals("WHEN_IDLE", response["scheduling"])
        assertEquals("উত্তর: ৩২°C", (response["response"] as Map<*, *>)["result"])
        // `response` carries the result object, never a bare string.
        assertNotNull(response["response"])
        assertTrue(response["response"] is Map<*, *>)
    }

    @Test
    fun theDefaultSchedulingIsWhenIdleSoAnAnswerInProgressIsNeverCutOff() {
        val json = LiveToolProtocol.toolResponseJson("call-1", "short")
        val response = ((MiniJson.parse(json) as Map<*, *>)["toolResponse"] as Map<*, *>)
            .let { it["functionResponses"] as List<*> }.single() as Map<*, *>
        assertEquals(LiveToolProtocol.SCHEDULING_WHEN_IDLE, response["scheduling"])
        assertEquals("WHEN_IDLE", response["scheduling"])
    }

    @Test
    fun everySearchResultWaitsForIdleWhetherItIsShortOrLong() {
        // 0.2.10 sent short results with INTERRUPT, which is documented as "interrupt ongoing
        // generation" — the 0.2.11 phone report. There is no length threshold any more.
        val short = "x".repeat(10)
        val long = "x".repeat(5_000)
        assertEquals("WHEN_IDLE", LiveToolProtocol.schedulingFor(short))
        assertEquals("WHEN_IDLE", LiveToolProtocol.schedulingFor(long))

        val json = LiveToolProtocol.toolResponseJson("c", short, LiveToolProtocol.schedulingFor(short))
        assertTrue(json.contains("\"scheduling\":\"WHEN_IDLE\""))
        // 0.2.12 (B7): `scheduling` is sent exactly once, in the place the API reference defines it
        // (a field of FunctionResponse, https://ai.google.dev/api/generate-content#FunctionResponse).
        // `response` is a free-form Struct with no such field, so the 0.2.11 copy inside it is gone.
        val response = (((MiniJson.parse(json) as Map<*, *>)["toolResponse"] as Map<*, *>)
            ["functionResponses"] as List<*>).single() as Map<*, *>
        assertEquals(
            "the result object must carry the result and nothing undocumented",
            setOf("result"),
            (response["response"] as Map<*, *>).keys
        )
        assertEquals("exactly one scheduling field", 1, Regex("\"scheduling\"").findAll(json).count())
    }

    @Test
    fun toolResponseEscapesQuotesAndNewlinesSoTheFrameStaysValidJson() {
        val nasty = "He said \"৩২°C\"\nline2\\end"
        val json = LiveToolProtocol.toolResponseJson("call-1", nasty)
        // Re-parsing proves the escaping, not a substring match.
        val result = (((MiniJson.parse(json) as Map<*, *>)["toolResponse"] as Map<*, *>)
            ["functionResponses"] as List<*>).single().let { (it as Map<*, *>)["response"] as Map<*, *> }["result"]
        assertEquals(nasty, result)
    }

    @Test
    fun theResponseNameAlwaysMatchesTheDeclaredFunction() {
        assertEquals(LiveSessionConfig.FUNCTION_SEARCH_WEB, "search_web")
        assertTrue(LiveToolProtocol.toolResponseJson("i", "r").contains("\"name\":\"search_web\""))
    }

    // ---- 0.2.20: video tools -----------------------------------------------------------

    private fun videoToolCallFrame(
        id: String = "call-v1",
        name: String = "search_videos",
        args: String = "\"query\":\"old song\""
    ) =
        """
        {"toolCall":{"functionCalls":[{"id":"$id","name":"$name","args":{$args}}]}}
        """.trimIndent()

    @Test
    fun readsVideoSearchCallsAndIgnoresOtherFunctions() {
        val calls = LiveToolProtocol.parseYouTubeSearchCalls(
            videoToolCallFrame(name = "search_videos", args = "\"query\":\"old song\"")
        )
        assertEquals(1, calls.size)
        assertEquals("call-v1", calls.single().callId)
        assertEquals("old song", calls.single().query)
        // A search_web frame is not a video search.
        assertTrue(LiveToolProtocol.parseYouTubeSearchCalls(toolCallFrame()).isEmpty())
        // A play_video frame is not a video search.
        assertTrue(
            LiveToolProtocol.parseYouTubeSearchCalls(
                videoToolCallFrame(name = "play_video", args = "\"videoId\":\"abc123\"")
            ).isEmpty()
        )
    }

    @Test
    fun readsPlayVideoCallsWithVideoIdAndTitle() {
        val calls = LiveToolProtocol.parseYouTubePlayCalls(
            videoToolCallFrame(name = "play_video", args = "\"videoId\":\"abc123\",\"title\":\"Old Song\"")
        )
        assertEquals("abc123", calls.single().videoId)
        assertEquals("Old Song", calls.single().title)
        // Missing videoId is dropped, not guessed.
        assertTrue(
            LiveToolProtocol.parseYouTubePlayCalls(
                videoToolCallFrame(name = "play_video", args = "\"title\":\"Old Song\"")
            ).isEmpty()
        )
    }

    @Test
    fun readsPlaybackControlCalls() {
        val calls = LiveToolProtocol.parseYouTubeControlCalls(
            videoToolCallFrame(name = "control_playback", args = "\"action\":\"pause\"")
        )
        assertEquals("pause", calls.single().action)
        assertTrue(
            LiveToolProtocol.parseYouTubeControlCalls(
                videoToolCallFrame(name = "control_playback", args = "\"action\":\"\"")
            ).isEmpty()
        )
    }

    @Test
    fun videoToolResponseCarriesTheVideoFunctionName() {
        val json = LiveToolProtocol.toolResponseJsonFor(
            "call-v1", LiveSessionConfig.FUNCTION_PLAY_VIDEO, "Playing Old Song."
        )
        assertTrue(json.contains("\"name\":\"play_video\""))
        assertTrue(json.contains("Playing Old Song."))
    }
}

