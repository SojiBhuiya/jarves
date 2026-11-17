#!/usr/bin/env python3
"""0.2.14 patch: task A (short answer + metrics), task D (real site names in SOURCES).

Run after cloning jarves-wip-backup; the new files SearchAnswerRules.kt and
SearchAnswerRulesTest.kt are committed to git, so only the anchored edits below are re-applied.
ASCII anchors, exactly one match each, re-run safe.
"""
import sys

ROOT = "/home/user/work0212/app/src/main/java/com/sojibhuiya/jarves"
TEST = "/home/user/work0212/app/src/test/java/com/sojibhuiya/jarves"
EDITS = []


def edit(path, old, new, label):
    EDITS.append((path, old, new, label))


def apply():
    failures = 0
    for path, old, new, label in EDITS:
        with open(path, encoding="utf-8") as f:
            text = f.read()
        # Idempotent: if the replacement text is already there, this edit was applied before.
        # (A plain "old not in text" test is wrong for edits whose new text contains the old text.)
        if new and new in text:
            print("SKIP (already applied):", label)
            continue
        count = text.count(old)
        if count != 1:
            print("FAIL count=%d: %s" % (count, label))
            failures += 1
            continue
        with open(path, "w", encoding="utf-8") as f:
            f.write(text.replace(old, new))
        print("ok:", label)
    return failures


# ------------------------------------------------------------- LiveSessionConfig: instruction --
edit(ROOT + "/intelligence/LiveSessionConfig.kt",
     r'''            "systemInstruction" to linkedMapOf<String, Any?>(
                "parts" to listOf(
                    linkedMapOf<String, Any?>(
                        "text" to (
                            "You are a web search engine for a voice assistant. Use Google Search for " +
                                "the question you are given. Answer in at most three short sentences, in " +
                                "the language of the question, and state the source name. If you cannot " +
                                "verify it, say so in one sentence. Never invent facts or links."
                            )
                    )
                )
            ),''',
     r'''            "systemInstruction" to linkedMapOf<String, Any?>(
                "parts" to listOf(
                    linkedMapOf<String, Any?>(
                        // 0.2.14 (task A): two short sentences, facts only, no spoken source line.
                        // The site names come from groundingMetadata, not from the model's words.
                        "text" to SearchAnswerRules.instruction()
                    )
                )
            ),''', "config.instruction")

# ------------------------------------------------------- LiveSearchSession: metrics + trace ----
edit(ROOT + "/intelligence/LiveSearchSession.kt",
     r'''            closeQuietly()
            val elapsed = since()
            val spoken = text.toString().trim()
            steps += "total ${elapsed}ms" + if (closeCode != 0) ", close $closeCode" else ""''',
     r'''            closeQuietly()
            val elapsed = since()
            val spoken = text.toString().trim()
            // 0.2.14 (task A): the two numbers the phone measurement needed — how long the answer
            // is, and how long the model spent between the first transcript and generationComplete
            // (0.2.13 measured 8.6 s there for a ~30-word answer plus a source line).
            val words = SearchAnswerRules.wordCount(spoken)
            val transcriptToComplete =
                if (firstTranscriptAt > 0L && finishedAt > firstTranscriptAt) finishedAt - firstTranscriptAt else 0L
            steps += "answer $words words"
            if (transcriptToComplete > 0L) {
                steps += "transcript->complete ${formatSeconds(transcriptToComplete)}s"
            }
            steps += "total ${elapsed}ms" + if (closeCode != 0) ", close $closeCode" else ""''', "session.steps")

edit(ROOT + "/intelligence/LiveSearchSession.kt",
     r'''                    textLength = spoken.length,
                    trace = traceLine(steps)
                )''',
     r'''                    textLength = spoken.length,
                    answerWords = words,
                    transcriptToCompleteMillis = transcriptToComplete,
                    trace = traceLine(steps) + "\n" + SearchAnswerRules.metricsLine(
                        totalMillis = elapsed,
                        connectMillis = setupCompleteAt,
                        firstTranscriptMillis = firstTranscriptAt,
                        transcriptToCompleteMillis = transcriptToComplete,
                        words = words,
                        chars = spoken.length
                    )
                )''', "session.answer")

# --------------------------------------------------- GroundedAnswer: the two new fields -------
edit(ROOT + "/intelligence/GroundedSearchClient.kt",
     r'''    /** 0.2.8: the step-by-step trace with timings, shown in the Diagnostic block. */
    val trace: String = ""''',
     r'''    /** 0.2.8: the step-by-step trace with timings, shown in the Diagnostic block. */
    val trace: String = "",
    /** 0.2.14 (task A): words in the transcribed answer, 0 when there is no answer. */
    val answerWords: Int = 0,
    /**
     * 0.2.14 (task A): milliseconds between the first transcription chunk and
     * `generationComplete` — the time the native-audio model spends speaking an answer whose audio
     * is thrown away. 0 when the turn ended before/without `generationComplete`.
     */
    val transcriptToCompleteMillis: Long = 0L''', "answer.fields")

edit(ROOT + "/intelligence/GroundedSearchClient.kt",
     r'''        if (serverMessage.isBlank() && detail.isBlank()) {
            append('\n').append("Google: (no error text returned)")
        }''',
     r'''        if (answerWords > 0 || transcriptToCompleteMillis > 0L) {
            append('\n').append(
                SearchAnswerRules.metricsLine(
                    totalMillis = elapsedMillis,
                    connectMillis = connectMillis,
                    firstTranscriptMillis = firstTranscriptMillis,
                    transcriptToCompleteMillis = transcriptToCompleteMillis,
                    words = answerWords,
                    chars = textLength
                )
            )
        }
        if (serverMessage.isBlank() && detail.isBlank()) {
            append('\n').append("Google: (no error text returned)")
        }''', "answer.report")

# ---------------------------------------------------- GroundingSources: real site names --------
edit(ROOT + "/intelligence/GroundingSources.kt",
     r'''/** One web source returned by Google Search grounding. */
data class GroundedSource(val title: String, val url: String) {
    /** Host used for corroboration counting, e.g. `theguardian.com`. */
    val host: String
        get() = url.substringAfter("://", url)
            .substringBefore('/')
            .substringBefore('?')
            .removePrefix("www.")
            .lowercase()
}''',
     r'''/**
 * One web source returned by Google Search grounding.
 *
 * 0.2.14 (task D): `groundingChunks[].web` carries **two** useful pieces — `title` (the site name,
 * e.g. "The Daily Star") and `uri` (which is often a `vertexaisearch.cloud.google.com/grounding-api-redirect/…`
 * wrapper, not the article URL). The old code printed the wrapper's host, which is why every source
 * looked like `google.com`. [displayName] prefers the real site name and [isRedirect] says when the
 * only thing we have is a redirect link.
 */
data class GroundedSource(val title: String, val url: String) {
    /** Host used for corroboration counting, e.g. `theguardian.com`. */
    val host: String
        get() = url.substringAfter("://", url)
            .substringBefore('/')
            .substringBefore('?')
            .removePrefix("www.")
            .lowercase()

    /**
     * True when the URL is one of Google's grounding redirect wrappers — it resolves to the real
     * page but tells the user nothing about where the answer came from
     * (docs: `GroundingChunkWeb.uri`, https://ai.google.dev/api/generate-content#GroundingChunkWeb).
     */
    val isRedirect: Boolean
        get() = REDIRECT_HOSTS.any { host == it || host.endsWith(".$it") } ||
            url.contains("grounding-api-redirect")

    /**
     * What the user should see: the site name from the metadata when there is one, otherwise the
     * host, so nothing is ever invented.
     */
    val displayName: String
        get() {
            val title = title.trim()
            val usableTitle = title.isNotEmpty() && !title.startsWith("http")
            return if (usableTitle) title else host.ifBlank { url }
        }

    /**
     * `The Daily Star` for a normal source, `The Daily Star (redirect link)` when only a redirect
     * URL was returned — task D asks for that distinction to be visible, not hidden.
     */
    val label: String
        get() = if (isRedirect) "$displayName (redirect link)" else displayName

    companion object {
        /** Hosts Google uses for grounding redirect wrappers. */
        val REDIRECT_HOSTS = listOf("vertexaisearch.cloud.google.com", "cloud.google.com")

        fun fromMetadata(title: String?, uri: String?): GroundedSource? {
            val url = uri?.trim().orEmpty()
            if (url.isEmpty()) return null
            return GroundedSource(title = title?.trim().orEmpty(), url = url)
        }
    }
}''', "sources.display")

edit(ROOT + "/intelligence/GroundingSources.kt",
     r'''                val url = (web[KEY_URI] as? String)?.trim().orEmpty()
                if (url.isEmpty()) return@mapNotNull null
                val title = (web[KEY_TITLE] as? String)?.trim().orEmpty().ifEmpty { url }
                GroundedSource(title = title, url = url)''',
     r'''                val url = (web[KEY_URI] as? String)?.trim().orEmpty()
                if (url.isEmpty()) return@mapNotNull null
                // 0.2.14 (task D): keep the metadata title as-is (no "title = url" substitution).
                // GroundedSource.displayName decides what is shown, so a redirect-only chunk shows
                // the page title and the "(redirect link)" marker instead of a long wrapper URL.
                GroundedSource(title = (web[KEY_TITLE] as? String)?.trim().orEmpty(), url = url)''',
     "sources.parser")

# ----------------------------------------------------- GroundingPolicy: site names, not hosts --
edit(ROOT + "/intelligence/GroundingPolicy.kt",
     r'''    /** "Sources: a.com, b.com" — used for the spoken citation and stored in chat history. */
    fun sourceSummary(assessment: GroundingAssessment): String? {
        if (assessment.sources.isEmpty()) return null
        val hosts = assessment.sources.map { it.host }.filter { it.isNotEmpty() }.distinct()
        if (hosts.isEmpty()) return null
        return "Sources: " + hosts.joinToString(", ")
    }''',
     r'''    /**
     * "Sources: The Daily Star, Wikipedia" — the spoken citation and the stored chat line.
     *
     * 0.2.14 (task D): the **site name from the grounding metadata** is shown when there is one,
     * because the URL is frequently a Google redirect wrapper; that case is marked
     * `(redirect link)` instead of printing `google.com` as if it were the publisher.
     * Corroboration counting still uses [GroundedSource.host], so confidence is unchanged.
     */
    fun sourceSummary(assessment: GroundingAssessment): String? {
        if (assessment.sources.isEmpty()) return null
        val names = assessment.sources.map { it.label }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return null
        return "Sources: " + names.joinToString(", ")
    }

    /** Task D: true when *every* source is only a redirect wrapper. */
    fun onlyRedirectLinks(assessment: GroundingAssessment): Boolean =
        assessment.sources.isNotEmpty() && assessment.sources.all { it.isRedirect }''', "policy.summary")

# ------------------------------------------------------------------ version bump ---------------
edit("/home/user/work0212/app/build.gradle.kts", 'versionCode = 15', 'versionCode = 16', "versionCode")
edit("/home/user/work0212/app/build.gradle.kts", 'versionName = "0.2.13"', 'versionName = "0.2.14"', "versionName")

edit(ROOT + "/GeminiLiveVoice.kt", 'const val FIX_TAG = "0.2.13-reconnect-1008"',
     'const val FIX_TAG = "0.2.14-short-answer-real-sites"', "fixTag")

# ------------------------------------------------------- GroundingTest: new source summary -----
edit(TEST + "/intelligence/GroundingTest.kt",
     r'''        assertEquals("Sources: thedailystar.net, en.wikipedia.org", GroundingPolicy.sourceSummary(assessment))''',
     r'''        // 0.2.14 (task D): site names from the metadata, not hosts — see SearchAnswerRulesTest.
        assertEquals("Sources: The Daily Star, Wikipedia", GroundingPolicy.sourceSummary(assessment))''',
     "test.summary")

# ------------------------------------------------ MainActivity: store the real site name ------
edit(ROOT + "/MainActivity.kt",
     r'''                onGrounding = { sources ->
                    pendingSources = sources.map { ChatSource(title = it.title, url = it.url) }
                },''',
     r'''                onGrounding = { sources ->
                    // 0.2.14 (task D): the stored/rendered title is the real site name from the
                    // grounding metadata; when the metadata only has a redirect wrapper the label
                    // says so, instead of showing "google.com" for every publisher.
                    pendingSources = sources.map { ChatSource(title = it.label, url = it.url) }
                },''', "activity.sources")

edit(ROOT + "/intelligence/GroundedSearchClient.kt",
     r'''            sources.take(MAX_RESULT_SOURCES).forEach { append("\n- ").append(it.title).append(": ").append(it.url) }''',
     r'''            // 0.2.14 (task D): the name handed to the voice model is the site name from the
            // metadata ("The Daily Star"), never a grounding redirect wrapper.
            sources.take(MAX_RESULT_SOURCES).forEach { append("\n- ").append(it.label).append(": ").append(it.url) }''', "toolresult.label")

if __name__ == "__main__":
    sys.exit(1 if apply() else 0)
