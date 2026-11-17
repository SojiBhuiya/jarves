package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.13 (task 5): the two scenarios from the 0.2.12 phone log, driven by a **mock socket**.
 *
 * The mock is deliberately tiny: [MockSocket] only knows how to open (handshake result) and how to
 * close (code + reason). Everything the app decides — how long to wait, whether an attempt is
 * spent, when to give up — comes from the production code under test
 * ([ReconnectCoordinator], [ServerBusyRetry], [CloseAlreadyConnected], [InterruptionLog]); nothing
 * here re-implements it, so a regression in those classes fails these tests.
 *
 * What this proves (TESTED-MOCK) and what it cannot: it proves the *decision sequence*. The glue in
 * the live-engine class in the root package (OkHttp callbacks, `AudioRecord`, the capture thread) is
 * not JVM-compilable, so the real behaviour against the Live endpoint stays NOT-RUN until it is
 * measured on the phone.
 */
class ServerBusyRetryTest {

    // ---------------------------------------------------------------- the mock socket -------

    /** What a new connection attempt produced. */
    private sealed interface OpenResult {
        /** `onOpen` + `setupComplete`: the session is alive. */
        data object Connected : OpenResult

        /** The server refused the socket and closed it with our frame. */
        data class Closed(val code: Int, val reason: String) : OpenResult
    }

    /**
     * A socket that opens and closes with whatever the scenario says, and records every open. The
     * counters are what the assertions read: how many sockets were opened, and with which frame.
     */
    private class MockSocket(private val script: MutableList<OpenResult>) {

        var opens: Int = 0
            private set
        var connected: Boolean = false
            private set
        val closeFrames = mutableListOf<String>()

        /** One connection attempt: the mock's whole behaviour. */
        fun open(): OpenResult {
            opens += 1
            connected = false
            val result = if (script.isEmpty()) OpenResult.Connected else script.removeAt(0)
            if (result is OpenResult.Closed) closeFrames += "${result.code} ${result.reason}"
            return result
        }

        fun setupComplete() {
            connected = true
        }
    }

    private val workingNetwork = NetworkSnapshot(available = true, type = "mobile")
    private val sessionStart = 1_000_000L

    /** Our 1008 reason, copied verbatim from the 0.2.12 phone log. */
    private val busyReason = "BidiGenerateContent session is already connected to an existing client"

    private fun coordinator() = ReconnectCoordinator().also { it.startTimeline(sessionStart) }

    // ------------------------------------------------- (a) 1008 twice, then connected ------

    @Test
    fun `send failure then two busy refusals then success keeps the normal attempts untouched`() {
        // Script: attempt #1 refused (busy), attempt #2 refused (busy), attempt #3 connected.
        val socket = MockSocket(
            mutableListOf(
                OpenResult.Closed(1008, busyReason),
                OpenResult.Closed(1008, busyReason),
                OpenResult.Connected
            )
        )
        val plan = coordinator()
        val log = InterruptionLog().also { it.onSessionStarted(sessionStart) }

        // 1) The audio send failed on a socket the server still saw as connected (the phone log).
        var now = sessionStart + 12_000L
        log.begin(now, "AUDIO_SEND_FAILED")
        val firstRaw = plan.onUnexpectedFailure(now, workingNetwork, "AUDIO_SEND_FAILED")
        assertTrue(firstRaw.toString(), firstRaw is ReconnectAction.Attempt)
        val first = firstRaw as ReconnectAction.Attempt
        assertEquals(1_000L, first.delayMillis)
        assertEquals("one normal attempt spent", 1, plan.attemptsUsed)

        // 2) Attempt #1 opens and is refused: 1008 "already connected".
        now += first.delayMillis
        plan.onAttemptStarted(now, first.attempt)
        val refused = socket.open()
        assertTrue(refused is OpenResult.Closed)
        assertTrue("the reason must be recognised", CloseAlreadyConnected.matches(1008, busyReason))

        val busy1raw = plan.onServerBusy(now)
        assertTrue(busy1raw.toString(), busy1raw is ReconnectAction.RetryServerBusy)
        val busy1 = busy1raw as ReconnectAction.RetryServerBusy
        assertEquals("first server-busy wait is 3 s", 3_000L, busy1.delayMillis)
        assertEquals("a busy refusal does not burn a normal attempt", 1, plan.attemptsUsed)
        assertEquals(1, plan.serverBusy.attemptsUsed)

        // 3) Attempt #2 opens and is refused again, 5 s later.
        now += busy1.delayMillis
        val refusedAgain = socket.open()
        assertTrue(refusedAgain is OpenResult.Closed)
        val busy2raw = plan.onServerBusy(now)
        assertTrue(busy2raw.toString(), busy2raw is ReconnectAction.RetryServerBusy)
        val busy2 = busy2raw as ReconnectAction.RetryServerBusy
        assertEquals("second server-busy wait is 5 s", 5_000L, busy2.delayMillis)
        assertEquals(1, plan.attemptsUsed)
        assertEquals(2, plan.serverBusy.attemptsUsed)
        assertFalse("must not have given up", plan.gaveUp)

        // 4) Third connection succeeds.
        now += busy2.delayMillis
        val ok = socket.open()
        assertTrue(ok is OpenResult.Connected)
        socket.setupComplete()
        plan.onAttemptSucceeded(now)
        log.end(now)

        assertTrue("session is alive", socket.connected)
        assertEquals("three sockets opened in total", 3, socket.opens)
        assertEquals("normal attempt budget back to zero", 0, plan.attemptsUsed)
        assertFalse("server-busy state cleared", plan.serverBusy.active)
        assertEquals(2, socket.closeFrames.size)
        assertTrue(socket.closeFrames.all { it.startsWith("1008 ") })

        // Diagnostics (task 4): one interruption, its total length in seconds, relative timeline.
        assertEquals(1, log.count)
        assertTrue(log.totalMillis(now) >= 8_000L)
        val line = log.line(now)
        assertTrue(line, line.startsWith("interruptions: 1"))
        assertTrue(line, line.contains("AUDIO_SEND_FAILED"))
        assertTrue("timeline is relative", plan.timelineText().startsWith("t+12.0s"))
        assertTrue("timeline shows the cleared state", plan.timelineText().contains("server-busy cleared"))
        assertFalse("no epoch numbers in the timeline", plan.timelineText().contains("E7"))
    }

    @Test
    fun `a refused socket is never mistaken for a network problem`() {
        val plan = coordinator()
        // Two busy refusals in a row, then the budget is checked: still no normal attempt spent.
        val now = sessionStart + 5_000L
        plan.onServerBusy(now)
        plan.onServerBusy(now + 3_000L)
        assertEquals(0, plan.attemptsUsed)
        assertFalse(plan.waitingForNetwork)
    }

    // ------------------------------------- (b) still busy after 30 s -> clean give-up ------

    @Test
    fun `server keeps refusing for the whole budget - gives up cleanly after about 30 seconds`() {
        val socket = MockSocket(MutableList(10) { OpenResult.Closed(1008, busyReason) })
        val plan = coordinator()
        val log = InterruptionLog().also { it.onSessionStarted(sessionStart) }
        var now = sessionStart + 20_000L
        log.begin(now, "send 1008")

        var sockets = 0
        var action: ReconnectAction = ReconnectAction.None
        var guard = 0
        while (guard++ < 10) {
            action = plan.onServerBusy(now)
            if (action !is ReconnectAction.RetryServerBusy) break
            now += action.delayMillis
            socket.open()
            sockets += 1
        }

        assertTrue("must give up, not loop forever", action is ReconnectAction.GiveUp)
        val giveUp = action as ReconnectAction.GiveUp
        assertEquals(GiveUpReason.SERVER_BUSY_TIMEOUT, giveUp.reason)
        assertTrue(plan.gaveUp)
        assertEquals(0, plan.attemptsUsed)
        // 3 + 5 + 8 + 12 = 28 s of waiting, and the next attempt would end past the 30 s budget.
        assertEquals(28_000L, now - (sessionStart + 20_000L))
        assertTrue("four retries were scheduled", sockets >= 3)

        // The message is calm, Bengali and tells the user what to do.
        assertTrue(giveUp.message, giveUp.message.contains("সার্ভার"))
        assertTrue(giveUp.message, giveUp.message.contains("আবার শুরু করুন"))
        assertFalse(giveUp.message.contains("Exception"))

        // The caller stops the microphone by ending the session (closeRunning + LiveState.FAILED);
        // what is provable here is that no further retry is offered after the give-up.
        assertTrue(plan.onServerBusy(now) !is ReconnectAction.RetryServerBusy)

        // The interruption is reported with its length, so the diagnostics show the 30 s silence.
        val line = log.line(now)
        assertTrue(line, line.contains("one open for ${formatSeconds(now - (sessionStart + 20_000L))}s"))
    }

    @Test
    fun `budget is respected even when the clock jumps`() {
        val busy = ServerBusyRetry()
        val start = 10_000L
        assertEquals(3_000L, busy.nextDelayMillis(start))
        assertNull("a single huge jump must not restart the schedule", busy.nextDelayMillis(start + 60_000L))
        assertEquals(1, busy.attemptsUsed)
        assertTrue(busy.describe(start + 60_000L).contains("budget=30.0s"))
    }

    // --------------------------------------------------------------- matching (task 2) -----

    @Test
    fun `only the already-connected 1008 is transient`() {
        assertTrue(CloseAlreadyConnected.matches(1008, busyReason))
        assertTrue(CloseAlreadyConnected.matches(1008, "already connected to another client"))
        assertFalse("other codes are not this condition", CloseAlreadyConnected.matches(1011, busyReason))
        assertFalse("a key problem is a real failure", CloseAlreadyConnected.matches(1008, "API key not valid"))
        assertFalse("null reason is not this condition", CloseAlreadyConnected.matches(1008, null))
        assertFalse(CloseAlreadyConnected.matches(1008, ""))
        // One line, cut at 64 characters so the diagnostics block stays readable.
        assertEquals(
            "close 1008: BidiGenerateContent session is already connected to an existing ",
            CloseAlreadyConnected.describe(1008, busyReason)
        )
    }

    // ------------------------------------------------ diagnostics formats (task 4) ---------

    @Test
    fun `durations are printed as plain seconds`() {
        assertEquals("0.0", formatSeconds(0))
        assertEquals("13.4", formatSeconds(13_400))
        assertEquals("30.0", formatSeconds(30_000))
        assertEquals("0.0", formatSeconds(-5))
        assertEquals("t+12.8s", formatRelativeMillis(1_012_800L, 1_000_000L))
        assertEquals("t+0.0s", formatRelativeMillis(5L, 0L))
        assertFalse(formatSeconds(12_345_678L).contains("E"))
    }

    @Test
    fun `interruption log counts each episode and its length`() {
        val log = InterruptionLog()
        log.onSessionStarted(0L)
        log.begin(1_000L, "AUDIO_SEND_FAILED")
        log.begin(2_000L, "repeated while open") // must be ignored
        log.end(14_400L)
        log.begin(30_000L, "SERVER_CLOSE")
        log.end(48_900L)

        assertEquals(2, log.count)
        assertEquals(32_300L, log.totalMillis(50_000L))
        assertEquals("#1 13.4s (AUDIO_SEND_FAILED) | #2 18.9s (SERVER_CLOSE)", log.summary(50_000L))
        assertTrue(log.line(50_000L).startsWith("interruptions: 2 (total 32.3s)"))
    }
}
