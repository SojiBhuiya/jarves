package com.sojibhuiya.jarves.intelligence

import java.util.Locale

/**
 * Decides whether a spoken turn needs live, current information (and therefore a grounded
 * answer) or is a stable/historical question that must NOT trigger a search.
 *
 * Deliberately conservative and deterministic: a factual question that names a fixed past
 * event, a year, or asks for a definition is treated as STABLE even when it mentions an
 * office such as "President".
 */
object FreshnessClassifier {

    enum class Kind {
        /** Needs current, frequently changing information (news, office holders, prices…). */
        CURRENT_INFO,

        /** Asking for the current date or time. */
        TIME,

        /** Stable knowledge: history, definitions, fixed facts. Should not trigger a search. */
        STABLE
    }

    data class Classification(val kind: Kind, val reason: String)

    private val TIME_HINTS = listOf(
        "what time", "what's the time", "current time", "time now", "today's date", "todays date",
        "what is the date", "what's the date", "what day is it", "what day is today", "date today",
        "কত তারিখ", "আজ কী বার", "আজ কি বার", "সময় কত", "এখন কটা", "এখন কয়টা", "তারিখ কত", "আজকের তারিখ"
    )

    /** Explicit "this is about the past" markers — checked before current-affairs keywords. */
    private val STABLE_HINTS = listOf(
        "history", "historical", "in 1947", "in 1952", "in 1971", "in 1975", "in 1990",
        "war of liberation", "liberation war", "independence", "language movement",
        "who was the first", "who was the", "first president", "first prime minister",
        "founder", "who founded", "who wrote", "when did", "what year", "which year",
        "how many districts", "how many divisions", "capital of", "definition", "meaning of",
        "difference between", "ইতিহাস", "১৯৭১", "১৯৪৭", "মুক্তিযুদ্ধ", "ভাষা আন্দোলন",
        "প্রথম রাষ্ট্রপতি", "প্রথম প্রধানমন্ত্রী", "প্রতিষ্ঠা", "কবে", "কত সালে", "রাজধানী", "সংজ্ঞা"
    )

    private val CURRENT_HINTS = listOf(
        // Most specific first: these give the clearest "why" in logs.
        "prime minister", "president", "chief adviser", "minister", "ceo", "chairman",
        "governor", "mayor", "who is the", "who leads", "who won", "election", "poll",
        "exchange rate", "weather", "temperature", "price", "prices", "rate", "score",
        "result", "standings", "schedule", "stock", "cricket",
        // Generic freshness words.
        "current", "currently", "now", "today", "tonight", "this year", "latest", "newest",
        "news", "breaking", "update", "is it raining", "how much is",
        // Bangla markers (most specific first).
        "প্রধানমন্ত্রী", "রাষ্ট্রপতি", "উপদেষ্টা", "মন্ত্রী", "নির্বাচন", "আবহাওয়া",
        "বিনিময় হার", "দাম", "স্কোর", "ফলাফল", "খবর", "সর্বশেষ", "বর্তমান", "বর্তমানে",
        "চ্যাম্পিয়ন", "কে আছেন", "কে হচ্ছেন", "কত টাকা", "এখন", "আজ", "আজকের", "বৃষ্টি"
    )

    fun classify(text: String): Classification {
        val normalised = text.lowercase(Locale.ROOT).trim()
        if (normalised.isEmpty()) return Classification(Kind.STABLE, "empty")

        if (TIME_HINTS.any { normalised.contains(it) }) {
            return Classification(Kind.TIME, "time-or-date question")
        }

        val stable = STABLE_HINTS.firstOrNull { normalised.contains(it) }
        val current = CURRENT_HINTS.firstOrNull { normalised.contains(it) }

        return when {
            // Historical framing wins over an office name: "who was the first President…".
            stable != null && current == null -> Classification(Kind.STABLE, "stable marker: $stable")
            stable != null && current != null -> Classification(Kind.STABLE, "stable marker '$stable' overrides '$current'")
            current != null -> Classification(Kind.CURRENT_INFO, "current-affairs marker: $current")
            else -> Classification(Kind.STABLE, "no freshness marker")
        }
    }

    fun needsLiveSources(text: String): Boolean = classify(text).kind == Kind.CURRENT_INFO
}
