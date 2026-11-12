package com.sojibhuiya.jarves.intelligence

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit connection state of a live session.
 *
 * This exists so the UI never has to infer state by searching Bengali status *text*. Text is for
 * the user to read; state is for the app to act on.
 */
enum class LiveState {
    /** Opening the socket or sending setup — including the tool-less retry, which is still connecting. */
    CONNECTING,

    /** `setupComplete` received with live search available. */
    CONNECTED_GROUNDED,

    /** `setupComplete` received without live search (the fallback). Voice works; verification does not. */
    CONNECTED_WITHOUT_SEARCH,

    /** A normal end: the user stopped, or the server finished cleanly. */
    ENDED,

    /** A terminal error. */
    FAILED
}

/** What the UI should do with its two booleans and its engine reference. */
data class UiConnectionState(
    val connected: Boolean,
    val connecting: Boolean,
    val releaseEngine: Boolean
)

/** The single mapping from protocol state to UI state. Pure, so it is fully unit-tested. */
object LiveStateRouting {

    fun route(state: LiveState): UiConnectionState = when (state) {
        LiveState.CONNECTING ->
            UiConnectionState(connected = false, connecting = true, releaseEngine = false)

        LiveState.CONNECTED_GROUNDED,
        LiveState.CONNECTED_WITHOUT_SEARCH ->
            UiConnectionState(connected = true, connecting = false, releaseEngine = false)

        // The engine owns its own teardown; the UI only drops its reference.
        LiveState.ENDED,
        LiveState.FAILED ->
            UiConnectionState(connected = false, connecting = false, releaseEngine = true)
    }
}

/**
 * Handles the end of one socket, **exactly once**.
 *
 * Why this class exists: OkHttp only calls `onClosed` after *both* peers have closed, so a
 * server-initiated close arrives in `onClosing` and never reaches a handler registered only on
 * `onClosed`. Waiting for `onClosed` blocks the fallback indefinitely. Both callbacks therefore
 * feed this object, and a thread-safe one-time guard makes the first one win.
 *
 * Order inside [handleCloseFrame]: echo the close **first** (completes the closing handshake and
 * releases the connection), then decide and act. Acting never waits for anything.
 */
class LiveCloseFlow(
    /** Sends `close(1000, …)` on the socket that just received a close frame. Called at most once. */
    private val echoClose: (code: Int, reason: String) -> Unit,
    /** Starts the single tool-less reconnect. Called at most once per socket. */
    private val retryWithoutSearch: (source: String) -> Unit,
    /** Reports a terminal failure. */
    private val reportFailure: (code: Int, reason: String) -> Unit,
    /** Reports a clean end. */
    private val reportStopped: () -> Unit
) {

    private val handled = AtomicBoolean(false)

    /** Called by the engine when a new socket is opened, so the latch is per-socket. */
    fun reset() {
        handled.set(false)
    }

    /** Diagnostics and tests: was the end of the current socket already handled? */
    fun wasHandled(): Boolean = handled.get()

    /**
     * A close frame from the server (or the echo of our own close). Echoes once, then decides.
     *
     * @param hadSearchTool whether the setup that was just sent declared `googleSearch`.
     */
    fun handleCloseFrame(
        code: Int,
        reason: String,
        hadSearchTool: Boolean,
        setupComplete: Boolean,
        fallbackUsed: Boolean
    ) {
        if (!handled.compareAndSet(false, true)) return
        // 1. Complete the closing handshake exactly once, regardless of the outcome.
        runCatching { echoClose(code, reason) }
        // 2. Decide and act. Never waits for onClosed.
        act(code, reason, hadSearchTool, setupComplete, fallbackUsed)
    }

    /**
     * A transport failure (`onFailure`). There is no close frame to echo.
     *
     * An HTTP 429 during the upgrade while search was declared is the same recoverable condition
     * as a quota close frame, so it takes the same one-shot retry; anything else is terminal.
     */
    fun handleFailure(
        httpCode: Int,
        message: String,
        hadSearchTool: Boolean,
        setupComplete: Boolean,
        fallbackUsed: Boolean
    ) {
        if (!handled.compareAndSet(false, true)) return
        if (httpCode == 429 && !setupComplete && hadSearchTool && !fallbackUsed) {
            retryWithoutSearch("HTTP $httpCode upgrade refusal")
            return
        }
        act(httpCode, message, hadSearchTool, setupComplete, fallbackUsed)
    }

    private fun act(
        code: Int,
        reason: String,
        hadSearchTool: Boolean,
        setupComplete: Boolean,
        fallbackUsed: Boolean
    ) {
        when (LiveSessionPlan.decide(hadSearchTool, setupComplete, fallbackUsed, code, reason)) {
            SessionAction.RETRY_WITHOUT_TOOLS -> retryWithoutSearch("close frame $code")
            SessionAction.STOP -> reportStopped()
            SessionAction.FAIL -> reportFailure(code, reason)
        }
    }
}
