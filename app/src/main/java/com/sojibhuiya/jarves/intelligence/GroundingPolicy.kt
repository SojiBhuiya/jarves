package com.sojibhuiya.jarves.intelligence

/** How well the returned sources support an answer. */
enum class SourceConfidence {
    /** No web sources were returned for this turn. */
    NONE,

    /** Sources exist but all come from one host — reported, not corroborated. */
    SINGLE_DOMAIN,

    /** Two or more independent hosts — corroborated. */
    MULTI_DOMAIN
}

data class GroundingAssessment(
    val confidence: SourceConfidence,
    val hostCount: Int,
    val sources: List<GroundedSource>
) {
    val isVerified: Boolean get() = confidence == SourceConfidence.MULTI_DOMAIN
}

/**
 * Turns raw grounding metadata into (a) an honest confidence level and (b) the extra
 * instruction that keeps the spoken answer truthful.
 *
 * The model compares sources; this layer only reports how much independent support exists and
 * makes sure the user is told when verification was impossible.
 */
object GroundingPolicy {

    /**
     * Spoken when a current-affairs answer had no sources behind it.
     * Bangla first because the assistant defaults to Bangla, with English for clarity.
     */
    const val NO_SOURCES_NOTICE =
        "[verification notice] Your previous answer used no live web sources. Briefly tell the " +
            "user, in the language they are speaking, that you could not verify this against live " +
            "sources just now and that they should double-check it. Do not repeat the whole answer."

    /** Spoken when Google Search grounding is not available in this session at all. */
    const val GROUNDING_UNAVAILABLE_NOTICE =
        "[verification notice] Web search is NOT available in this session. Briefly tell the user, " +
            "in their language, that you cannot verify live information right now, and do not " +
            "present remembered facts as confirmed current information."

    fun assess(report: GroundingReport): GroundingAssessment {
        val hosts = report.sources.map { it.host }.filter { it.isNotEmpty() }.distinct()
        val confidence = when {
            report.sources.isEmpty() -> SourceConfidence.NONE
            hosts.size >= 2 -> SourceConfidence.MULTI_DOMAIN
            else -> SourceConfidence.SINGLE_DOMAIN
        }
        return GroundingAssessment(confidence, hosts.size, report.sources)
    }

    /** "Sources: a.com, b.com" — used for the spoken citation and stored in chat history. */
    fun sourceSummary(assessment: GroundingAssessment): String? {
        if (assessment.sources.isEmpty()) return null
        val hosts = assessment.sources.map { it.host }.filter { it.isNotEmpty() }.distinct()
        if (hosts.isEmpty()) return null
        return "Sources: " + hosts.joinToString(", ")
    }

    /** Guidance handed to the model for this turn, or null when nothing needs saying. */
    fun guidanceFor(assessment: GroundingAssessment): String? = when (assessment.confidence) {
        SourceConfidence.NONE -> null
        SourceConfidence.SINGLE_DOMAIN ->
            "Only one source (${
                assessment.sources.first().host
            }) was found. Present the answer as reported by that source and say it is not independently confirmed."
        SourceConfidence.MULTI_DOMAIN ->
            "The answer is supported by ${assessment.hostCount} independent sources. Name them briefly, " +
                "and if they disagree, say clearly that the sources conflict instead of picking one."
    }

    /** Text injected when the model answered a current-affairs question without any sources. */
    fun fallbackNotice(groundingAvailable: Boolean): String =
        if (groundingAvailable) NO_SOURCES_NOTICE else GROUNDING_UNAVAILABLE_NOTICE
}
