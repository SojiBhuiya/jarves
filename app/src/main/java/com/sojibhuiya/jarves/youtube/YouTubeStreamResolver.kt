package com.sojibhuiya.jarves.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException

/** Resolves a videoId into direct playback URLs (no YouTube player, no ads). */
interface YouTubeStreamResolver {
    suspend fun resolve(videoId: String): YouTubeStreams
}

/**
 * Picks the streams ExoPlayer gets. Pure, so it is unit-tested without the network:
 * the best muxed (audio included) video at most 1080p, falling back to any muxed video,
 * then to the best video-only stream; plus the highest-bitrate audio-only stream for
 * lock-screen / audio-only playback.
 */
internal fun pickPlaybackStreams(
    videos: List<StreamCandidate>,
    audios: List<StreamCandidate>
): Pair<String?, String?> {
    val muxed = videos.filter { it.hasAudio && it.url.isNotBlank() }
    val videoUrl = muxed.filter { it.height in 1..1080 }.maxByOrNull { it.height }?.url
        ?: muxed.maxByOrNull { it.height }?.url
        ?: videos.filter { it.url.isNotBlank() }.maxByOrNull { it.height }?.url
    val audioUrl = audios.filter { it.url.isNotBlank() }.maxByOrNull { it.bitrate }?.url
    return videoUrl to audioUrl
}

/**
 * NewPipe Extractor implementation. The extractor fetches YouTube's own pages and solves
 * the stream URLs itself, so playback never goes through YouTube's player (and its ads).
 *
 * The extractor is synchronous and blocking, so every call runs on Dispatchers.IO inside
 * a timeout. `NewPipe.init` runs once per process.
 *
 * 0.2.24: when YouTube bot-checks the visionOS client ("Sign in to confirm you're not
 * a bot" — what the phone hit on mobile data in 0.2.23), the visionOS attempt is
 * retried once after a short pause (the block is often transient), and then the
 * [AndroidPlayerClient] is tried: its requests look like the official YouTube app's.
 * 0.2.26: the Android attempt now runs on ANY visionOS failure, not only the
 * bot-check — the phone's signed-in 0.2.25 test threw a plain ExtractionException
 * from visionOS and the old code never tried Android at all.
 */
open class NewPipeStreamResolver(
    private val downloader: OkHttpNewPipeDownloader,
    // 0.2.22: raised from 30 s to match the runner's cap (see YouTubeToolRunner) — the
    // phone's connection is slow even on Wi-Fi, and the YouTube page is large.
    private val timeoutMillis: Long = 60_000L,
    /** Injectable for tests: the Android fallback (a fake can stand in for the network). */
    private val androidClient: AndroidPlayerClient? = null,
    /** Injectable for tests, so the retry pause costs nothing there. */
    private val retryDelayMillis: Long = 3_000L
) : YouTubeStreamResolver {

    override suspend fun resolve(videoId: String): YouTubeStreams {
        val id = videoId.trim()
        if (id.isEmpty()) return YouTubeStreams(videoId = id, failure = YouTubeFailure.EMPTY)
        return withContext(Dispatchers.IO) {
            val fetched = withTimeoutOrNull(timeoutMillis) {
                runInterruptible { fetch(id) }
            }
            fetched ?: YouTubeStreams(
                videoId = id,
                failure = YouTubeFailure.NETWORK,
                detail = "timeout"
            )
        }
    }

    private fun fetch(videoId: String): YouTubeStreams {
        val first = tryVisionOs(videoId)
        if (first.failure == null) return first
        if (first.detail.startsWith(BOT_CHECK)) {
            try {
                Thread.sleep(retryDelayMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return first
            }
            val retried = tryVisionOs(videoId)
            if (retried.failure == null) return retried.copy(source = "visionOS-retry")
            val android = androidFallback(videoId)
            if (android.failure == null) return android
            // 0.2.25: both ways failed — keep the first cause, but name what the Android
            // fallback hit too, or the next phone test cannot tell them apart (0.2.24 showed
            // only the first failure, so the Android result was invisible).
            return first.copy(
                detail = "${first.detail}; android ${android.failure} (${android.detail})"
            )
        }
        // 0.2.26: any other visionOS failure gets the Android attempt too. The phone's
        // 0.2.25 test proved why: signed in, the bot-check is gone, but visionOS now
        // throws a plain ExtractionException — the visionOS client plus web cookies is
        // a combination YouTube answers badly — and the old code returned that failure
        // without ever trying Android. The Android client plus the signed-in session is
        // the combination yt-dlp uses.
        val android = androidFallback(videoId)
        if (android.failure == null) return android
        return first.copy(
            detail = "${first.detail}; android ${android.failure} (${android.detail})"
        )
    }

    private fun androidFallback(videoId: String): YouTubeStreams =
        (androidClient ?: AndroidPlayerClient(downloader)).fetch(videoId)

    /** One visionOS attempt (the extractor's `StreamInfo` path). Overridden in tests. */
    protected open fun tryVisionOs(videoId: String): YouTubeStreams {
        ensureInit()
        return try {
            val info = StreamInfo.getInfo(
                NewPipe.getService(0),
                "https://www.youtube.com/watch?v=$videoId"
            )
            val videos = info.videoStreams.map {
                StreamCandidate(
                    url = it.url.orEmpty(),
                    height = parseHeight(it.resolution),
                    bitrate = it.bitrate,
                    hasAudio = !it.isVideoOnly
                )
            }
            val audios = info.audioStreams.map {
                StreamCandidate(
                    url = it.url.orEmpty(),
                    height = 0,
                    bitrate = it.averageBitrate,
                    hasAudio = true
                )
            }
            val (videoUrl, audioUrl) = pickPlaybackStreams(videos, audios)
            if (videoUrl == null && audioUrl == null) {
                return YouTubeStreams(
                    videoId = videoId,
                    title = info.name.orEmpty(),
                    failure = YouTubeFailure.EMPTY,
                    detail = "no playable streams"
                )
            }
            YouTubeStreams(
                videoId = videoId,
                title = info.name.orEmpty(),
                durationSeconds = if (info.duration > 0) info.duration else null,
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                videoOptions = videos.filter { it.hasAudio }.sortedByDescending { it.height }
            )
        } catch (e: IOException) {
            YouTubeStreams(
                videoId = videoId,
                failure = YouTubeFailure.NETWORK,
                detail = describeFailure(e)
            )
        } catch (e: Exception) {
            YouTubeStreams(
                videoId = videoId,
                failure = YouTubeFailure.INTERNAL,
                detail = describeFailure(e)
            )
        }
    }

    private fun ensureInit() {
        synchronized(initLock) {
            if (!initialized) {
                NewPipe.init(downloader)
                initialized = true
            }
        }
    }

    private companion object {
        private val initLock = Any()
        private var initialized = false
        /** The extractor's detail when YouTube answers the player request with the
         * "confirm you're not a bot" challenge (see `SignInConfirmNotBotException`). */
        private const val BOT_CHECK = "SignInConfirmNotBotException"
    }
}

/**
 * 0.2.26: the failure detail carries the exception's message too, not just its class
 * name — the 0.2.25 phone test showed only "ExtractionException", which says nothing
 * about what YouTube actually answered. No message means the plain class name, so
 * existing detail strings are unchanged. The message is bounded to one line so the
 * diagnostics stay readable.
 */
internal fun describeFailure(e: Exception): String {
    val name = e.javaClass.simpleName
    val message = e.message?.replace('\n', ' ')?.replace('\r', ' ')?.trim()
        ?.takeIf { it.isNotBlank() }?.take(200)
    return if (message == null) name else "$name: $message"
}

/** "720p", "720p60" → 720. Anything else → 0 (sorts last). */
internal fun parseHeight(resolution: String?): Int {
    if (resolution.isNullOrBlank()) return 0
    return Regex("""^(\d+)""").find(resolution.trim())?.groupValues?.getOrNull(1)
        ?.toIntOrNull() ?: 0
}
