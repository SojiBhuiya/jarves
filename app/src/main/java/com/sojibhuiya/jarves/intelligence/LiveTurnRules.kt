package com.sojibhuiya.jarves.intelligence

/**
 * 0.2.15 — the turn rules from the phone report, in one place so every number and every rule can be
 * quoted by the diagnostics instead of being hidden inside the two long classes that use them.
 *
 * Doc anchors used by this file (read from the official reference, not from memory):
 *  * https://ai.google.dev/api/live#BidiGenerateContentClientContent — `turnComplete`: "If true,
 *    indicates that the server content generation should start with the currently accumulated
 *    prompt. Otherwise, the server awaits additional messages before starting generation."
 *    → this is why every silent injection must carry `turnComplete = false`.
 *  * https://ai.google.dev/api/live#BidiGenerateContentServerContent — `turnComplete` /
 *    `generationComplete` are the two completion flags of a server turn.
 *  * https://ai.google.dev/gemini-api/docs/live-guide — native-audio models answer in AUDIO only;
 *    the text we read comes from `outputAudioTranscription`, in chunks, so several chunks belong to
 *    ONE spoken answer.
 */
object TurnRules {

    /**
     * Rule 10: how long the search waits after the last `serverContent` of any kind before it says
     * "the answer is finished". 2.5 s is longer than the natural pause between two sentences of the
     * native-audio model (the 0.2.14 phone run had no gap at all inside the 11-word answer) and far
     * shorter than the 20 s cap the user complained about.
     */
    const val QUIET_GAP_MILLIS = 2_500L

    /** Rule 7: "did the model speak within 2 s of this injection?" — the measurement window. */
    const val MODEL_SPEECH_WINDOW_MILLIS = 2_000L

    /** Rule 9: how many of the last server messages the diagnostics keep. */
    const val SEARCH_MESSAGE_LOG_CAPACITY = 100

    /** Rule 6: 8 s was too tight (0.2.14 measured setupComplete at 4004 ms on a good network). */
    const val SETUP_TIMEOUT_MILLIS = 15_000L

    /** Rule 6: exactly one retry, and only for a setup timeout. */
    const val SETUP_RETRIES = 1

    /** Rule 4: app-injected context is silent — it must never start a generation. */
    const val SILENT_INJECTION_TURN_COMPLETE = false

    /** Rule 10: the last resort, unchanged from 0.2.14. */
    const val TIMEOUT_CAP_MILLIS = 20_000L
}

/** Rule 10: which rule ended the search, printed in the answer + diagnostics. */
enum class SearchEndRule(val label: String) {
    TURN_COMPLETE("turnComplete"),
    GENERATION_COMPLETE("generationComplete"),
    QUIET_GAP("quiet gap"),
    TIMEOUT("timeout"),
    CLOSED("socket closed"),
    FAILED("failed")
}

/**
 * Rules 1–3: the state machine that keeps the assistant to **one** acknowledgement and **one**
 * failure sentence per search.
 *
 * It is deliberately ignorant of Android and of the socket: [GeminiLiveVoice] feeds it the events,
 * [describe] turns it into the diagnostics line, and the unit tests drive it directly.
 */
class SearchTurnGuard {

    /** Tool calls seen in this session (rule 1 counts searches, not turns). */
    var toolCalls: Int = 0
        private set

    /** True between `toolCall` and the matching `toolResponse`. */
    var searchPending: Boolean = false
        private set

    /** Spoken (transcribed) chunks while a search was pending — rule 1 says this must stay tiny. */
    var speechWhilePending: Int = 0
        private set

    /** Completed assistant turns while a search was pending = acknowledgements (rule 1: at most 1). */
    var acknowledgements: Int = 0
        private set

    /** Rule 2: how many times a real failure was announced (must never exceed 1 per search). */
    var failuresAnnounced: Int = 0
        private set

    /** Rule 3/4: how many "cannot verify"-style notes were let through. */
    var contextInjections: Int = 0
        private set

    /** Rule 1/3 violations, in order — empty means the phone report cannot repeat. */
    private val violations = mutableListOf<String>()

    /** Rule 1: a search starts. The model may say one short ack and then has to stay quiet. */
    fun onToolCall() {
        toolCalls += 1
        searchPending = true
    }

    /** Any transcription chunk. Counted only while a search is pending (that is the ack window). */
    fun onModelSpoke() {
        if (searchPending) speechWhilePending += 1
    }

    /**
     * Rule 5: one finished assistant turn. While a search is pending this *is* the acknowledgement,
     * so a second one is exactly the "একটু সময় দিন" + apology pair from the phone report.
     */
    fun onAssistantTurnEnded() {
        if (!searchPending) return
        acknowledgements += 1
        if (acknowledgements > 1) {
            violations += "acknowledged $acknowledgements times while search pending"
        }
    }

    /** Rule 1/2: the toolResponse arrived. A real failure is announced once, a success never. */
    fun onToolResponseSent(failed: Boolean) {
        searchPending = false
        if (failed) failuresAnnounced += 1
    }

    /** Rule 1: more than one acknowledgement inside one search = the reported bug. */
    fun acknowledgedMoreThanOnce(): Boolean = acknowledgements > 1

    /**
     * 0.2.16 (task 5): the counter bug. `acks`/`speechWhilePending` are **per search**, so five
     * searches with one acknowledgement each must report zero violations; 0.2.15 compared the
     * cumulative acknowledgement count with a per-call limit and printed
     * "acknowledged 2 times while search pending … 5 times".
     *
     * Called by the engine on every new `toolCall`, before [onToolCall].
     */
    fun startSearch(callId: String) {
        lastCallId = callId
        acknowledgements = 0
        speechWhilePending = 0
    }

    /** The call id of the search these counters belong to (diagnostics only, never printed in full). */
    var lastCallId: String = ""
        private set

    /** Rule 3/4: may the app add "I could not verify" context right now? */
    fun canInjectContext(): Boolean = !searchPending && failuresAnnounced == 0

    /** Rule 3: the same question, in the caller's words — needs no search pending and none tried. */
    fun mayAnnounceCannotVerify(searchedThisTurn: Boolean): Boolean =
        !searchPending && !searchedThisTurn && failuresAnnounced == 0

    /** Rule 3/4: recorded by [GeminiLiveVoice.injectSilentContext] after a silent frame was sent. */
    fun onContextInjected() {
        contextInjections += 1
    }

    fun violations(): List<String> = violations.toList()

    fun describe(): String = buildString {
        append("pending=").append(searchPending)
        append(", toolCalls=").append(toolCalls)
        append(", acks(this search)=").append(acknowledgements)
        append(", speechWhilePending=").append(speechWhilePending)
        append(", failures=").append(failuresAnnounced)
        append(", injections=").append(contextInjections)
        if (violations.isNotEmpty()) append(", violations=").append(violations.joinToString("; "))
    }
}

/**
 * Rules 3 and 4: the single place that decides whether the "I have no live sources / I could not
 * verify" note may be injected. Every other call site must ask this object, so the list of places
 * that can produce that sentence stays one line long.
 */
object NoticePolicy {

    fun shouldInject(
        needsLiveSources: Boolean,
        groundingForTurn: Boolean,
        noticeSentForTurn: Boolean,
        searchPending: Boolean,
        searchedThisTurn: Boolean,
        canInjectContext: Boolean
    ): Boolean =
        needsLiveSources &&
            !groundingForTurn &&
            !noticeSentForTurn &&
            canInjectContext &&
            !searchPending &&
            !searchedThisTurn

    fun describe(
        needsLiveSources: Boolean,
        searchPending: Boolean,
        searchedThisTurn: Boolean,
        allowed: Boolean
    ): String = "notice needed=$needsLiveSources, searchPending=$searchPending, " +
        "searchedThisTurn=$searchedThisTurn -> " + if (allowed) "injected silently" else "suppressed"
}

/**
 * Rule 5: the output transcriptions of ONE model turn are one chat message.
 *
 * The native-audio model streams `outputTranscription` in small chunks ("দেখে", "নিচ্ছি।দুঃখিত…"),
 * and 0.2.14 sent each chunk to the chat, which is what fragmented the bubbles.
 */
class TurnTranscript {
    private val builder = StringBuilder()

    /** Number of chunks appended since the last flush — diagnostics only. */
    var chunks: Int = 0
        private set

    fun append(chunk: String) {
        if (chunk.isEmpty()) return
        builder.append(chunk)
        chunks += 1
    }

    fun peek(): String = builder.toString()

    /** Returns the whole turn, trimmed, or null when there is nothing to show; always clears. */
    fun flush(): String? {
        val text = builder.toString().trim()
        builder.setLength(0)
        chunks = 0
        return text.ifEmpty { null }
    }

    fun clear() {
        builder.setLength(0)
        chunks = 0
    }
}

/**
 * Rule 7: the ledger of everything the app itself pushed into the session, and whether the model
 * answered it. This is the measurement that settles the 0.2.12 hypothesis (injecting with
 * `turnComplete = true` made the model talk) instead of arguing about it.
 */
class InjectionLedger {

    private data class Entry(
        val atMillis: Long,
        val line: String,
        val windowMillis: Long
    )

    private val entries = mutableListOf<Entry>()
    private val speech = mutableListOf<Long>()

    fun onClientContent(now: Long, kind: String, turnComplete: Boolean, bytes: Int) {
        entries += Entry(
            atMillis = now,
            line = "clientContent $kind turnComplete=$turnComplete ${bytes}B",
            windowMillis = TurnRules.MODEL_SPEECH_WINDOW_MILLIS
        )
    }

    fun onToolResponse(now: Long, scheduling: String, bytes: Int) {
        entries += Entry(
            atMillis = now,
            line = "toolResponse scheduling=$scheduling ${bytes}B",
            windowMillis = TurnRules.MODEL_SPEECH_WINDOW_MILLIS
        )
    }

    /** Any transcription chunk: the model is speaking. */
    fun onModelSpoke(now: Long) {
        speech += now
    }

    fun lines(sessionStartMillis: Long): List<String> =
        entries.map { entry ->
            val reaction = speech.firstOrNull { it > entry.atMillis && it - entry.atMillis <= entry.windowMillis }
            val head = "t+${seconds(entry.atMillis - sessionStartMillis)} ${entry.line}"
            if (reaction == null) {
                "$head -> no speech within ${TurnRules.MODEL_SPEECH_WINDOW_MILLIS / 1000}s"
            } else {
                "$head -> model spoke t+${seconds(reaction - sessionStartMillis)}"
            }
        }

    fun text(sessionStartMillis: Long): String =
        lines(sessionStartMillis).joinToString(" | ").ifEmpty { "none" }

    val size: Int get() = entries.size

    companion object {
        fun seconds(millis: Long): String = String.format(java.util.Locale.US, "%.1fs", millis / 1000.0)
    }
}

/**
 * Rule 9: the last 100 raw server messages with `t+seconds` and a classified type, so the phone
 * diagnostics can answer "was there a turnComplete at all?" without a packet capture.
 */
class ServerMessageLog(private val capacity: Int = TurnRules.SEARCH_MESSAGE_LOG_CAPACITY) {

    private data class Entry(val atMillis: Long, val type: String, val bytes: Int, val gapMillis: Long)

    private val entries = ArrayDeque<Entry>()
    private var lastAtMillis = 0L

    var dropped: Int = 0
        private set

    /** 0.2.16 (task 6): counted, never listed. */
    var modelTurnCount: Int = 0
        private set
    var transcriptionCount: Int = 0
        private set
    var firstTranscriptionAtMillis: Long = 0L
        private set
    var lastTranscriptionAtMillis: Long = 0L
        private set
    var lastModelTurnAtMillis: Long = 0L
        private set

    /**
     * 0.2.16 (task 6): `modelTurn` and `outputTranscription` frames are the two that arrive dozens of
     * times per answer; they are counted in [modelTurnCount] / [transcriptionCount] instead of being
     * listed. Everything else (setup, completion flags, tool calls, errors) is logged as before —
     * that is what the "why did it wait?" question needs.
     */
    fun record(atMillis: Long, raw: String) {
        val type = classify(raw)
        if (type.contains("modelTurn")) {
            modelTurnCount += 1
            lastAtMillis = atMillis
            lastModelTurnAtMillis = atMillis
            return
        }
        if (type.contains("outputTranscription")) {
            transcriptionCount += 1
            if (firstTranscriptionAtMillis == 0L) firstTranscriptionAtMillis = atMillis
            lastAtMillis = atMillis
            lastTranscriptionAtMillis = atMillis
            return
        }
        val gap = if (lastAtMillis == 0L) 0L else atMillis - lastAtMillis
        lastAtMillis = atMillis
        entries.addLast(Entry(atMillis, type, raw.length, gap))
        while (entries.size > capacity) {
            entries.removeFirst()
            dropped += 1
        }
    }

    val size: Int get() = entries.size

    fun lines(startedAt: Long): List<String> = entries.map { entry ->
        val gap = if (entry.gapMillis > 0L) " (+${InjectionLedger.seconds(entry.gapMillis)})" else ""
        "t+${InjectionLedger.seconds(entry.atMillis - startedAt)}$gap ${entry.type} ${entry.bytes}B"
    }

    fun text(startedAt: Long): String {
        val counts = "modelTurn x$modelTurnCount, transcr x$transcriptionCount"
        val listed = lines(startedAt).joinToString(" | ").ifEmpty { "none" }
        return "$counts | $listed"
    }

    companion object {
        /** Only the shape is needed in the log: the type names come from the Live API reference. */
        fun classify(raw: String): String = when {
            raw.contains("\"setupComplete\"") -> "setupComplete"
            raw.contains("\"goAway\"") -> "goAway"
            raw.contains("\"sessionResumptionUpdate\"") -> "sessionResumptionUpdate"
            raw.contains("\"usageMetadata\"") -> "usageMetadata"
            raw.contains("\"toolCallCancellation\"") -> "toolCallCancellation"
            raw.contains("\"toolCall\"") -> "toolCall"
            raw.contains("\"turnComplete\"") -> "serverContent.turnComplete"
            raw.contains("\"generationComplete\"") -> "serverContent.generationComplete"
            raw.contains("\"interrupted\"") -> "serverContent.interrupted"
            raw.contains("\"outputTranscription\"") -> "serverContent.outputTranscription"
            raw.contains("\"inputTranscription\"") -> "serverContent.inputTranscription"
            raw.contains("\"modelTurn\"") -> "serverContent.modelTurn"
            raw.contains("\"serverContent\"") -> "serverContent"
            raw.contains("\"error\"") -> "error"
            else -> "unknown"
        }
    }
}
