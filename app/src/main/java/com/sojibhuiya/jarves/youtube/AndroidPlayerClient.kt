package com.sojibhuiya.jarves.youtube

import com.sojibhuiya.jarves.apiproviders.MiniJson
import java.io.IOException
import org.schabi.newpipe.extractor.localization.Localization

/**
 * 0.2.24: a second Innertube client for stream URLs, used when the visionOS client is
 * bot-checked ("Sign in to confirm you're not a bot").
 *
 * Why a second client: the visionOS request the app sends is correct — it was replayed
 * live and returns playable streams — but YouTube blocks anonymous watching from some
 * IPs (the phone's mobile data is one). The Android client is what YouTube's own app
 * uses, so it is the least likely client to be blanket-blocked, and it needs no PO token
 * for the player response (yt-dlp: PLAYER_PO_TOKEN_POLICY required=false).
 *
 * What it sends (values verified live, 2026-11-24): a `visitor_id` POST for fresh
 * visitorData, then a `player` POST on www.youtube.com — both with the Android client
 * context (yt-dlp's current values) and the `X-YouTube-Client-Name/Version` headers.
 * The player response's progressive formats carry direct URLs (itag 18, 360p muxed);
 * adaptive formats are usually `signatureCipher`-only, so they are skipped — the
 * fallback plays at 360p, but it plays.
 *
 * 0.2.27: when the signed-in player request is answered with HTTP 400, the same
 * request is tried once more with no session at all (see [fetch]).
 *
 * Blocking; call on Dispatchers.IO (the resolver does). [fetch] never throws: every
 * failure comes back as a [YouTubeStreams] with `failure` set and `source` "android".
 */
open class AndroidPlayerClient(
    private val downloader: OkHttpNewPipeDownloader,
    /** Injectable for tests: a MockWebServer URL replaces the real endpoint. */
    private val visitorIdUrl: String = VISITOR_ID_URL,
    /** Injectable for tests; `?prettyPrint=false&t=…&id=…` is appended. */
    private val playerUrlBase: String = PLAYER_URL_BASE,
    /**
     * 0.2.27: the downloader for the anonymous retry (no session cookies). When null
     * a plain [OkHttpNewPipeDownloader] is created on first use. Injectable for
     * tests so the retry can be pointed at the same MockWebServer.
     */
    private val anonymousDownloader: OkHttpNewPipeDownloader? = null
) {
    /** Created once, on first use — a plain downloader with no auth provider. */
    private val anonDownloader: OkHttpNewPipeDownloader by lazy {
        anonymousDownloader ?: OkHttpNewPipeDownloader()
    }

    /**
     * 0.2.27: when the signed-in player request is answered with HTTP 400, the
     * request is tried once more with no session at all. A session can go stale
     * (the phone's 0.2.26 test: signed in at 00:01, player 400 at 10:50) and
     * YouTube answers a mismatched session with 400 while the identical request
     * without cookies returns 200 (verified live). A stale sign-in must never
     * block playback — and the retry's result is visible in the detail either
     * way, so the next diagnosis is not blind.
     */
    open fun fetch(videoId: String): YouTubeStreams {
        val first = fetchWith(downloader, videoId)
        if (first.failure == YouTubeFailure.HTTP && first.detail == "400") {
            val anon = fetchWith(anonDownloader, videoId)
            if (anon.failure == null) return anon
            return first.copy(detail = "400; anonymous ${anon.failure} (${anon.detail})")
        }
        return first
    }

    /** One visitor_id → player attempt with the given downloader. Never throws. */
    private fun fetchWith(downloader: OkHttpNewPipeDownloader, videoId: String): YouTubeStreams {
        return try {
            val visitorData = visitorData(downloader)
                ?: return YouTubeStreams(
                    videoId = videoId,
                    failure = YouTubeFailure.INTERNAL,
                    detail = "no visitorData",
                    source = "android"
                )
            val body = MiniJson.encode(playerBody(videoId, visitorData))
                .toByteArray(Charsets.UTF_8)
            val response = downloader.postWithContentTypeJson(
                playerUrl(videoId), headers(), body, Localization.DEFAULT
            )
            if (response.responseCode() != 200) {
                return YouTubeStreams(
                    videoId = videoId,
                    failure = YouTubeFailure.HTTP,
                    detail = response.responseCode().toString(),
                    source = "android"
                )
            }
            val text = response.responseBody()
                ?: return YouTubeStreams(
                    videoId = videoId,
                    failure = YouTubeFailure.INTERNAL,
                    detail = "empty player response",
                    source = "android"
                )
            parsePlayerResponse(videoId, text)
        } catch (e: IOException) {
            YouTubeStreams(
                videoId = videoId,
                failure = YouTubeFailure.NETWORK,
                detail = e.javaClass.simpleName,
                source = "android"
            )
        } catch (e: Exception) {
            YouTubeStreams(
                videoId = videoId,
                failure = YouTubeFailure.INTERNAL,
                detail = e.javaClass.simpleName,
                source = "android"
            )
        }
    }

    private fun visitorData(downloader: OkHttpNewPipeDownloader): String? {
        val body = MiniJson.encode(contextJson(null)).toByteArray(Charsets.UTF_8)
        val response = downloader.postWithContentTypeJson(
            visitorIdUrl, headers(), body, Localization.DEFAULT
        )
        if (response.responseCode() != 200) return null
        val text = response.responseBody() ?: return null
        return parseVisitorData(text)
    }

    private fun playerUrl(videoId: String): String =
        "$playerUrlBase?prettyPrint=false&t=${randomToken(12)}&id=$videoId"

    companion object {
        /** yt-dlp's current Android client (verified 2026-11-24). */
        const val ANDROID_CLIENT_VERSION = "21.26.364"
        const val ANDROID_USER_AGENT =
            "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"

        private const val VISITOR_ID_URL =
            "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"
        private const val PLAYER_URL_BASE = "https://www.youtube.com/youtubei/v1/player"

        private const val TOKEN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        internal fun headers(): Map<String, List<String>> = mapOf(
            "User-Agent" to listOf(ANDROID_USER_AGENT),
            "X-Goog-Api-Format-Version" to listOf("2"),
            "X-YouTube-Client-Name" to listOf("3"),
            "X-YouTube-Client-Version" to listOf(ANDROID_CLIENT_VERSION)
        )

        internal fun randomToken(length: Int): String =
            (1..length).map { TOKEN_ALPHABET.random() }.joinToString("")

        /** Mirrors NewPipe's `prepareJsonBuilder` for the Android client. */
        internal fun contextJson(visitorData: String?): Map<String, Any?> {
            val client = linkedMapOf<String, Any?>(
                "clientName" to "ANDROID",
                "clientVersion" to ANDROID_CLIENT_VERSION,
                "androidSdkVersion" to 30,
                "osName" to "Android",
                "osVersion" to "11",
                "hl" to "en",
                "gl" to "US",
                "utcOffsetMinutes" to 0
            )
            if (visitorData != null) client["visitorData"] = visitorData
            return mapOf(
                "context" to mapOf(
                    "client" to client,
                    "request" to mapOf(
                        "internalExperimentFlags" to emptyList<Any?>(),
                        "useSsl" to true
                    ),
                    "user" to mapOf("lockedSafetyMode" to false)
                )
            )
        }

        internal fun playerBody(videoId: String, visitorData: String): Map<String, Any?> {
            val body = contextJson(visitorData).toMutableMap()
            body["videoId"] = videoId
            body["cpn"] = randomToken(16)
            body["contentCheckOk"] = true
            body["racyCheckOk"] = true
            return body
        }

        /** `responseContext.visitorData`, or null when the response is not usable. */
        internal fun parseVisitorData(json: String): String? {
            val root = try {
                MiniJson.parse(json) as? Map<*, *>
            } catch (_: Exception) {
                null
            } ?: return null
            val context = obj(root, "responseContext") ?: return null
            return str(context, "visitorData")?.takeIf { it.isNotBlank() }
        }

        /** Pure, so it is unit-tested without the network. Direct `url`s only: entries
         * that carry only a `signatureCipher` cannot be played without a decipherer. */
        internal fun parsePlayerResponse(videoId: String, json: String): YouTubeStreams {
            val root = try {
                MiniJson.parse(json) as? Map<*, *>
            } catch (_: Exception) {
                null
            } ?: return YouTubeStreams(
                videoId = videoId,
                failure = YouTubeFailure.INTERNAL,
                detail = "bad player json",
                source = "android"
            )
            val playability = obj(root, "playabilityStatus")
            val status = playability?.let { str(it, "status") }
            if (status != "OK") {
                val reason = playability?.let { str(it, "reason") }.orEmpty()
                val detail =
                    if (status == "login_required" && reason.contains("a bot", ignoreCase = true)) {
                        "SignInConfirmNotBotException"
                    } else {
                        "playability:$status"
                    }
                return YouTubeStreams(
                    videoId = videoId,
                    failure = YouTubeFailure.INTERNAL,
                    detail = detail,
                    source = "android"
                )
            }
            val streaming = obj(root, "streamingData")
                ?: return YouTubeStreams(
                    videoId = videoId,
                    failure = YouTubeFailure.EMPTY,
                    detail = "no streamingData",
                    source = "android"
                )
            // 0.2.26: YouTube sometimes replaces the real player response with
            // another video's ("not available on this app") — NewPipe checks for
            // exactly this on the visionOS path, and the phone's signed-in 0.2.25
            // test hit it there. Without the check the app could play the wrong
            // video; a substituted answer is a failure, not a stream.
            val details = obj(root, "videoDetails")
            val responseVideoId = details?.let { str(it, "videoId") }
            if (responseVideoId != videoId) {
                return YouTubeStreams(
                    videoId = videoId,
                    failure = YouTubeFailure.INTERNAL,
                    detail = "response videoId mismatch",
                    source = "android"
                )
            }
            val videos = mutableListOf<StreamCandidate>()
            val audios = mutableListOf<StreamCandidate>()
            // `formats` are progressive (muxed); `adaptiveFormats` are video-only or
            // audio-only, told apart by the mime type.
            for ((key, muxed) in listOf("formats" to true, "adaptiveFormats" to false)) {
                val entries = arr(streaming, key) ?: continue
                for (entry in entries) {
                    val format = entry as? Map<*, *> ?: continue
                    val url = str(format, "url") ?: continue
                    val mime = str(format, "mimeType").orEmpty()
                    val isAudio = mime.startsWith("audio/")
                    val candidate = StreamCandidate(
                        url = url,
                        height = parseHeight(str(format, "qualityLabel")),
                        bitrate = (num(format, "bitrate") as? Number)?.toInt() ?: 0,
                        hasAudio = muxed || isAudio
                    )
                    if (isAudio) audios += candidate else videos += candidate
                }
            }
            val (videoUrl, audioUrl) = pickPlaybackStreams(videos, audios)
            if (videoUrl == null && audioUrl == null) {
                return YouTubeStreams(
                    videoId = videoId,
                    failure = YouTubeFailure.EMPTY,
                    detail = "no playable streams",
                    source = "android"
                )
            }
            return YouTubeStreams(
                videoId = videoId,
                title = details?.let { str(it, "title") }.orEmpty(),
                durationSeconds = details?.let { str(it, "lengthSeconds") }?.toLongOrNull(),
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                videoOptions = videos.filter { it.hasAudio }.sortedByDescending { it.height },
                source = "android"
            )
        }

        private fun obj(map: Map<*, *>, key: String): Map<*, *>? =
            map[key] as? Map<*, *>

        private fun str(map: Map<*, *>, key: String): String? = map[key] as? String

        private fun arr(map: Map<*, *>, key: String): List<*>? = map[key] as? List<*>

        private fun num(map: Map<*, *>, key: String): Any? = map[key]
    }
}
