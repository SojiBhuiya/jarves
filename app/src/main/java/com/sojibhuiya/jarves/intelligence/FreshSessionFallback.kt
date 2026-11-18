package com.sojibhuiya.jarves.intelligence

/**
 * 0.2.16 (task 2) — the two-stage recovery after `1008 … already connected to an existing client`.
 *
 * ### What happened on the phone (0.2.15)
 *
 * `Reconnects: 1/3 (SERVER_BUSY) | gave up after 1 attempts (last close=1008, reason=
 * BidiGenerateContent session is already connected to an existing client)` after ~30 s. The server
 * kept the old session attached, so every handle-based attempt was refused and the app gave up.
 *
 * ### What the docs say (quoted, because the difference decides the design)
 *
 *  * `SessionResumptionConfig.handle` — "The handle of a previous session. **If not present then a
 *    new session is created.**"
 *    (https://ai.google.dev/api-reference/live → SessionResumptionConfig)
 *  * `SessionResumptionUpdate` — "Only sent if `BidiGenerateContentSetup.sessionResumption` was set.
 *    … `newHandle`: New handle that represents a state that can be resumed. Empty if
 *    `resumable`=false. … Resumption is not possible at some points in the session. For example,
 *    when the model is executing function calls or generating."
 *    (https://ai.google.dev/api-reference/live → SessionResumptionUpdate)
 *  * `sessionResumption` in the setup — "Optional. Configures session resumption mechanism. If
 *    included, the server will send `SessionResumptionUpdate` messages."
 *    (https://ai.google.dev/api-reference/live → BidiGenerateContentSetup)
 *  * the management guide: "Passing this configuration causes the server to send
 *    `SessionResumptionUpdate` messages, which can be used to resume the session by passing the last
 *    resumption token as the `SessionResumptionConfig.handle` of the subsequent connection."
 *    (https://ai.google.dev/gemini-api/docs/live-session → Session resumption)
 *
 * **What the docs do NOT say** (honest gap): nowhere in the Live API reference or the session
 * management guide does it say that one session may have only one connection, that the old
 * connection is held for X seconds after the client disappears, or that a *fresh* session (no
 * handle) can connect while the old one is still held. The 1008 text we saw is the server's own
 * wording. So the fresh-session fallback below is a **client-side policy that the docs make
 * plausible** (`handle` absent ⇒ new session), not a documented guarantee. Proof label:
 * READ-FROM-DOCS-ONLY (partly) / NOT-RUN for the real server.
 *
 * ### The policy
 *
 * 1. keep the existing handle-based attempts exactly as they are;
 * 2. if the 1008 refusal is still happening after [HANDLE_PHASE_MILLIS] (12 s), switch once to a
 *    **fresh session without a handle** — and tell the user, calmly, that the earlier context is
 *    gone;
 * 3. if the fresh session is refused with 1008 as well, keep retrying **without a handle** with
 *    backoff for up to [FRESH_PHASE_BUDGET_MILLIS] (90 s in total from the first 1008), then give up
 *    with one clear message and stop the microphone.
 *
 * Terminal rules are untouched: a user stop or an intentional close never reconnects, and a setup
 * failure of the fresh session ends the session (it is not another 1008 episode).
 */
class FreshSessionFallback(
    private val handlePhaseMillis: Long = HANDLE_PHASE_MILLIS,
    private val freshBudgetMillis: Long = FRESH_PHASE_BUDGET_MILLIS
) {

    enum class Phase {
        /** Normal: reconnect with the resumption handle, like every build before 0.2.16. */
        HANDLE,

        /** The handle keeps being refused: open a new session without it. */
        FRESH,

        /** Two stages spent: one clear message and stop. */
        GIVEN_UP
    }

    /** Backoff for the fresh phase: 3 s, 5 s, 8 s, 12 s, 15 s, then 15 s until the budget is gone. */
    enum class Step { RETRY_FRESH, GIVE_UP }

    var phase: Phase = Phase.HANDLE
        private set

    var first1008AtMillis: Long = 0L
        private set

    /** 0.2.16: a flag, not the value 0 — a caller's clock may legitimately start at 0 in a test. */
    private var started: Boolean = false

    var handleAttempts: Int = 0
        private set

    var freshAttempts: Int = 0
        private set

    /** How many times the switch to a fresh session happened (must be 0 or 1). */
    var freshSessionsOpened: Int = 0
        private set

    /** True once the "context lost" line was shown, so it is shown exactly once. */
    var contextLostAnnounced: Boolean = false
        private set

    private val events = mutableListOf<String>()

    val inFreshPhase: Boolean get() = phase == Phase.FRESH
    val active: Boolean get() = started

    fun elapsedMillis(now: Long): Long =
        if (!started) 0L else (now - first1008AtMillis).coerceAtLeast(0L)

    /**
     * The server refused a connection with `1008 already connected`.
     *
     * @param hadHandle whether the refused attempt carried a resumption handle.
     * @return the next wait, and whether it must be a fresh session.
     */
    fun onAlreadyConnected(
        now: Long,
        hadHandle: Boolean,
        sessionStart: Long
    ): NextAttempt {
        if (!started) {
            started = true
            first1008AtMillis = now
            events += "${stamp(now, sessionStart)} first 1008 (handle=$hadHandle, policy v0.2.16)"
        }
        if (phase == Phase.GIVEN_UP) return NextAttempt(Step.GIVE_UP, 0L, hadHandle)

        if (hadHandle) {
            handleAttempts += 1
            val elapsed = elapsedMillis(now)
            if (elapsed < handlePhaseMillis && phase == Phase.HANDLE) {
                val wait = handleDelays.getOrElse(handleAttempts - 1) { handleDelays.last() }
                events += "${stamp(now, sessionStart)} keep the handle: attempt #$handleAttempts in ${wait}ms " +
                    "(handle phase ${elapsed}ms/${handlePhaseMillis}ms)"
                return NextAttempt(Step.RETRY_FRESH, wait, hadHandle = true)
            }
            // The handle phase is spent: switch to a fresh session, once.
            phase = Phase.FRESH
            freshSessionsOpened += 1
            contextLostAnnounced = true
            events += "${stamp(now, sessionStart)} handle phase spent after ${elapsed}ms -> " +
                "fresh session WITHOUT handle (#$freshSessionsOpened)"
            return NextAttempt(Step.RETRY_FRESH, 0L, hadHandle = false)
        }

        // Already in the fresh phase and refused again: back off, then retry fresh until the budget.
        freshAttempts += 1
        val elapsed = elapsedMillis(now)
        if (elapsed >= freshBudgetMillis) {
            phase = Phase.GIVEN_UP
            events += "${stamp(now, sessionStart)} fresh phase spent after ${elapsed}ms -> give up"
            return NextAttempt(Step.GIVE_UP, 0L, hadHandle = false)
        }
        val wait = freshDelays.getOrElse(freshAttempts - 1) { freshDelays.last() }
        // The next attempt starts after `wait`; it must still start inside the 90 s budget. A small
        // slack keeps a 3 s retry planned at t+87 s from being discarded.
        if (elapsed + wait > freshBudgetMillis + 3_000L) {
            phase = Phase.GIVEN_UP
            events += "${stamp(now, sessionStart)} no time left for another fresh attempt " +
                "(${elapsed}ms + ${wait}ms > ${freshBudgetMillis}ms) -> give up"
            return NextAttempt(Step.GIVE_UP, 0L, hadHandle = false)
        }
        events += "${stamp(now, sessionStart)} fresh session refused (#$freshAttempts) -> retry in ${wait}ms " +
            "(fresh phase ${elapsed}ms/${freshBudgetMillis}ms)"
        return NextAttempt(Step.RETRY_FRESH, wait, hadHandle = false)
    }

    /** The fresh session connected: the user is told once that the old context is gone. */
    fun onFreshSessionConnected(now: Long, sessionStart: Long) {
        events += "${stamp(now, sessionStart)} fresh session connected (context lost line shown)"
        started = false
        first1008AtMillis = 0L
    }

    /** A connection (with or without handle) succeeded — a later 1008 episode starts over. */
    fun reset() {
        phase = Phase.HANDLE
        started = false
        first1008AtMillis = 0L
        handleAttempts = 0
        freshAttempts = 0
        freshSessionsOpened = 0
        contextLostAnnounced = false
    }

    fun timeline(): List<String> = events.toList()

    fun describe(now: Long): String = buildString {
        append("phase=").append(phase)
        append(", sinceFirst1008=").append(elapsedMillis(now)).append("ms")
        append(", handleAttempts=").append(handleAttempts)
        append(", freshAttempts=").append(freshAttempts)
        append(", freshSessions=").append(freshSessionsOpened)
        append(", budget=").append(freshBudgetMillis).append("ms")
        if (events.isNotEmpty()) append(" | ").append(events.joinToString(" -> "))
    }

    private fun stamp(now: Long, sessionStart: Long): String =
        if (sessionStart <= 0L) "t+0.0s" else formatRelativeMillis(now, sessionStart)

    /** The answer to "what should the client do next?". */
    data class NextAttempt(val step: Step, val delayMillis: Long, val hadHandle: Boolean)

    companion object {
        /** "if 1008 persists for about 12 s, open a FRESH session" — the task's own number. */
        const val HANDLE_PHASE_MILLIS = 12_000L

        /** "keep retrying it with backoff for up to 90 s in total" — measured from the first 1008. */
        const val FRESH_PHASE_BUDGET_MILLIS = 90_000L

        /** The handle phase keeps its old schedule, only compressed into the 12 s window. */
        val handleDelays = listOf(3_000L, 5_000L, 4_000L)

        /** Backoff for the fresh phase; the last value repeats. */
        val freshDelays = listOf(3_000L, 5_000L, 8_000L, 12_000L, 15_000L)

        /** The calm line after a fresh session, exactly as the task worded it. */
        const val STATUS_CONTEXT_LOST = "সংযোগ ফিরেছে, তবে আগের কথার প্রসঙ্গ হারিয়েছি"

        /** One clear message when even the fresh session never gets in. */
        const val MESSAGE_GAVE_UP =
            "সার্ভারের পুরোনো সংযোগ এখনো ছাড়া হয়নি, তাই প্রায় ৯০ সেকেন্ড চেষ্টার পর বন্ধ করছি। " +
                "মাইক বন্ধ করেছি — একটু পরে আবার শুরু করুন, সাধারণত তখনই যুক্ত হয়ে যায়।"
    }
}
