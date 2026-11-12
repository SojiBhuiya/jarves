package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenarios 6 (search provider failure) and 7 (conflicting search results), plus grounding
 * metadata parsing.
 */
class GroundingTest {

    private fun serverMessage(grounding: String) =
        """{"serverContent":{"turnComplete":true,"groundingMetadata":$grounding}}"""

    private val twoSources = """
        {"webSearchQueries":["current prime minister of bangladesh"],
         "groundingChunks":[
           {"web":{"uri":"https://www.thedailystar.net/news/example","title":"The Daily Star"}},
           {"web":{"uri":"https://en.wikipedia.org/wiki/Prime_Minister_of_Bangladesh","title":"Wikipedia"}}
         ]}
    """.trimIndent()

    private val oneSource = """
        {"webSearchQueries":["president of bangladesh"],
         "groundingChunks":[{"web":{"uri":"https://www.bssnews.net/x","title":"BSS"}}]}
    """.trimIndent()

    @Test
    fun parsesSourcesAndQueries() {
        val report = GroundingParser.fromServerMessage(serverMessage(twoSources))
        assertEquals(2, report.sources.size)
        assertEquals("The Daily Star", report.sources[0].title)
        assertEquals("thedailystar.net", report.sources[0].host)
        assertEquals("en.wikipedia.org", report.sources[1].host)
        assertEquals(listOf("current prime minister of bangladesh"), report.queries)
        assertTrue(report.hasSources)
    }

    @Test
    fun ordinaryFramesAreNotParsed() {
        val plain = """{"serverContent":{"modelTurn":{"parts":[]}}}"""
        assertFalse(GroundingParser.mightContainGrounding(plain))
        assertEquals(GroundingReport.EMPTY, GroundingParser.fromServerMessage(plain))
        assertEquals(GroundingReport.EMPTY, GroundingParser.fromServerMessage(null))
    }

    @Test
    fun malformedGroundingIsIgnoredSafely() {
        assertEquals(GroundingReport.EMPTY, GroundingParser.fromServerMessage("{not json"))
        assertEquals(
            GroundingReport.EMPTY,
            GroundingParser.fromServerMessage("""{"serverContent":{"groundingMetadata":{}}}""")
        )
        // Entries without a usable URL are dropped rather than crashing.
        val emptyUrls = serverMessage("""{"groundingChunks":[{"web":{"title":"no url"}},{}]}""")
        assertEquals(GroundingReport.EMPTY, GroundingParser.fromServerMessage(emptyUrls))
    }

    @Test
    fun twoIndependentHostsAreCorroborated() {
        val assessment = GroundingPolicy.assess(GroundingParser.fromServerMessage(serverMessage(twoSources)))
        assertEquals(SourceConfidence.MULTI_DOMAIN, assessment.confidence)
        assertEquals(2, assessment.hostCount)
        assertTrue(assessment.isVerified)
        assertTrue(GroundingPolicy.guidanceFor(assessment)!!.contains("2 independent sources"))
        assertEquals("Sources: thedailystar.net, en.wikipedia.org", GroundingPolicy.sourceSummary(assessment))
    }

    @Test
    fun scenario7_conflictingResultsAreNotSilentlyResolved() {
        // One host only -> must be presented as unconfirmed, and the model is told to report
        // disagreement rather than pick a side.
        val single = GroundingPolicy.assess(GroundingParser.fromServerMessage(serverMessage(oneSource)))
        assertEquals(SourceConfidence.SINGLE_DOMAIN, single.confidence)
        assertFalse(single.isVerified)
        val singleGuidance = GroundingPolicy.guidanceFor(single)!!
        assertTrue(singleGuidance.contains("not independently confirmed"))

        // Multiple hosts -> the model must surface disagreement if sources differ.
        val multi = GroundingPolicy.assess(GroundingParser.fromServerMessage(serverMessage(twoSources)))
        assertTrue(GroundingPolicy.guidanceFor(multi)!!.contains("sources conflict"))
    }

    @Test
    fun scenario6_searchFailureProducesAnHonestNotice() {
        val failed = GroundingPolicy.assess(GroundingReport.EMPTY)
        assertEquals(SourceConfidence.NONE, failed.confidence)
        assertNull(GroundingPolicy.sourceSummary(failed))
        assertNull(GroundingPolicy.guidanceFor(failed))

        val notice = GroundingPolicy.fallbackNotice(groundingAvailable = true)
        assertTrue(notice.contains("could not verify", ignoreCase = true))

        val noGrounding = GroundingPolicy.fallbackNotice(groundingAvailable = false)
        assertTrue(noGrounding.contains("NOT available"))
        assertTrue(noGrounding.contains("do not present remembered facts", ignoreCase = true))
    }

    @Test
    fun duplicateHostsCountAsOneSource() {
        val sameHostTwice = serverMessage(
            """{"groundingChunks":[
                 {"web":{"uri":"https://example.com/a","title":"A"}},
                 {"web":{"uri":"https://example.com/b","title":"B"}}]}"""
        )
        val assessment = GroundingPolicy.assess(GroundingParser.fromServerMessage(sameHostTwice))
        assertEquals(SourceConfidence.SINGLE_DOMAIN, assessment.confidence)
        assertEquals(1, assessment.hostCount)
        assertEquals(2, assessment.sources.size)
    }
}
