package com.sojibhuiya.jarves.intelligence

/**
 * 0.2.16 (task B) — the outgoing audio queue must not be able to kill the session.
 *
 * ### What the 0.2.15 phone log showed
 *
 * `Send failures: 1 (send=ok, queueSize=64151B, sinceLastServerMessage=7.5s, sinceLastGoodSend=0.0s,
 * network=mobile (CHANGED), app=background (screen on), reason=queue too large)` — and the user was
 * standing still with a working connection. So the "failure" was **our own rule**: 0.2.13 tore the
 * socket down as soon as OkHttp's outgoing queue held more than 64,000 bytes (two seconds of
 * 16 kHz, mono, 16-bit PCM), and the server then kept the old session alive, answering every new
 * connection with `1008 … already connected to an existing client`.
 *
 * ### What the OkHttp docs say (quoted)
 *
 *  * `WebSocket.queueSize()` — "Returns the size in bytes of all messages enqueued to be transmitted
 *    to the server. This doesn't include framing overhead. … It also doesn't include any bytes
 *    buffered by the operating system or network intermediaries. This method returns 0 if no
 *    messages are waiting in the queue."
 *    (https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/WebSocket.kt)
 *  * `WebSocket.send(text)` — "This method returns true if the message was enqueued. Messages that
 *    would overflow the outgoing message buffer will be rejected and trigger a graceful shutdown of
 *    this web socket. This method returns false in that case …"
 *  * the buffer itself: `RealWebSocket.MAX_QUEUE_SIZE = 16L * 1024 * 1024 // 16 MiB.`
 *    (https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/ws/RealWebSocket.kt)
 *
 * So OkHttp's own limit is 16 MiB, i.e. **our 64 KB rule was ~250× stricter than the library** and
 * fired on a two-second write stall. Nothing in the Live API docs says a silent but open WebSocket
 * is broken either; the "10 minutes" limit is about the *connection lifetime* only
 * (https://ai.google.dev/gemini-api/docs/live-session → "The lifetime of a connection is limited as
 * well, to around 10 minutes.").
 *
 * ### The rule this class implements
 *
 *  * a backed-up queue is **not** a failure: keep the socket, drop the OLDEST audio and keep roughly
 *    the last second of it, so memory and latency stay small;
 *  * only two things may declare the connection dead — OkHttp's own `onFailure`/`onClosing`/
 *    `onClosed`, or a stall longer than [DEAD_STALL_MILLIS] with **no write progress at all**
 *    (`queueSize()` not going down) **and** no server message in that time.
 *
 * Pure Kotlin (no Android, no clock of its own: every call takes `now`), so every branch is a unit
 * test. The engine feeds it one measurement per captured audio chunk.
 */
class StallTolerance(
    /** Above this the app starts dropping its own oldest audio instead of growing the queue. */
    private val softLimitBytes: Long = SOFT_LIMIT_BYTES,
    /** How much audio is kept when the queue is trimmed ("roughly the last 1 second"). */
    private val keepBytes: Long = KEEP_BYTES,
    /** A stall must last this long before the connection may be declared dead. */
    private val deadStallMillis: Long = DEAD_STALL_MILLIS,
    /** The calm status line appears only after this long (task B: "about 3 s"). */
    private val statusAfterMillis: Long = STATUS_AFTER_MILLIS
) {

    /** One sample of the capture loop's view of the socket. */
    enum class Verdict {
        /** Nothing to do: the queue is small or the socket is fine. */
        HEALTHY,

        /** The queue is backed up but within [deadStallMillis] — audio may be trimmed, never failed. */
        STALLED,

        /** The queue is still not moving after [deadStallMillis] with no server message: dead. */
        DEAD
    }

    var stalls: Int = 0
        private set

    var droppedChunks: Long = 0
        private set

    var droppedBytes: Long = 0
        private set

    /** Stalls that ended by themselves (the queue drained) — the "if the stall ends, say so" line. */
    var recovered: Int = 0
        private set

    /** The longest stall seen, in milliseconds. */
    var longestStallMillis: Long = 0
        private set

    var stallStartMillis: Long = 0L
        private set

    /** A flag, not the value 0: a test clock may legitimately start at 0. */
    private var stallingNow: Boolean = false

    var statusShown: Boolean = false
        private set

    /** When [trim] last dropped audio, so it cannot run on every chunk. */
    var lastTrimAtMillis: Long = 0L
        private set

    /** Diagnostics timeline entries, newest last. */
    private val events = mutableListOf<String>()

    /** The last end reason, for the diagnostics line. */
    var lastEndRule: String = "none"
        private set

    val stalling: Boolean get() = stallingNow

    /**
     * One measurement.
     *
     * @param now caller's clock.
     * @param queueSize what `WebSocket.queueSize()` returned, or a negative value when unknown.
     * @param sinceLastServerMessage how long the socket has been silent, in ms.
     * @param sessionStart the session start, so every event is printed as `t+…s`.
     */
    fun onSample(
        now: Long,
        queueSize: Long,
        sinceLastServerMessage: Long,
        sessionStart: Long
    ): Verdict {
        val backedUp = queueSize >= softLimitBytes
        if (!backedUp) {
            if (stallingNow) endStall(now, sessionStart, "queue drained (${queueSize}B)")
            return Verdict.HEALTHY
        }
        if (!stallingNow) {
            stallingNow = true
            stallStartMillis = now
            stalls += 1
            statusShown = false
            lastTrimAtMillis = 0L
            events += "${stamp(now, sessionStart)} stall started at ${queueSize}B"
        }
        val length = now - stallStartMillis
        longestStallMillis = maxOf(longestStallMillis, length)
        // Dead only when BOTH halves are true: no write progress and a completely silent server.
        if (length >= deadStallMillis && sinceLastServerMessage >= deadStallMillis) {
            lastEndRule = "no write progress for ${length}ms and no server message for ${sinceLastServerMessage}ms"
            return Verdict.DEAD
        }
        return Verdict.STALLED
    }

    /** True when the calm status line is due (the stall lasted at least [statusAfterMillis]). */
    fun shouldShowStatus(now: Long): Boolean {
        if (!stallingNow || statusShown) return false
        if (now - stallStartMillis < statusAfterMillis) return false
        statusShown = true
        return true
    }

    /**
     * Trims the local audio buffer down to [keepBytes] — the newest audio is what the user is saying
     * now, so the OLD bytes go. Returns how many bytes were dropped, or 0 when nothing had to go.
     *
     * **Trimming is rate limited** to once every [TRIM_INTERVAL_MILLIS] of *stall time* (once a
     * second). Without the limit the capture loop — which reads faster than the network sends — would
     * trim on every chunk and the buffer would only ever contain the newest chunk, so the backlog
     * would never drain at all; with it, up to ~1 s of audio is held and drained at about twice real
     * time.
     *
     * The queue itself belongs to OkHttp and cannot be trimmed from outside (the docs offer
     * `queueSize()` and `cancel()` only, and OkHttp's own buffer is 16 MiB), so the trimming happens
     * on our own buffer **before** the bytes are handed to `send()`; that keeps memory and the added
     * latency bounded.
     *
     * @param now caller's clock (only used for the rate limit).
     */
    fun trim(now: Long, buffer: ArrayDeque<ByteArray>): Long {
        if (lastTrimAtMillis > 0L && now - lastTrimAtMillis < TRIM_INTERVAL_MILLIS) return 0L
        lastTrimAtMillis = now
        return trimNow(buffer)
    }

    /** The unbounded version, kept public for the unit tests that check the cap itself. */
    fun trimNow(buffer: ArrayDeque<ByteArray>): Long {
        if (buffer.isEmpty()) return 0L
        var total = 0
        for (chunk in buffer) total += chunk.size
        if (total <= keepBytes) return 0L
        var dropped = 0L
        var droppedCount = 0
        while (buffer.isNotEmpty() && total > keepBytes) {
            val oldest = buffer.removeFirst()
            total -= oldest.size
            dropped += oldest.size
            droppedCount += 1
        }
        droppedBytes += dropped
        droppedChunks += droppedCount
        return dropped
    }

    /** The stall ended without a reconnect (the queue drained): record it and say so. */
    fun endStall(now: Long, sessionStart: Long, why: String) {
        if (!stallingNow) return
        val length = now - stallStartMillis
        stallingNow = false
        stallStartMillis = 0L
        statusShown = false
        lastTrimAtMillis = 0L
        recovered += 1
        lastEndRule = "recovered by itself after ${length}ms ($why)"
        events += "${stamp(now, sessionStart)} stall ended after ${length}ms ($why) — no reconnect"
    }

    /** Whatever ended the session: make the open stall visible with its full length. */
    fun onSessionEnded(now: Long, sessionStart: Long, why: String) {
        if (!stallingNow) return
        val length = now - stallStartMillis
        stallingNow = false
        stallStartMillis = 0L
        lastEndRule = "ended by $why after ${length}ms"
        events += "${stamp(now, sessionStart)} stall open for ${length}ms when $why"
    }

    fun timeline(): List<String> = events.toList()

    /** One compact line for the diagnostics block. */
    fun describe(now: Long): String {
        val open = if (stallingNow) ", one open for ${now - stallStartMillis}ms" else ""
        return "stalls=$stalls (longest ${longestStallMillis}ms$open) | recovered=$recovered | " +
            "dropped=$droppedChunks chunks/${droppedBytes}B | last end: $lastEndRule"
    }

    private fun stamp(now: Long, sessionStart: Long): String =
        if (sessionStart <= 0L) "t+0.0s" else formatRelativeMillis(now, sessionStart)

    companion object {
        /**
         * Where the app starts trimming instead of failing. Two seconds of audio at 32,000 B/s is
         * 64,000 B — the number 0.2.13 used as a *hard* limit; it is now only the point where the
         * oldest audio starts to go.
         */
        const val SOFT_LIMIT_BYTES = 64_000L

        /** "keep roughly the last 1 second": 16,000 samples × 2 bytes = 32,000 B. */
        const val KEEP_BYTES = 32_000L

        /** 12 s: longer than any write stall a phone network produces, short enough to recover. */
        const val DEAD_STALL_MILLIS = 12_000L

        /** The calm line appears only after ~3 s, so a normal hiccup stays invisible. */
        const val STATUS_AFTER_MILLIS = 3_000L

        /** At most one trim per second of stall: keeps ~1 s of audio in hand instead of one chunk. */
        const val TRIM_INTERVAL_MILLIS = 1_000L

        /** The calm, non-alarming status line (may be replaced by the engine's own wording). */
        const val STATUS_STALLED = "সংযোগ ধীর — শব্দ পাঠানো সাময়িকভাবে কমিয়েছি, কথা বলুন…"
    }
}
