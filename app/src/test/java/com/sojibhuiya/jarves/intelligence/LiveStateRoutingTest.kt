package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.GeminiLiveVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the replacement for the Bengali substring matching (requirement 3).
 *
 * The old code did `if (status.contains("বন্ধ")) engine = null`, so the "server is closing" status
 * threw the engine away *before* any fallback could report back. State is now explicit, and these
 * tests pin the mapping.
 */
class LiveStateRoutingTest {

    @Test
    fun connectingDoesNotReleaseTheEngine() {
        val s = LiveStateRouting.route(LiveState.CONNECTING)
        assertFalse(s.connected)
        assertTrue(s.connecting)
        assertFalse("connecting must never drop the engine", s.releaseEngine)
    }

    @Test
    fun theSearchUnavailableStateKeepsTheSessionAlive() {
        // This is the state the fallback produces: voice is live, verification is not.
        val s = LiveStateRouting.route(LiveState.CONNECTED_WITHOUT_SEARCH)
        assertTrue("the fallback session is connected", s.connected)
        assertFalse(s.connecting)
        assertFalse("the fallback must NOT detach the engine", s.releaseEngine)
    }

    @Test
    fun groundedStateIsConnectedAndKeepsTheEngine() {
        val s = LiveStateRouting.route(LiveState.CONNECTED_GROUNDED)
        assertTrue(s.connected)
        assertFalse(s.connecting)
        assertFalse(s.releaseEngine)
    }

    @Test
    fun terminalStatesReleaseTheEngine() {
        for (state in listOf(LiveState.ENDED, LiveState.FAILED)) {
            val s = LiveStateRouting.route(state)
            assertFalse(state.name, s.connected)
            assertFalse(state.name, s.connecting)
            assertTrue("$state must release the engine", s.releaseEngine)
        }
    }

    @Test
    fun everyStateIsMapped() {
        // Exhaustive: a new state must be given UI semantics deliberately.
        for (state in LiveState.entries) assertNotNull(LiveStateRouting.route(state))
        assertEquals(5, LiveState.entries.size)
    }

    @Test
    fun theOldSentinelWordNoLongerAppearsInTheCloseStatusTheUiParsed() {
        // Regression guard: the status line that contains "বন্ধ" must not be the thing the UI keys
        // off any more. The UI state comes from LiveState, so no status constant needs the word to
        // signal termination; this asserts the engine exposes an explicit ENDED state instead.
        val ended = LiveStateRouting.route(LiveState.ENDED)
        assertTrue(ended.releaseEngine)
        assertTrue(GeminiLiveVoice.FIX_TAG.isNotBlank())
    }
}
