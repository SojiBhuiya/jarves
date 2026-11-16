package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.2.8 — why Auto mode failed on the phone, and the regression tests that keep it fixed.
 *
 * Root causes found and proven here:
 *  1. the search socket never handled **binary** frames, so `setupComplete` and the transcription
 *     were never seen (0.2.7 only implemented the text callback) → always a 20 s timeout;
 *  2. the question was sent on socket-open instead of after `setupComplete`, which the API
 *     reference forbids ("Clients should wait for a `BidiGenerateContentSetupComplete` message
 *     before sending any additional messages", https://ai.google.dev/api/live#BidiGenerateContentSetup).
 *
 * Everything here is TESTED-MOCK: a fake transport plays the server.
 */
class AutoSearchFlowTest {

    /**
     * Fake server. It also plays the transport: like the real OkHttp transport it hands every frame
     * to the listener as decoded JSON text, and it waits for the session to open the socket before
     * delivering anything, so the scripted frames can never race the session start.
     */
    private class FakeTransport : LiveSearchTransport {
        val sent = CopyOnWriteArrayList<String>()
        @Volatile
        var closedWith: Pair<Int, String>? = null
        @Volatile
        var model: String? = null
        private val opened = java.util.concurrent.CountDownLatch(1)
        @Volatile
        private var listener: LiveSearchListener? = null

        override fun open(model: String, listener: LiveSearchListener): LiveSearchConnection {
            this.model = model
            this.listener = listener
            opened.countDown()
            listener.onOpen()
            return object : LiveSearchConnection {
                override fun send(json: String): Boolean {
                    sent += json
                    return true
                }

                override fun close(code: Int, reason: String) {
                    closedWith = code to reason
                }
            }
        }

        private fun await(): LiveSearchListener {
            check(opened.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "session never opened the socket" }
            return listener!!
        }

        fun server(json: String) = await().onServerMessage(json)

        fun serverCloses(code: Int, reason: String) = await().onClosed(code, reason)
    }

    private val setupComplete = """{"setupComplete":{}}"""

    private fun transcription(text: String) =
        """{"serverContent":{"outputTranscription":{"text":"$text"}}}"""

    private fun generationComplete() = """{"serverContent":{"generationComplete":true}}"""

    private fun turnComplete() = """{"serverContent":{"turnComplete":true}}"""

    /** Runs a scripted exchange on a background thread while the session waits. */
    private fun script(transport: FakeTransport, vararg steps: Pair<Long, () -> Unit>): Thread {
        val t = Thread {
            steps.forEach { (delay, action) ->
                Thread.sleep(delay)
                action()
            }
        }
        t.start()
        return t
    }

    // ---- 1. the ordering bug -------------------------------------------------------

    @Test
    fun theQuestionIsNotSentBeforeSetupComplete() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 5_000, setupTimeoutMillis = 2_000)
        val done = script(
            transport,
            60L to { transport.server(setupComplete) },
            20L to { transport.server(transcription("ঢাকায় ৩২ ডিগ্রি।")) },
            20L to { transport.server(generationComplete()) }
        )

        val answer = session.search("আজ ঢাকার আবহাওয়া কেমন?")
        done.join()

        assertTrue(answer.isSuccess)
        // Frame 0 must be setup, frame 1 the question — and only after setupComplete arrived.
        assertEquals(2, transport.sent.size)
        assertTrue(transport.sent[0].startsWith("""{"setup":"""))
        assertTrue(transport.sent[1].contains("clientContent"))
        assertTrue("the trace must show the order", answer.trace.contains("setupComplete"))
        assertTrue(answer.trace.contains("question sent"))
        assertTrue(
            "question must come after setupComplete in the trace",
            answer.trace.indexOf("setupComplete") < answer.trace.indexOf("question sent")
        )
    }

    @Test
    fun aSocketThatNeverCompletesSetupFailsVisiblyAtTheSetupStep() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 5_000, setupTimeoutMillis = 150)

        val answer = session.search("প্রশ্ন")

        assertEquals(GroundedSearchFailure.TIMEOUT, answer.failure)
        assertEquals(SearchStep.SETUP_COMPLETE.label, answer.step)
        assertTrue(answer.detail.contains("setupComplete"))
        assertTrue("only the setup frame may be sent", transport.sent.size == 1)
        assertTrue(answer.trace.contains("FAILED at ${SearchStep.SETUP_COMPLETE.label}"))
        assertTrue("the socket is closed", transport.closedWith != null)
    }

    // ---- 2. the binary-frame bug ---------------------------------------------------

    @Test
    fun theRealTransportHandlesBinaryFramesAsWellAsText() {
        // The Live API delivers its JSON inside binary WebSocket frames. 0.2.7 implemented only
        // onMessage(text), so the search session saw nothing and always timed out. The fix is the
        // byte callback below; this asserts it is really there.
        val methods = OkHttpLiveSearchTransport.FrameRelay::class.java.declaredMethods.toList()
        fun has(type: String) = methods.any { m ->
            m.name == "onMessage" && m.parameterTypes.any { it.simpleName == type }
        }
        assertTrue(
            "binary onMessage(ByteString) is missing — this is the 0.2.7 bug. Found: " +
                methods.filter { it.name == "onMessage" }.map { m -> m.parameterTypes.map { it.simpleName } },
            has("ByteString")
        )
        assertTrue("text onMessage(String) is missing", has("String"))
    }

    // ---- 3. the answer path ---------------------------------------------------------

    @Test
    fun generationCompleteFinishesTheTurnWithoutWaitingForTurnComplete() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 3_000, setupTimeoutMillis = 2_000)
        val done = script(
            transport,
            40L to { transport.server(setupComplete) },
            30L to { transport.server(transcription("উত্তর এক")) },
            30L to { transport.server(transcription(" এবং দুই।")) },
            30L to { transport.server(generationComplete()) }
            // turnComplete deliberately never arrives (the server may delay it for playback)
        )

        val started = System.currentTimeMillis()
        val answer = session.search("প্রশ্ন")
        val elapsed = System.currentTimeMillis() - started
        done.join()

        assertTrue(answer.isSuccess)
        assertEquals("উত্তর এক এবং দুই।", answer.text)
        assertTrue("must not wait for the full deadline: ${elapsed}ms", elapsed < 2_500)
        assertTrue(answer.trace.contains("generationComplete"))
    }

    @Test
    fun turnCompleteIsAlsoAcceptedAsTheEndOfTheTurn() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 3_000, setupTimeoutMillis = 2_000)
        val done = script(
            transport,
            40L to { transport.server(setupComplete) },
            30L to { transport.server(transcription("ঠিক আছে।")) },
            30L to { transport.server(turnComplete()) }
        )
        val answer = session.search("প্রশ্ন")
        done.join()

        assertTrue(answer.isSuccess)
        assertEquals("ঠিক আছে।", answer.text)
    }

    @Test
    fun everyDiagnosticNumberIsRecordedForTheDiagnosticBlock() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 3_000, setupTimeoutMillis = 2_000)
        val done = script(
            transport,
            40L to { transport.server(setupComplete) },
            40L to { transport.server(transcription("উত্তর")) },
            40L to { transport.server(generationComplete()) }
        )
        val answer = session.search("প্রশ্ন")
        done.join()

        assertEquals(LiveModels.SEARCH_NATIVE_AUDIO, answer.model)
        assertTrue("setupComplete timing", answer.connectMillis > 0)
        assertTrue("first transcription timing", answer.firstTranscriptMillis >= answer.connectMillis)
        assertTrue("total", answer.elapsedMillis >= answer.firstTranscriptMillis)
        assertEquals(answer.text.length, answer.textLength)
        assertTrue(answer.trace.startsWith("Auto steps:"))
    }

    // ---- 4. failure branches --------------------------------------------------------

    @Test
    fun aPolicyCloseIsReportedWithCodeReasonAndStep() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 3_000, setupTimeoutMillis = 2_000)
        val done = script(transport, 40L to { transport.serverCloses(1008, "quota exceeded") })
        val answer = session.search("প্রশ্ন")
        done.join()

        assertEquals(1008, answer.closeCode)
        assertEquals(GroundedSearchFailure.QUOTA, answer.failure)
        assertEquals(SearchStep.CLOSED.label, answer.step)
        val report = answer.failureReport()
        assertTrue(report.contains("close=1008"))
        assertTrue(report.contains("quota exceeded"))
        assertTrue(report.contains("Step that failed: ${SearchStep.CLOSED.label}"))
        assertTrue(report.contains("Auto steps:"))
        assertTrue(report.contains("key=AIza****"))
    }

    @Test
    fun aServerErrorFrameNamesTheSetupStep() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 3_000, setupTimeoutMillis = 2_000)
        val done = script(
            transport,
            40L to { transport.server("""{"error":{"code":429,"message":"Resource has been exhausted"}}""") }
        )
        val answer = session.search("প্রশ্ন")
        done.join()

        assertEquals(GroundedSearchFailure.HTTP, answer.failure)
        assertEquals(SearchStep.SETUP_COMPLETE.label, answer.step)
        assertTrue("Google's own words must survive into the report", answer.serverMessage.contains("429"))
        assertTrue(answer.detail.contains("429"))
        assertTrue(answer.failureReport().contains("429"))
    }

    @Test
    fun noTranscriptionInsideTheDeadlineIsHonestAboutIt() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 200, setupTimeoutMillis = 2_000)
        val done = script(
            transport,
            40L to { transport.server(setupComplete) }
            // ...and then nothing at all
        )
        val answer = session.search("প্রশ্ন")
        done.join()

        assertEquals(GroundedSearchFailure.TIMEOUT, answer.failure)
        assertEquals(SearchStep.FIRST_TRANSCRIPT.label, answer.step)
        assertTrue(answer.text.isEmpty())
        assertTrue(
            "3.8 must be told the search failed",
            answer.asToolResult().contains("did not finish in time")
        )
    }

    @Test
    fun anEmptyTurnIsNotSuccess() = runBlocking {
        val transport = FakeTransport()
        val session = LiveSearchSession(transport, timeoutMillis = 2_000, setupTimeoutMillis = 1_000)
        val done = script(
            transport,
            40L to { transport.server(setupComplete) },
            40L to { transport.server(generationComplete()) }
        )
        val answer = session.search("প্রশ্ন")
        done.join()

        assertEquals(GroundedSearchFailure.EMPTY, answer.failure)
        assertFalse(answer.isSuccess)
    }

    // ---- 5. the setup frames themselves ---------------------------------------------

    @Test
    fun the38SessionDeclaresSearchWebNonBlockingAndNoBuiltInSearch() {
        val setup = (MiniJson.parse(
            LiveSessionConfig.setupJson(
                model = LiveModels.VOICE,
                voiceName = LiveModels.VOICE_NAME,
                systemInstruction = "x",
                searchTool = LiveSearchTool.FUNCTION_NON_BLOCKING
            )
        ) as Map<*, *>)["setup"] as Map<*, *>

        assertEquals("models/gemini-3.8-live", setup["model"])
        val tool = (setup["tools"] as List<*>)[0] as Map<*, *>
        val declaration = (tool["functionDeclarations"] as List<*>)[0] as Map<*, *>
        assertEquals("search_web", declaration["name"])
        assertEquals("NON_BLOCKING", declaration["behavior"])
        assertFalse(tool.containsKey("googleSearch"))
        // No VAD config is needed here conceptually, but the default LOW is sent because the docs
        // list 3.8 as supporting it — see MicSensitivityTest.
        assertTrue(setup.containsKey("realtimeInputConfig"))
    }

    @Test
    fun theSearchSessionSetupHasSearchOnlyAndAsksForTranscription() {
        val setup = (MiniJson.parse(
            LiveSessionConfig.searchSessionSetupJson(LiveModels.SEARCH_NATIVE_AUDIO, LiveModels.VOICE_NAME)
        ) as Map<*, *>)["setup"] as Map<*, *>

        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", setup["model"])
        assertTrue(setup.containsKey("outputAudioTranscription"))
        val tool = (setup["tools"] as List<*>)[0] as Map<*, *>
        assertTrue(tool.containsKey("googleSearch"))
        assertFalse(MiniJson.encode(setup).contains("functionDeclarations"))
        assertFalse("no microphone config in the search session", setup.containsKey("realtimeInputConfig"))
    }

    @Test
    fun theToolResponseCarriesIdNameAndDocumentedScheduling() {
        val json = LiveToolProtocol.toolResponseJson("abc123", "উত্তর")
        val root = MiniJson.parse(json) as Map<*, *>
        val response = ((root["toolResponse"] as Map<*, *>)["functionResponses"] as List<*>)[0] as Map<*, *>

        assertEquals("abc123", response["id"])
        assertEquals("search_web", response["name"])
        // 0.2.11: WHEN_IDLE, so a result that arrives while the model is answering waits its
        // turn instead of interrupting. Documented enum values: SILENT / WHEN_IDLE / INTERRUPT.
        assertEquals("WHEN_IDLE", response["scheduling"])
        assertNotNull(response["response"])
        assertTrue(
            setOf("SILENT", "WHEN_IDLE", "INTERRUPT")
                .contains(LiveToolProtocol.schedulingFor("x"))
        )
        assertEquals("WHEN_IDLE", LiveToolProtocol.schedulingFor("x"))
    }
}
