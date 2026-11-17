package com.sojibhuiya.jarves.intelligence

/**
 * 0.2.14 (task A): how the **second** Live session is told to answer.
 *
 * The phone measurement from 0.2.13 was `first transcription 6821ms -> generationComplete 15444ms`:
 * 8.6 s spent generating a ~30-word answer plus a spoken source line, and the audio itself is thrown
 * away — only `outputTranscription` is used. The answer is fed to the voice model as a tool result,
 * so a long answer costs time and gains nothing.
 *
 * Docs that shaped this text (all READ-FROM-DOCS-ONLY, none of it measured here):
 *  * the transcript is the *only* text a native-audio session can give us — "The native audio models
 *    only support `AUDIO` response modality. If you need the model response as text, use the output
 *    audio transcription feature."
 *    (https://ai.google.dev/gemini-api/docs/live-guide → Limitations → Response modalities)
 *  * the last transcription of a turn is sent **before** `generationComplete`
 *    (https://ai.google.dev/api/live → BidiGenerateContentServerContent.outputTranscription),
 *    so the words arrive first and the model finishes speaking after them.
 *  * the source names do **not** come from the model's words at all: `groundingMetadata` carries
 *    them (`groundingChunks[].web.title` / `.uri`,
 *    https://ai.google.dev/api/live → GroundingMetadata), which is why the instruction no longer
 *    asks for a spoken source line — [GroundingPolicy] shows the real site names instead.
 *
 * There is no documented `maxOutputTokens` behaviour for audio output and no `stopSequence` at all
 * (the Live reference lists `stopSequence` among the **unsupported** `generationConfig` fields), so
 * length is controlled the only documented way: by instructing the model.
 */
object SearchAnswerRules {

    /** Marker for the unit tests: the instruction must be the short-answer one. */
    const val MARK = "SHORT ANSWER RULE"

    /** Roughly how many words the answer may use (task A: "about 25 words"). */
    const val MAX_WORDS = 25

    /** How many sentences the answer may use. */
    const val MAX_SENTENCES = 2

    /**
     * The system instruction of the 2.5 search session.
     *
     * Deliberate omissions, each of them a task-A requirement:
     *  * no "state the source name" (the app shows the real sites from `groundingMetadata`);
     *  * no greeting, no repeating of the question, no markdown/list;
     *  * the answer stays in the language of the question.
     */
    fun instruction(): String = buildString {
        append(MARK).append(": ")
        append("You are a web search engine for a voice assistant. ")
        append("Use Google Search for the question you are given, then reply with the facts only: ")
        append("at most $MAX_SENTENCES short plain sentences, about $MAX_WORDS words in total. ")
        append("Do not write a greeting. Do not repeat the question. ")
        append("No markdown, no bullet points, no links, and no source line — the app shows the sources itself. ")
        append("Write in the language of the question. ")
        append("If the search results cannot support an answer, say that in one short sentence. ")
        append("Never invent facts.")
    }

    /** True when [instruction] carries the short-answer rule (used by the wiring test). */
    fun isShortAnswerInstruction(instruction: String): Boolean =
        instruction.contains(MARK) && instruction.contains("$MAX_WORDS words")

    /**
     * Words in a transcribed answer.
     *
     * Whitespace split on purpose: Bengali sentences are separated by spaces too, and a
     * character-based estimate would make Bengali look three times longer than English.
     */
    fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    /**
     * The diagnostics line for one Auto search — the numbers the 0.2.13 phone log made necessary:
     *
     * `Search metrics: total=15.4s | connect=3.7s | first transcript=6.8s | transcript->complete=8.6s
     *  | answer=30 words (164 chars)`
     *
     * Gaps that cannot be computed yet (a failure before the first transcript, for example) are
     * printed as `n/a` instead of a misleading `0.0s`.
     */
    fun metricsLine(
        totalMillis: Long,
        connectMillis: Long,
        firstTranscriptMillis: Long,
        transcriptToCompleteMillis: Long,
        words: Int,
        chars: Int,
        /** 0.2.15 (rule 10): turnComplete / generationComplete / quiet gap / timeout / closed. */
        endRule: String = "",
        /** 0.2.15 (rule 10): the quiet gap constant this build used. */
        quietGapMillis: Long = 0L,
        /** 0.2.15 (rule 6): sockets opened for this search (2 only after a setup timeout). */
        attempts: Int = 0
    ): String = buildString {
        append("Search metrics: total=").append(seconds(totalMillis))
        append(" | connect=").append(secondsOrNa(connectMillis))
        append(" | first transcript=").append(secondsOrNa(firstTranscriptMillis))
        append(" | transcript->complete=").append(secondsOrNa(transcriptToCompleteMillis))
        append(" | answer=").append(words).append(" words (").append(chars).append(" chars)")
        if (endRule.isNotBlank()) append(" | ended=").append(endRule)
        if (quietGapMillis > 0L) {
            append(" (quiet gap ").append(formatSeconds(quietGapMillis)).append("s)")
        }
        if (attempts > 0) append(" | attempts=").append(attempts)
    }

    private fun seconds(millis: Long): String = formatSeconds(millis) + "s"

    private fun secondsOrNa(millis: Long): String = if (millis <= 0L) "n/a" else seconds(millis)
}
