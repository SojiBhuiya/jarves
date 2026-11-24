package com.sojibhuiya.jarves.youtube

import com.sojibhuiya.jarves.apiproviders.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * YouTube Data API v3 search, over the OkHttp client the app already uses.
 *
 * Two calls per search: `search.list` (100/day bucket) for matches, then `videos.list`
 * (1 unit per 50) for durations. A failed durations call never fails the search — the
 * videos simply come back without durations.
 *
 * The API key is passed in by the caller (it is stored encrypted elsewhere); it is never
 * logged and never appears in any [YouTubeSearchResult.detail].
 */
class YouTubeSearchClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://www.googleapis.com/youtube/v3"
) {

    suspend fun search(query: String, apiKey: String, maxResults: Int = 10): YouTubeSearchResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return YouTubeSearchResult(failure = YouTubeFailure.EMPTY)
        if (apiKey.isBlank()) return YouTubeSearchResult(failure = YouTubeFailure.NO_KEY)
        return try {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("part", "snippet")
                .addQueryParameter("type", "video")
                .addQueryParameter("maxResults", maxResults.coerceIn(1, 50).toString())
                .addQueryParameter("q", trimmed)
                .addQueryParameter("key", apiKey)
                .build()
            val (code, body) = get(url.toString())
            if (code != 200 || body == null) {
                return YouTubeSearchResult(
                    failure = failureFor(code, body),
                    statusCode = code,
                    detail = "search.list HTTP $code"
                )
            }
            val videos = parseSearchResponse(body)
            if (videos.isEmpty()) {
                return YouTubeSearchResult(failure = YouTubeFailure.EMPTY, statusCode = code)
            }
            val durations = runCatching { fetchDurations(videos.map { it.videoId }, apiKey) }
                .getOrElse { emptyMap() }
            YouTubeSearchResult(
                videos = videos.map { it.copy(durationSeconds = durations[it.videoId]) },
                statusCode = code
            )
        } catch (e: IOException) {
            YouTubeSearchResult(failure = YouTubeFailure.NETWORK, detail = e.javaClass.simpleName)
        } catch (e: Exception) {
            YouTubeSearchResult(failure = YouTubeFailure.INTERNAL, detail = e.javaClass.simpleName)
        }
    }

    private suspend fun fetchDurations(videoIds: List<String>, apiKey: String): Map<String, Long> {
        val url = "$baseUrl/videos".toHttpUrl().newBuilder()
            .addQueryParameter("part", "contentDetails")
            .addQueryParameter("id", videoIds.joinToString(","))
            .addQueryParameter("maxResults", "50")
            .addQueryParameter("key", apiKey)
            .build()
        val (code, body) = get(url.toString())
        if (code != 200 || body == null) return emptyMap()
        return parseDurations(body)
    }

    /** Returns (HTTP status, body or null). The body is read fully on the IO thread. */
    private suspend fun get(url: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        runInterruptible {
            http.newCall(request).execute().use { response ->
                response.code to response.body?.string()
            }
        }
    }

    companion object {
        /**
         * Maps an HTTP status + error body to a failure. Quota errors are their own value so
         * the app can say plainly that today's searches are used up.
         */
        internal fun failureFor(code: Int, body: String?): YouTubeFailure = when (code) {
            401, 403 -> if (body != null && (body.contains("quotaExceeded") ||
                    body.contains("dailyLimitExceeded") || body.contains("rateLimitExceeded"))
            ) YouTubeFailure.QUOTA else YouTubeFailure.AUTH
            429 -> YouTubeFailure.QUOTA
            else -> YouTubeFailure.HTTP
        }

        /** Parses a `search.list` response into videos. Unknown shapes yield an empty list. */
        internal fun parseSearchResponse(body: String): List<YouTubeVideo> {
            val root = runCatching { MiniJson.parse(body) }.getOrNull() as? Map<*, *>
                ?: return emptyList()
            val items = root["items"] as? List<*> ?: return emptyList()
            return items.mapNotNull { entry ->
                val item = entry as? Map<*, *> ?: return@mapNotNull null
                val id = (item["id"] as? Map<*, *>)?.get("videoId") as? String
                    ?: return@mapNotNull null
                val snippet = item["snippet"] as? Map<*, *> ?: return@mapNotNull null
                val title = (snippet["title"] as? String)?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                val channel = (snippet["channelTitle"] as? String).orEmpty()
                val published = (snippet["publishedAt"] as? String).orEmpty()
                val thumbs = snippet["thumbnails"] as? Map<*, *>
                val thumb = ((thumbs?.get("medium") as? Map<*, *>)?.get("url") as? String)
                    ?: ((thumbs?.get("default") as? Map<*, *>)?.get("url") as? String)
                    .orEmpty()
                YouTubeVideo(
                    videoId = id,
                    title = title,
                    channelTitle = channel,
                    publishedAt = published,
                    thumbnailUrl = thumb
                )
            }
        }

        /** Parses a `videos.list` (contentDetails) response into videoId → duration seconds. */
        internal fun parseDurations(body: String): Map<String, Long> {
            val root = runCatching { MiniJson.parse(body) }.getOrNull() as? Map<*, *>
                ?: return emptyMap()
            val items = root["items"] as? List<*> ?: return emptyMap()
            val out = mutableMapOf<String, Long>()
            for (entry in items) {
                val item = entry as? Map<*, *> ?: continue
                val id = item["id"] as? String ?: continue
                val raw = ((item["contentDetails"] as? Map<*, *>)?.get("duration") as? String)
                    ?: continue
                parseIso8601Duration(raw)?.let { out[id] = it }
            }
            return out
        }

        /**
         * ISO 8601 durations as YouTube returns them (`PT4M12S`, `PT1H2M3S`, `P1DT2H`).
         * Returns seconds, or null when the text is not a duration.
         */
        internal fun parseIso8601Duration(text: String): Long? {
            val match = Regex("""^P(?:(\d+)D)?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$""")
                .matchEntire(text.trim()) ?: return null
            val (days, hours, minutes, seconds) = match.destructured
            if (days.isEmpty() && hours.isEmpty() && minutes.isEmpty() && seconds.isEmpty()) {
                return null
            }
            val d = days.toLongOrNull() ?: 0L
            val h = hours.toLongOrNull() ?: 0L
            val m = minutes.toLongOrNull() ?: 0L
            val s = seconds.toLongOrNull() ?: 0L
            return d * 86_400L + h * 3_600L + m * 60L + s
        }
    }
}
