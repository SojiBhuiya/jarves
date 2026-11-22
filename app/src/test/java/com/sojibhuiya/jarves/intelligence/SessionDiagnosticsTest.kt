package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.9 — the end-of-session diagnostic line, tested against the exact phone report:
 * `code=0 | reason=Socket closed | onFailure | SetupComplete: true | 124 server messages`.
 */
class SessionDiagnosticsTest {

    private fun line(
        initiator: CloseInitiator = CloseInitiator.NONE,
        environment: SessionEnvironment = SessionEnvironment.UNKNOWN,
        durationMillis: Long = 79_000L,
        code: Int = 0,
        reason: String = "Socket closed",
        reconnects: Int = 0,
        goAway: Boolean = false,
        cause: ReconnectCause = ReconnectCause.NONE,
        handle: String? = null
    ): String {
        val resumption = SessionResumptionState()
        if (handle != null) resumption.onUpdate(ResumptionUpdate(handle, true))
        return SessionDiagnostics.line(
            initiator = initiator,
            environment = environment,
            sessionDurationMillis = durationMillis,
            closeCode = code,
            closeReason = reason,
            reconnectsUsed = reconnects,
            resumption = resumption,
            goAwaySeen = goAway,
            reconnectCause = cause
        )
    }

    @Test
    fun theLineAnswersWhoClosedItAndHowLongItLived() {
        val text = line(initiator = CloseInitiator.USER_STOP)
        assertTrue(text.contains("Closed by: user stop button"))
        assertTrue(text.contains("Session duration: 1m19s"))
        assertTrue(text.contains("code=0"))
        assertTrue(text.contains("reason=Socket closed"))
    }

    @Test
    fun aFailureWithNoInitiatorIsReportedAsNobodysFaultRatherThanGuessed() {
        val text = line(initiator = CloseInitiator.NONE)
        assertTrue(text.contains("none (no app-initiated close)"))
    }

    @Test
    fun thePhoneEnvironmentIsAllPresent() {
        val text = line(
            environment = SessionEnvironment(
                appVisible = false,
                screenInteractive = false,
                keyguardLocked = true,
                networkType = "mobile",
                networkChangedAgoMillis = 3_200,
                powerSaveMode = true
            )
        )
        assertTrue(text.contains("App: background"))
        assertTrue(text.contains("Screen: off"))
        assertTrue(text.contains("Locked: yes"))
        assertTrue(text.contains("Network: mobile (CHANGED 3.2s before the end)"))
        assertTrue(text.contains("Battery saver: on"))
    }

    @Test
    fun aNetworkChangeTenSecondsBeforeTheEndIsCalledOut() {
        assertTrue(line(environment = SessionEnvironment(networkChangedAgoMillis = 10_000))
            .contains("CHANGED 10.0s before the end"))
        assertTrue(line(environment = SessionEnvironment(networkChangedAgoMillis = 10_001))
            .contains("last changed 10s ago"))
        assertTrue(line(environment = SessionEnvironment(networkChangedAgoMillis = null))
            .contains("unchanged this session"))
    }

    @Test
    fun unknownReadingsSayUnknownInsteadOfInventingValues() {
        val text = line(environment = SessionEnvironment.UNKNOWN)
        assertTrue(text.contains("App: unknown"))
        assertTrue(text.contains("Screen: unknown"))
        assertTrue(text.contains("Locked: unknown"))
        assertTrue(text.contains("Battery saver: unknown"))
        assertTrue(text.contains("isDeviceIdleMode=unknown"))
        assertTrue(text.contains("appStandbyBucket=unavailable"))
        assertFalse(text.contains("CHANGED"))
    }

    @Test
    fun reconnectAndResumptionStateAreVisible() {
        val text = line(reconnects = 2, goAway = true, cause = ReconnectCause.GO_AWAY, handle = "h-abcdef")
        assertTrue(text.contains("GoAway: yes"))
        assertTrue(text.contains("Reconnects: 2/3 (GO_AWAY)"))
        assertTrue(text.contains("resume handle=present(8)"))
        assertTrue("the handle itself must never be printed", !text.contains("h-abcdef"))
    }

    @Test
    fun shortAndLongSessionsAreFormattedForHumans() {
        assertTrue(line(durationMillis = 0L).contains("Session duration: 0s"))
        assertTrue(line(durationMillis = 12_000L).contains("Session duration: 12s"))
        assertTrue(line(durationMillis = 95_000L).contains("Session duration: 1m35s"))
    }

    @Test
    fun theLineIsNeverEmptyEvenWithEverythingUnknown() {
        assertEquals(
            5,
            line().lines().size // Closed by / App / Network / power / resume handle
        )
    }
}
