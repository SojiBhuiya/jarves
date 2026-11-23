package com.sojibhuiya.jarves.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamInfo
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
 */
class NewPipeStreamResolver(
    private val downloader: OkHttpNewPipeDownloader,
    private val timeoutMillis: Long = 30_000L
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
        ensureInit()
        return try {
            val info = StreamInfo.getInfo(
                NewPipe.getService(0),
                "https://www.youtube.com/watch?v=$videoId"
            )
            val videos = info.videoStreams.map {
                StreamCandidate(
                    url = it.url,
                    height = parseHeight(it.resolution),
                    bitrate = it.averageBitrate,
                    hasAudio = !it.isVideoOnly
                )
            }
            val audios = info.audioStreams.map {
                StreamCandidate(
                    url = it.url,
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
                durationSeconds = if (info.length > 0) info.length else null,
                videoUrl = videoUrl,
                audioUrl = audioUrl
            )
        } catch (e: IOException) {
            YouTubeStreams(
                videoId = videoId,
                failure = YouTubeFailure.NETWORK,
                detail = e.javaClass.simpleName
            )
        } catch (e: Exception) {
            YouTubeStreams(
                videoId = videoId,
                failure = YouTubeFailure.INTERNAL,
                detail = e.javaClass.simpleName
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
    }
}

/** "720p", "720p60" → 720. Anything else → 0 (sorts last). */
internal fun parseHeight(resolution: String?): Int {
    if (resolution.isNullOrBlank()) return 0
    return Regex("""^(\d+)""").find(resolution.trim())?.groupValues?.getOrNull(1)
        ?.toIntOrNull() ?: 0
}
