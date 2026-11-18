package com.sojibhuiya.jarves.intelligence

/**
 * Client-side policy for the reported 1008 "already connected". Gemini documents that omitting
 * SessionResumptionConfig.handle creates a new session, not how quickly a server releases a handle.
 * https://ai.google.dev/api/live#SessionResumptionConfig
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
    enum class Step { RETRY_FRESH, WAIT_FOR_HANDLE_DEADLINE, GIVE_UP }

    var phase: Phase = Phase.HANDLE
        private set

    var first1008AtMillis: Long = 0L
        private set

    /** 0.2.16: a flag, not the value 0 — a caller's clock may legitimately start at 0 in a test. */
    private var started: Boolean = false

    var handleAttempts: Int = 0
        private set

    private var startedHandleRetries = 0

    var freshAttempts: Int = 0
        private set

    /** How many times the switch to a fresh session happened (must be 0 or 1). */
    var freshSessionsOpened: Int = 0
        private set

    private val events = mutableListOf<String>()

    val inFreshPhase: Boolean get() = phase == Phase.FRESH
    val active: Boolean get() = started

    fun elapsedMillis(now: Long): Long =
        if (!started) 0L else (now - first1008AtMillis).coerceAtLeast(0L)

    val handleDeadline: Long? get() =
        if (started && phase == Phase.HANDLE) first1008AtMillis + handlePhaseMillis else null
    val freshDeadline: Long? get() =
        if (started && phase == Phase.FRESH) first1008AtMillis + freshBudgetMillis else null

    fun takeHandleRetry(): Boolean {
        if (startedHandleRetries >= MAX_HANDLE_ATTEMPTS - 1) return false
        startedHandleRetries++
        return true
    }

    fun onAlreadyConnected(now: Long, hadHandle: Boolean, sessionStart: Long): NextAttempt {
        if (!started) {
            started = true
            first1008AtMillis = now
            events += "${stamp(now, sessionStart)} first 1008 (handle=$hadHandle)"
        }
        if (phase == Phase.GIVEN_UP) return NextAttempt(Step.GIVE_UP, 0L, false)
        if (hadHandle && phase == Phase.HANDLE) {
            handleAttempts++
            val remaining = checkNotNull(handleDeadline) - now
            return if (handleAttempts < MAX_HANDLE_ATTEMPTS && HANDLE_RETRY_MILLIS < remaining) {
                events += "${stamp(now, sessionStart)} retry with handle in ${HANDLE_RETRY_MILLIS}ms"
                NextAttempt(Step.RETRY_FRESH, HANDLE_RETRY_MILLIS, true)
            } else {
                NextAttempt(Step.WAIT_FOR_HANDLE_DEADLINE, remaining.coerceAtLeast(0L), false)
            }
        }
        if (phase == Phase.HANDLE) beginFresh(now, sessionStart)
        freshAttempts += 1
        val elapsed = elapsedMillis(now)
        val wait = freshDelays.getOrElse(freshAttempts - 1) { freshDelays.last() }
        if (elapsed >= freshBudgetMillis) {
            phase = Phase.GIVEN_UP
            events += "${stamp(now, sessionStart)} fresh phase spent after ${elapsed}ms -> give up"
            return NextAttempt(Step.GIVE_UP, 0L, hadHandle = false)
        }
        if (elapsed + wait >= freshBudgetMillis) {
            return NextAttempt(Step.GIVE_UP, freshBudgetMillis - elapsed, hadHandle = false)
        }
        events += "${stamp(now, sessionStart)} fresh session refused (#$freshAttempts) -> retry in ${wait}ms " +
            "(fresh phase ${elapsed}ms/${freshBudgetMillis}ms)"
        return NextAttempt(Step.RETRY_FRESH, wait, hadHandle = false)
    }

    /** Also called by the deadline timer when the second handle attempt is still handshaking. */
    fun beginFresh(now: Long, sessionStart: Long): Boolean {
        if (phase != Phase.HANDLE) return false
        phase = Phase.FRESH
        freshSessionsOpened++
        events += "${stamp(now, sessionStart)} switching to fresh session WITHOUT handle"
        return true
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
        startedHandleRetries = 0
        freshAttempts = 0
        freshSessionsOpened = 0
    }

    fun timeline(): List<String> = events.toList()

    fun describe(now: Long): String = buildString {
        append("phase=").append(phase)
        append(", sinceFirst1008=").append(elapsedMillis(now)).append("ms")
        append(", handleAttempts=").append(handleAttempts)
        append(", freshAttempts=").append(freshAttempts)
        append(", freshSessions=").append(freshSessionsOpened)
        append(", budget=").append(freshBudgetMillis).append("ms")
        if (events.isNotEmpty()) append(" | ").append(events.takeLast(3).joinToString(" -> "))
    }

    private fun stamp(now: Long, sessionStart: Long): String =
        if (sessionStart <= 0L) "t+0.0s" else formatRelativeMillis(now, sessionStart)

    /** The answer to "what should the client do next?". */
    data class NextAttempt(val step: Step, val delayMillis: Long, val hadHandle: Boolean)

    companion object {
        const val HANDLE_PHASE_MILLIS = 6_000L
        const val MAX_HANDLE_ATTEMPTS = 2 // including the first refusal
        const val HANDLE_RETRY_MILLIS = 3_000L

        /** "keep retrying it with backoff for up to 90 s in total" — measured from the first 1008. */
        const val FRESH_PHASE_BUDGET_MILLIS = 90_000L

        /** Backoff for the fresh phase; the last value repeats. */
        val freshDelays = listOf(3_000L, 5_000L, 8_000L, 12_000L, 15_000L)

        /** The calm line after a fresh session, exactly as the task worded it. */
        const val STATUS_CONTEXT_LOST = "সংযোগ ফিরেছে, তবে আগের কথার প্রসঙ্গ হারিয়েছি"

        /** One clear message when even the fresh session never gets in. */
        const val MESSAGE_GAVE_UP =
            "৯০ সেকেন্ডেও সংযোগ ফেরেনি — মাইক বন্ধ করেছি।"
    }
}
