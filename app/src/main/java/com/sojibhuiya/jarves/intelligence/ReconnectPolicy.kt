package com.sojibhuiya.jarves.intelligence

/**
 * Why an automatic reconnect was considered.
 */
enum class ReconnectCause {
    /** No reconnect was considered. */
    NONE,

    /** The server sent `goAway` — it will disconnect soon (https://ai.google.dev/api/live#GoAway). */
    GO_AWAY,

    /** The socket failed under us (`onFailure`, e.g. "Socket closed", reset, TLS error). */
    SOCKET_FAILURE,

    /** The peer sent a close frame with a code that is not a normal end. */
    SERVER_CLOSE,

    /** 0.2.10: the network came back after we had been waiting for it. */
    NETWORK_BACK,

    /** 0.2.10: a new default network appeared while the session was healthy. */
    NEW_NETWORK,

    /** 0.2.10: a microphone audio **send** failed — the socket failure one step earlier. */
    AUDIO_SEND_FAILED,

    /**
     * 0.2.12: `AudioRecord.read()` returned an error code (`ERROR_DEAD_OBJECT`, …). Android usually
     * kills the recorder when the network drops, so this is the same failure one step earlier and
     * takes the same reconnect path — the 0.2.10 fix covered only the send side.
     */
    AUDIO_READ_FAILED
}

enum class ReconnectDecision(val automatic: Boolean) {
    /** Open a new socket now, resuming with the stored handle when there is one. */
    RECONNECT(true),

    /** The server told us it will disconnect: wait a moment, then reconnect. */
    RECONNECT_AFTER_GO_AWAY(true),

    /** The user (or the lifecycle) ended the session on purpose. Never reconnect. */
    NO_RECONNECT_INTENTIONAL(false),

    /** The engine is no longer running. */
    NO_RECONNECT_STOPPED(false),

    /** All attempts used — report one clear message and stop. */
    GIVE_UP(false);

    companion object {
        fun of(decision: ReconnectDecision) = decision
    }
}

/**
 * The automatic-reconnect rules, as pure logic so every branch is unit-tested.
 *
 * Policy (deliberate, documented in the 0.2.9 report):
 *  * at most [MAX_ATTEMPTS] automatic attempts per session, with exponential backoff
 *    (1 s, 2 s, 4 s) so a flapping network cannot spin;
 *  * **never** after the app itself ended the session — a `Socket closed` that follows our own
 *    `stop()` is OkHttp reporting our close, not a broken session;
 *  * a `goAway` is *expected* and is handled before the socket dies, so it does not consume the
 *    "unexpected failure" budget twice: it reconnects immediately with the resumption handle.
 */
object ReconnectPolicy {

    const val MAX_ATTEMPTS = 3

    /** 1 s, 2 s, 4 s — total wait 7 s across three attempts. */
    fun backoffMillis(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 4_000L
        else -> 4_000L
    }

    /**
     * How long to wait after a `goAway` before reconnecting: half of the server's remaining time,
     * clamped to 0.5–5 s, so the new socket exists before the old one is torn down but we do not
     * wait the whole remaining window doing nothing.
     */
    fun goAwayDelayMillis(timeLeftMillis: Long): Long =
        (timeLeftMillis / 2).coerceIn(500L, 5_000L)

    /**
     * @param running whether the engine still wants a session (`false` after stop/lifecycle end).
     * @param initiator who closed the previous socket (see [CloseInitiator]).
     * @param cause why we are here.
     * @param attemptsUsed automatic attempts already spent in this session.
     */
    fun decide(
        running: Boolean,
        initiator: CloseInitiator,
        cause: ReconnectCause,
        attemptsUsed: Int
    ): ReconnectDecision {
        if (!running) return ReconnectDecision.NO_RECONNECT_STOPPED
        if (initiator.intentional) return ReconnectDecision.NO_RECONNECT_INTENTIONAL
        if (cause == ReconnectCause.NONE) return ReconnectDecision.NO_RECONNECT_INTENTIONAL
        if (attemptsUsed >= MAX_ATTEMPTS) return ReconnectDecision.GIVE_UP
        return if (cause == ReconnectCause.GO_AWAY) {
            ReconnectDecision.RECONNECT_AFTER_GO_AWAY
        } else {
            ReconnectDecision.RECONNECT
        }
    }

    /** The one message the user sees when automatic recovery has been exhausted. */
    fun giveUpMessage(attempts: Int): String =
        "সংযোগ কয়েকবার বিচ্ছিন্ন হয়েছে এবং $attempts বার আবার জোড়া লাগানোর চেষ্টাও ব্যর্থ হয়েছে। " +
            "মাইক ও ইন্টারনেট দেখে আবার শুরু করুন।"
}
