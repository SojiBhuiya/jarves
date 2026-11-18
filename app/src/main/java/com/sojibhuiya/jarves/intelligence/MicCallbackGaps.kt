package com.sojibhuiya.jarves.intelligence

/** Counts positive AudioRecord reads. A cross-minute gap belongs to the later read's minute. */
internal class MicCallbackGaps {
    data class Sample(val callbacks: Int, val maxGapMillis: Long?)

    private var previous: Long? = null
    private var callbacks = 0
    private var maxGap: Long? = null

    @Synchronized fun onRead(now: Long) {
        previous?.let { maxGap = maxOf(maxGap ?: 0L, now - it) }
        previous = now
        callbacks++
    }

    /** A recorder was replaced; don't mistake the time with no recorder for CPU suspension. */
    @Synchronized fun onRecorderEnded() { previous = null }

    @Synchronized fun take(): Sample = Sample(callbacks, maxGap).also {
        callbacks = 0
        maxGap = null
    }
}
