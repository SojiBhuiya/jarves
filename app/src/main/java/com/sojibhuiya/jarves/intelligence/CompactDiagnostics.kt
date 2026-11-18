package com.sojibhuiya.jarves.intelligence

/**
 * 0.2.16 (task 6) — the diagnostics that fits in a phone dialog.
 *
 * 0.2.15 printed every `serverContent.modelTurn` frame of the last 100 messages, which pushed the
 * copied diagnostics block far past what a person can read (and what a clipboard can usefully hold).
 * Per search the block now carries only *counts* and *times*:
 *
 * `search#1 total=8.8s | modelTurn=11 transcr=4 | first transcript=5.1s | last msg before
 *  generationComplete=8.7s (+0.1s) | ended=generationComplete | 14 words`
 *
 * The whole block is kept under [MAX_CHARS] (~6,000) by dropping the oldest per-search sections and
 * by trimming the message log, never by cutting a line in half.
 */
object CompactDiagnostics {

    /** Task 6: "keep the whole copied diagnostics text under about 6000 characters". */
    const val MAX_CHARS = 6_000

    /** How many per-search lines the compact block keeps (newest kept, oldest dropped). */
    const val MAX_SEARCH_LINES = 12

    /** One search, as counted from the messages themselves. */
    data class SearchSummary(
        val index: Int,
        val modelTurnMessages: Int,
        val transcriptionMessages: Int,
        val firstTranscriptionMillis: Long,
        val lastMessageBeforeCompletionMillis: Long,
        val endRule: String,
        val totalMillis: Long,
        val words: Int,
        /** Milliseconds between the first transcription and `generationComplete` (task 4). */
        val transcriptToCompleteMillis: Long = 0L
    )

    /**
     * Counts modelTurn / outputTranscription frames and remembers when the turn ended — the only
     * things kept from a long stream of messages.
     */
    class Counter {
        var modelTurn = 0
            private set
        var transcriptions = 0
            private set
        var lastModelTurnAtMillis = 0L
            private set
        var lastTranscriptionAtMillis = 0L
            private set
        var firstTranscriptionAtMillis = 0L
            private set
        var firstTranscriptionText = ""
            private set
        var completionAtMillis = 0L
            private set
        var lastMessageAtMillis = 0L
            private set

        fun onMessage(now: Long, type: String, text: String = "") {
            lastMessageAtMillis = now
            when {
                type.contains("modelTurn") -> {
                    modelTurn += 1
                    lastModelTurnAtMillis = now
                }
                type.contains("outputTranscription") -> {
                    transcriptions += 1
                    lastTranscriptionAtMillis = now
                    if (firstTranscriptionAtMillis == 0L) {
                        firstTranscriptionAtMillis = now
                        firstTranscriptionText = text
                    }
                }
                type.contains("generationComplete") || type.contains("turnComplete") -> {
                    completionAtMillis = now
                }
            }
        }

        /** The last message that arrived *before* the completion flag — the "why did it wait?" number. */
        fun lastBeforeCompletion(): Long {
            if (completionAtMillis == 0L) return lastMessageAtMillis
            val candidates = listOf(lastTranscriptionAtMillis, lastModelTurnAtMillis)
                .filter { it in 1 until completionAtMillis }
            return candidates.maxOrNull() ?: 0L
        }

        fun summary(
            index: Int,
            endRule: String,
            totalMillis: Long,
            words: Int,
            sessionStart: Long
        ): SearchSummary = SearchSummary(
            index = index,
            modelTurnMessages = modelTurn,
            transcriptionMessages = transcriptions,
            firstTranscriptionMillis = rel(firstTranscriptionAtMillis, sessionStart),
            lastMessageBeforeCompletionMillis = rel(lastBeforeCompletion(), sessionStart),
            endRule = endRule,
            totalMillis = totalMillis,
            words = words,
            transcriptToCompleteMillis =
                if (completionAtMillis > 0L && firstTranscriptionAtMillis > 0L) {
                    (completionAtMillis - firstTranscriptionAtMillis).coerceAtLeast(0L)
                } else {
                    0L
                }
        )

        /** A new search counts from zero again (task 6: one line per search). */
        fun reset() {
            modelTurn = 0
            transcriptions = 0
            lastModelTurnAtMillis = 0L
            lastTranscriptionAtMillis = 0L
            firstTranscriptionAtMillis = 0L
            firstTranscriptionText = ""
            completionAtMillis = 0L
            lastMessageAtMillis = 0L
        }

        private fun rel(value: Long, start: Long): Long =
            if (value <= 0L || start <= 0L) 0L else value - start
    }

    /** The one line per search (task 6 + task 4's "log word counts and transcript→complete time"). */
    fun searchLine(summary: SearchSummary, base: Long = 0L): String = buildString {
        append("search#").append(summary.index)
        append(" total=").append(formatSeconds(summary.totalMillis)).append("s")
        append(" | modelTurn=").append(summary.modelTurnMessages)
        append(" transcr=").append(summary.transcriptionMessages)
        append(" | first transcript=")
        if (summary.firstTranscriptionMillis <= 0L) append("n/a")
        else append(formatSeconds(summary.firstTranscriptionMillis)).append("s")
        append(" | last msg before complete=")
        if (summary.lastMessageBeforeCompletionMillis <= 0L) append("n/a")
        else {
            val gapInTurn = summary.totalMillis - summary.lastMessageBeforeCompletionMillis
            append(formatSeconds(summary.lastMessageBeforeCompletionMillis)).append("s (+")
            append(formatSeconds(gapInTurn.coerceAtLeast(0L))).append("s)")
        }
        append(" | ended=").append(summary.endRule)
        append(" | ").append(summary.words).append(" words")
        if (summary.transcriptToCompleteMillis > 0L) {
            append(" | transcr->complete=").append(formatSeconds(summary.transcriptToCompleteMillis)).append("s")
        }
    }

    /**
     * The whole block, bounded by [MAX_CHARS].
     *
     * @param head the fixed parts (model, tools, resumption, network, …) in the order they print.
     * @param searches the per-search summaries, oldest first.
     * @param tails long blocks (search messages, timelines) that are trimmed from the front, never
     *   cut in the middle of a line.
     */
    fun block(
        head: List<String>,
        searches: List<SearchSummary>,
        tails: List<Pair<String, List<String>>>
    ): String {
        var searchLimit = minOf(searches.size, MAX_SEARCH_LINES)
        var tailLimit = 12
        var text = render(head, searches, tails, searchLimit, tailLimit)
        // Shrink one knob at a time, so a long log never starves a short one.
        while (text.length > MAX_CHARS && (searchLimit > 1 || tailLimit > 2)) {
            if (tailLimit > 2) tailLimit -= 2 else searchLimit -= 1
            text = render(head, searches, tails, searchLimit, tailLimit)
        }
        if (text.length <= MAX_CHARS) return text
        // Last resort: the head alone, cut at a line boundary and marked as truncated.
        val builder = StringBuilder()
        for (line in head) {
            if (builder.length + line.length + 1 > MAX_CHARS - 30) break
            builder.append(line).append('\n')
        }
        return builder.append("… (diagnostics truncated at ").append(MAX_CHARS).append(" chars)").toString()
    }

    private fun render(
        head: List<String>,
        searches: List<SearchSummary>,
        tails: List<Pair<String, List<String>>>,
        searchLimit: Int,
        tailLimit: Int
    ): String = buildString {
        head.forEach { append(it).append('\n') }
        if (searches.isNotEmpty()) {
            append("Recent searches (").append(minOf(searchLimit, searches.size))
            append('/').append(searches.size).append("):\n")
            searches.takeLast(searchLimit).forEach { append("  ").append(searchLine(it)).append('\n') }
        }
        tails.forEach { (label, lines) ->
            val kept = lines.takeLast(tailLimit)
            if (kept.isEmpty()) {
                append(label).append(": none\n")
            } else {
                append(label).append(" (").append(kept.size).append('/').append(lines.size)
                append("): ").append(kept.joinToString(" | ")).append('\n')
            }
        }
    }
}
