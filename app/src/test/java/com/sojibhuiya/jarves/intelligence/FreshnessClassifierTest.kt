package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.intelligence.FreshnessClassifier.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenarios 1–5: current-info detection, dates, Bangladesh current affairs, and a historical
 * question that must NOT trigger a search.
 */
class FreshnessClassifierTest {

    @Test
    fun scenario1_currentPrimeMinisterNeedsLiveSources() {
        val result = FreshnessClassifier.classify("Who is the current Prime Minister of Bangladesh?")
        assertEquals(Kind.CURRENT_INFO, result.kind)
        assertTrue(result.reason.contains("prime minister"))
    }

    @Test
    fun scenario2_todaysDateIsATimeQuestion() {
        assertEquals(Kind.TIME, FreshnessClassifier.classify("What is today's date?").kind)
        assertEquals(Kind.TIME, FreshnessClassifier.classify("what time is it now").kind)
        assertEquals(Kind.TIME, FreshnessClassifier.classify("আজকের তারিখ কী?").kind)
        assertEquals(Kind.TIME, FreshnessClassifier.classify("ঢাকায় এখন কয়টা বাজে?").kind)
    }

    @Test
    fun scenario3_latestNewsNeedsLiveSources() {
        assertEquals(
            Kind.CURRENT_INFO,
            FreshnessClassifier.classify("What is the latest news in Bangladesh?").kind
        )
        assertEquals(
            Kind.CURRENT_INFO,
            FreshnessClassifier.classify("বাংলাদেশের সর্বশেষ খবর কী?").kind
        )
    }

    @Test
    fun scenario4_currentPresidentNeedsLiveSources() {
        assertEquals(
            Kind.CURRENT_INFO,
            FreshnessClassifier.classify("Who is the current President of Bangladesh?").kind
        )
    }

    @Test
    fun scenario5_historicalQuestionDoesNotTriggerSearch() {
        assertEquals(
            Kind.STABLE,
            FreshnessClassifier.classify("Who was the first President of Bangladesh?").kind
        )
        assertEquals(
            Kind.STABLE,
            FreshnessClassifier.classify("What happened in 1971 in Bangladesh?").kind
        )
        assertEquals(
            Kind.STABLE,
            FreshnessClassifier.classify("What is the capital of Bangladesh?").kind
        )
        assertEquals(
            Kind.STABLE,
            FreshnessClassifier.classify("মুক্তিযুদ্ধ কত সালে হয়েছিল?").kind
        )
        assertFalse(FreshnessClassifier.needsLiveSources("Who was the first President of Bangladesh?"))
    }

    @Test
    fun otherCurrentTopicsAreDetected() {
        listOf(
            "What's the weather in Dhaka today?",
            "What is the dollar exchange rate now?",
            "What is the price of rice?",
            "Who won the match today?",
            "আজ আবহাওয়া কেমন?",
            "ডলারের দাম কত?"
        ).forEach { question ->
            assertEquals(question, Kind.CURRENT_INFO, FreshnessClassifier.classify(question).kind)
        }
    }

    @Test
    fun emptyAndNeutralTextIsStable() {
        assertEquals(Kind.STABLE, FreshnessClassifier.classify("").kind)
        assertEquals(Kind.STABLE, FreshnessClassifier.classify("Tell me a poem about rain").kind)
        assertFalse(FreshnessClassifier.needsLiveSources(""))
    }
}
