package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.2.7 Auto mode: the second, short-lived search session.
 *
 * The WebSocket is faked through [LiveSearchTransport], so the whole protocol — setup frame, one
 * text turn, transcription gathering, grounding metadata, close codes, the 20 s deadline — is
 * exercised without a Gemini key. Label: TESTED-MOCK.
 */
class LiveSearchSessionTest {

    /** A transport whose "server" the test drives by hand. */
    private class FakeTransport : LiveSearchTransport {
        val sent = CopyOnWriteArrayList<String>()
        var listener: LiveSearchListener? = null
        var closedWith: Pair<Int, String>? = null
        var failOnSend = false

        override fun open(model: String, listener: LiveSearchListener): LiveSearchConnection {
            this.listener = listener
            listener.onOpen()
            return object : LiveSearchConnection {
                override fun send(json: String): Boolean {
                    if (failOnSend) return false
                    sent += json
                    return true
                }

                override fun close(code: Int, reason: String) {
                    closedWith = code to reason
                }
            }
        }

        fun server(text: String) = listener?.onServerMessage(text)
        fun close(code: Int, reason: String) = listener?.onClosed(code, reason)
    }

    private fun transcript(text: String): String =
        """{"serverContent":{"outputTranscription":{"text":"$text"}}}"""

    private fun turnComplete(): String = """{"serverContent":{"turnComplete":true}}"""

    private val setupComplete = """{"setupComplete":{}}"""

    private fun grounding(): String =
        """{"serverContent":{"groundingMetadata":{"webSearchQueries":["weather dhaka"],""" +
            """"groundingChunks":[{"web":{"uri":"https://weather.example/dhaka","title":"Dhaka Weather"}}]}}}"""

    // ---- the happy path -------------------------------------------------------------

    @Test
    fun oneTextTurnBecomesTheAnswerAndTheAudioIsDiscarded() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 5_000)

        val speaker = Thread {
            Thread.sleep(30)
            transport.server(setupComplete)
            transport.server(transcript("ঢাকায় আজ "))
            transport.server(transcript("৩২ ডিগ্রি, রোদ।"))
            transport.server(turnComplete())
        }
        speaker.start()

        val answer = session.search("আজ ঢাকার আবহাওয়া কেমন?")
        speaker.join()

        assertTrue("the answer must succeed", answer.isSuccess)
        assertEquals("ঢাকায় আজ ৩২ ডিগ্রি, রোদ।", answer.text)
        assertEquals(LiveModels.SEARCH_NATIVE_AUDIO, answer.model)
        assertTrue("connect time must be measured", answer.connectMillis >= 0)
        assertEquals("text length is reported for diagnostics", answer.text.length, answer.textLength)
        assertTrue("total time must be measured", answer.elapsedMillis >= 0)
        assertFalse(answer.asToolResult().contains("AIzaS"))
    }

    @Test
    fun theSetupFrameDeclaresOnlyGoogleSearchAndTheTurnIsATextTurn() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 5_000)
        val speaker = Thread {
            Thread.sleep(30)
            transport.server(setupComplete)
            transport.server(transcript("ok"))
            transport.server(turnComplete())
        }
        speaker.start()
        session.search("test query")
        speaker.join()

        assertEquals("exactly two frames: setup + one turn", 2, transport.sent.size)

        @Suppress("UNCHECKED_CAST")
        val setup = (MiniJson.parse(transport.sent[0]) as Map<String, Any?>)["setup"] as Map<*, *>
        assertEquals("models/${LiveModels.SEARCH_NATIVE_AUDIO}", setup["model"])
        val tools = setup["tools"] as List<*>
        assertEquals(1, tools.size)
        assertTrue((tools[0] as Map<*, *>).containsKey("googleSearch"))
        assertTrue("the transcription is mandatory", setup.containsKey("outputAudioTranscription"))
        assertFalse(MiniJson.encode(setup).contains("functionDeclarations"))

        @Suppress("UNCHECKED_CAST")
        val turn = (MiniJson.parse(transport.sent[1]) as Map<String, Any?>)["clientContent"] as Map<*, *>
        assertEquals(true, turn["turnComplete"])
        val parts = ((turn["turns"] as List<*>)[0] as Map<*, *>)["parts"] as List<*>
        assertEquals("test query", (parts[0] as Map<*, *>)["text"])
    }

    @Test
    fun groundingMetadataIsTurnedIntoRealSources() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 5_000)
        val speaker = Thread {
            Thread.sleep(30)
            transport.server(setupComplete)
            transport.server(transcript("ঢাকায় ৩২ ডিগ্রি।"))
            transport.server(grounding())
            transport.server(turnComplete())
        }
        speaker.start()
        val answer = session.search("আজ ঢাকার আবহাওয়া কেমন?")
        speaker.join()

        assertEquals(1, answer.sources.size)
        assertEquals("Dhaka Weather", answer.sources[0].title)
        assertEquals("https://weather.example/dhaka", answer.sources[0].url)
        assertTrue(answer.asToolResult().contains("weather.example"))
    }

    // ---- the failure paths the spec lists -------------------------------------------

    @Test
    fun noAnswerInsideTheDeadlineIsATimeoutNotAnInvention() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 150)
        transport.server(setupComplete)   // connected, but the model never speaks

        val started = System.currentTimeMillis()
        val answer = session.search("slow question")
        val elapsed = System.currentTimeMillis() - started

        assertEquals(GroundedSearchFailure.TIMEOUT, answer.failure)
        assertTrue("the deadline must actually be enforced", elapsed >= 120)
        assertTrue("nothing may be invented", answer.text.isEmpty())
        assertTrue(
            "the voice model is told the search failed, so it says so",
            answer.asToolResult().contains("did not finish in time")
        )
        assertEquals("the socket is closed after a timeout", 1000, transport.closedWith?.first)
        assertNotNull(answer.detail)
    }

    @Test
    fun aPolicyCloseCodeIsReportedWithItsExactCodeAndReason() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 5_000)
        val speaker = Thread {
            Thread.sleep(30)
            transport.close(1008, "quota exceeded")
        }
        speaker.start()
        val answer = session.search("question")
        speaker.join()

        assertEquals(GroundedSearchFailure.QUOTA, answer.failure)
        assertEquals(1008, answer.closeCode)
        val report = answer.failureReport()
        assertTrue("the chat report must carry the code", report.contains("close=1008"))
        assertTrue("the chat report must carry the reason", report.contains("quota exceeded"))
        assertTrue("and the search-session timings", report.contains("Search session: connect="))
        assertTrue(report.contains("key=AIza****"))
    }

    @Test
    fun anEmptyTranscriptionIsNotEmptySuccess() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 5_000)
        val speaker = Thread {
            Thread.sleep(30)
            transport.server(setupComplete)
            transport.server(turnComplete())   // finished, but said nothing
        }
        speaker.start()
        val answer = session.search("question")
        speaker.join()

        assertEquals(GroundedSearchFailure.EMPTY, answer.failure)
        assertTrue(answer.text.isEmpty())
        assertFalse(answer.isSuccess)
    }

    @Test
    fun aSetupThatCannotBeSentFailsImmediately() = runBlocking {
        val transport = FakeTransport().apply { failOnSend = true }
        val answer = LiveSearchSession(transport, timeoutMillis = 5_000).search("question")

        assertEquals(GroundedSearchFailure.INTERNAL, answer.failure)
        assertTrue(answer.detail.contains("setup"))
    }

    @Test
    fun aServerErrorFrameIsReportedInsteadOfAnEmptyAnswer() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 5_000)
        val speaker = Thread {
            Thread.sleep(30)
            transport.server("""{"error":{"code":429,"message":"Resource has been exhausted"}}""")
        }
        speaker.start()
        val answer = session.search("question")
        speaker.join()

        assertEquals(GroundedSearchFailure.HTTP, answer.failure)
        assertTrue(answer.serverMessage.contains("429"))
    }

    // ---- mapping and protocol ------------------------------------------------------

    @Test
    fun closeCodesMapToTheFailureTheModelIsToldAbout() {
        assertEquals(GroundedSearchFailure.QUOTA, LiveSearchSession.failureFor(1008))
        assertEquals(GroundedSearchFailure.QUOTA, LiveSearchSession.failureFor(4403))
        assertEquals(GroundedSearchFailure.UNSUPPORTED, LiveSearchSession.failureFor(1007))
        assertEquals(GroundedSearchFailure.UNSUPPORTED, LiveSearchSession.failureFor(4400))
        assertEquals(GroundedSearchFailure.HTTP, LiveSearchSession.failureFor(1011))
        assertEquals(GroundedSearchFailure.NETWORK, LiveSearchSession.failureFor(1006))
    }

    @Test
    fun everyServerFrameKindIsParsed() {
        assertTrue(LiveSearchProtocol.events(setupComplete).contains(LiveEvent.SetupComplete))
        assertEquals(
            listOf(LiveEvent.Transcript("hi")),
            LiveSearchProtocol.events(transcript("hi"))
        )
        assertTrue(LiveSearchProtocol.events(turnComplete()).contains(LiveEvent.TurnComplete))
        val sources = LiveSearchProtocol.events(grounding()).filterIsInstance<LiveEvent.Sources>()
        assertEquals(1, sources.size)
        assertEquals("https://weather.example/dhaka", sources[0].sources[0].url)
        val error = LiveSearchProtocol.events("""{"error":{"code":500,"message":"boom"}}""")
            .filterIsInstance<LiveEvent.Error>()
        assertEquals(1, error.size)
        assertTrue(error[0].message.contains("boom"))
        // Unknown frames must never throw or invent an event.
        assertTrue(LiveSearchProtocol.events("""{"serverContent":{}}""").isEmpty())
    }

    // ---- cache ---------------------------------------------------------------------

    private class CountingBackend(private val answer: (String) -> GroundedAnswer) : SearchBackend {
        var calls = 0
        override suspend fun search(query: String): GroundedAnswer {
            calls += 1
            return answer(query)
        }

        override fun describe(): String = "counting"
    }

    @Test
    fun aRepeatedQuestionReusesTheShortCacheButAFailureIsNeverCached() = runBlocking {
        val backend = CountingBackend { query ->
            GroundedAnswer(query = query, text = "উত্তর", model = LiveModels.SEARCH_NATIVE_AUDIO)
        }
        val cached = CachedSearchBackend(backend, ttlMillis = 60_000)

        val first = cached.search("ঢাকার আবহাওয়া")
        val second = cached.search("  ঢাকার   আবহাওয়া  ")
        assertEquals(1, backend.calls)
        assertTrue(second.detail.contains("cached"))
        assertEquals("উত্তর", first.text)

        val failing = CountingBackend { query ->
            GroundedAnswer(query = query, text = "", failure = GroundedSearchFailure.TIMEOUT)
        }
        val cachedFailure = CachedSearchBackend(failing)
        cachedFailure.search("প্রশ্ন")
        cachedFailure.search("প্রশ্ন")
        assertEquals("a failure is retried, never cached", 2, failing.calls)
    }

    @Test
    fun theCacheExpiresAfterItsTtl() = runBlocking {
        var now = 1_000L
        val backend = CountingBackend { query -> GroundedAnswer(query = query, text = "a") }
        val cached = CachedSearchBackend(backend, ttlMillis = 1_000, clock = { now })

        cached.search("q")
        now += 500
        cached.search("q")
        assertEquals(1, backend.calls)
        now += 600
        cached.search("q")
        assertEquals("after the TTL the backend is asked again", 2, backend.calls)
    }

    @Test
    fun theDiagnosticsLineNeverContainsTheKey() {
        val session = LiveSearchSession(FakeTransport(), timeoutMillis = 1_000)
        val line = session.describe()
        assertTrue(line.contains(LiveModels.SEARCH_NATIVE_AUDIO))
        assertTrue(line.contains("AIza****"))
        assertTrue(line.contains("googleSearch"))
        assertTrue((line.indexOf("AIzaS")).takeIf { it >= 0 } == null)
    }
}
