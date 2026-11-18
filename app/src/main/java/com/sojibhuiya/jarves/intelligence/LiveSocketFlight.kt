package com.sojibhuiya.jarves.intelligence

import okhttp3.WebSocket

internal fun interface LiveTask { fun cancel() }

internal interface LiveScheduler {
    fun nowMillis(): Long
    /** Enqueue, never call inline. */
    fun after(delayMillis: Long, action: () -> Unit): LiveTask
    fun shutdown()
}

/**
 * One primary conversation socket, including the opening and retiring states. All transitions run
 * under the voice engine's session lock. Cancel first, then wait for a terminal listener callback
 * AND the transport to become idle. Returning from cancel(), or receiving onClosing, does not
 * mean the preceding handshake/reader has finished.
 */
internal class LiveSocketFlight(
    private val now: () -> Long,
    private val after: (Long, () -> Unit) -> LiveTask,
    private val isTransportIdle: () -> Boolean,
    private val log: (String) -> Unit,
    private val onTimeout: (opening: Boolean) -> Unit,
    private val onOpenError: (Exception) -> Unit
) {
    private enum class Phase { OPENING, SETUP, READY, RETIRING }
    private class Slot(val socket: WebSocket, val id: Int, var deadline: Long) {
        var phase = Phase.OPENING
        var terminal = false
    }
    private class Pending(val at: Long, val open: () -> WebSocket?)

    private var slot: Slot? = null
    private var next: Pending? = null
    private var wakeup: LiveTask? = null
    private var timeout: LiveTask? = null
    private var sequence = 0
    private var stopped = false

    @Volatile var socket: WebSocket? = null
        private set

    val hasPending: Boolean get() = next != null
    val ready: Boolean get() = slot?.phase == Phase.READY
    val opening: Boolean get() = slot?.phase == Phase.OPENING

    /** Replace the one pending request (e.g. validation accelerates a scheduled network retry). */
    fun request(delayMillis: Long, open: () -> WebSocket?) {
        if (stopped) return
        wakeup?.cancel()
        val pending = Pending(now() + delayMillis, open)
        next = pending
        if (delayMillis == 0L) pump() else wakeup = after(delayMillis) {
            if (next === pending) pump()
        }
    }

    fun transportIdle() = pump()

    fun terminal(ws: WebSocket) {
        val previous = slot?.takeIf { it.socket === ws } ?: return
        previous.terminal = true
        pump()
    }

    private fun pump() {
        val pending = next ?: return
        if (stopped || now() < pending.at) return
        val previous = slot
        if (previous != null) {
            retire("replacement requested")
            if (!previous.terminal) return
        }
        if (!isTransportIdle()) return
        slot = null
        next = null
        wakeup?.cancel()
        wakeup = null
        val id = ++sequence
        val started = now()
        try {
            val ws = pending.open() ?: return
            val active = Slot(ws, id, started + OPEN_MILLIS)
            slot = active
            socket = ws
            log("attempt #$id started")
            armTimeout(active)
        } catch (e: Exception) {
            log("attempt #$id failed to start (${e.javaClass.simpleName})")
            onOpenError(e)
        }
    }

    /** Do not expose a retiring transport to capture or to late application callbacks. */
    fun retire(reason: String) {
        val active = slot ?: return
        if (active.phase == Phase.RETIRING) return
        timeout?.cancel()
        timeout = null
        val outcome = if (active.phase == Phase.READY) "socket ended" else "failed"
        active.phase = Phase.RETIRING
        socket = null
        log("attempt #${active.id} $outcome ($reason); cancelling socket")
        runCatching { active.socket.cancel() }
    }

    fun opened(ws: WebSocket, httpCode: Int): Boolean {
        val active = slot?.takeIf { it.socket === ws && it.phase == Phase.OPENING } ?: return false
        if (expired(active)) return false
        log("attempt #${active.id} socket open (HTTP $httpCode)")
        active.phase = Phase.SETUP
        active.deadline = now() + SETUP_MILLIS
        armTimeout(active)
        return true
    }

    fun setupSent(hadHandle: Boolean, fresh: Boolean) {
        slot?.let { log("attempt #${it.id} setup sent (handle=$hadHandle, fresh=$fresh)") }
    }

    fun setupComplete(ws: WebSocket): Boolean {
        val active = slot?.takeIf { it.socket === ws && it.phase == Phase.SETUP } ?: return false
        if (expired(active)) return false
        timeout?.cancel()
        timeout = null
        active.phase = Phase.READY
        log("attempt #${active.id} setupComplete")
        return true
    }

    private fun armTimeout(active: Slot) {
        timeout?.cancel()
        timeout = after((active.deadline - now()).coerceAtLeast(0L)) {
            if (slot === active) expired(active)
        }
    }

    private fun expired(active: Slot): Boolean {
        if (active.phase != Phase.OPENING && active.phase != Phase.SETUP) return false
        if (now() < active.deadline) return false
        val opening = active.phase == Phase.OPENING
        val step = if (opening) "TCP/TLS/WebSocket open" else "setupComplete"
        log("attempt #${active.id} timed out ($step)")
        retire("$step timeout")
        onTimeout(opening)
        return true
    }

    fun stop() {
        stopped = true
        next = null
        wakeup?.cancel()
        wakeup = null
        retire("session ended")
    }

    companion object {
        const val OPEN_MILLIS = 10_000L
        const val SETUP_MILLIS = 10_000L
    }
}
