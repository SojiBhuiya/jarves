package com.sojibhuiya.jarves.intelligence

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 0.2.15 — the eleven turn rules from the phone report, each one pinned by a test.
 *
 * Everything here is **TESTED-MOCK**: the socket is [ScriptedTransport] and the clock is a counter
 * the test advances, so no key and no device are involved. What a real server actually sends
 * (`turnComplete` vs a silent turn end) is *not* proven here — the phone run has to show it.
 */
class LiveTurnRulesTest {

    // ------------------------------------------------------------------ fake time ------------

    /**
     * Fake clock at 10× speed: the poll loop waits in real milliseconds, so the fake clock has to
     * move on its own while the session is waiting. Everything is daemon, so a failing test cannot
     * keep the suite alive (the 0.2.14 lesson: a stuck fake clock hung the whole harness).
     */
    private class FakeTime {
        private val now = AtomicLong(1_000L)
        private var ticker: Thread? = null

        fun now(): Long = now.get()

        fun advance(millis: Long) {
            now.addAndGet(millis)
        }

        fun start() {
            if (ticker != null) return
            ticker = Thread {
                while (true) {
                    Thread.sleep(2)
                    now.addAndGet(2 * SPEED)
                }
            }.apply { isDaemon = true; start() }
        }

        /** A pause in *fake* time (the ticker moves the clock, this only waits). */
        fun pause(fakeMillis: Long) {
            Thread.sleep((fakeMillis / SPEED).coerceAtLeast(1L))
        }
    }

    // ------------------------------------------------------------------ fake socket ----------

    private class ScriptedTransport(
        private val scripts: List<(FakeSocket) -> Unit>,
        /** Per attempt: does the socket answer the setup frame at all? */
        private val answersSetup: List<Boolean> = listOf(true)
    ) : LiveSearchTransport {
        val opened = AtomicLong(0)
        val sent = CopyOnWriteArrayList<String>()

        override fun open(model: String, listener: LiveSearchListener): LiveSearchConnection {
            val index = opened.getAndIncrement().toInt()
            return FakeSocket(
                listener = listener,
                sent = sent,
                answerSetup = answersSetup.getOrElse(index) { answersSetup.last() },
                script = scripts.getOrElse(index) { scripts.last() }
            )
        }
    }

    private class FakeSocket(
        private val listener: LiveSearchListener,
        private val sent: MutableList<String>,
        /** False simulates the 0.2.14 phone case: the socket never answers the setup frame. */
        private val answerSetup: Boolean,
        private val script: ((FakeSocket) -> Unit)?
    ) : LiveSearchConnection {
        private var started = false

        override fun send(json: String): Boolean {
            sent += json
            // Only the setup frame starts the script: a fake that answered every frame would hide
            // exactly the "question before setupComplete" bug these tests are about.
            if (!started && json.contains("\"setup\"")) {
                started = true
                if (answerSetup) listener.onServerMessage("""{"setupComplete":{}}""")
                script?.let { body ->
                    Thread { runCatching { body(this) } }.apply { isDaemon = true }.start()
                }
            }
            return true
        }

        override fun close(code: Int, reason: String) {}

        fun transcript(text: String) =
            listener.onServerMessage("""{"serverContent":{"outputTranscription":{"text":"$text"}}}""")

        fun audio() =
            listener.onServerMessage(
                """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm","data":"AAAA"}}]}}}"""
            )

        fun turnComplete() = listener.onServerMessage("""{"serverContent":{"turnComplete":true}}""")

        fun generationComplete() =
            listener.onServerMessage("""{"serverContent":{"generationComplete":true}}""")

        fun raw(json: String) = listener.onServerMessage(json)
    }

    private fun session(
        transport: LiveSearchTransport,
        time: FakeTime,
        timeoutMillis: Long = 20_000L,
        setupTimeoutMillis: Long = TurnRules.SETUP_TIMEOUT_MILLIS
    ): LiveSearchSession {
        time.start()
        return LiveSearchSession(
            transport,
            timeoutMillis = timeoutMillis,
            setupTimeoutMillis = setupTimeoutMillis,
            clock = time::now
        )
    }

    // ------------------------------------------------------------- rule 1: one acknowledgement --

    @Test(timeout = 20_000)
    fun `a search gets one short acknowledgement and then silence`() {
        val guard = SearchTurnGuard()
        guard.onToolCall()
        // The model said two chunks while the search was pending — that is still ONE ack.
        guard.onModelSpoke()
        guard.onModelSpoke()
        guard.onAssistantTurnEnded()
        assertFalse("one turn is not a repeat", guard.acknowledgedMoreThanOnce())
        assertEquals(1, guard.acknowledgements)
        assertEquals(2, guard.speechWhilePending)
        assertTrue(guard.violations().isEmpty())
        assertTrue(guard.describe(), guard.describe().startsWith("pending=true, toolCalls=1, acks(this search)=1"))
    }

    @Test(timeout = 20_000)
    fun `a second acknowledgement inside one search is the reported bug and is recorded`() {
        val guard = SearchTurnGuard()
        guard.onToolCall()
        guard.onAssistantTurnEnded()
        guard.onAssistantTurnEnded()
        assertTrue("two acks in one search", guard.acknowledgedMoreThanOnce())
        assertEquals(1, guard.violations().size)
        assertTrue(guard.violations()[0], guard.violations()[0].contains("acknowledged 2 times"))
    }

    @Test(timeout = 20_000)
    fun `after the toolResponse the ack window is closed`() {
        val guard = SearchTurnGuard()
        guard.onToolCall()
        guard.onAssistantTurnEnded()
        guard.onToolResponseSent(failed = false)
        // The answer itself is a turn, but it is no longer an acknowledgement of the search.
        guard.onAssistantTurnEnded()
        assertEquals(1, guard.acknowledgements)
        assertFalse(guard.searchPending)
    }

    // ------------------------------------------------------------------ rule 2: one failure --

    @Test(timeout = 20_000)
    fun `a real failure is announced once and the notice stays off`() {
        val guard = SearchTurnGuard()
        guard.onToolCall()
        guard.onToolResponseSent(failed = true)
        assertEquals(1, guard.failuresAnnounced)
        assertFalse(
            "'could not verify' must not be added on top of a real failure message",
            guard.mayAnnounceCannotVerify(searchedThisTurn = false)
        )
    }

    @Test(timeout = 20_000)
    fun `a successful search never announces a failure`() {
        val guard = SearchTurnGuard()
        guard.onToolCall()
        guard.onToolResponseSent(failed = false)
        assertEquals(0, guard.failuresAnnounced)
        assertFalse(guard.searchPending)
    }

    // --------------------------------------------------- rule 3: no cannot-verify while pending --

    @Test(timeout = 20_000)
    fun `no cannot-verify text is injected while a search is pending`() {
        val pending = NoticePolicy.shouldInject(
            needsLiveSources = true,
            groundingForTurn = false,
            noticeSentForTurn = false,
            searchPending = true,
            searchedThisTurn = true,
            canInjectContext = false
        )
        assertFalse("rule 3: silent while the search runs", pending)
    }

    @Test(timeout = 20_000)
    fun `no cannot-verify text is injected for a turn that already searched`() {
        val afterSearch = NoticePolicy.shouldInject(
            needsLiveSources = true,
            groundingForTurn = false,
            noticeSentForTurn = false,
            searchPending = false,
            searchedThisTurn = true,
            canInjectContext = true
        )
        assertFalse("rule 1: the failure comes from the toolResponse, not from a second notice", afterSearch)
    }

    @Test(timeout = 20_000)
    fun `the notice is allowed only when nothing was searched and nothing is pending`() {
        val allowed = NoticePolicy.shouldInject(
            needsLiveSources = true,
            groundingForTurn = false,
            noticeSentForTurn = false,
            searchPending = false,
            searchedThisTurn = false,
            canInjectContext = true
        )
        assertTrue(allowed)
        assertTrue(
            NoticePolicy.describe(true, false, false, allowed).contains("injected silently")
        )
        assertTrue(
            NoticePolicy.describe(true, true, true, false).contains("suppressed")
        )
    }

    @Test(timeout = 20_000)
    fun `the fallback notice text exists exactly once and is only reachable through the policy`() {
        // Rule 3 asked for the list of places that can say "I could not verify". One text
        // (GroundingPolicy), one decision (NoticePolicy), one exit (injectSilentContext).
        val text = GroundingPolicy.fallbackNotice(groundingAvailable = false)
        assertTrue(text.isNotBlank())
        assertFalse(
            "the placement of that text is the policy's job",
            NoticePolicy.shouldInject(
                needsLiveSources = false,
                groundingForTurn = false,
                noticeSentForTurn = false,
                searchPending = false,
                searchedThisTurn = false,
                canInjectContext = true
            )
        )
    }

    // --------------------------------------------- rule 4: silent injections never start a turn --

    @Test(timeout = 20_000)
    fun `injected context is silent and only a real turn starts generation`() {
        val sameText = "date: 17 November 2026"
        val silent = LiveSessionConfig.silentContextJson(sameText)
        val loud = LiveSessionConfig.turnCompletingClientContentJson(sameText)

        assertTrue(silent, silent.contains("\"turnComplete\":false"))
        assertTrue(loud, loud.contains("\"turnComplete\":true"))
        assertFalse(TurnRules.SILENT_INJECTION_TURN_COMPLETE)
        assertEquals(
            "no other field may differ",
            loud.replace("\"turnComplete\":true", "\"turnComplete\":false"),
            silent
        )
    }

    @Test(timeout = 20_000)
    fun `the search result is still a toolResponse with WHEN_IDLE`() {
        val json = LiveToolProtocol.toolResponseJson(
            "call-1", "Dhaka: 32 degrees", LiveToolProtocol.SCHEDULING_WHEN_IDLE
        )
        assertTrue(json, json.contains("\"toolResponse\""))
        assertTrue(json, json.contains("WHEN_IDLE"))
        assertEquals(
            LiveToolProtocol.SCHEDULING_WHEN_IDLE,
            LiveToolProtocol.schedulingFor("short result")
        )
    }

    // ------------------------------------------------------- rule 5: one turn is one message --

    @Test(timeout = 20_000)
    fun `the fragmented chunks of one turn become one chat message`() {
        // The exact 0.2.14 phone sequence: three bubbles where there should be one.
        val turn = TurnTranscript()
        turn.append("দেখে")
        turn.append(" নিচ্ছি।")
        turn.append("দুঃখিত, আমি এই")
        assertEquals(3, turn.chunks)
        val one = turn.flush()
        assertEquals("দেখে নিচ্ছি।দুঃখিত, আমি এই", one)
        assertNull("a turn is flushed exactly once", turn.flush())
    }

    @Test(timeout = 20_000)
    fun `an empty turn produces no message at all`() {
        val turn = TurnTranscript()
        turn.append("   ")
        assertNull(turn.flush())
        assertEquals(0, turn.chunks)
    }

    // ------------------------------------------------------- rule 7/9: the diagnostics ledger --

    @Test(timeout = 20_000)
    fun `the injection ledger says whether the model reacted within two seconds`() {
        val ledger = InjectionLedger()
        val start = 2_000L
        ledger.onClientContent(now = 2_000L, kind = "time sync", turnComplete = false, bytes = 210)
        ledger.onModelSpoke(2_400L) // heard it? the model spoke 400 ms later
        ledger.onClientContent(now = 8_000L, kind = "no-sources notice", turnComplete = false, bytes = 120)
        ledger.onToolResponse(now = 9_000L, scheduling = "WHEN_IDLE", bytes = 300)

        val lines = ledger.lines(sessionStartMillis = start)
        assertEquals(3, lines.size)
        assertEquals("t+0.0s clientContent time sync turnComplete=false 210B -> model spoke t+0.4s", lines[0])
        assertEquals("t+6.0s clientContent no-sources notice turnComplete=false 120B -> no speech within 2s", lines[1])
        assertEquals("t+7.0s toolResponse scheduling=WHEN_IDLE 300B -> no speech within 2s", lines[2])
        assertTrue(ledger.text(start).contains(" | "))
    }

    @Test(timeout = 20_000)
    fun `the server message log keeps the last hundred messages with t plus seconds`() {
        val log = ServerMessageLog()
        val start = 10_000L
        log.record(start, """{"setupComplete":{}}""")
        log.record(start + 4_000L, """{"serverContent":{"outputTranscription":{"text":"আজ"}}}""")
        log.record(start + 4_200L, """{"serverContent":{"turnComplete":true}}""")
        log.record(start + 4_300L, """{"usageMetadata":{"totalTokenCount":12}}""")
        repeat(120) { log.record(start + 5_000L + it, """{"serverContent":{"modelTurn":{}}}""") }

        // 0.2.16 (task 6): modelTurn frames are COUNTED, not listed — that is what keeps the block
        // under 6,000 characters. The non-chatty frames are still listed, oldest dropped first.
        assertEquals("modelTurn frames are counted", 120, log.modelTurnCount)
        assertEquals("transcription frames are counted", 1, log.transcriptionCount)
        // one setupComplete + one turnComplete are listed; the transcription and the modelTurn
        // frames are counted. (usageMetadata is counted as "other" but classified as usage — it is
        // in the list, which is why the number is 3.)
        assertEquals("only the other frames are listed", 3, log.size)
        assertEquals(0, log.dropped)
        val tail = log.lines(start)
        assertTrue("the three listed lines carry their own t+", tail[0].startsWith("t+0.0s setupComplete"))
        assertTrue(
            ServerMessageLog.classify("""{"serverContent":{"generationComplete":true}}""") ==
                "serverContent.generationComplete"
        )
        assertTrue(
            ServerMessageLog.classify("""{"serverContent":{"outputTranscription":{"text":"x"}}}""") ==
                "serverContent.outputTranscription"
        )
        assertTrue(ServerMessageLog.classify("""{"error":{"code":429}}""") == "error")
    }

    @Test(timeout = 20_000)
    fun `the phone timeline can be reproduced from the log`() {
        // 0.2.14 phone measurement: setup sent -> socket open 3171ms -> setupComplete 4004ms ->
        // question 4005ms -> first transcription 6749ms -> answer -> nothing until the 20 s cap.
        val log = ServerMessageLog()
        val start = 0L
        log.record(3_171L, """{"setupComplete":{"ignored":false}}""")
        log.record(4_004L, """{"setupComplete":{}}""")
        log.record(6_749L, """{"serverContent":{"outputTranscription":{"text":"আজ ঢাকায়"}}}""")
        log.record(6_900L, """{"serverContent":{"outputTranscription":{"text":" ৩২ ডিগ্রি।"}}}""")
        log.record(20_001L, """{"usageMetadata":{"totalTokenCount":99}}""")

        val text = log.text(start)
        assertTrue(text, text.contains("t+3.2s"))
        // 0.2.16 (task 6): the transcription frames are summarised as a count, the completion flags
        // are still listed one by one — the missing turnComplete is therefore still visible.
        assertTrue("rule 9 + task 6: transcriptions are counted", text.contains("transcr x2"))
        assertFalse("no completion flag arrived in the measured run", text.contains("turnComplete"))
        // the gap between the two transcription chunks (both counted) is still measurable
        assertEquals(2, log.transcriptionCount)
        assertEquals(0L, log.firstTranscriptionAtMillis - 6_749L)
    }

    // ---------------------------------------------- rules 10/11: how a search is allowed to end --

    @Test(timeout = 20_000)
    fun `turnComplete ends the search with its own label`() = runBlocking {
        val time = FakeTime()
        val transport = ScriptedTransport(
            listOf { socket ->
                socket.transcript("এক উত্তর।")
                socket.turnComplete()
            }
        )
        val answer = session(transport, time).search("প্রশ্ন")
        assertTrue(answer.isSuccess)
        assertEquals("এক উত্তর।", answer.text)
        assertEquals(SearchEndRule.TURN_COMPLETE.label, answer.endRule)
        assertTrue(answer.trace, answer.trace.contains("ended=turnComplete"))
    }

    @Test(timeout = 20_000)
    fun `generationComplete ends the search with its own label`() = runBlocking {
        val time = FakeTime()
        val transport = ScriptedTransport(
            listOf { socket ->
                socket.transcript("আরেক উত্তর।")
                socket.generationComplete()
            }
        )
        val answer = session(transport, time).search("প্রশ্ন")
        assertEquals("আরেক উত্তর।", answer.text)
        assertEquals(SearchEndRule.GENERATION_COMPLETE.label, answer.endRule)
    }

    @Test(timeout = 20_000)
    fun `when no completion signal ever comes the quiet gap ends the search`() = runBlocking {
        // The 0.2.14 phone bug: a short answer, then nothing at all until the 20 s cap.
        val time = FakeTime()
        val transport = ScriptedTransport(
            listOf { socket ->
                socket.transcript("আজ ঢাকায় ৩২ ডিগ্রি রোদ।")
                // ... and no turnComplete, no generationComplete: the server just stops.
            }
        )
        val answer = session(transport, time).search("আজ ঢাকার আবহাওয়া?")

        assertTrue("rule 11: the collected text is a normal result", answer.isSuccess)
        assertEquals("আজ ঢাকায় ৩২ ডিগ্রি রোদ।", answer.text)
        assertEquals(SearchEndRule.QUIET_GAP.label, answer.endRule)
        assertTrue(answer.trace, answer.trace.contains("ended=" + SearchEndRule.QUIET_GAP.label))
        assertTrue(answer.trace, answer.trace.contains("quiet gap 2.5s"))
        assertTrue(answer.trace, answer.trace.contains("attempts=1"))
        assertTrue(
            "far below the 20 s cap: ${answer.elapsedMillis}ms",
            answer.elapsedMillis < TurnRules.TIMEOUT_CAP_MILLIS
        )
    }

    @Test(timeout = 20_000)
    fun `a slow but successful search never produces an apology`() = runBlocking {
        // The user's own sequence: 4 s setup, then the answer arrives with pauses.
        val time = FakeTime()
        val transport = ScriptedTransport(
            listOf { socket ->
                time.pause(4_000) // slow socket: setupComplete took 4 s on the phone
                socket.transcript("ঢাকায় এখন ৩২ ডিগ্রি।")
                time.pause(1_000)
                socket.transcript(" আকাশ পরিষ্কার।")
                socket.turnComplete()
            }
        )
        val answer = session(transport, time).search("আজ ঢাকার তাপমাত্রা কত?")
        assertTrue(answer.isSuccess)
        assertEquals("ঢাকায় এখন ৩২ ডিগ্রি। আকাশ পরিষ্কার।", answer.text)
        assertEquals(SearchEndRule.TURN_COMPLETE.label, answer.endRule)
        assertFalse(
            "no apology may appear in a successful search: ${answer.text}",
            answer.text.contains("যাচাই করতে পারিনি")
        )
        assertFalse(answer.text.contains("দুঃখিত"))
    }

    @Test(timeout = 20_000)
    fun `the quiet gap does not cut a slow answer in half`() = runBlocking {
        // Three chunks with 1.5 s (fake) pauses — below the 2.5 s quiet gap, so nothing is lost.
        val time = FakeTime()
        val transport = ScriptedTransport(
            listOf { socket ->
                socket.transcript("প্রথম বাক্য।")
                time.pause(1_500)
                socket.transcript(" দ্বিতীয় বাক্য।")
                time.pause(1_500)
                socket.transcript(" তৃতীয় বাক্য।")
            }
        )
        val answer = session(transport, time).search("ধীর উত্তর")
        assertEquals("প্রথম বাক্য। দ্বিতীয় বাক্য। তৃতীয় বাক্য।", answer.text)
        assertEquals(SearchEndRule.QUIET_GAP.label, answer.endRule)
        assertTrue("the three chunks are one result", answer.text.contains("তৃতীয় বাক্য।"))
    }

    @Test(timeout = 20_000)
    fun `a search that only ever sends audio still ends at the 20 s cap`() = runBlocking {
        // No transcription and no completion flag: there is nothing to return, so the cap applies.
        val time = FakeTime()
        val transport = ScriptedTransport(
            listOf { socket ->
                repeat(30) {
                    socket.audio()
                    time.pause(1_000) // keeps the quiet gap from firing: the model is "speaking"
                }
            }
        )
        val answer = session(transport, time, timeoutMillis = 20_000L).search("শুধু অডিও")
        assertFalse("no text means no result", answer.isSuccess)
        assertEquals(SearchEndRule.TIMEOUT.label, answer.endRule)
    }

    // ---------------------------------------------------------- rule 6: setup wait and one retry --

    @Test(timeout = 20_000)
    fun `a setup timeout is retried once and the attempt count is visible`() = runBlocking {
        val time = FakeTime()
        val transport = ScriptedTransport(
            scripts = listOf(
                { _ -> /* never answers the setup: the 0.2.14 phone case */ },
                { socket ->
                    socket.transcript("দ্বিতীয় চেষ্টায় উত্তর।")
                    socket.turnComplete()
                }
            ),
            answersSetup = listOf(false, true)
        )
        val answer = session(transport, time, setupTimeoutMillis = 300L).search("প্রশ্ন")
        assertTrue("the retry must succeed", answer.isSuccess)
        assertEquals("দ্বিতীয় চেষ্টায় উত্তর।", answer.text)
        assertEquals(2, answer.attempts)
        assertEquals(2L, transport.opened.get())
        assertTrue(answer.trace, answer.trace.contains("retried once"))
        assertTrue("rule 6/7: both attempts are in the metrics", answer.trace.contains("attempts=2"))
        assertEquals("15 s is the new setup wait", 15_000L, TurnRules.SETUP_TIMEOUT_MILLIS)
        assertEquals("exactly one retry", 1, TurnRules.SETUP_RETRIES)
    }

    @Test(timeout = 20_000)
    fun `a hard server error is not retried`() = runBlocking {
        val time = FakeTime()
        val transport = ScriptedTransport(
            listOf { socket ->
                socket.raw("""{"error":{"code":400,"message":"API key not valid"}}""")
            }
        )
        val answer = session(transport, time, setupTimeoutMillis = 300L).search("প্রশ্ন")
        assertFalse(answer.isSuccess)
        assertEquals(GroundedSearchFailure.HTTP, answer.failure)
        assertEquals("an error frame is not a slow socket", 1, answer.attempts)
        assertEquals(1L, transport.opened.get())
    }

    private companion object {
        /** Fake clock speed: 1 real ms = 10 fake ms. */
        const val SPEED = 10L
    }
}
