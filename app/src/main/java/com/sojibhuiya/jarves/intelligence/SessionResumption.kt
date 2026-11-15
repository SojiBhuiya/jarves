package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson

/**
 * Who ended the socket.
 *
 * The 0.2.8 phone report was
 * `code=0 | reason=Socket closed | Callback: onFailure | SetupComplete: true | 124 server messages`
 * — a *local* close (OkHttp only says "Socket closed" when this process closed the socket itself,
 * never for a peer reset), yet the diagnostic block could not say who did it. Every close site in
 * [com.sojibhuiya.jarves.GeminiLiveVoice] now records its reason here **before** calling
 * `WebSocket.close(...)`, so the next report names the culprit instead of guessing.
 *
 * `NONE` means no code path of ours closed the socket: the stream died underneath us.
 */
enum class CloseInitiator(val label: String) {
    /** No close of ours happened — the transport failed on its own. */
    NONE("none (no app-initiated close)"),

    /** The user pressed the stop button (or "New chat" while connected). */
    USER_STOP("user stop button"),

    /** The activity left composition: `DisposableEffect.onDispose` (destroy, config change, task removed). */
    LIFECYCLE("activity destroyed / left composition"),

    /** "New chat" — a fresh conversation must not inherit the old session's context. */
    NEW_CHAT("new chat"),

    /** Gemini was switched off in API Providers while a session was live. */
    PROVIDER_DISABLED("Gemini provider disabled"),

    /** `fail(...)` — setup never completed, an API error frame, or the mic could not be started. */
    SETUP_FAILURE("session failure (fail())"),

    /** The one-shot retry that drops the search tool after a refusal. */
    SEARCH_FALLBACK_RETRY("retry without search tool"),

    /** A close frame arrived from the server; the echo close is ours. */
    SERVER_CLOSE_FRAME("server closed the connection"),

    /** We are replacing the socket during an automatic reconnect. */
    RECONNECT("automatic reconnect");

    val intentional: Boolean
        get() = this in INTENTIONAL

    companion object {
        /**
         * Closes the app chose on purpose. These must **never** trigger an automatic reconnect
         * ("Do not auto-reconnect after the user pressed stop") and must never surface as an error
         * bubble: a `onFailure("Socket closed")` that follows one of these is just OkHttp reporting
         * our own close, not a failure of the session.
         */
        val INTENTIONAL: Set<CloseInitiator> = setOf(
            USER_STOP, LIFECYCLE, NEW_CHAT, PROVIDER_DISABLED, SEARCH_FALLBACK_RETRY, RECONNECT
        )
    }
}

/** One `sessionResumptionUpdate` frame. */
data class ResumptionUpdate(val newHandle: String?, val resumable: Boolean)

/** One `goAway` frame. [timeLeftMillis] is the server's `Duration` converted to milliseconds. */
data class GoAwayNotice(val timeLeftMillis: Long)

/**
 * Keeps the newest session-resumption handle and builds the `sessionResumption` field for the next
 * setup frame.
 *
 * Docs — https://ai.google.dev/gemini-api/docs/live-session#session-resumption:
 * "configure the `sessionResumption` field within the setup configuration. Passing this
 * configuration causes the server to send `SessionResumptionUpdate` messages, which can be used to
 * resume the session by passing the last resumption token as the `SessionResumptionConfig.handle`
 * of the subsequent connection." Tokens are valid for 2 hours after the session terminates.
 *
 * Field names — https://ai.google.dev/api/live#SessionResumptionConfig (`handle`) and
 * https://ai.google.dev/api/live#SessionResumptionUpdate (`newHandle`, `resumable`; "Empty if
 * `resumable`=false"). `resumable=false` means "resumption is not possible at this point … for
 * example, when the model is executing function calls or generating", so a handle is only accepted
 * while `resumable=true`.
 *
 * Not secret, but still never printed in full: [describe] shows presence and length only.
 */
class SessionResumptionState {

    @Volatile
    var handle: String? = null
        private set

    @Volatile
    var resumable: Boolean = false
        private set

    @Volatile
    var updates: Int = 0
        private set

    /** Applies one `SessionResumptionUpdate`. Returns true when a new usable handle was stored. */
    fun onUpdate(update: ResumptionUpdate): Boolean {
        updates += 1
        val fresh = update.newHandle?.takeIf { it.isNotBlank() && update.resumable }
        return if (fresh != null) {
            handle = fresh
            resumable = true
            true
        } else {
            // The docs are explicit that an empty newHandle with resumable=false is a *temporary*
            // "cannot resume right now", not "this handle is worthless": keep the last good one.
            resumable = false
            false
        }
    }

    /** The handle to resume with, or null for a fresh session. */
    fun handleForReconnect(): String? = handle?.takeIf { resumable } ?: handle

    /** The `sessionResumption` object for a setup frame, or null when there is nothing to resume. */
    fun setupField(): Map<String, Any?>? = handle?.let { linkedMapOf<String, Any?>("handle" to it) }

    fun clear() {
        handle = null
        resumable = false
        updates = 0
    }

    /** Secret-free one-liner for the diagnostics block. */
    fun describe(): String =
        "resume handle=" + (handle?.let { "present(${it.length})" } ?: "none") +
            " | resumable=$resumable | updates=$updates"

    companion object {

        /**
         * Parses `{"sessionResumptionUpdate":{"newHandle":"…","resumable":true}}`. Unknown or
         * malformed frames return null and never throw.
         */
        fun parseUpdate(json: String): ResumptionUpdate? = runCatching {
            val root = MiniJson.parse(json) as? Map<*, *> ?: return null
            val update = root["sessionResumptionUpdate"] as? Map<*, *> ?: return null
            ResumptionUpdate(
                newHandle = (update["newHandle"] as? String)?.takeIf { it.isNotBlank() },
                resumable = update["resumable"] as? Boolean ?: false
            )
        }.getOrNull()

        /**
         * Parses `{"goAway":{"timeLeft":"9.5s"}}`. The protobuf `Duration` is serialized as a
         * string with an `s` suffix (https://protobuf.dev/reference/protobuf/google.protobuf/#duration),
         * which is how the Live API sends it.
         */
        fun parseGoAway(json: String): GoAwayNotice? = runCatching {
            val root = MiniJson.parse(json) as? Map<*, *> ?: return null
            val goAway = root["goAway"] as? Map<*, *> ?: return null
            val raw = goAway["timeLeft"]
            val millis = when (raw) {
                is String -> {
                    val seconds = raw.removeSuffix("s").toDoubleOrNull() ?: return null
                    (seconds * 1000).toLong()
                }
                is Number -> (raw.toDouble() * 1000).toLong()
                else -> return null
            }
            GoAwayNotice(millis.coerceAtLeast(0L))
        }.getOrNull()
    }
}
