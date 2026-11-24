package com.sojibhuiya.jarves.youtube

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the popup player shows. The single source of truth for the player UI. */
data class YouTubePlayerState(
    val video: YouTubeVideo? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val audioOnly: Boolean = false,
    val qualityHeight: Int = 0,
    val statusLine: String = ""
)

/**
 * One ExoPlayer for the whole process, shared by the controller and the playback service,
 * so the lock-screen controls and the popup drive the same playback.
 */
object YouTubePlayerHolder {
    @Volatile
    private var playerRef: ExoPlayer? = null

    fun player(context: Context): ExoPlayer = playerRef ?: synchronized(this) {
        playerRef ?: ExoPlayer.Builder(context.applicationContext).build().also { playerRef = it }
    }

    fun release() {
        playerRef?.run { release() }
        playerRef = null
    }
}

/**
 * The next lower muxed option when the network cannot keep up, or null when already at
 * the lowest. Pure, so it is unit-tested without a player.
 */
internal fun nextLowerOption(
    options: List<StreamCandidate>,
    currentHeight: Int
): StreamCandidate? =
    options.filter { it.height in 1 until currentHeight }.maxByOrNull { it.height }

/**
 * Owns playback: one ExoPlayer, adaptive quality, background audio.
 *
 * Quality works like Netflix: the player starts at the best muxed stream (at most 1080p);
 * if it keeps buffering for [BUFFERING_STEP_DOWN_MS], it steps down to the next lower
 * resolution at the same position instead of stalling.
 *
 * Background audio: playback runs in [YouTubePlaybackService] (a foreground media service),
 * so the audio keeps playing when the phone is locked, with controls on the lock screen.
 * [toggleAudioOnly] switches to the audio-only stream to save data.
 */
class YouTubePlayerController(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {

    companion object {
        /** Buffering longer than this steps the quality down instead of stalling. */
        const val BUFFERING_STEP_DOWN_MS = 5_000L
    }

    private val player: ExoPlayer get() = YouTubePlayerHolder.player(context)

    private val _state = MutableStateFlow(YouTubePlayerState())
    val state: StateFlow<YouTubePlayerState> = _state.asStateFlow()

    private var options: List<StreamCandidate> = emptyList()
    private var currentOptionIndex: Int = 0
    private var audioUrl: String? = null
    private var bufferingJob: Job? = null
    private var positionJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val buffering = playbackState == Player.STATE_BUFFERING
            _state.value = _state.value.copy(
                isBuffering = buffering,
                durationMs = player.duration.coerceAtLeast(0L)
            )
            bufferingJob?.cancel()
            bufferingJob = null
            if (buffering && _state.value.video != null) {
                bufferingJob = scope.launch {
                    delay(BUFFERING_STEP_DOWN_MS)
                    stepDown()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(
                isBuffering = false,
                statusLine = "ভিডিও চালানো যায়নি"
            )
        }
    }

    init {
        player.addListener(listener)
        startPositionPolling()
    }

    /** Starts playing a resolved video. */
    fun play(video: YouTubeVideo, streams: YouTubeStreams) {
        options = streams.videoOptions.filter { it.hasAudio }.sortedByDescending { it.height }
        currentOptionIndex = options.indexOfFirst { it.url == streams.videoUrl }.takeIf { it >= 0 } ?: 0
        audioUrl = streams.audioUrl
        val startUrl = options.getOrNull(currentOptionIndex)?.url ?: streams.videoUrl ?: return
        _state.value = YouTubePlayerState(
            video = video,
            isPlaying = false,
            isBuffering = true,
            qualityHeight = options.getOrNull(currentOptionIndex)?.height ?: 0
        )
        startService()
        setItem(startUrl, 0L)
    }

    fun pause() {
        player.pause()
    }

    fun resume() {
        player.play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** Switches between the video stream and the audio-only stream, keeping the position. */
    fun toggleAudioOnly() {
        val current = _state.value
        if (current.video == null) return
        if (current.audioOnly) {
            val url = options.getOrNull(currentOptionIndex)?.url ?: return
            _state.value = current.copy(audioOnly = false)
            setItem(url, player.currentPosition)
        } else {
            val url = audioUrl ?: return
            _state.value = current.copy(audioOnly = true)
            setItem(url, player.currentPosition)
        }
    }

    /** Stops playback and hides the player. */
    fun stop() {
        bufferingJob?.cancel()
        bufferingJob = null
        player.stop()
        _state.value = YouTubePlayerState()
        runCatching {
            context.stopService(Intent(context, YouTubePlaybackService::class.java))
        }
    }

    private fun stepDown() {
        val current = options.getOrNull(currentOptionIndex) ?: return
        val lower = nextLowerOption(options, current.height) ?: return
        currentOptionIndex = options.indexOf(lower)
        _state.value = _state.value.copy(
            statusLine = "নেট slow — কোয়ালিটি কমানো হচ্ছে",
            qualityHeight = lower.height
        )
        setItem(lower.url, player.currentPosition)
    }

    private fun setItem(url: String, positionMs: Long) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        if (positionMs > 0) player.seekTo(positionMs)
        player.play()
    }

    private fun startService() {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, YouTubePlaybackService::class.java)
            )
        }
    }

    private fun startPositionPolling() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (true) {
                delay(500)
                val current = _state.value
                if (current.video != null) {
                    _state.value = current.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        durationMs = player.duration.coerceAtLeast(0L),
                        isPlaying = player.isPlaying
                    )
                }
            }
        }
    }
}
