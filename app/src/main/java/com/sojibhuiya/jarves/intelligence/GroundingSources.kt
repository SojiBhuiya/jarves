package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson

/**
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
                // 0.2.14 (task D): keep the metadata title as-is (no "title = url" substitution).
                // GroundedSource.displayName decides what is shown, so a redirect-only chunk shows
                // the page title and the "(redirect link)" marker instead of a long wrapper URL.
                GroundedSource(title = (web[KEY_TITLE] as? String)?.trim().orEmpty(), url = url)
            }
            // Ignore redirect wrappers that carry no real host information.
            .filter { it.url.startsWith("http") }

        val queries = (grounding[KEY_QUERIES] as? List<*>).orEmpty()
            .mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }

        return GroundingReport(sources = sources, queries = queries)
    }
}
