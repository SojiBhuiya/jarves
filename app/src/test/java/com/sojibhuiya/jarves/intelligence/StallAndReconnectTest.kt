package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.16 (tasks A, B, C and E) — the stall tolerance, the fresh-session fallback, the network-change
 * rule and the compact diagnostics.
 *
 * Everything here is **TESTED-MOCK**: fake clocks, no socket, no key, no device. What the real server
 * does with a fresh session while the old one is still held is **NOT-RUN** — the docs do not say.
 */
class StallAndReconnectTest {

    private fun buffer(bytes: Int, chunks: Int): ArrayDeque<ByteArray> {
        val queue = ArrayDeque<ByteArray>()
        repeat(chunks) { queue.addLast(ByteArray(bytes)) }
        return queue
    }

    // ------------------------------------------------------- task B: stall tolerance ----------

    @Test(timeout = 10_000)
    fun `a five second stall that recovers by itself does not end the session`() {
        val stall = StallTolerance()
        var now = 0L
        // The queue is backed up but the server keeps talking, so this is never "dead".
        var verdict = StallTolerance.Verdict.HEALTHY
        for (second in 0 until 5) {
            now = second * 1_000L
            verdict = stall.onSample(
                now = now,
                queueSize = 70_000L,
                sinceLastServerMessage = 500L,
                sessionStart = 0L
            )
            assertTrue("still tolerated at ${now}ms", verdict == StallTolerance.Verdict.STALLED)
        }
        assertEquals(1, stall.stalls)
        assertTrue(stall.stalling)
        // The queue drains: no reconnect, and the diagnostics say it recovered by itself.
        verdict = stall.onSample(now = 5_500L, queueSize = 0L, sinceLastServerMessage = 200L, sessionStart = 0L)
        assertEquals(StallTolerance.Verdict.HEALTHY, verdict)
        assertEquals(1, stall.recovered)
        assertFalse("no stall is open any more", stall.stalling)
        // The stall ran from the first backed-up sample (t+0) until the queue drained (t+5.5 s),
        // but the loop above only sampled up to t+4 s; the drain is what closes it.
        assertTrue("longest stall ${stall.longestStallMillis}ms", stall.longestStallMillis >= 4_000L)
        assertTrue(
            "the line says both: how long it ran (5.5 s, to the drain) and the last sampled length",
            stall.describe(5_500L).contains("recovered by itself after 5500ms")
        )
        assertTrue(
            "the timeline says the stall ended without a reconnect",
            stall.timeline().any { it.contains("no reconnect") }
        )
    }

    @Test(timeout = 10_000)
    fun `a fifteen second stall without server messages is declared dead`() {
        val stall = StallTolerance()
        stall.onSample(now = 0L, queueSize = 70_000L, sinceLastServerMessage = 0L, sessionStart = 0L)
        var verdict = StallTolerance.Verdict.STALLED
        var now = 0L
        while (now < 15_000L) {
            now += 500L
            verdict = stall.onSample(
                now = now,
                queueSize = 70_000L,
                sinceLastServerMessage = now, // the server said nothing at all
                sessionStart = 0L
            )
        }
        assertEquals(
            "12 s without write progress and without a server message is the documented-by-policy limit",
            StallTolerance.Verdict.DEAD,
            verdict
        )
        assertEquals(12000L, StallTolerance.DEAD_STALL_MILLIS)
        stall.onSessionEnded(now, 0L, "stall rule")
        assertTrue(stall.describe(now), stall.describe(now).contains("ended by stall rule"))
    }

    @Test(timeout = 10_000)
    fun `a backed up queue with recent server messages is never declared dead`() {
        val stall = StallTolerance()
        // 20 s of a full queue, but the server keeps sending content: the connection is alive.
        for (second in 0..20) {
            val verdict = stall.onSample(
                now = second * 1_000L,
                queueSize = 200_000L,
                sinceLastServerMessage = 300L,
                sessionStart = 0L
            )
            assertTrue("alive at ${second}s", verdict != StallTolerance.Verdict.DEAD)
        }
    }

    @Test(timeout = 10_000)
    fun `dropping the oldest audio keeps the buffer under the cap`() {
        val stall = StallTolerance()
        val queue = buffer(bytes = 640, chunks = 400) // 256,000 B of audio
        val dropped = stall.trimNow(queue)
        var remaining = 0
        for (chunk in queue) remaining += chunk.size
        assertTrue("kept $remaining B", remaining <= StallTolerance.KEEP_BYTES)
        assertEquals("256000 - ${remaining}", 256_000L - remaining, dropped)
        assertTrue("chunks were dropped, not bytes in the middle of a chunk", stall.droppedChunks > 0)
        assertEquals(dropped, stall.droppedBytes)
        // A second trim with the same buffer is a no-op: nothing left to drop.
        assertEquals(0L, stall.trimNow(queue))
    }

    @Test(timeout = 10_000)
    fun `a short queue is left alone`() {
        val stall = StallTolerance()
        val queue = buffer(bytes = 640, chunks = 5) // 3,200 B
        assertEquals(0L, stall.trimNow(queue))
        assertEquals(5, queue.size)
        assertEquals(0L, stall.droppedBytes)
    }

    @Test(timeout = 10_000)
    fun `the calm status line appears only after about three seconds`() {
        val stall = StallTolerance()
        stall.onSample(now = 0L, queueSize = 70_000L, sinceLastServerMessage = 100L, sessionStart = 0L)
        // A new sample arrives with every chunk; the stall started at 0, so at t+1 s it is still a
        // normal hiccup and no line is due.
        stall.onSample(now = 1_000L, queueSize = 70_000L, sinceLastServerMessage = 100L, sessionStart = 0L)
        assertFalse("no line during a normal hiccup", stall.shouldShowStatus(1_000L))
        assertFalse(stall.shouldShowStatus(2_900L))
        assertTrue("shown once the stall is real", stall.shouldShowStatus(3_000L))
        assertFalse("and only once", stall.shouldShowStatus(4_000L))
    }

    @Test(timeout = 10_000)
    fun `the old hard limit is now only the trimming threshold`() {
        // 0.2.15: `queue too large` at 64,151 B tore the socket down. 0.2.16: same number, no teardown.
        assertEquals(64_000L, StallTolerance.SOFT_LIMIT_BYTES)
        assertEquals(32_000L, StallTolerance.KEEP_BYTES)
        val stall = StallTolerance()
        val verdict = stall.onSample(
            now = 0L, queueSize = 64_151L, sinceLastServerMessage = 7_500L, sessionStart = 0L
        )
        assertEquals("a backed-up queue is a stall, not a failure", StallTolerance.Verdict.STALLED, verdict)
    }

    // --------------------------------------------------- task 2: fresh-session fallback ------

    @Test(timeout = 10_000)
    fun `1008 for twelve seconds then a fresh session succeeds`() {
        val fallback = FreshSessionFallback()
        var now = 0L
        // Handle-based refusals inside the 12 s window: one at 0 s, one at 3 s, one at 8 s.
        var next = fallback.onAlreadyConnected(now, hadHandle = true, sessionStart = 0L)
        assertEquals(FreshSessionFallback.Step.RETRY_FRESH, next.step)
        assertTrue("still with the handle", next.hadHandle)
        now += 3_000L
        next = fallback.onAlreadyConnected(now, hadHandle = true, sessionStart = 0L)
        now += 5_000L
        next = fallback.onAlreadyConnected(now, hadHandle = true, sessionStart = 0L)
        now += 4_000L // t+12 s: the window is spent
        next = fallback.onAlreadyConnected(now, hadHandle = true, sessionStart = 0L)
        // 0.2.15's log would have called t+12 s another handle attempt; 0.2.16 switches to a fresh
        // session on this very refusal.
        assertEquals("the next connection is FRESH", false, next.hadHandle)
        assertTrue(
            "the switch must be recorded: " + fallback.timeline().joinToString(" | "),
            fallback.timeline().any { it.contains("handle phase spent") }
        )
        assertEquals("four refusals inside the 12 s window (the last one switches)", 4, fallback.handleAttempts)
        assertTrue(fallback.inFreshPhase)
        assertEquals(1, fallback.freshSessionsOpened)
        assertTrue("the calm line is due", fallback.contextLostAnnounced)
        assertEquals("সংযোগ ফিরেছে, তবে আগের কথার প্রসঙ্গ হারিয়েছি", FreshSessionFallback.STATUS_CONTEXT_LOST)
        // The fresh session connects: the state machine resets and the user was told once.
        fallback.onFreshSessionConnected(now, 0L)
        assertFalse(fallback.active)
        fallback.reset()
        assertEquals(FreshSessionFallback.Phase.HANDLE, fallback.phase)
    }

    @Test(timeout = 10_000)
    fun `a fresh session rejected for ninety seconds ends with one clear message`() {
        val fallback = FreshSessionFallback()
        var now = 0L
        // Burn the handle phase.
        fallback.onAlreadyConnected(now, hadHandle = true, sessionStart = 0L)
        now = 3_000L
        fallback.onAlreadyConnected(now, hadHandle = true, sessionStart = 0L)
        now = 8_000L
        fallback.onAlreadyConnected(now, hadHandle = true, sessionStart = 0L)
        now = 12_000L
        var next = fallback.onAlreadyConnected(now, hadHandle = true, sessionStart = 0L)
        assertFalse("fresh from here on: " + fallback.timeline().joinToString(" | "), next.hadHandle)

        var step = FreshSessionFallback.Step.RETRY_FRESH
        var guard = 0
        while (step == FreshSessionFallback.Step.RETRY_FRESH && guard < 100) {
            guard += 1
            next = fallback.onAlreadyConnected(now, hadHandle = false, sessionStart = 0L)
            step = next.step
            now += next.delayMillis
        }
        assertEquals("giving up is the only end", FreshSessionFallback.Step.GIVE_UP, step)
        assertEquals(FreshSessionFallback.Phase.GIVEN_UP, fallback.phase)
        assertTrue("the budget is 90 s of waiting, measured from the first 1008: $now", now >= 84_000L)
        assertTrue("and not much more: $now", now <= 100_000L)
        assertTrue(fallback.freshAttempts >= 4)
        assertTrue(
            "one clear Bengali message",
            FreshSessionFallback.MESSAGE_GAVE_UP.contains("৯০ সেকেন্ড") &&
                FreshSessionFallback.MESSAGE_GAVE_UP.contains("মাইক বন্ধ")
        )
        assertEquals(90_000L, FreshSessionFallback.FRESH_PHASE_BUDGET_MILLIS)
        assertEquals(12_000L, FreshSessionFallback.HANDLE_PHASE_MILLIS)
    }

    @Test(timeout = 10_000)
    fun `the fallback never opens a second fresh session in one episode`() {
        val fallback = FreshSessionFallback()
        var now = 0L
        repeat(4) {
            fallback.onAlreadyConnected(now, hadHandle = true, sessionStart = 0L)
            now += 4_000L
        }
        assertEquals(1, fallback.freshSessionsOpened)
        // Every later refusal is a fresh retry, never another handle attempt.
        val next = fallback.onAlreadyConnected(now, hadHandle = false, sessionStart = 0L)
        assertFalse(next.hadHandle)
    }

    // ------------------------------------------------ task C: what counts as a change ---------

    @Test(timeout = 10_000)
    fun `a capability-only callback is not a network change`() {
        val timeline = SessionTimeline()
        timeline.start(1_000_000L)
        // Same network object, same transport: signal strength / bandwidth estimate updates.
        repeat(5) {
            val changed = timeline.onNetworkEvent(
                now = 1_000_000L + 1_000L * it,
                kind = "capabilities",
                newNetworkId = 42L,
                previousNetworkId = 42L,
                newType = "mobile",
                previousType = "mobile"
            )
            assertFalse("capability update $it must not count", changed)
        }
        assertEquals(0, timeline.networkChanges)
        assertEquals(5, timeline.capabilityUpdates)
        assertTrue(timeline.summary(), timeline.summary().contains("capability updates: 5"))
    }

    @Test(timeout = 10_000)
    fun `a new default network and a transport switch do count`() {
        val timeline = SessionTimeline()
        timeline.start(0L)
        assertTrue(
            "wifi -> mobile",
            timeline.onNetworkEvent(1_000L, "available", 7L, 7L, "mobile", "wifi")
        )
        assertTrue(
            "a different network object",
            timeline.onNetworkEvent(2_000L, "available", 8L, 7L, "mobile", "mobile")
        )
        assertEquals(2, timeline.networkChanges)
        assertTrue(timeline.lines()[0], timeline.lines()[0].contains("transport wifi -> mobile"))
        assertTrue(timeline.lines()[1], timeline.lines()[1].contains("new default network"))
    }

    @Test(timeout = 10_000)
    fun `a real default-network loss is a loss and starts the wait`() {
        val timeline = SessionTimeline()
        timeline.start(0L)
        assertTrue(timeline.onNetworkEvent(5_000L, "lost", -1L, 9L, "none", "mobile"))
        assertEquals(1, timeline.networkLosses)
        assertEquals(1, timeline.networkChanges)
        assertTrue(timeline.lines()[0], timeline.lines()[0].contains("onLost"))

        // The coordinator is what turns that into "wait, do not spend an attempt".
        val plan = ReconnectCoordinator()
        plan.startTimeline(0L)
        val action = plan.onUnexpectedFailure(
            now = 5_000L,
            network = NetworkSnapshot(available = false, type = NetworkSnapshot.TYPE_NONE),
            reason = "socket failed while the network was lost"
        )
        assertTrue(action is ReconnectAction.WaitForNetwork)
        assertEquals("no attempt is spent while there is no network", 0, plan.attemptsUsed)
    }

    // ---------------------------------------------- task 6: compact diagnostics length -------

    @Test(timeout = 10_000)
    fun `the compact diagnostics stays under six thousand characters`() {
        val searches = (1..40).map { index ->
            CompactDiagnostics.SearchSummary(
                index = index,
                modelTurnMessages = 11 + index,
                transcriptionMessages = 30 + index,
                firstTranscriptionMillis = 5_100L + index * 100L,
                lastMessageBeforeCompletionMillis = 8_700L + index * 100L,
                endRule = "generationComplete",
                totalMillis = 8_800L + index * 150L,
                words = 14 + index,
                transcriptToCompleteMillis = 3_600L
            )
        }
        val head = (1..12).map { "head line $it — some diagnostics text that a person reads on a phone" }
        val tails = listOf(
            "Network timeline" to (1..80).map { "t+${it}.0s network change (new default network, mobile)" },
            "Stall timeline" to (1..40).map { "t+${it}.0s stall started at 70000B" },
            "Fresh-session timeline" to (1..20).map { "t+${it}.0s fresh session refused (#$it) -> retry" }
        )
        val block = CompactDiagnostics.block(head, searches, tails)
        assertTrue("length ${block.length}", block.length <= CompactDiagnostics.MAX_CHARS)
        assertTrue(block.trim().isNotEmpty())
        assertTrue("the newest search survives", block.contains("search#40"))
        assertTrue("a line is never cut in half", block.lines().none { it.endsWith("…") })
    }

    @Test(timeout = 10_000)
    fun `a search line shows only counts and times`() {
        val summary = CompactDiagnostics.SearchSummary(
            index = 3,
            modelTurnMessages = 11,
            transcriptionMessages = 41,
            firstTranscriptionMillis = 5_100L,
            lastMessageBeforeCompletionMillis = 8_700L,
            endRule = SearchEndRule.GENERATION_COMPLETE.label,
            totalMillis = 8_800L,
            words = 14,
            transcriptToCompleteMillis = 3_600L
        )
        val line = CompactDiagnostics.searchLine(summary)
        assertTrue(line, line.startsWith("search#3 total=8.8s"))
        assertTrue(line, line.contains("modelTurn=11"))
        assertTrue(line, line.contains("transcr=41"))
        assertTrue(line, line.contains("first transcript=5.1s"))
        assertTrue(line, line.contains("ended=generationComplete"))
        assertTrue(line, line.contains("14 words"))
        assertTrue("task 4: transcript -> generationComplete time", line.contains("transcr->complete=3.6s"))
        assertFalse("no per-frame dump", line.contains("modelTurn {"))
    }

    @Test(timeout = 10_000)
    fun `the counter counts chunks and never keeps the frames`() {
        val counter = CompactDiagnostics.Counter()
        var now = 1_000L
        repeat(11) {
            counter.onMessage(now, "serverContent.modelTurn")
            now += 100L
        }
        repeat(4) {
            counter.onMessage(now, "serverContent.outputTranscription", "শব্দ")
            now += 100L
        }
        counter.onMessage(now, "serverContent.generationComplete")
        val summary = counter.summary(1, "generationComplete", 9_000L, 14, sessionStart = 1_000L)
        assertEquals(11, summary.modelTurnMessages)
        assertEquals(4, summary.transcriptionMessages)
        assertTrue(summary.lastMessageBeforeCompletionMillis > 0L)
        assertTrue(summary.transcriptToCompleteMillis > 0L)
        counter.reset()
        assertEquals(0, counter.modelTurn)
        assertEquals(0, counter.transcriptions)
    }

    // ---------------------------------------------- task 5: per-search violation counter -----

    @Test(timeout = 10_000)
    fun `five sequential searches report zero violations`() {
        val guard = SearchTurnGuard()
        for (call in 1..5) {
            // The engine calls startSearch(callId) on every toolCall (0.2.16, task 5).
            guard.startSearch("call-$call")
            guard.onToolCall()
            guard.onModelSpoke() // the one acknowledgement
            guard.onAssistantTurnEnded()
            guard.onToolResponseSent(failed = false)
            assertFalse("call-$call must not look like a repeat", guard.acknowledgedMoreThanOnce())
            assertTrue(guard.violations().isEmpty())
            assertEquals("the counter is per search", 1, guard.acknowledgements)
        }
        assertEquals(5, guard.toolCalls)
        assertTrue(guard.violations().isEmpty())
        assertFalse(
            "0.2.15's 'acknowledged 2 times … 5 times' can no longer happen",
            guard.describe().contains("violations=")
        )
    }

    @Test(timeout = 10_000)
    fun `two acknowledgements inside ONE search are still a violation`() {
        val guard = SearchTurnGuard()
        guard.startSearch("call-1")
        guard.onToolCall()
        guard.onAssistantTurnEnded()
        guard.onAssistantTurnEnded()
        assertTrue(guard.acknowledgedMoreThanOnce())
        assertEquals(1, guard.violations().size)
        // … and the next search starts clean.
        guard.startSearch("call-2")
        guard.onToolCall()
        guard.onAssistantTurnEnded()
        assertFalse(guard.acknowledgedMoreThanOnce())
        assertEquals("the violation of the previous search is still recorded", 1, guard.violations().size)
    }

    // ------------------------------------------- task 4: the spoken copy is sentence cut -----

    @Test(timeout = 10_000)
    fun `the toolResponse text is cut at a sentence boundary and never mid-sentence`() {
        val long = "ঢাকায় এখন ৩২ ডিগ্রি সেলসিয়াস। আকাশ পরিষ্কার আছে। বাতাসে আর্দ্রতা ৬৫ শতাংশ। " +
            "আগামীকাল বৃষ্টির সম্ভাবনা আছে।"
        val cut = SearchAnswerRules.truncateForToolResponse(long)
        val sentences = SearchAnswerRules.splitSentences(cut)
        assertTrue("at most two sentences: $cut", sentences.size <= SearchAnswerRules.TOOL_MAX_SENTENCES)
        assertTrue("about 35 words at most: $cut", SearchAnswerRules.wordCount(cut) <= SearchAnswerRules.TOOL_MAX_WORDS)
        assertTrue("it ends on a terminator, not mid-words: $cut", cut.endsWith("।"))
        assertTrue("the first fact survives", cut.contains("৩২ ডিগ্রি"))
        assertFalse("the trailing sentences are what goes", cut.contains("আগামীকাল"))
    }

    @Test(timeout = 10_000)
    fun `one very long sentence is kept whole rather than chopped`() {
        val oneSentence = (1..60).joinToString(" ") { "শব্দ$it" } + "।"
        val cut = SearchAnswerRules.truncateForToolResponse(oneSentence)
        assertEquals("a half sentence read aloud is worse than a long one", oneSentence, cut)
        assertEquals(1, SearchAnswerRules.splitSentences(cut).size)
    }

    @Test(timeout = 10_000)
    fun `the chat keeps the full answer while the voice gets the short copy`() {
        val item = SearchResultItem(
            callId = "c1",
            question = "আজ ঢাকার আবহাওয়া?",
            answer = "ঢাকায় এখন ৩২ ডিগ্রি। আকাশ পরিষ্কার। বাতাসে আর্দ্রতা ৬৫ শতাংশ। বৃষ্টি নেই।",
            sourceName = "The Daily Star"
        )
        val spoken = SearchResultText.spokenText(item)
        assertTrue(SearchAnswerRules.wordCount(spoken) <= SearchAnswerRules.TOOL_MAX_WORDS)
        val tool = SearchResultText.toolResponse(item)
        assertTrue(tool, tool.contains("Answer: " + spoken))
        assertTrue("the source name still travels with it", tool.contains("Source: The Daily Star"))
        val chat = SearchResultText.chatLine(item)
        assertTrue("the chat keeps everything", chat.contains("বৃষ্টি নেই"))
        assertTrue(chat.contains("The Daily Star"))
    }

    @Test(timeout = 10_000)
    fun `a failed search never gets a truncated spoken answer`() {
        val item = SearchResultItem(callId = "c2", question = "q", answer = "x", success = false)
        assertEquals("", SearchResultText.spokenText(item))
        assertTrue(SearchResultText.toolResponse(item).contains("failed"))
    }
}
