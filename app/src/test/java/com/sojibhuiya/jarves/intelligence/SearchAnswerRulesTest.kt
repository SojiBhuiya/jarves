package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.14 (tasks A, D and E): the short-answer instruction, the diagnostics metrics line, and the
 * source-name mapping.
 *
 * Pure JVM — nothing here touches Android or the network. Label: TESTED-MOCK (the text and the
 * arithmetic), NOT-RUN for what the real model does with the instruction.
 */
class SearchAnswerRulesTest {

    // ------------------------------------------------- (A) the instruction text -------------

    @Test
    fun `the search session is told to answer in two short sentences of about 25 words`() {
        val instruction = SearchAnswerRules.instruction()
        assertTrue(SearchAnswerRules.isShortAnswerInstruction(instruction))
        assertTrue(instruction, instruction.contains("at most 2 short plain sentences"))
        assertTrue(instruction, instruction.contains("about 25 words"))
        assertTrue("the answer must be in the language of the question",
            instruction.contains("language of the question"))
    }

    @Test
    fun `the instruction contains no spoken source line, no greeting and no repetition of the question`() {
        val instruction = SearchAnswerRules.instruction()
        // The 0.2.13 text asked for "at most three short sentences" *and* a spoken source name; both
        // are exactly what the phone measured as wasted generation time.
        assertFalse(instruction, instruction.contains("three short sentences"))
        assertFalse(instruction, instruction.contains("state the source name"))
        assertFalse(instruction, instruction.contains("state the source"))
        assertTrue(instruction, instruction.contains("Do not write a greeting"))
        assertTrue(instruction, instruction.contains("Do not repeat the question"))
        assertTrue(instruction, instruction.contains("No markdown"))
        assertTrue("sources come from groundingMetadata, not from the words",
            instruction.contains("no source line"))
        // An instruction that is longer than the answer would defeat the purpose.
        assertTrue(
            "instruction is ${SearchAnswerRules.wordCount(instruction)} words",
            SearchAnswerRules.wordCount(instruction) <= 120
        )
    }

    @Test
    fun `the setup frame carries the short-answer instruction and still asks for transcription`() {
        val frame = LiveSessionConfig.searchSessionSetupJson(model = LiveModels.SEARCH_NATIVE_AUDIO, voiceName = "Aoede")
        assertTrue(frame, frame.contains(SearchAnswerRules.MARK))
        assertTrue(frame, frame.contains("about 25 words"))
        // Unchanged on purpose: native-audio models only support AUDIO, so text comes from the
        // output transcription (live-guide → Limitations → Response modalities).
        assertTrue(frame, frame.contains("\"outputAudioTranscription\""))
        assertTrue(frame, frame.contains("\"AUDIO\""))
        assertTrue(frame, frame.contains("\"googleSearch\""))
        assertFalse("no function declarations in the search session", frame.contains("functionDeclarations"))
    }

    // ------------------------------------------------- (A) the metrics line ----------------

    @Test
    fun `word count works for Bengali and English answers`() {
        assertEquals(0, SearchAnswerRules.wordCount(""))
        assertEquals(0, SearchAnswerRules.wordCount("   \n "))
        // ঢাকায় | আজ | ৩২ | ডিগ্রি, | রোদ।  — punctuation stays attached, nothing is dropped.
        assertEquals(5, SearchAnswerRules.wordCount("ঢাকায় আজ ৩২ ডিগ্রি, রোদ।"))
        assertEquals(4, SearchAnswerRules.wordCount("  Sunny  and  32 degrees.  "))
    }

    @Test
    fun `metrics line reproduces the 0_2_13 phone numbers in plain seconds`() {
        // The phone log: setupComplete 3713ms, first transcription 6821ms,
        // generationComplete 15444ms -> 8.6 s of generation for a ~30-word answer.
        val line = SearchAnswerRules.metricsLine(
            totalMillis = 15_444L,
            connectMillis = 3_713L,
            firstTranscriptMillis = 6_821L,
            transcriptToCompleteMillis = 8_623L,
            words = 30,
            chars = 164
        )
        assertEquals(
            "Search metrics: total=15.4s | connect=3.7s | first transcript=6.8s | " +
                "transcript->complete=8.6s | answer=30 words (164 chars)",
            line
        )
        assertFalse("never scientific notation", line.contains("E"))
    }

    @Test
    fun `missing gaps are printed as n a instead of a misleading zero`() {
        val line = SearchAnswerRules.metricsLine(
            totalMillis = 2_000L,
            connectMillis = 0L,
            firstTranscriptMillis = 0L,
            transcriptToCompleteMillis = 0L,
            words = 0,
            chars = 0
        )
        assertTrue(line, line.contains("connect=n/a"))
        assertTrue(line, line.contains("first transcript=n/a"))
        assertTrue(line, line.contains("transcript->complete=n/a"))
        assertTrue(line, line.contains("answer=0 words (0 chars)"))
    }

    // ------------------------------------------------- (D) source names --------------------

    @Test
    fun `a normal source shows the site name from the metadata`() {
        val source = GroundedSource(title = "The Daily Star", url = "https://www.thedailystar.net/news/x")
        assertEquals("thedailystar.net", source.host)
        assertFalse(source.isRedirect)
        assertEquals("The Daily Star", source.displayName)
        assertEquals("The Daily Star", source.label)
    }

    @Test
    fun `a redirect-only source says so and still shows the page title`() {
        val source = GroundedSource(
            title = "The Daily Star",
            url = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AbC-123"
        )
        assertTrue(source.isRedirect)
        assertEquals("The Daily Star", source.displayName)
        assertEquals("The Daily Star (redirect link)", source.label)
    }

    @Test
    fun `a redirect wrapper without a title never pretends to be the publisher`() {
        val source = GroundedSource(
            title = "",
            url = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AbC-123"
        )
        assertTrue(source.isRedirect)
        assertEquals("vertexaisearch.cloud.google.com", source.displayName)
        assertEquals("vertexaisearch.cloud.google.com (redirect link)", source.label)
        assertFalse("the wrapper is not a real publisher name", source.displayName.equals("google.com"))
    }

    @Test
    fun `a title that is just the url falls back to the host`() {
        val source = GroundedSource(title = "https://www.bssnews.net/x", url = "https://www.bssnews.net/x")
        assertEquals("bssnews.net", source.displayName)
        assertFalse(source.isRedirect)
    }

    @Test
    fun `source summary uses site names and marks redirect-only results`() {
        val metadata = """
            {"webSearchQueries":["weather dhaka"],
             "groundingChunks":[
               {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/xyz","title":"The Daily Star"}},
               {"web":{"uri":"https://en.wikipedia.org/wiki/Dhaka","title":"Wikipedia"}}
             ]}
        """.trimIndent()
        val report = GroundingParser.fromServerMessage(
            """{"serverContent":{"groundingMetadata":${metadata}}}"""
        )
        assertEquals(2, report.sources.size)
        // The title survives parsing (0.2.14 stopped substituting the url for a missing title).
        assertEquals("The Daily Star", report.sources[0].title)
        val assessment = GroundingPolicy.assess(report)
        assertEquals(SourceConfidence.MULTI_DOMAIN, assessment.confidence)
        assertEquals(
            "Sources: The Daily Star (redirect link), Wikipedia",
            GroundingPolicy.sourceSummary(assessment)
        )
        assertFalse("not every source is a redirect here", GroundingPolicy.onlyRedirectLinks(assessment))
    }

    @Test
    fun `all-redirect results are reported as such`() {
        val report = GroundingReport(
            sources = listOf(
                GroundedSource("First", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/1"),
                GroundedSource("Second", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/2")
            ),
            queries = listOf("q")
        )
        val assessment = GroundingPolicy.assess(report)
        assertTrue(GroundingPolicy.onlyRedirectLinks(assessment))
        assertEquals("Sources: First (redirect link), Second (redirect link)", GroundingPolicy.sourceSummary(assessment))
        // Confidence still counts hosts, so two wrappers are still "one domain" — honest.
        assertEquals(SourceConfidence.SINGLE_DOMAIN, assessment.confidence)
    }
}
