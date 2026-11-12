package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the close-frame decision table: the recovery must fire once, only before
 * `setupComplete`, and only when the search tool was actually in the setup. There must be no path
 * to an infinite retry loop, and a normal shutdown must never be reported as an error.
 */
class LiveSessionPlanTest {

    private val spentQuota =
        "You exceeded your current quota, please check your plan and billing details."

    private fun decide(
        hadSearchTool: Boolean = true,
        setupComplete: Boolean = false,
        fallbackUsed: Boolean = false,
        code: Int = 1011,
        reason: String = spentQuota
    ) = LiveSessionPlan.decide(hadSearchTool, setupComplete, fallbackUsed, code, reason)

    // ---- The fix -----------------------------------------------------------------------------

    @Test
    fun toolRefusalBeforeSetupRetriesWithoutTools() {
        assertEquals(SessionAction.RETRY_WITHOUT_TOOLS, decide())
    }

    @Test
    fun quotaWithoutToolsIsTerminal() {
        assertEquals(SessionAction.FAIL, decide(hadSearchTool = false))
    }

    @Test
    fun generic1011BeforeSetupIsTerminal() {
        assertEquals(
            SessionAction.FAIL,
            decide(reason = "Internal error encountered.")
        )
    }

    // ---- Exactly once ------------------------------------------------------------------------

    @Test
    fun secondRefusalAfterTheFallbackIsTerminal() {
        // The fallback already ran and was refused again: fail instead of looping.
        assertEquals(
            SessionAction.FAIL,
            decide(fallbackUsed = true)
        )
    }

    @Test
    fun fallbackSessionWithoutToolsIsTerminal() {
        assertEquals(
            SessionAction.FAIL,
            decide(hadSearchTool = false, fallbackUsed = true)
        )
    }

    // ---- Never after setupComplete -----------------------------------------------------------

    @Test
    fun quotaAfterSetupCompleteIsTerminalNotARetry() {
        assertEquals(
            SessionAction.FAIL,
            decide(setupComplete = true)
        )
    }

    @Test
    fun midSessionInternalErrorIsTerminal() {
        assertEquals(
            SessionAction.FAIL,
            decide(setupComplete = true, reason = "Internal error encountered.")
        )
    }

    // ---- Normal shutdown must stay silent ----------------------------------------------------

    @Test
    fun cleanCloseAfterSetupCompleteIsStop() {
        assertEquals(
            SessionAction.STOP,
            decide(setupComplete = true, code = 1000, reason = "")
        )
    }

    @Test
    fun cleanCloseBeforeSetupCompleteIsStop() {
        assertEquals(
            SessionAction.STOP,
            decide(code = 1000, reason = "")
        )
    }

    @Test
    fun userStoppedSessionIsStopNotFailure() {
        assertEquals(
            SessionAction.STOP,
            decide(setupComplete = true, code = 1000, reason = "User stopped session")
        )
    }

    @Test
    fun cleanCloseWithReasonBeforeSetupIsStillAStop() {
        assertEquals(
            SessionAction.STOP,
            decide(code = 1000, reason = "retry without grounding")
        )
    }

    @Test
    fun retryIsNeverChosenForACleanClose() {
        // Even with the tool in the setup and no fallback used yet, a 1000 never reconnects.
        val action = decide(code = 1000, reason = "You exceeded your current quota")
        assertEquals(SessionAction.STOP, action)
    }

    // ---- Cross-check with the classifier -----------------------------------------------------

    @Test
    fun planAndClassifierAgreeOnEverySample() {
        val samples = listOf(
            "You exceeded your current quota, please check your plan and billing details.",
            "Internal error encountered.",
            "RESOURCE_EXHAUSTED",
            "Rate limit exceeded. Your quota will reset in 30s.",
            "models/gemini-3.8-live is not found for API version v1beta",
            ""
        )
        for (reason in samples) {
            val rejection = LiveRejection.classify(1011, reason, toolsRequested = true)
            val action = LiveSessionPlan.decide(
                hadSearchTool = true,
                setupComplete = false,
                fallbackUsed = false,
                code = 1011,
                reason = reason
            )
            val expected = if (rejection == Rejection.TOOL_REFUSED) {
                SessionAction.RETRY_WITHOUT_TOOLS
            } else {
                SessionAction.FAIL
            }
            assertEquals("reason: $reason", expected, action)
        }
    }
}
