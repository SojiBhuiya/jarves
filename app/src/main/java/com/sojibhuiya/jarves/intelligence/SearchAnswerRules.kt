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

    /** 0.2.16 (task 4): "ONE sentence, at most 20 words". */
    const val MAX_WORDS = 20

    /** 0.2.16 (task 4): one sentence, not two. */
    const val MAX_SENTENCES = 1

    /**
     * 0.2.16 (task 4): what may travel to the 3.8 session inside the `toolResponse` — "at most 2
     * sentences or about 35 words". The chat bubble keeps the full text.
     */
    const val TOOL_MAX_SENTENCES = 2

    const val TOOL_MAX_WORDS = 35

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
        append("Use Google Search for the question you are given. ")
        append("Answer in ONE sentence of at most $MAX_WORDS words, the key facts and numbers only, ")
        append("each number with its unit. No advice, no greeting, no repeat of the question, ")
        append("no markdown, no links, no source line — the app shows the sources itself. ")
        append("Write in the language of the question. ")
        // Task 4 asked for one short example inside the instruction. The Live API best-practices page
        // recommends exactly this style and the setup field only requires text:
        //  * "Add any necessary guardrails: … Feel free to provide specific examples of if *x*
        //    happens, you want the model to do *y*." (https://ai.google.dev/gemini-api/docs/live-api/best-practices
        //    → Design clear system instructions)
        //  * "systemInstruction … Note: Only text should be used in parts" (https://ai.google.dev/api/live
        //    → BidiGenerateContentSetup.systemInstruction)
        // Whether an example actually shortens the answer is NOT claimed here: it can only be seen on a
        // phone (NOT-RUN).
        append("Example — question: \"আজ ঢাকার তাপমাত্রা কত?\" answer: ")
        append("\"ঢাকায় এখন ৩২ ডিগ্রি সেলসিয়াস, আকাশ পরিষ্কার।\" ")
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
     * 0.2.16 (task 4): the text sent to the 3.8 voice model is cut at a **sentence boundary** to at
     * most [TOOL_MAX_SENTENCES] sentences / about [TOOL_MAX_WORDS] words.
     *
     * Never in the middle of a sentence: if one sentence alone is longer than the word budget, that
     * whole sentence is kept rather than chopped (a half sentence read aloud is worse than a long
     * one). The chat bubble and the sources are built from the untouched answer, so nothing is lost
     * for the user — only the spoken version is bounded.
     *
     * The split recognises the terminators used by the three languages the app actually sees:
     * `.` `!` `?` `।` (Bengali danda) and `…`.
     */
    fun truncateForToolResponse(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val sentences = splitSentences(trimmed)
        if (sentences.isEmpty()) return trimmed
        val chosen = mutableListOf<String>()
        var words = 0
        for (sentence in sentences) {
            val count = wordCount(sentence)
            val wouldBeWords = words + count
            if (chosen.isNotEmpty() && (chosen.size >= TOOL_MAX_SENTENCES || wouldBeWords > TOOL_MAX_WORDS)) break
            chosen += sentence
            words = wouldBeWords
            if (chosen.size >= TOOL_MAX_SENTENCES) break
        }
        if (chosen.isEmpty()) return sentences.first()
        return chosen.joinToString(" ")
    }

    /** Sentences in the order they appear, terminators kept. Empty input gives an empty list. */
    fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val builder = StringBuilder()
        for (char in text) {
            builder.append(char)
            if (char == '.' || char == '!' || char == '?' || char == '।' || char == '…') {
                val sentence = builder.toString().trim()
                if (sentence.isNotEmpty()) out += sentence
                builder.setLength(0)
            }
        }
        val tail = builder.toString().trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }

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
        attempts: Int = 0,
        /** 0.2.16 (task 4): words in the text actually sent to the 3.8 session. */
        toolWords: Int = 0,
        /** 0.2.16 (task 4): words of the full answer kept in the chat. */
        fullWords: Int = 0
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
        if (toolWords > 0) append(" | to voice=").append(toolWords).append(" words")
        if (fullWords > 0 && fullWords != toolWords) append(" (chat ").append(fullWords).append(" words)")
    }

    private fun seconds(millis: Long): String = formatSeconds(millis) + "s"

    private fun secondsOrNa(millis: Long): String = if (millis <= 0L) "n/a" else seconds(millis)
}
