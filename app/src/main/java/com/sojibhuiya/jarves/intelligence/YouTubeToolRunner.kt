package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.youtube.YouTubeFailure
import com.sojibhuiya.jarves.youtube.YouTubeSearchClient
import com.sojibhuiya.jarves.youtube.YouTubeSearchResult
import com.sojibhuiya.jarves.youtube.YouTubeStreamResolver
import com.sojibhuiya.jarves.youtube.YouTubeStreams
import com.sojibhuiya.jarves.youtube.YouTubeVideo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs `search_videos`, `play_video` and `control_playback` calls off the WebSocket thread.
 *
 * Same guarantees as [SearchToolRunner]: [handleSearch]/[handlePlay]/[handleControl] return
 * immediately; at most [maxConcurrent] calls run at once (a further call gets an honest
 * "too many" answer); every call gets a reply inside [timeoutMillis]; a cancelled call is
 * dropped; a callId is answered at most once.
 *
 * The runner never touches the player itself: [onPlayVideo] starts playback (UI + ExoPlayer)
 * and [onControl] drives the player that is already running. Both are supplied by the
 * activity that owns the player.
 */
class YouTubeToolRunner(
    private val searchClient: YouTubeSearchClient,
    /** Suspended because the store reads DataStore; the runner calls it from its own scope. */
    private val keyProvider: suspend () -> String?,
    private val resolver: YouTubeStreamResolver,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxConcurrent: Int = 2,
    // 0.2.22: raised from 30 s — the phone's connection is slow even on Wi-Fi (the voice
    // socket took ~5 s to open), and resolving a video fetches a large YouTube page. The
    // resolver has the same cap; both must move together or the shorter one wins.
    private val timeoutMillis: Long = 60_000L,
    /** Delivered on a background thread: (callId, functionName, resultText, scheduling). */
    private val onResult: (String, String, String, String) -> Unit,
    /** Short, secret-free status lines for the UI. */
    private val onEvent: (String) -> Unit = {},
    /** A video was picked and its streams resolved: start playback (UI + player). */
    private val onPlayVideo: (YouTubeVideo, YouTubeStreams) -> Unit = { _, _ -> },
    /** A playback control was requested: pause, resume, next, previous or stop. */
    private val onControl: (String) -> Unit = {},
    /** A search succeeded: the video list, so the UI can show tappable version cards. */
    private val onSearchResults: (List<YouTubeVideo>) -> Unit = {}
) {

    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private var activeCount = 0

    /** How many video calls are running right now (diagnostics and tests). */
    val active: Int get() = synchronized(lock) { activeCount }

    private enum class Refusal { DUPLICATE, OVER_CAPACITY }

    fun handleSearch(call: YouTubeSearchCall) = enqueue(
        callId = call.callId,
        functionName = LiveSessionConfig.FUNCTION_SEARCH_VIDEOS,
        busyText = "Too many video searches are running. Please wait a moment and ask again."
    ) {
        onEvent("ভিডিও খোঁজা হচ্ছে: ${call.query}")
        val key = keyProvider()
        val result = if (key.isNullOrBlank()) {
            YouTubeSearchResult(failure = YouTubeFailure.NO_KEY)
        } else {
            withTimeoutOrNull(timeoutMillis) { searchClient.search(call.query, key) }
                ?: YouTubeSearchResult(failure = YouTubeFailure.NETWORK, detail = "timeout")
        }
        if (result.failure == null) runCatching { onSearchResults(result.videos) }
        deliver(call.callId, LiveSessionConfig.FUNCTION_SEARCH_VIDEOS, formatSearchResult(call.query, result))
    }

    fun handlePlay(call: YouTubePlayCall) = enqueue(
        callId = call.callId,
        functionName = LiveSessionConfig.FUNCTION_PLAY_VIDEO,
        busyText = "Too many video actions are running. Please wait a moment and ask again."
    ) {
        onEvent("ভিডিও চালানো হচ্ছে")
        val streams = withTimeoutOrNull(timeoutMillis) { resolver.resolve(call.videoId) }
            ?: YouTubeStreams(videoId = call.videoId, failure = YouTubeFailure.NETWORK, detail = "timeout")
        if (streams.failure != null || (streams.videoUrl == null && streams.audioUrl == null)) {
            deliver(call.callId, LiveSessionConfig.FUNCTION_PLAY_VIDEO, playFailureText(streams))
            return@enqueue
        }
        val video = YouTubeVideo(
            videoId = call.videoId,
            title = call.title.ifBlank { streams.title },
            channelTitle = ""
        )
        runCatching { onPlayVideo(video, streams) }
        deliver(call.callId, LiveSessionConfig.FUNCTION_PLAY_VIDEO, "Playing ${video.title}.")
    }

    fun handleControl(call: YouTubeControlCall) = enqueue(
        callId = call.callId,
        functionName = LiveSessionConfig.FUNCTION_CONTROL_PLAYBACK,
        busyText = "Too many video actions are running. Please wait a moment and ask again."
    ) {
        val action = call.action.trim().lowercase()
        if (action !in setOf("pause", "resume", "next", "previous", "stop")) {
            deliver(
                call.callId,
                LiveSessionConfig.FUNCTION_CONTROL_PLAYBACK,
                "Unknown playback action \"$action\". Use pause, resume, next, previous or stop."
            )
            return@enqueue
        }
        runCatching { onControl(action) }
        deliver(call.callId, LiveSessionConfig.FUNCTION_CONTROL_PLAYBACK, "Playback: $action.")
    }

    /**
     * The user interrupted the turn: drop the work and send nothing. The server has already
     * cancelled the call, so a late `toolResponse` would be an orphan.
     */
    fun cancel(callIds: List<String>) {
        val toCancel = synchronized(lock) {
            callIds.mapNotNull { id -> jobs.remove(id)?.also { activeCount = (activeCount - 1).coerceAtLeast(0) } }
        }
        toCancel.forEach { it.cancel() }
        if (toCancel.isNotEmpty()) onEvent("ভিডিও কাজ বাতিল হয়েছে")
    }

    /** Stops everything (session ended). */
    fun shutdown() {
        val running = synchronized(lock) {
            val copy = jobs.values.toList()
            jobs.clear()
            activeCount = 0
            copy
        }
        running.forEach { it.cancel() }
        scope.cancel()
    }

    private fun enqueue(callId: String, functionName: String, busyText: String, work: suspend () -> Unit) {
        // LAZY is the whole trick: `launch` hands back a Job immediately but runs nothing, so the
        // callId can be reserved in one atomic step *before* the body starts (same as SearchToolRunner).
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                work()
            } finally {
                synchronized(lock) {
                    if (jobs.remove(callId) != null) {
                        activeCount = (activeCount - 1).coerceAtLeast(0)
                    }
                }
            }
        }
        val refusal = synchronized(lock) {
            when {
                jobs.containsKey(callId) -> Refusal.DUPLICATE
                activeCount >= maxConcurrent -> Refusal.OVER_CAPACITY
                else -> {
                    jobs[callId] = job
                    activeCount += 1
                    null
                }
            }
        }
        if (refusal != null) {
            job.cancel()
            if (refusal == Refusal.OVER_CAPACITY) {
                onEvent("একসাথে বেশি ভিডিও কাজ — অপেক্ষা করতে বলা হচ্ছে")
                deliver(callId, functionName, busyText)
            } else {
                onEvent("একই ভিডিও কাজ আবার এসেছে — উপেক্ষা করা হচ্ছে")
            }
            return
        }
        job.start()
    }

    private fun deliver(callId: String, functionName: String, result: String) {
        runCatching { onResult(callId, functionName, result, LiveToolProtocol.SCHEDULING_WHEN_IDLE) }
    }

    companion object {
        /** Seconds → "4:12". */
        internal fun formatDuration(seconds: Long): String {
            val m = seconds / 60
            val s = seconds % 60
            return "$m:${s.toString().padStart(2, '0')}"
        }

        /** The numbered list the model presents — and asks the user to pick from. */
        internal fun formatSearchResult(query: String, result: YouTubeSearchResult): String {
            val failure = result.failure
            if (failure != null) return when (failure) {
                YouTubeFailure.NO_KEY -> "No YouTube API key is saved. Ask the user to add one in Settings, under API Providers."
                YouTubeFailure.QUOTA -> "Today's YouTube searches are used up (100 per day). Try again tomorrow."
                YouTubeFailure.EMPTY -> "No videos found for \"$query\"."
                YouTubeFailure.NETWORK -> "The video search failed: no connection."
                YouTubeFailure.AUTH -> "The YouTube API key was not accepted."
                YouTubeFailure.HTTP -> "The video search failed (HTTP ${result.statusCode})."
                YouTubeFailure.INTERNAL -> "The video search failed inside the app."
            }
            val lines = result.videos.mapIndexed { index, video ->
                val duration = video.durationSeconds?.let { " (${formatDuration(it)})" }.orEmpty()
                "${index + 1}. ${video.title} — ${video.channelTitle}$duration [videoId: ${video.videoId}]"
            }
            return "Found ${result.videos.size} videos for \"$query\":\n" +
                lines.joinToString("\n") +
                "\nAsk the user which one they want. Do not play the first one by itself. " +
                "When they pick one, call play_video with its videoId at once — do not ask again."
        }

        // 0.2.22: the failure text now names the cause. On the phone every load failure
        // sounded the same ("লোড করতে ব্যর্থ হয়েছে") and the Wi-Fi test ruled the network
        // out, so without the cause the next fix would be a guess. The model speaks this
        // text, so the reason reaches the user in his own language. The EMPTY wording is
        // kept verbatim: a test asserts the result contains "no playable streams".
        internal fun playFailureText(streams: YouTubeStreams): String = when (streams.failure) {
            YouTubeFailure.NETWORK -> "The video could not be loaded: no connection (${streams.detail})."
            YouTubeFailure.EMPTY -> "That video has no playable streams."
            YouTubeFailure.HTTP -> "The video could not be loaded (HTTP ${streams.detail})."
            YouTubeFailure.INTERNAL -> "The video failed to load inside the app (${streams.detail})."
            else -> "The video could not be played."
        }
    }
}
