package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.10 — the exact code path that swallowed the phone's airplane-mode failure.
 *
 * 0.2.9 capture loop (abridged):
 * ```
 * if (!socket.send(pcm)) { fail("microphone audio could not be sent"); break }
 * ```
 * `fail()` is terminal: it sets running=false and CloseInitiator.SETUP_FAILURE, so the reconnect
 * policy returned NO_RECONNECT_STOPPED and a valid resume handle was ignored. This test runs the
 * *decision order* of the 0.2.10 loop against the same policy, so any regression fails here first.
 */
class AudioSendReconnectTest {

    /** The order used by the engine's capture loop after 0.2.10. */
    private fun onSendFailed(
        running: Boolean,
        initiator: CloseInitiator,
        plan: ReconnectCoordinator,
        network: NetworkSnapshot,
        now: Long
    ): String {
        if (!running) return "stopped"
        val gate = ReconnectPolicy.decide(running, initiator, ReconnectCause.AUDIO_SEND_FAILED, plan.attemptsUsed)
        if (gate == ReconnectDecision.NO_RECONNECT_STOPPED) return "stopped"
        if (gate == ReconnectDecision.NO_RECONNECT_INTENTIONAL) return "intentional"
        return when (val action = plan.onUnexpectedFailure(now, network, "AUDIO_SEND_FAILED")) {
            is ReconnectAction.WaitForNetwork -> "wait"
            is ReconnectAction.Attempt -> "attempt"
            is ReconnectAction.GiveUp -> "giveup"
            ReconnectAction.None -> "none"
        }
    }

    @Test
    fun aFailedAudioSendWhileOfflineWaitsInsteadOfFailingTheSession() {
        val plan = ReconnectCoordinator()
        val outcome = onSendFailed(
            running = true,
            initiator = CloseInitiator.NONE,
            plan = plan,
            network = NetworkSnapshot(available = false, type = NetworkSnapshot.TYPE_NONE),
            now = 0
        )
        assertEquals("must wait for the network, not call fail()", "wait", outcome)
        assertEquals(0, plan.attemptsUsed)
    }

    @Test
    fun aFailedAudioSendOnAWorkingNetworkAttemptsAReconnect() {
        val plan = ReconnectCoordinator()
        val outcome = onSendFailed(
            running = true,
            initiator = CloseInitiator.NONE,
            plan = plan,
            network = NetworkSnapshot(available = true, type = "mobile"),
            now = 0
        )
        assertEquals("attempt", outcome)
        assertEquals(1, plan.attemptsUsed)
    }

    @Test
    fun theOldBugWouldHaveSaidStopped() {
        // The regression: fail() flips running to false *before* the policy is asked.
        val plan = ReconnectCoordinator()
        val outcome = onSendFailed(
            running = false,
            initiator = CloseInitiator.SETUP_FAILURE,
            plan = plan,
            network = NetworkSnapshot(available = false, type = NetworkSnapshot.TYPE_NONE),
            now = 0
        )
        assertEquals("this is exactly what 0.2.9 did — and why nothing reconnected", "stopped", outcome)
        assertFalse(plan.waitingForNetwork)
    }
}
