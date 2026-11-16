package com.sojibhuiya.jarves.intelligence

/**
 * One search result on its way back to the 3.8 session.
 *
 * [question] is what the user actually asked (the `search_web` query argument), [answer] is already
 * limited to [MAX_ANSWER_SENTENCES] plain sentences, and [sourceName] is the publisher the grounded
 * answer named.
 */
data class SearchResultItem(
    val callId: String,
    val question: String,
    val answer: String,
    val sourceName: String = "",
    val success: Boolean = true,
    val askedAtMillis: Long = 0L,
    val receivedAtMillis: Long = 0L,
    /**
     * The order the *questions* were asked (1, 2, 3 …), not the order the searches happened to
     * finish. Two back-to-back searches can finish in either order; the user must hear them in the
     * order they asked.
     */
    val orderIndex: Long = 0L,
    /** True when the user asked something else before this result arrived. */
    val askedBeforeResult: Boolean = false
)

/**
 * The words sent back to the model, and the words shown in the chat.
 *
 * **Why this exists (0.2.11 phone report):** the model used to receive only `answer.asToolResult()`
 * — the grounded text with a source list and a generic "tell the user this" line — and the search
 * result was never written into the chat unless it failed. Two consequences: the model could not
 * name the question (it was not in the payload), and a voice-only answer could be lost entirely.
 *
 * Shape of the tool text (task C2): the original question, at most three plain sentences, the
 * source name.
 */
object SearchResultText {

    const val MAX_ANSWER_SENTENCES = 3

    /**
     * The `toolResponse` payload. Keep it short and factual: the model turns it into speech.
     *
     * When the result arrives after newer conversation, the model is told to name the question
     * first (task C3) — that instruction travels with the data so it cannot drift from it.
     */
    fun toolResponse(item: SearchResultItem): String = buildString {
        if (!item.success) {
            append("The web search for \"")
                .append(item.question.take(160))
                .append("\" failed. Tell the user in their language, in one short sentence, that you ")
                .append("could not verify it right now. Do not invent facts or links.")
            return@buildString
        }
        append("The user's question was: \"").append(item.question.take(160)).append("\".\n")
        append("Answer: ").append(limitSentences(item.answer)).append('\n')
        if (item.sourceName.isNotBlank()) append("Source: ").append(item.sourceName).append('\n')
        if (item.askedBeforeResult) {
            append("The user has asked other things since this question, so BEGIN by naming the ")
            append("question, for example: \"আপনি ")
            append(item.question.take(60))
            append(" জানতে চেয়েছিলেন, তার উত্তর: …\", then answer in a few short sentences. ")
            append("Never cut off or replace an answer that is already being given.")
        } else {
            append("Answer naturally in a few short sentences and name the source.")
        }
        append(" Never invent facts or links.")
    }

    /**
     * What is always written into the chat, whether or not the voice picks it up (task C5).
     * Bengali label words, plain text, no markdown that the chat cannot render.
     */
    fun chatLine(item: SearchResultItem, kindLabel: String = "সার্চ"): String = buildString {
        append("🔎 ").append(kindLabel).append(" — প্রশ্ন: ").append(item.question.take(200)).append('\n')
        if (item.success) {
            append("উত্তর: ").append(limitSentences(item.answer))
            if (item.sourceName.isNotBlank()) append('\n').append("সূত্র: ").append(item.sourceName)
        } else {
            append("সার্চ করা যায়নি, তাই এই প্রশ্নের নির্ভরযোগ্য উত্তর দেওয়া যাচ্ছে না।")
        }
    }

    /** Keeps the first [MAX_ANSWER_SENTENCES] sentences; never returns an empty string for text. */
    fun limitSentences(text: String, max: Int = MAX_ANSWER_SENTENCES): String {
        val trimmed = text.trim().ifBlank { return "no answer text was returned" }
        val parts = trimmed.split(Regex("(?<=[।.!?])\\s+")).filter { it.isNotBlank() }
        val kept = parts.take(max).joinToString(" ").trim()
        return kept.ifBlank { trimmed }
    }
}

/**
 * FIFO of results waiting for the model to be idle.
 *
 * Order is the order the searches finished, which is the order the user asked them when the
 * searches take similar time; two pending results are delivered one at a time (task C4). A
 * duplicate `callId` is ignored — the same search must never be announced twice.
 */
class PendingResultQueue(private val capacity: Int = 8) {

    private val lock = Any()
    private val items = ArrayDeque<SearchResultItem>()

    val size: Int get() = synchronized(lock) { items.size }

    fun enqueue(item: SearchResultItem): Boolean = synchronized(lock) {
        if (items.any { it.callId == item.callId }) return false
        if (items.size >= capacity) items.removeFirst()
        // Question order, not completion order: a fast second search must not overtake the first.
        val index = if (item.orderIndex > 0L) {
            items.indexOfFirst { it.orderIndex > item.orderIndex }.let { if (it < 0) items.size else it }
        } else {
            items.size
        }
        items.add(index, item)
        true
    }

    fun poll(): SearchResultItem? = synchronized(lock) { items.removeFirstOrNull() }

    fun peek(): SearchResultItem? = synchronized(lock) { items.firstOrNull() }

    fun remove(callId: String): Boolean = synchronized(lock) {
        val victim = items.firstOrNull { it.callId == callId } ?: return false
        items.remove(victim)
        true
    }

    fun clear() = synchronized(lock) { items.clear() }

    fun questions(): List<String> = synchronized(lock) { items.map { it.question } }
}

/**
 * "Did the model actually say anything after we handed it the result?" (task C5 safety net)
 *
 * If the result was accepted but no audio follows within [IDLE_AUDIO_GRACE_MILLIS] of the model
 * being idle, that is worth one nudge and one diagnostics line — never a loop.
 */
class IdleAnnouncementTracker(
    private val graceMillis: Long = IDLE_AUDIO_GRACE_MILLIS
) {
    private var awaitingSince: Long = 0L
    private var reminded = false
    private var callId: String = ""

    val isAwaitingAudio: Boolean get() = awaitingSince > 0L

    fun onResultSent(callId: String, now: Long) {
        this.callId = callId
        awaitingSince = now
        reminded = false
    }

    fun onAudio(now: Long) {
        awaitingSince = 0L
        reminded = false
    }

    /** True exactly once per result, [graceMillis] after it was sent with no audio in between. */
    fun shouldRemind(now: Long): Boolean {
        if (awaitingSince <= 0L || reminded) return false
        if (now - awaitingSince < graceMillis) return false
        reminded = true
        return true
    }

    fun describe(now: Long): String = when {
        awaitingSince <= 0L -> "announced"
        else -> "waiting for audio ${now - awaitingSince}ms after id=${callId.take(24)}" +
            if (reminded) " (one reminder sent)" else ""
    }

    companion object {
        /** Task C5: "no audio follows within 8 seconds of idle". */
        const val IDLE_AUDIO_GRACE_MILLIS = 8_000L
    }
}

/**
 * The per-search timeline (task A). One instance per live session; every event carries the
 * millisecond offset from the moment the session became usable (`setupComplete`).
 *
 * Kept deliberately small (the last [CAPACITY] events) because it is rendered inside the
 * diagnostics block on a phone screen.
 */
class SearchTimeline(private val capacity: Int = 40) {

    private val lock = Any()
    private val events = ArrayDeque<String>()
    private var originMillis: Long = 0L

    fun start(now: Long) {
        synchronized(lock) {
            originMillis = now
            events.clear()
        }
    }

    fun mark(label: String, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (originMillis == 0L) originMillis = now
            val offset = now - originMillis
            events.addLast("${"%.1f".format(offset / 1000.0)}s $label")
            while (events.size > capacity) events.removeFirst()
        }
    }

    fun text(): String = synchronized(lock) {
        if (events.isEmpty()) "none" else events.joinToString(" -> ")
    }

    fun events(): List<String> = synchronized(lock) { events.toList() }

    fun clear() = synchronized(lock) { events.clear(); originMillis = 0L }

    companion object {
        const val CAPACITY_DEFAULT = 40
    }
}
