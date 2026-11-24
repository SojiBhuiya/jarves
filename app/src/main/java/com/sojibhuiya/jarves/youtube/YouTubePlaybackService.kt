package com.sojibhuiya.jarves.youtube

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground media service for the YouTube player. It owns the [MediaSession] over the
 * shared player ([YouTubePlayerHolder]), which is what keeps the audio playing when the
 * phone is locked and puts play/pause/next on the lock screen and in the notification.
 *
 * The service is started by [YouTubePlayerController] when playback starts and stopped
 * when playback stops. It never creates its own player.
 */
class YouTubePlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = YouTubePlayerHolder.player(this)
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
