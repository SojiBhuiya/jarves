package com.sojibhuiya.jarves.intelligence

/**
 * The failure-handling state machine of the live engine, extracted into pure Kotlin.
 *
 * Why this class exists (0.2.12): the three ways a session can break *without* the socket telling
 * us — an `AudioRecord` read error, an unparseable server frame and an `AudioTrack` write error —
 * used to be decided inline inside the engine class, which Android classes make impossible to
 * unit-test. The decisions now live here, where JVM unit tests drive them:
 *
 *  * a microphone **read** error is the socket failure one step earlier → reconnect
 *    (0.2.10 fixed only the *send* path; the phone report "airplane mode on/off" was still
 *    terminal through this path);
 *  * a **bad frame** must never end a conversation → skip that frame and keep going;
 *  * a **playback** error leaves a session that no longer produces sound, which the UI used to
 *    drop while the microphone kept recording ("ghost session") → stop the engine cleanly, so the
 *    foreground service is stopped by the existing `LiveState.FAILED` routing
 *    ([LiveStateRouting] → `releaseEngine = true`);
 *  * a **user stop** is terminal by definition and must never reconnect.
 *
 * Pure: no Android, no OkHttp, no threads of its own. The engine owns the real work, this class
 * only owns the *decision* and the one-time guards.
 */
class LiveFailureRouter {

    /** What the engine should do next. */
    enum class Action {
        /** Hand the failure to the reconnect path (`tryAutomaticReconnect`). */
        RECONNECT,

        /** Stop the engine for good and report one terminal error. */
        STOP_TERMINAL,

        /** Ignore this frame; the session stays alive. */
        SKIP_FRAME,

        /** Nothing to do: the session already ended, or a reconnect is already in flight. */
        IGNORE
    }

    /** True once the session is over (user stop, terminal failure) — no reconnect after this. */
    var ended: Boolean = false
        private set

    /** True between a scheduled reconnect and the following `setupComplete`. */
    var reconnectInFlight: Boolean = false
        private set

    /** Frames skipped because they could not be parsed. Shown in the diagnostics block. */
    var skippedFrames: Int = 0
        private set

    /** A fresh engine (or a fresh `start()`) begins a fresh session. */
    fun onSessionStarted() {
        ended = false
        reconnectInFlight = false
        skippedFrames = 0
    }

    /** The session is over: user stop, `stop(LIFECYCLE)`, a terminal `fail()`, or giving up. */
    fun onSessionEnded() {
        ended = true
        reconnectInFlight = false
    }

    /** The engine scheduled (or is waiting for the network before) a reconnect. */
    fun onReconnectScheduled() {
        reconnectInFlight = true
    }

    /** `setupComplete` arrived: the reconnect is finished, the session is healthy again. */
    fun onReconnectSucceeded() {
        reconnectInFlight = false
    }

    /**
     * `AudioRecord.read` returned a negative error code (`ERROR_DEAD_OBJECT`,
     * `ERROR_INVALID_OPERATION`, …).
     *
     * After a network blip Android often kills the recorder, so this is treated exactly like a
     * failed audio send: reconnect, and the microphone is recreated once `setupComplete` returns.
     * Only when the session has already ended, or a reconnect is already running, is nothing done.
     */
    fun onAudioReadError(): Action = when {
        ended -> Action.IGNORE
        reconnectInFlight -> Action.IGNORE
        else -> {
            reconnectInFlight = true
            Action.RECONNECT
        }
    }

    /**
     * A server frame could not be parsed. One bad frame is not a broken conversation: it is
     * skipped, counted, and the session continues (the docs make no promise that every future
     * frame has a shape this client knows).
     */
    fun onFrameParseError(): Action =
        if (ended) {
            Action.IGNORE
        } else {
            skippedFrames += 1
            Action.SKIP_FRAME
        }

    /**
     * `AudioTrack` threw while writing. The session can no longer speak, so it is ended cleanly
     * (engine + foreground service) instead of being left half-alive.
     */
    fun onPlaybackError(): Action =
        if (ended) {
            Action.IGNORE
        } else {
            ended = true
            reconnectInFlight = false
            Action.STOP_TERMINAL
        }

    /** One-line state for the diagnostics block. */
    fun describe(): String = "failure router: ended=$ended, reconnectInFlight=$reconnectInFlight, " +
        "skippedFrames=$skippedFrames"
}
