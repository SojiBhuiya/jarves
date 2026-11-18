package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.10 — the airplane-mode failure from the phone, and every branch of the network-aware
 * reconnect.
 *
 * The phone report was:
 *  `Closed by: session failure (fail()) | Session duration: 49s | Close: code=0 reason=Gemini Live
 *   connection failed: Software caused connection abort | Cause: SocketException … | App:
 *   background | Network: unknown (unchanged this session) | resume handle=present(36) |
 *   resumable=true`
 * plus the bubble "microphone audio could not be sent to the WebSocket".
 *
 * Root cause, reproduced here: the capture loop's `send()` failed, the loop called `fail()`, which
 * is terminal (running=false, CloseInitiator.SETUP_FAILURE), so the reconnect policy answered
 * NO_RECONNECT_STOPPED and the valid resume handle was never used. And because
 * `ACCESS_NETWORK_STATE` was missing, the network was reported as "unknown" instead of "gone", so
 * even a fixed policy could not have waited for the network.
 *
 * Everything is TESTED-MOCK: one coordinator, a fake clock, a fake network.
 */
class NetworkReconnectTest {

    private val offline = NetworkSnapshot(available = false, type = NetworkSnapshot.TYPE_NONE)
    private val wifi = NetworkSnapshot(available = true, type = "wifi")
    private val mobile = NetworkSnapshot(available = true, type = "mobile")

    private fun coordinator() = ReconnectCoordinator()

    // ---- (a) the phone's exact sequence ------------------------------------------------

    @Test
    fun thePhoneFailureWhileOfflineWaitsForTheNetworkAndReconnectsWithTheHandle() {
        val plan = coordinator()
        // t=0: airplane mode on. The audio send fails and the socket dies.
        var action = plan.onUnexpectedFailure(0, offline, "AUDIO_SEND_FAILED: Software caused connection abort")

        assertTrue("no attempt may be spent while offline", action is ReconnectAction.WaitForNetwork)
        assertEquals(0, plan.attemptsUsed)
        assertTrue(plan.waitingForNetwork)

        // A second failure while still offline must not spend an attempt either.
        action = plan.onUnexpectedFailure(2_000, offline, "SOCKET_FAILURE: Socket closed")
        assertTrue(action is ReconnectAction.WaitForNetwork)
        assertEquals(0, plan.attemptsUsed)

        // t=15s: airplane mode off, wifi is back.
        val back = plan.onNetworkAvailable(15_000, wifi)
        assertTrue("must reconnect once the network is back", back is ReconnectAction.Attempt)
        val attempt = back as ReconnectAction.Attempt
        assertEquals("validation requests an immediate, uncharged probe", 0L, attempt.delayMillis)
        assertEquals(0, attempt.attempt)
        assertTrue("only a successful route proof clears the outage", plan.waitingForNetwork)

        // The reconnect succeeds: the session continues.
        plan.onAttemptSucceeded(16_500)
        assertTrue(plan.timelineText().contains("reconnected"))
        assertEquals("a success resets the budget", 0, plan.attemptsUsed)
    }

    @Test
    fun theTimelineRecordsEachFailureWaitAndAttempt() {
        val plan = coordinator()
        plan.onUnexpectedFailure(0, offline, "audio send failed")
        plan.onNetworkAvailable(15_000, wifi)
        plan.onAttemptStarted(16_000, 1)
        plan.onAttemptFailed(16_500, wifi)
        val text = plan.timelineText()

        assertTrue(text, text.contains("failure"))
        assertTrue(text, text.contains("waiting for network"))
        assertTrue(text, text.contains("network available (wifi)"))
        assertTrue(text, text.contains("attempt #1"))
    }

    // ---- (b) the network never comes back --------------------------------------------

    @Test
    fun threeMinutesWithoutNetworkGivesUpCleanly() {
        val plan = coordinator()
        plan.onUnexpectedFailure(0, offline, "audio send failed")
        assertTrue(plan.checkNetworkDeadline(179_999) is ReconnectAction.None)

        val late = plan.checkNetworkDeadline(180_000)
        assertTrue(late is ReconnectAction.GiveUp)
        val giveUp = late as ReconnectAction.GiveUp
        assertEquals(GiveUpReason.NO_NETWORK_TIMEOUT, giveUp.reason)
        assertTrue("the message must be Bengali", giveUp.message.any { it.code in 0x0980..0x09FF })
        assertTrue("and must say what to do", giveUp.message.contains("ইন্টারনেট"))
        assertTrue(plan.gaveUp)
        // The engine stops the microphone on this path (closeRunning -> stopAudio), see
        // giveUpAfterReconnects.
    }

    @Test
    fun afterGivingUpALateNetworkDoesNotRestartTheSessionByItself() {
        val plan = coordinator()
        plan.onUnexpectedFailure(0, offline, "audio send failed")
        plan.checkNetworkDeadline(180_000)
        assertTrue(plan.gaveUp)
        assertEquals(ReconnectAction.None, plan.onNetworkAvailable(190_000, wifi))
    }

    // ---- (c) a failure on a working network ------------------------------------------

    @Test
    fun aWorkingNetworkUsesThreeAttemptsWithOneTwoFourBackoff() {
        val plan = coordinator()
        val delays = mutableListOf<Long>()
        var now = 0L

        var action = plan.onUnexpectedFailure(now, wifi, "Socket closed")
        repeat(3) {
            assertTrue("expected an attempt, got $action", action is ReconnectAction.Attempt)
            delays += (action as ReconnectAction.Attempt).delayMillis
            now += 500
            action = plan.onAttemptFailed(now, wifi)
            now += 500
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
        assertTrue("the fourth decision is a give-up", action is ReconnectAction.GiveUp)
        assertEquals(GiveUpReason.ATTEMPTS_EXHAUSTED, (action as ReconnectAction.GiveUp).reason)
        assertEquals("at most 3 attempts on a working network", 3, plan.attemptsUsed)
    }

    @Test
    fun anAttemptThatFailsWhileTheNetworkVanishedGoesBackToWaiting() {
        val plan = coordinator()
        plan.onUnexpectedFailure(0, wifi, "Socket closed")
        val action = plan.onAttemptFailed(2_000, offline)
        assertTrue(action is ReconnectAction.WaitForNetwork)
        assertEquals("refund the attempt that had no usable network", 0, plan.attemptsUsed)
    }

    @Test
    fun classifiesTransportFailuresWithoutTrustingConnectivityManager() {
        assertEquals(NetworkDown.DNS, NetworkDown.from(java.io.IOException("EAI_NODATA"), true))
        assertEquals(NetworkDown.DNS, NetworkDown.from(java.net.UnknownHostException(), false))
        assertEquals(NetworkDown.NO_ROUTE, NetworkDown.from(java.io.IOException("ENETUNREACH"), false))
        assertEquals(NetworkDown.NO_ROUTE, NetworkDown.from(java.io.IOException("outer", java.io.IOException("Network is unreachable")), true))
        assertEquals(NetworkDown.CONNECT_TIMEOUT, NetworkDown.from(java.net.SocketTimeoutException(), true))
        assertNull("a read timeout after open is not a connect timeout", NetworkDown.from(java.net.SocketTimeoutException(), false))
        val plan = coordinator()
        val validated = mobile.copy(internet = true, validated = true)
        val first = plan.onUnexpectedFailure(100, validated, "DNS", NetworkDown.DNS) as ReconnectAction.WaitForNetwork
        val second = plan.onUnexpectedFailure(2_100, validated, "DNS", NetworkDown.DNS) as ReconnectAction.WaitForNetwork
        assertEquals(2_000L, first.delayMillis)
        assertEquals(4_000L, second.delayMillis)
        assertEquals(first.deadlineMillis, second.deadlineMillis)
        assertEquals(0, plan.attemptsUsed)
    }

    // ---- (d) user stop ----------------------------------------------------------------

    @Test
    fun aUserStopNeverReconnectsAndNeverCountsAsAnError() {
        // The gate that the engine consults before touching the coordinator.
        val gate = ReconnectPolicy.decide(
            running = true,
            initiator = CloseInitiator.USER_STOP,
            cause = ReconnectCause.SOCKET_FAILURE,
            attemptsUsed = 0
        )
        assertEquals(ReconnectDecision.NO_RECONNECT_INTENTIONAL, gate)
        // And a stopped engine refuses even with no initiator.
        assertEquals(
            ReconnectDecision.NO_RECONNECT_STOPPED,
            ReconnectPolicy.decide(false, CloseInitiator.NONE, ReconnectCause.SOCKET_FAILURE, 0)
        )
    }

    // ---- (e) missing handle -----------------------------------------------------------

    @Test
    fun withoutAHandleTheSetupFrameStartsFreshAndTheUserIsTold() {
        val state = SessionResumptionState()
        assertNull("a fresh session has nothing to resume", state.setupField())

        val setup = LiveSessionConfig.setupJson(
            model = LiveModels.VOICE,
            voiceName = LiveModels.VOICE_NAME,
            systemInstruction = "x",
            searchTool = LiveSearchTool.NONE,
            sessionResumptionHandle = state.handleForReconnect()
        )
        val body = (com.sojibhuiya.jarves.apiproviders.MiniJson.parse(setup) as Map<*, *>)["setup"] as Map<*, *>
        assertFalse(
            "a fresh session must not send a handle",
            com.sojibhuiya.jarves.apiproviders.MiniJson.encode(body["sessionResumption"]).contains("handle")
        )
        assertTrue(
            ReconnectCoordinator.STATUS_CONTEXT_MAY_BE_LOST.any { it.code in 0x0980..0x09FF }
        )
        assertTrue(ReconnectCoordinator.STATUS_RECONNECTED.any { it.code in 0x0980..0x09FF })
    }

    // ---- network line in the diagnostics ---------------------------------------------

    @Test
    fun theNetworkLineNamesTheTypeTheChangeAndTheLastLoss() {
        val snapshot = NetworkSnapshot(
            available = true,
            type = "mobile",
            changedAtMillis = 96_800,
            lastLostAtMillis = 90_000,
            unknownReason = ""
        )
        val text = ReconnectCoordinator.describeNetwork(snapshot, now = 100_000)
        assertTrue(text, text.contains("mobile"))
        assertTrue(text, text.contains("CHANGED 3.2s ago"))
        assertTrue(text, text.contains("last onLost: 10.0s ago"))
    }

    @Test
    fun anUnreadableNetworkSaysWhyInsteadOfJustUnknown() {
        val text = ReconnectCoordinator.describeNetwork(
            NetworkSnapshot(available = false, type = NetworkSnapshot.TYPE_UNKNOWN,
                unknownReason = "SecurityException (ACCESS_NETWORK_STATE missing?)"),
            now = 5_000
        )
        assertTrue(text, text.contains("unknown (SecurityException (ACCESS_NETWORK_STATE missing?))"))
    }

    @Test
    fun aNetworkWithNoDefaultIsCalledNoneNotUnknown() {
        val text = ReconnectCoordinator.describeNetwork(
            NetworkSnapshot(available = false, type = NetworkSnapshot.TYPE_NONE), now = 1_000
        )
        assertTrue(text, text.startsWith("none"))
        assertTrue(text.contains("last onLost: never"))
    }

    private fun assertNull(message: String, value: Any?) = org.junit.Assert.assertNull(message, value)
}
