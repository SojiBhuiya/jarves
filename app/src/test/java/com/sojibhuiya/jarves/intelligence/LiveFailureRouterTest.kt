package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.12: behaviour tests for the live engine's failure-handling state machine.
 *
 * These are the five phone problems that could not be tested before, because the decisions lived
 * inside the engine class in the root package (Android classes, not JVM-testable):
 *  1. microphone **read** error → reconnect (not a terminal `fail()`);
 *  2. a **bad frame** → the session must not end;
 *  3. **playback** error → clean terminal stop, never a ghost session;
 *  4. **user stop** → no reconnect, ever;
 *  5. a second failure while a reconnect is already running → nothing is done twice.
 */
class LiveFailureRouterTest {

    private fun started() = LiveFailureRouter().apply { onSessionStarted() }

    // ---- 1. read error → reconnect -------------------------------------------------------

    @Test
    fun audioReadErrorTakesTheReconnectPath() {
        val router = started()
        assertEquals(LiveFailureRouter.Action.RECONNECT, router.onAudioReadError())
        assertTrue("a reconnect is now in flight", router.reconnectInFlight)
        assertFalse("the session is NOT over", router.ended)
    }

    @Test
    fun audioReadErrorWhileAReconnectIsRunningIsIgnored() {
        val router = started()
        assertEquals(LiveFailureRouter.Action.RECONNECT, router.onAudioReadError())
        router.onReconnectScheduled()

        // The capture loop may report a second read error before setupComplete arrives.
        assertEquals(LiveFailureRouter.Action.IGNORE, router.onAudioReadError())
        assertFalse(router.ended)
    }

    @Test
    fun audioReadErrorAfterTheUserStoppedNeverReconnects() {
        val router = started()
        router.onSessionEnded() // user pressed stop

        assertEquals(LiveFailureRouter.Action.IGNORE, router.onAudioReadError())
        assertFalse(router.reconnectInFlight)
    }

    @Test
    fun aSuccessfulReconnectMakesTheNextReadErrorReconnectableAgain() {
        val router = started()
        assertEquals(LiveFailureRouter.Action.RECONNECT, router.onAudioReadError())
        router.onReconnectSucceeded()
        assertFalse(router.reconnectInFlight)
        assertEquals(LiveFailureRouter.Action.RECONNECT, router.onAudioReadError())
    }

    // ---- 2. bad frame → session stays alive ----------------------------------------------

    @Test
    fun aBadFrameIsSkippedAndTheSessionKeepsRunning() {
        val router = started()
        assertEquals(LiveFailureRouter.Action.SKIP_FRAME, router.onFrameParseError())
        assertFalse("skipping a frame must never end the session", router.ended)
        assertEquals(1, router.skippedFrames)
    }

    @Test
    fun manyBadFramesNeverEndTheSession() {
        val router = started()
        repeat(5) { assertEquals(LiveFailureRouter.Action.SKIP_FRAME, router.onFrameParseError()) }
        assertFalse(router.ended)
        assertEquals(5, router.skippedFrames)
    }

    @Test
    fun aBadFrameAfterTheSessionEndedIsIgnoredInsteadOfCounted() {
        val router = started()
        router.onSessionEnded()
        assertEquals(LiveFailureRouter.Action.IGNORE, router.onFrameParseError())
        assertEquals(0, router.skippedFrames)
    }

    // ---- 3. playback error → clean stop --------------------------------------------------

    @Test
    fun playbackErrorStopsTheSessionTerminally() {
        val router = started()
        assertEquals(LiveFailureRouter.Action.STOP_TERMINAL, router.onPlaybackError())
        assertTrue("the engine must consider the session over", router.ended)
        assertFalse("a playback error never reconnects", router.reconnectInFlight)
    }

    @Test
    fun afterAPlaybackErrorNoGhostSessionCanStartReconnecting() {
        // The 0.2.11 regression: playback error → UI dropped the engine → the microphone kept
        // running → the next read error could still schedule a reconnect for a session nobody owns.
        val router = started()
        assertEquals(LiveFailureRouter.Action.STOP_TERMINAL, router.onPlaybackError())
        assertEquals(LiveFailureRouter.Action.IGNORE, router.onAudioReadError())
        assertEquals(LiveFailureRouter.Action.IGNORE, router.onFrameParseError())
        assertEquals(LiveFailureRouter.Action.IGNORE, router.onPlaybackError())
    }

    // ---- 4. user stop → no reconnect -----------------------------------------------------

    @Test
    fun afterAUserStopNoFailureCanEverReconnect() {
        val router = started()
        router.onSessionEnded() // stop(USER_STOP) / stop(LIFECYCLE)

        assertEquals(LiveFailureRouter.Action.IGNORE, router.onAudioReadError())
        assertEquals(LiveFailureRouter.Action.IGNORE, router.onPlaybackError())
        assertFalse(router.reconnectInFlight)
        assertTrue(router.ended)
    }

    @Test
    fun aFreshStartClearsEverythingFromThePreviousSession() {
        val router = started()
        router.onPlaybackError()
        router.onFrameParseError()
        assertTrue(router.ended)

        router.onSessionStarted()

        assertFalse(router.ended)
        assertFalse(router.reconnectInFlight)
        assertEquals(0, router.skippedFrames)
        assertEquals(LiveFailureRouter.Action.RECONNECT, router.onAudioReadError())
    }

    @Test
    fun describeNamesTheStateForTheDiagnosticsBlock() {
        val router = started()
        router.onFrameParseError()
        val line = router.describe()
        assertTrue(line, line.contains("skippedFrames=1"))
        assertTrue(line, line.contains("ended=false"))
    }
}
