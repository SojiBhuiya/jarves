package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the close/failure classification rules.
 *
 * The whole point of these tests: `1011` is Google's catch-all for *several* different
 * conditions, so it must never be read as "search quota exhausted" on its own. Only a spent-quota
 * reason, on a setup that actually declared the tool, may drive the recovery.
 */
class LiveRejectionTest {

    /** Google's real spent-quota close reason, as seen on device. */
    private val spentQuota =
        "You exceeded your current quota, please check your plan and billing details. " +
            "For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits"

    // ---- Tool refusal (the recoverable case) -------------------------------------------------

    @Test
    fun spentQuotaOnToolSessionIsToolRefusal() {
        assertEquals(
            Rejection.TOOL_REFUSED,
            LiveRejection.classify(1011, spentQuota, toolsRequested = true)
        )
    }

    @Test
    fun spentQuotaWithoutToolsIsNotBlamedOnTheTool() {
        // Same text, but no tool was requested: this is a real quota problem, not a tool refusal.
        assertEquals(
            Rejection.QUOTA,
            LiveRejection.classify(1011, spentQuota, toolsRequested = false)
        )
    }

    @Test
    fun grpcResourceExhaustedWordingIsRecognised() {
        assertEquals(
            Rejection.TOOL_REFUSED,
            LiveRejection.classify(1011, "8 RESOURCE_EXHAUSTED", toolsRequested = true)
        )
        assertEquals(
            Rejection.TOOL_REFUSED,
            LiveRejection.classify(1008, "Resource has been exhausted (e.g. check quota).", true)
        )
    }

    @Test
    fun reasonMatchingIgnoresCaseAndExtraWhitespace() {
        assertEquals(
            Rejection.TOOL_REFUSED,
            LiveRejection.classify(1011, "  you   EXCEEDED your Current\tQuota  ", true)
        )
    }

    @Test
    fun explicitToolSupportWordingIsToolRefusal() {
        assertEquals(
            Rejection.TOOL_REFUSED,
            LiveRejection.classify(1008, "Google Search is not supported for this project", true)
        )
        // …but only when a tool was actually requested.
        assertEquals(
            Rejection.OTHER,
            LiveRejection.classify(1008, "Google Search is not supported for this project", false)
        )
    }

    // ---- Generic 1011: the regression this change must prevent -------------------------------

    @Test
    fun generic1011IsNotAToolRefusal() {
        assertEquals(
            Rejection.OTHER,
            LiveRejection.classify(1011, "Internal error encountered.", toolsRequested = true)
        )
    }

    @Test
    fun emptyReasonIsNotAToolRefusal() {
        assertEquals(Rejection.OTHER, LiveRejection.classify(1011, "", toolsRequested = true))
    }

    @Test
    fun serverDrainIsNotAToolRefusal() {
        assertEquals(
            Rejection.OTHER,
            LiveRejection.classify(1012, "Service Restart", toolsRequested = true)
        )
    }

    // ---- Throttle vs spend: easy to confuse, must not be --------------------------------

    @Test
    fun transientThrottleIsNotAToolRefusal() {
        val throttle = "Rate limit exceeded. Your quota will reset in 30s."
        assertEquals(Rejection.QUOTA, LiveRejection.classify(429, throttle, toolsRequested = true))
        assertEquals(Rejection.QUOTA, LiveRejection.classify(1011, throttle, toolsRequested = false))
    }

    // ---- Auth / model problems ---------------------------------------------------------------

    @Test
    fun invalidKeyIsAuth() {
        assertEquals(
            Rejection.AUTH,
            LiveRejection.classify(1007, "API key not valid. Please pass a valid API key.", true)
        )
    }

    @Test
    fun deniedProjectIsAuthEvenWhenQuotaTextFollows() {
        assertEquals(
            Rejection.AUTH,
            LiveRejection.classify(
                1008,
                "Your project has been denied access. You exceeded your current quota.",
                toolsRequested = true
            )
        )
    }

    @Test
    fun httpAuthCodesAreAuth() {
        assertEquals(Rejection.AUTH, LiveRejection.classify(401, "", toolsRequested = true))
        assertEquals(Rejection.AUTH, LiveRejection.classify(403, "", toolsRequested = true))
    }

    @Test
    fun unknownModelIsNotAToolRefusal() {
        assertEquals(
            Rejection.OTHER,
            LiveRejection.classify(
                1008,
                "models/gemini-3.8-live is not found for API version v1beta",
                toolsRequested = true
            )
        )
    }

    @Test
    fun normalizeCollapsesWhitespace() {
        assertEquals("a b c", LiveRejection.normalize("  a   b\t\nc  "))
    }
}
