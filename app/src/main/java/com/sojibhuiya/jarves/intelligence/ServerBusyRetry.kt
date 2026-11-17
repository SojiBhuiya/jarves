package com.sojibhuiya.jarves.intelligence

/**
 * 0.2.13 — the server said: `1008 "BidiGenerateContent session is already connected to an existing
 * client"`.
 *
 * That is not a broken key, a bad model or a dead network: it is the server telling us the **old**
 * connection is still attached to the resumable session, so the new one is refused. The phone log
 * from 0.2.12 shows the consequence: send failure → attempt #1 → 1008 → attempt #2 → 1008 →
 * attempt #3 → connected, with 13–19 s of silence in between.
 *
 * Two independent pieces live here, both pure Kotlin so the whole behaviour is unit-tested:
 *
 *  * [CloseAlreadyConnected] — recognising that specific close frame (code **and** reason, never a
 *    Bengali/English substring of anything else);
 *  * [ServerBusyRetry] — its own waiting schedule (3 s, 5 s, 8 s, 12 s, ~30 s in total) that does
 *    **not** touch the three normal reconnect attempts, because nothing is wrong with the network.
 *
 * Docs consulted (see the 0.2.13 report): the Live API reference
 * (https://ai.google.dev/api/live) documents close frames and `GoAway`, but says nothing about how
 * long a session is kept after the client disappears, and nothing about this 1008 reason — so the
 * behaviour here is a **deliberate client-side policy**, not a server contract.
 */
object CloseAlreadyConnected {

    /** WebSocket policy violation, RFC 6455 §7.4.1 — the code the server used. */
    const val CODE = 1008

    /** The exact reason text observed on the phone (0.2.12 log). */
    const val REASON = "already connected to an existing client"

    /**
     * True when this close is the "session still attached to the previous connection" refusal.
     *
     * Matching is on the *reason* only (the server's own wording), case-insensitively, and the code
     * must be 1008. Other 1008 reasons (an API key problem, a bad setup frame) are real failures and
     * must keep the normal path.
     */
    fun matches(code: Int, reason: String?): Boolean {
        if (code != CODE) return false
        val text = reason?.lowercase().orEmpty()
        return text.contains("already connected") &&
            (text.contains("existing client") || text.contains("another client") || text.contains("session"))
    }

    /** Diagnostics wording, short enough for one line. */
    fun describe(code: Int, reason: String?): String =
        "close $code: ${reason?.take(64).orEmpty().ifBlank { "no reason" }}"
}

/**
 * The waiting schedule for [CloseAlreadyConnected]: **3 s, 5 s, 8 s, 12 s**, and it gives up after
 * ~30 s in total. The normal reconnect budget (3 attempts, 1/2/4 s) is deliberately untouched:
 * those attempts are for network trouble, this is for a server that still holds the old session.
 *
 * Why longer waits than the network backoff: the phone log shows two immediate retries both being
 * refused with the same 1008, i.e. the server needed *time* to notice that the old connection was
 * gone. Retrying faster only burns the schedule.
 */
class ServerBusyRetry(
    /** Delays between attempts, in order. */
    private val delaysMillis: List<Long> = DEFAULT_DELAYS,
    /** Total time this condition may hold the session, measured on the caller's clock. */
    private val totalBudgetMillis: Long = DEFAULT_BUDGET_MILLIS
) {

    /** How many 1008 "already connected" retries were started for this session. */
    var attemptsUsed: Int = 0
        private set

    /** First time this condition was seen (caller's clock), or 0 when it is not active. */
    var firstSeenAtMillis: Long = 0L
        private set

    /** When the next attempt is due, or 0 when no retry is scheduled. */
    var nextAttemptAtMillis: Long = 0L
        private set

    /** True while the session is waiting out this condition. */
    val active: Boolean get() = firstSeenAtMillis > 0L

    /** Time spent in this condition so far. */
    fun elapsedMillis(now: Long): Long =
        if (firstSeenAtMillis == 0L) 0L else (now - firstSeenAtMillis).coerceAtLeast(0L)

    /**
     * The next wait, or **null** when the budget is spent and the caller must give up.
     *
     * @param now caller's clock in milliseconds.
     */
    fun nextDelayMillis(now: Long): Long? {
        if (firstSeenAtMillis == 0L) firstSeenAtMillis = now
        val index = attemptsUsed.coerceAtMost(delaysMillis.lastIndex)
        val delay = delaysMillis[index]
        // Stop when either the schedule is used up or the next attempt would start after the
        // budget: a 12 s wait that begins at t+28 s can never finish inside 30 s.
        if (attemptsUsed >= delaysMillis.size) return null
        if (elapsedMillis(now) + delay > totalBudgetMillis) return null
        attemptsUsed = index + 1
        nextAttemptAtMillis = now + delay
        return delay
    }

    /** The session resumed: forget the condition, so a later episode starts from 3 s again. */
    fun reset() {
        attemptsUsed = 0
        firstSeenAtMillis = 0L
        nextAttemptAtMillis = 0L
    }

    /** One diagnostics line. */
    fun describe(now: Long): String =
        if (!active) {
            "none"
        } else {
            "attempts=$attemptsUsed/${delaysMillis.size}, elapsed=" +
                formatSeconds(elapsedMillis(now)) + "s, budget=${formatSeconds(totalBudgetMillis)}s"
        }

    companion object {
        /** 3 s, 5 s, 8 s, 12 s — 28 s of waiting before the last attempt starts. */
        val DEFAULT_DELAYS = listOf(3_000L, 5_000L, 8_000L, 12_000L)

        /** After this long, the session is ended cleanly instead of waiting forever. */
        const val DEFAULT_BUDGET_MILLIS = 30_000L

        /** The calm status line while this condition is being waited out. */
        const val STATUS_FIXING_CONNECTION = "সংযোগ ঠিক করছি…"
    }
}

/**
 * How many interruptions the session had and how long each one lasted — task 4 of the 0.2.13 list.
 *
 * An "interruption" starts when the socket stops working (send failure, close frame, failure) and
 * ends when `setupComplete` arrives again. Every timestamp is **relative to the start of the
 * session** (`t+12.8s`), never an epoch value, so the diagnostics block is readable on a phone.
 */
class InterruptionLog(private val capacity: Int = 12) {

    private data class Entry(val startedAtMillis: Long, val cause: String, var endedAtMillis: Long = 0L)

    private val entries = mutableListOf<Entry>()

    /** Session start, the reference for every `t+…` value. */
    var sessionStartedAtMillis: Long = 0L
        private set

    /** The interruption in progress, if any. */
    var openSinceMillis: Long = 0L
        private set

    var count: Int = 0
        private set

    fun onSessionStarted(now: Long) {
        sessionStartedAtMillis = now
        openSinceMillis = 0L
        count = 0
        entries.clear()
    }

    /** The socket stopped working: start timing. Repeats while one is open are ignored. */
    fun begin(now: Long, cause: String) {
        if (openSinceMillis > 0L) return
        openSinceMillis = now
        count += 1
        entries += Entry(now, cause.take(40))
        while (entries.size > capacity) entries.removeAt(0)
    }

    /** `setupComplete` arrived: the interruption (if one was open) is over. */
    fun end(now: Long) {
        if (openSinceMillis == 0L) return
        entries.lastOrNull()?.let { if (it.endedAtMillis == 0L) it.endedAtMillis = now }
        openSinceMillis = 0L
    }

    /** Total time spent interrupted so far, including an interruption still open. */
    fun totalMillis(now: Long): Long {
        var total = 0L
        for (entry in entries) {
            total += if (entry.endedAtMillis > 0L) {
                entry.endedAtMillis - entry.startedAtMillis
            } else {
                now - entry.startedAtMillis
            }
        }
        return total.coerceAtLeast(0L)
    }

    /** `#1 13.4s (AUDIO_SEND_FAILED) | #2 18.9s (SERVER_CLOSE)` — newest last. */
    fun summary(now: Long): String {
        if (entries.isEmpty()) return "none"
        return entries.withIndex().joinToString(" | ") { (index, entry) ->
            val length = if (entry.endedAtMillis > 0L) {
                entry.endedAtMillis - entry.startedAtMillis
            } else {
                now - entry.startedAtMillis
            }
            "#${index + 1} ${formatSeconds(length)}s (${entry.cause})"
        }
    }

    /** The line the diagnostics block prints: how many, and how long in total. */
    fun line(now: Long): String =
        "interruptions: $count (total ${formatSeconds(totalMillis(now))}s" +
            (if (openSinceMillis > 0L) ", one open for ${formatSeconds(now - openSinceMillis)}s" else "") +
            ") -> ${summary(now)}"
}

/**
 * A phone-friendly duration: always seconds with one decimal, never `1.2E7`.
 * Pure so the diagnostics unit tests can pin the exact text.
 */
fun formatSeconds(millis: Long): String {
    val safe = if (millis < 0L) 0L else millis
    val tenths = (safe + 50L) / 100L
    return "${tenths / 10}.${tenths % 10}"
}

/** `t+12.8s` relative to the session start (never an epoch timestamp). */
fun formatRelativeMillis(now: Long, sessionStartMillis: Long): String =
    "t+" + formatSeconds(if (sessionStartMillis <= 0L) 0L else now - sessionStartMillis) + "s"
