package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson

/** One web source returned by Google Search grounding. */
data class GroundedSource(val title: String, val url: String) {
    /** Host used for corroboration counting, e.g. `theguardian.com`. */
    val host: String
        get() = url.substringAfter("://", url)
            .substringBefore('/')
            .substringBefore('?')
            .removePrefix("www.")
            .lowercase()
}

/** Grounding metadata for one model turn. */
data class GroundingReport(
    val sources: List<GroundedSource>,
    val queries: List<String>
) {
    val hasSources: Boolean get() = sources.isNotEmpty()

    companion object {
        val EMPTY = GroundingReport(emptyList(), emptyList())
    }
}

/**
 * Extracts grounding metadata (web sources + executed search queries) from a raw Gemini Live
 * server message.
 *
 * Kept platform-free — it uses the app's own [MiniJson] rather than `org.json`, which is only a
 * stub in JVM unit tests — so the parser is covered by real tests.
 */
object GroundingParser {

    private const val KEY_SERVER_CONTENT = "serverContent"
    private const val KEY_GROUNDING = "groundingMetadata"
    private const val KEY_CHUNKS = "groundingChunks"
    private const val KEY_WEB = "web"
    private const val KEY_URI = "uri"
    private const val KEY_TITLE = "title"
    private const val KEY_QUERIES = "webSearchQueries"

    /** Cheap pre-check so ordinary audio/text frames are never parsed. */
    fun mightContainGrounding(message: String): Boolean =
        message.contains(KEY_GROUNDING) || message.contains(KEY_QUERIES)

    fun fromServerMessage(message: String?): GroundingReport {
        if (message.isNullOrBlank() || !mightContainGrounding(message)) return GroundingReport.EMPTY
        val root = runCatching { MiniJson.parse(message) }.getOrNull() as? Map<*, *>
            ?: return GroundingReport.EMPTY
        val serverContent = root[KEY_SERVER_CONTENT] as? Map<*, *> ?: return GroundingReport.EMPTY
        val grounding = serverContent[KEY_GROUNDING] as? Map<*, *> ?: return GroundingReport.EMPTY
        return fromMetadata(grounding)
    }

    /** Parses a `groundingMetadata` object that has already been located. */
    fun fromMetadata(grounding: Map<*, *>): GroundingReport {
        val sources = (grounding[KEY_CHUNKS] as? List<*>).orEmpty()
            .mapNotNull { chunk ->
                val web = (chunk as? Map<*, *>)?.get(KEY_WEB) as? Map<*, *> ?: return@mapNotNull null
                val url = (web[KEY_URI] as? String)?.trim().orEmpty()
                if (url.isEmpty()) return@mapNotNull null
                val title = (web[KEY_TITLE] as? String)?.trim().orEmpty().ifEmpty { url }
                GroundedSource(title = title, url = url)
            }
            // Ignore redirect wrappers that carry no real host information.
            .filter { it.url.startsWith("http") }

        val queries = (grounding[KEY_QUERIES] as? List<*>).orEmpty()
            .mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }

        return GroundingReport(sources = sources, queries = queries)
    }
}
