package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Callback-wiring tests.
 *
 * The previous suite proved the *decision table* and still the fix failed on-device, because the
 * decision was never reached: OkHttp does not call `onClosed` for a server-initiated close. These
 * tests drive the actual entry points that `onClosing`, `onClosed` and `onFailure` call, so the
 * wiring itself is covered.
 */
class LiveCloseFlowTest {

    /** Exactly the reason text Gemini sent on the device. */
    private val spentQuota =
        "You exceeded your current quota, please check your plan and billing details. " +
            "For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits"

    private class Recorder {
        val echoes = mutableListOf<Pair<Int, String>>()
        var retries = 0
        var retrySources = mutableListOf<String>()
        val failures = mutableListOf<Pair<Int, String>>()
        var stopped = 0
    }

    private fun flowOn(r: Recorder) = LiveCloseFlow(
        echoClose = { code, reason -> r.echoes += code to reason },
        retryWithoutSearch = { source -> r.retries += 1; r.retrySources += source },
        reportFailure = { code, reason -> r.failures += code to reason },
        reportStopped = { r.stopped += 1 }
    )

    // ---- 1. the echo ---------------------------------------------------------------------

    @Test
    fun serverInitiatedCloseIsEchoedExactlyOnce() {
        val r = Recorder()
        flowOn(r).handleCloseFrame(1011, spentQuota, hadSearchTool = true, setupComplete = false, fallbackUsed = false)

        assertEquals("the close frame must be echoed once", 1, r.echoes.size)
        assertEquals(1011, r.echoes.single().first)
    }

    @Test
    fun echoHappensBeforeTheDecisionSoNothingWaitsForOnClosed() {
        val r = Recorder()
        val flow = LiveCloseFlow(
            echoClose = { _, _ -> r.echoes += 1011 to "echo" },
            retryWithoutSearch = {
                // When the retry starts, the echo must already have happened: this is what
                // unblocks OkHttp's closing handshake.
                assertEquals("echo must precede the retry", 1, r.echoes.size)
                r.retries += 1
            },
            reportFailure = { _, _ -> fail("should retry") },
            reportStopped = { fail("should retry") }
        )
        flow.handleCloseFrame(1011, spentQuota, true, false, false)
        assertEquals(1, r.retries)
    }

    // ---- 2. the decision runs on the onClosing path ---------------------------------------

    @Test
    fun retryRunsOnTheOnClosingPath() {
        val r = Recorder()
        // handleCloseFrame IS the onClosing entry point.
        flowOn(r).handleCloseFrame(1011, spentQuota, hadSearchTool = true, setupComplete = false, fallbackUsed = false)

        assertEquals(1, r.retries)
        assertEquals(emptyList<Pair<Int, String>>(), r.failures)
        assertEquals(0, r.stopped)
    }

    // ---- 3. onClosed afterwards is a no-op ------------------------------------------------

    @Test
    fun onClosedDoesNotRunTheDecisionASecondTime() {
        val r = Recorder()
        val flow = flowOn(r)
        flow.handleCloseFrame(1011, spentQuota, true, false, false)   // onClosing
        flow.handleCloseFrame(1011, spentQuota, true, false, false)   // onClosed (same socket)
        flow.handleCloseFrame(1011, spentQuota, true, false, false)   // any late duplicate

        assertEquals("retry must happen exactly once", 1, r.retries)
        assertEquals("echo must happen exactly once", 1, r.echoes.size)
        assertTrue(flow.wasHandled())
    }

    // ---- 4. requirement 7: the retry payload has no Google Search -------------------------

    @Test
    fun retrySendsASetupPayloadWithoutGoogleSearch() {
        var searchTool = LiveSearchTool.FUNCTION_NON_BLOCKING
        val payloads = mutableListOf<String>()
        fun setup() = LiveSessionConfig.setupJson(
            model = "gemini-3.8-live",
            voiceName = "Aoede",
            systemInstruction = LiveSessionConfig.systemInstruction(
                "Jarves",
                TimeContext.snapshot(java.time.Instant.parse("2026-11-09T10:15:00Z"), java.time.ZoneId.of("Europe/Paris")),
                searchTool = searchTool
            ),
            searchTool = searchTool
        )

        val flow = LiveCloseFlow(
            echoClose = { _, _ -> },
            retryWithoutSearch = {
                // exactly what GeminiLiveVoice.retryWithoutSearch() does to the flag
                searchTool = LiveSearchTool.NONE
                payloads += setup()
            },
            reportFailure = { _, _ -> fail("should retry") },
            reportStopped = { fail("should retry") }
        )

        payloads += setup()                       // attempt 1: with search
        flow.handleCloseFrame(1011, spentQuota, true, false, false)

        assertEquals(2, payloads.size)
        // Design 4: search is a declared function, and the built-in googleSearch tool is never sent.
        assertTrue("attempt 1 must declare the search function", payloads[0].contains("search_web"))
        assertFalse("googleSearch must never be sent", payloads[0].contains("googleSearch"))
        assertFalse("the retry must NOT declare search", payloads[1].contains("googleSearch"))
        assertFalse("the retry must not send a tools key", payloads[1].contains("\"tools\""))
        // …and the retry switches the instruction to the honest "cannot verify" wording.
        assertFalse(payloads[1].contains("You have live Google Search grounding"))
        assertTrue(payloads[1].contains("Web search is NOT available"))
    }

    // ---- 5. state flags / remembered refusal ---------------------------------------------

    @Test
    fun retryMarksTheRefusalAndBlocksFurtherRetries() {
        val r = Recorder()
        var fallbackUsed = false
        var groundingAvailable = true
        val memo = GroundingAvailability()
        val apiKey = "AIzaSyFAKE-wiring-test-key"
        val flow = LiveCloseFlow(
            echoClose = { _, _ -> },
            retryWithoutSearch = {
                fallbackUsed = true; groundingAvailable = false
                memo.markUnavailable(apiKey)
                r.retries += 1
            },
            reportFailure = { _, _ -> },
            reportStopped = { }
        )

        flow.handleCloseFrame(1011, spentQuota, groundingAvailable, false, fallbackUsed)
        assertEquals(1, r.retries)
        assertFalse(groundingAvailable)
        assertTrue(fallbackUsed)
        assertTrue("refusal must be remembered for this credential", memo.isUnavailable(apiKey))

        // A second socket refused again (fallback already used) must not retry: no loop.
        flow.reset()
        flow.handleCloseFrame(1011, spentQuota, hadSearchTool = false, setupComplete = false, fallbackUsed = fallbackUsed)
        assertEquals("no second retry", 1, r.retries)
    }

    // ---- 6. generic 1011 -----------------------------------------------------------------

    @Test
    fun generic1011IsTerminalAndNotRetried() {
        val r = Recorder()
        flowOn(r).handleCloseFrame(1011, "Internal error encountered.", true, false, false)

        assertEquals(0, r.retries)
        assertEquals(1, r.failures.size)
        assertEquals(1011, r.failures.single().first)
        assertEquals("the echo still completes the handshake", 1, r.echoes.size)
    }

    // ---- 7. never after setupComplete -----------------------------------------------------

    @Test
    fun quotaAfterSetupCompleteIsTerminalAndNeverRetried() {
        val r = Recorder()
        flowOn(r).handleCloseFrame(1011, spentQuota, hadSearchTool = true, setupComplete = true, fallbackUsed = false)

        assertEquals(0, r.retries)
        assertEquals(1, r.failures.size)
    }

    // ---- 8. clean shutdown ---------------------------------------------------------------

    @Test
    fun cleanCloseIsAStopWithNoErrorAndNoRetry() {
        val r = Recorder()
        flowOn(r).handleCloseFrame(1000, "", true, true, false)

        assertEquals(0, r.retries)
        assertEquals(0, r.failures.size)
        assertEquals(1, r.stopped)
    }

    @Test
    fun userStoppedSessionIsAStopWithNoError() {
        val r = Recorder()
        flowOn(r).handleCloseFrame(1000, "User stopped session", true, true, false)

        assertEquals(0, r.failures.size)
        assertEquals(1, r.stopped)
    }

    // ---- 9. HTTP 429 upgrade refusal (no close frame to echo) ----------------------------

    @Test
    fun http429UpgradeRefusalRetriesOnceWithoutSearch() {
        val r = Recorder()
        flowOn(r).handleFailure(429, "Client Error", hadSearchTool = true, setupComplete = false, fallbackUsed = false)

        assertEquals(1, r.retries)
        assertEquals("there is no close frame on a failed upgrade, so nothing to echo", 0, r.echoes.size)
        assertEquals(0, r.failures.size)
    }

    @Test
    fun http429AfterTheFallbackIsTerminal() {
        val r = Recorder()
        flowOn(r).handleFailure(429, "Client Error", hadSearchTool = false, setupComplete = false, fallbackUsed = true)

        assertEquals(0, r.retries)
        assertEquals(1, r.failures.size)
    }

    @Test
    fun networkFailureWithNoHttpResponseIsTerminal() {
        val r = Recorder()
        flowOn(r).handleFailure(0, "Gemini Live connect failed: timeout", true, false, false)

        assertEquals(0, r.retries)
        assertEquals(1, r.failures.size)
    }

    // ---- 10. per-socket guard, stale callbacks -------------------------------------------

    @Test
    fun resetGivesTheNextSocketAFreshGuard() {
        val r = Recorder()
        val flow = flowOn(r)
        flow.handleCloseFrame(1011, spentQuota, true, false, false)      // socket 1 refused
        assertEquals(1, r.retries)

        flow.reset()                                                    // socket 2 opened
        assertFalse(flow.wasHandled())
        // Socket 2's session dies for a different reason: it is decided normally, exactly once.
        flow.handleCloseFrame(1011, "Internal error encountered.", false, true, true)
        assertEquals(1, r.failures.size)
        assertEquals("still only one retry in the whole session", 1, r.retries)
    }

    @Test
    fun aStaleDuplicateAfterResetCannotRetryAgain() {
        val r = Recorder()
        val flow = flowOn(r)
        flow.handleCloseFrame(1011, spentQuota, true, false, false)   // socket 1 -> retry started
        flow.reset()                                                  // socket 2 opened
        // A late duplicate from the dead socket: search no longer requested, fallback already used.
        flow.handleCloseFrame(1011, spentQuota, hadSearchTool = false, setupComplete = false, fallbackUsed = true)
        assertEquals(1, r.retries)
    }

    // ---- extra regression ------------------------------------------------------------------

    @Test
    fun throttleIsNotTreatedAsAToolRefusal() {
        val r = Recorder()
        flowOn(r).handleCloseFrame(
            1011, "Rate limit exceeded. Your quota will reset in 30s.", true, false, false
        )
        assertEquals(0, r.retries)
        assertEquals(1, r.failures.size)
    }
}
