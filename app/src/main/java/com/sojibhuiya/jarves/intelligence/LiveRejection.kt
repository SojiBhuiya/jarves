package com.sojibhuiya.jarves.intelligence

/**
 * Why a Gemini Live session ended, as far as the app can tell from the close frame.
 *
 * The distinction that matters: Google reports **several** different conditions with the same
 * WebSocket close code `1011` (an exhausted search-grounding quota, a plain internal error, a
 * server drain, or a spent billing balance). Only the *reason text* carries the meaning, so a
 * `1011` alone must never be read as "Google Search quota exhausted".
 */
enum class Rejection {
    /**
     * The setup was refused because it declared the `googleSearch` tool and the project has no
     * quota/entitlement for search grounding. Recoverable: reconnect once without the tool.
     */
    TOOL_REFUSED,

    /** A genuine quota / rate-limit condition. Not caused by the tool; must be surfaced. */
    QUOTA,

    /** The key or project is not accepted (invalid key, revoked, denied access). */
    AUTH,

    /** Anything else — including a generic `1011 "Internal error encountered."`. */
    OTHER
}

/**
 * Classifies a Live close/failure into a [Rejection].
 *
 * Matching is deliberately narrow and case/whitespace insensitive:
 *  * a spent-quota reason is the only text allowed to trigger [Rejection.TOOL_REFUSED], and only
 *    when search was actually requested;
 *  * a *throttle* ("rate limit exceeded", "quota will reset in 30s") is never a tool refusal;
 *  * auth wording wins over quota wording (a denied project can echo quota text).
 */
object LiveRejection {

    /** Google's exact spent-quota wording, plus the gRPC spelling of the same condition. */
    private val SPENT_QUOTA = listOf(
        "exceeded your current quota",
        "exceeded its quota",
        "resource has been exhausted",
        "resource_exhausted"
    )

    /** A transient throttle. Google emits these too, and they must not be blamed on the tool. */
    private val THROTTLE = listOf(
        "rate limit exceeded",
        "quota will reset",
        "please retry",
        "try again later",
        "temporarily unavailable"
    )

    /** Auth / permission wording. */
    private val AUTH = listOf(
        "api key not valid",
        "api_key_invalid",
        "api key expired",
        "denied access",
        "permission denied",
        "permission_denied",
        "unauthenticated",
        "not authorized",
        "unauthorized",
        "leaked",
        "consumer_suspended",
        "invalid api key"
    )

    /** Explicit tool-not-supported wording (some keys/models reject the field itself). */
    private val TOOL_UNSUPPORTED = listOf(
        "is not supported",
        "not supported for",
        "unsupported",
        "invalid tool",
        "unknown name \"tools\"",
        "tool is not enabled"
    )

    private val AUTH_CODES = setOf(401, 403, 1007)

    fun classify(code: Int, reason: String, toolsRequested: Boolean): Rejection {
        val text = normalize(reason)
        if (isAuth(code, text)) return Rejection.AUTH
        if (isThrottle(text)) return Rejection.QUOTA
        if (isSpentQuota(text)) {
            return if (toolsRequested) Rejection.TOOL_REFUSED else Rejection.QUOTA
        }
        if (isToolUnsupported(text)) {
            return if (toolsRequested) Rejection.TOOL_REFUSED else Rejection.OTHER
        }
        return Rejection.OTHER
    }

    /**
     * Design 4: was the handshake refused specifically because `functionDeclarations[].behavior`
     * was set to `NON_BLOCKING`? Used to retry once with a plain blocking function instead of
     * losing search entirely.
     *
     * A close reason is truncated to 123 bytes, so this needs only the word "non_blocking" /
     * "behavior" (or a tool-declaration word) together with any refusal wording. A false positive
     * costs one reconnect (the fallback runs at most once per process); a false negative would
     * silently drop search, which is the worse failure.
     */
    fun isNonBlockingRejection(reason: String): Boolean {
        if (reason.isBlank()) return false
        val text = normalize(reason)
        val mentionsDeclaration =
            NON_BLOCKING_MARKERS.any { text.contains(it) } || containsAny(text, TOOL_UNSUPPORTED)
        val mentionsRefusal = REFUSAL_MARKERS.any { text.contains(it) }
        return mentionsDeclaration && mentionsRefusal && !containsAny(text, MODEL_CODES_TEXT)
    }

    private val NON_BLOCKING_MARKERS = listOf(
        "non_blocking", "non-blocking", "nonblocking", "behavior"
    )

    private val REFUSAL_MARKERS = listOf(
        "not supported", "unsupported", "not allowed", "unknown", "unrecognized",
        "invalid", "failed", "error", "cannot", "unexpected", "not enabled"
    )

    fun normalize(reason: String): String =
        reason.lowercase().replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")

    private fun containsAny(text: String, needles: List<String>): Boolean =
        needles.any { text.contains(it) }

    private fun isAuth(code: Int, text: String): Boolean =
        code in AUTH_CODES || containsAny(text, AUTH)

    private fun isSpentQuota(text: String): Boolean = containsAny(text, SPENT_QUOTA)

    private fun isThrottle(text: String): Boolean =
        containsAny(text, THROTTLE) && !isSpentQuota(text)

    private fun isToolUnsupported(text: String): Boolean =
        containsAny(text, TOOL_UNSUPPORTED) && !containsAny(text, MODEL_CODES_TEXT)

    /** Wording that means "this model/path does not exist" — a config bug, not a tool problem. */
    private val MODEL_CODES_TEXT = listOf(
        "is not found for api version",
        "model not found",
        "not found for api version"
    )
}

/** What the engine should do about a close frame. */
enum class SessionAction {
    /** Close the dead socket cleanly and reconnect exactly once without the search tool. */
    RETRY_WITHOUT_TOOLS,

    /** Report a terminal error. */
    FAIL,

    /** Normal end (user stopped, or a clean 1000 with no complaint). */
    STOP
}

/**
 * The complete close-frame decision, as a pure function so every rule is unit-tested without a
 * socket.
 *
 * Hard rules:
 *  * a retry happens **only** before `setupComplete` and **only** for a tool refusal, and
 *    **only** if the fallback has not already been used — so a retry loop is impossible;
 *  * once `setupComplete` has been seen the session never retries (a mid-session error is real);
 *  * a clean `1000` is a normal stop, never an error, and never a retry.
 */
object LiveSessionPlan {

    fun decide(
        hadSearchTool: Boolean,
        setupComplete: Boolean,
        fallbackUsed: Boolean,
        code: Int,
        reason: String
    ): SessionAction {
        // The recovery is only ever considered before the session is live: after setupComplete a
        // failure is real and must be surfaced, never silently re-connected.
        if (!setupComplete && code != 1000) {
            val rejection = LiveRejection.classify(code, reason, toolsRequested = hadSearchTool)
            if (rejection == Rejection.TOOL_REFUSED && hadSearchTool && !fallbackUsed) {
                return SessionAction.RETRY_WITHOUT_TOOLS
            }
        }
        // A clean 1000 is a normal shutdown (the user stopped, or the server finished politely).
        if (code == 1000) return SessionAction.STOP
        return SessionAction.FAIL
    }
}
