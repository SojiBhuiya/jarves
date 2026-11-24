package com.sojibhuiya.jarves.youtube

/** Why a YouTube search or stream lookup did not produce a result. Never contains anything secret. */
enum class YouTubeFailure {
    /** No API key is saved yet. */
    NO_KEY,

    /** Socket/DNS/TLS problem, or the extractor timed out. */
    NETWORK,

    /** The key or project was not accepted. */
    AUTH,

    /** The daily search quota (100 searches) is used up. */
    QUOTA,

    /** The search ran but nothing matched. */
    EMPTY,

    /** Any other non-2xx response. */
    HTTP,

    /** The app failed to read a response it should have understood. */
    INTERNAL
}

/** One video from a search. Durations come from `videos.list` (or the stream resolver). */
data class YouTubeVideo(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val publishedAt: String = "",
    val thumbnailUrl: String = "",
    /** Seconds, when known. */
    val durationSeconds: Long? = null
)

data class YouTubeSearchResult(
    val videos: List<YouTubeVideo> = emptyList(),
    val failure: YouTubeFailure? = null,
    val statusCode: Int = 0,
    /** Short, secret-free technical detail for diagnostics only. */
    val detail: String = ""
)

/** Resolved playback URLs for one video. */
data class YouTubeStreams(
    val videoId: String,
    val title: String,
    val durationSeconds: Long? = null,
    /** Best video stream that carries audio: plays as-is in ExoPlayer. Null when none found. */
    val videoUrl: String? = null,
    /** Best audio-only stream: used for lock-screen / audio-only playback. */
    val audioUrl: String? = null,
    /** Every muxed (audio included) video option, best first — used for quality stepping. */
    val videoOptions: List<StreamCandidate> = emptyList(),
    val failure: YouTubeFailure? = null,
    val detail: String = ""
)

/**
 * One stream candidate, in the app's own terms, so picking is pure and unit-testable
 * without NewPipe types.
 */
data class StreamCandidate(
    val url: String,
    /** Video height in pixels (0 for audio-only). */
    val height: Int = 0,
    val bitrate: Int = 0,
    val hasAudio: Boolean = false
)
