package com.sojibhuiya.jarves

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.sojibhuiya.jarves.intelligence.FreshnessClassifier
import com.sojibhuiya.jarves.intelligence.GroundedSource
import com.sojibhuiya.jarves.intelligence.GroundingParser
import com.sojibhuiya.jarves.intelligence.CloseAlreadyConnected
import com.sojibhuiya.jarves.intelligence.InterruptionLog
import com.sojibhuiya.jarves.intelligence.LiveFailureRouter
import com.sojibhuiya.jarves.intelligence.LivePingSetting
import com.sojibhuiya.jarves.intelligence.ServerBusyRetry
import com.sojibhuiya.jarves.intelligence.formatSeconds
import com.sojibhuiya.jarves.intelligence.LiveSearchTool
import com.sojibhuiya.jarves.intelligence.LiveModels
import com.sojibhuiya.jarves.intelligence.SessionEndReport
import com.sojibhuiya.jarves.intelligence.VoiceSearchMode
import com.sojibhuiya.jarves.intelligence.LiveToolProtocol
import com.sojibhuiya.jarves.intelligence.CloseInitiator
import com.sojibhuiya.jarves.intelligence.IdleAnnouncementTracker
import com.sojibhuiya.jarves.intelligence.MicSensitivity
import com.sojibhuiya.jarves.intelligence.PendingResultQueue
import com.sojibhuiya.jarves.intelligence.SearchResultItem
import com.sojibhuiya.jarves.intelligence.SearchResultText
import com.sojibhuiya.jarves.intelligence.SearchTimeline
import com.sojibhuiya.jarves.intelligence.NetworkMonitor
import com.sojibhuiya.jarves.intelligence.NetworkSnapshot
import com.sojibhuiya.jarves.intelligence.ReconnectAction
import com.sojibhuiya.jarves.intelligence.ReconnectCoordinator
import com.sojibhuiya.jarves.intelligence.ReconnectCause
import com.sojibhuiya.jarves.intelligence.ReconnectDecision
import com.sojibhuiya.jarves.intelligence.ReconnectPolicy
import com.sojibhuiya.jarves.intelligence.SessionDiagnostics
import com.sojibhuiya.jarves.intelligence.SessionEnvironment
import com.sojibhuiya.jarves.intelligence.SessionEnvironmentProvider
import com.sojibhuiya.jarves.intelligence.SessionResumptionState
import com.sojibhuiya.jarves.intelligence.SearchFunctionCall
import com.sojibhuiya.jarves.intelligence.SearchBackend
import com.sojibhuiya.jarves.intelligence.SearchBudget
import com.sojibhuiya.jarves.intelligence.SearchToolRunner
import com.sojibhuiya.jarves.intelligence.GroundingAvailability
import com.sojibhuiya.jarves.intelligence.GroundingPolicy
import com.sojibhuiya.jarves.intelligence.GroundingReport
import com.sojibhuiya.jarves.intelligence.LiveCloseFlow
import com.sojibhuiya.jarves.intelligence.LiveRejection
import com.sojibhuiya.jarves.intelligence.LiveState
import com.sojibhuiya.jarves.intelligence.LiveSessionConfig
import com.sojibhuiya.jarves.intelligence.LiveSessionPlan
import com.sojibhuiya.jarves.intelligence.Rejection
import com.sojibhuiya.jarves.intelligence.SessionAction
import com.sojibhuiya.jarves.intelligence.TimeContext

/**
 * Gemini Live audio-to-audio transport.
 * Mic: 16 kHz, mono, PCM16. Speaker: 24 kHz, mono, PCM16.
 */
class GeminiLiveVoice(
    private val apiKey: String,
    private val assistantName: String,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String, Boolean) -> Unit,
    private val onError: (String) -> Unit,
    /** Web sources returned by Google Search grounding for the latest turn. */
    private val onGrounding: (List<GroundedSource>) -> Unit = {},
    /**
     * Explicit connection state. The UI must never infer state from Bengali status text, so every
     * transition is reported here.
     */
    private val onState: (LiveState) -> Unit = {},
    /**
     * Design 4 search backend. `null` means voice-only: no function tool is declared and the app
     * never makes a REST search call.
     */
    private val searchBackend: SearchBackend? = null,
    /**
     * Design 4 (0.2.4): the technical failure text that must also appear *in the chat*, so the user
     * can read and copy the real HTTP status and Google's own error message. Called only when a
     * search fails; already scrubbed of anything key-shaped.
     */
    private val onSearchReport: (String) -> Unit = {},
    /**
     * 0.2.6: which voice/search mode this session uses. The model ID and the setup tools both come
     * from the mode, so nothing at a call site can pick a model on its own.
     */
    private val mode: VoiceSearchMode = VoiceSearchMode.VOICE_ONLY,
    /**
     * 0.2.6: called when a session ends with a code the user can act on (1007 / 1008 / 1011 …).
     * The report carries the exact code and reason and says whether Mode B is the way out.
     */
    private val onSessionEnd: (SessionEndReport) -> Unit = {},
    /**
     * 0.2.8: how eagerly microphone input is treated as speech. Settings → Advanced → Mic
     * sensitivity; a conservative value keeps nearby voices and faint noise from starting a turn.
     */
    private val micSensitivity: MicSensitivity = MicSensitivity.DEFAULT,
    /**
     * 0.2.9: where the "what was the phone doing?" readings come from. Android passes a
     * [SessionEnvironmentProvider]; tests pass a fake, and the default is "unknown" everywhere.
     */
    environmentProvider: SessionEnvironmentProvider =
        SessionEnvironmentProvider { SessionEnvironment.UNKNOWN },
    /**
     * 0.2.10: the real network, so a reconnect can wait for it instead of spending attempts on a
     * dead link. Defaults to [NetworkMonitor.NONE] ("unknown") for plain-JVM tests.
     */
    networkMonitor: NetworkMonitor = NetworkMonitor.NONE,
    /**
     * 0.2.10: the Service / notification / battery-optimisation / Data-Saver line. Supplied by the
     * Android layer; the default keeps the engine JVM-only.
     */
    systemDiagnostics: () -> String = { "unknown (no Android context)" },
    /**
     * 0.2.13 (task 3): EXPERIMENT, off by default (0). When set to 15 or 20, OkHttp sends a
     * WebSocket ping every that many seconds, which is the documented way to notice a half-open
     * connection (OkHttp `pingInterval`). The Live docs say nothing about ping frames, RFC 6455
     * §5.5.2 says a peer MUST answer a ping with a pong, and the real Gemini behaviour is NOT-RUN,
     * so this stays behind a hidden setting.
     */
    private val pingIntervalSeconds: Int = 0
) {
    companion object {
        /**
         * Endpoint, model and audio settings are the already working Gemini Live configuration.
         * They are constants so the provider adapter and the unit tests reference the exact
         * same values instead of duplicating (and drifting from) them.
         */
        /** Auto mode: one search per user question, as the 0.2.7 spec requires. */
        const val MAX_SEARCHES_PER_QUESTION = 1

        /**
         * 0.2.13 (task 2): "do not queue more than a few seconds of audio". Input is 16 kHz mono
         * PCM16 = 32 000 bytes/s, so 64 000 bytes is two seconds. Above this the queue is treated as
         * backpressure and the socket is reconnected instead of buffering more.
         */
        const val MAX_SEND_QUEUE_BYTES = 64_000L

        /**
         * 0.2.9: a connection that stayed up this long resets the automatic-reconnect budget, so a
         * long conversation survives the ~10-minute connection lifetime the docs describe
         * (https://ai.google.dev/gemini-api/docs/live-session#session-lifetime) without giving up.
         */
        const val RECONNECT_BUDGET_RESET_MILLIS = 60_000L

        const val WEBSOCKET_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        /** The Mode B model. Kept as a constant only for compatibility; the source of truth is
         * [LiveModels], which owns every Live model ID in the app. */
        const val MODEL = LiveModels.VOICE_ONLY
        const val INPUT_RATE = 16000
        const val OUTPUT_RATE = 24000
        const val CHUNK_BYTES = 640
        const val VOICE_NAME = "Aoede"

        /** How often the live session's date/time context is re-checked (also on midnight crossing). */
        const val TIME_SYNC_INTERVAL_MILLIS = 30 * 60 * 1000L

        /**
         * Shown when the session had to restart without the Google Search tool. Honest: voice
         * works, live verification does not.
         */
        const val SEARCH_UNAVAILABLE_STATUS =
            "\u098f\u0987 \u0995\u09bf-\u09a4\u09c7 Live Web Search \u09b8\u09ae\u09b0\u09cd\u09a5\u09bf\u09a4 \u09a8\u09af\u09bc (\u09af\u09c7\u09ae\u09a8 free tier)\u0964 \u09ad\u09af\u09bc\u09c7\u09b8 \u099a\u09be\u09b2\u09c1 \u09b0\u09be\u0996\u09be \u09b9\u099a\u09cd\u099b\u09c7 \u2014 \u09b2\u09be\u0987\u09ad \u09a4\u09a5\u09cd\u09af \u09af\u09be\u099a\u09be\u0987 \u0995\u09b0\u09be \u09af\u09be\u09ac\u09c7 \u09a8\u09be\u0964"

        /** Shown when the server refused `behavior: NON_BLOCKING` and the function went blocking. */
        const val NON_BLOCKING_UNSUPPORTED_STATUS =
            "\u09b8\u09be\u09b0\u09cd\u09ad\u09be\u09b0 NON_BLOCKING \u09b8\u09ae\u09b0\u09cd\u09a5\u09a8 \u0995\u09b0\u09c7 \u09a8\u09be \u2014 \u09b8\u09be\u09b0\u09cd\u099a \u099a\u09be\u09b2\u09c1 \u0986\u099b\u09c7, \u0995\u09bf\u09a8\u09cd\u09a4\u09c1 \u09ab\u09b2 \u0986\u09b8\u09be \u09aa\u09b0\u09cd\u09af\u09a8\u09cd\u09a4 \u0985\u09aa\u09c7\u0995\u09cd\u09b7\u09be \u0995\u09b0\u09a4\u09c7 \u09b9\u09ac\u09c7"

        /** Connected status when live search verification is unavailable. */
        const val CONNECTED_WITHOUT_SEARCH_STATUS =
            "\u09b8\u0982\u09af\u09c1\u0995\u09cd\u09a4\u0964 \u098f\u0996\u09a8 \u0995\u09a5\u09be \u09ac\u09b2\u09c1\u09a8 (\u09b2\u09be\u0987\u09ad \u09b8\u09be\u09b0\u09cd\u099a \u099b\u09be\u09a1\u09bc\u09be \u2014 \u09b2\u09be\u0987\u09ad \u09a4\u09a5\u09cd\u09af \u09af\u09be\u099a\u09be\u0987 \u0995\u09b0\u09be \u09af\u09be\u09ac\u09c7 \u09a8\u09be)"

        /** Identifies this build in the on-device diagnostic block. */
        const val FIX_TAG = "0.2.13-reconnect-1008"

        /**
         * The mode/model/tool/close line of the diagnostic block. Pure and public so it is
         * unit-tested directly and cannot drift from what the block prints.
         */
        fun diagnosticsFor(
            mode: VoiceSearchMode,
            setupTool: LiveSearchTool,
            closeCode: Int,
            closeReason: String
        ): String {
            val tool = when (setupTool) {
                LiveSearchTool.NONE -> "none"
                LiveSearchTool.GOOGLE_SEARCH_BUILT_IN -> "googleSearch(built-in)"
                LiveSearchTool.FUNCTION_NON_BLOCKING -> "search_web/NON_BLOCKING"
                LiveSearchTool.FUNCTION_BLOCKING -> "search_web/blocking"
            }
            val close = if (closeCode == 0) "none" else "$closeCode / ${closeReason.ifBlank { "none" }}"
            return "Mode: ${mode.name} (${mode.label}) | Live model: ${LiveModels.modelFor(mode)} | " +
                "Tools: $tool | Close: $close"
        }

        /** Connected status for Mode A: Google Search runs inside the session. */
        const val CONNECTED_WITH_BUILTIN_SEARCH_STATUS =
            "\u09b8\u0982\u09af\u09c1\u0995\u09cd\u09a4\u0964 \u098f\u0996\u09a8 \u0995\u09a5\u09be \u09ac\u09b2\u09c1\u09a8 (Google Search \u099a\u09be\u09b2\u09c1)"

        /** Connected status when live search verification is available. */
        const val CONNECTED_WITH_SEARCH_STATUS =
            "\u09b8\u0982\u09af\u09c1\u0995\u09cd\u09a4\u0964 \u098f\u0996\u09a8 \u0995\u09a5\u09be \u09ac\u09b2\u09c1\u09a8 (\u09b2\u09be\u0987\u09ad \u09b8\u09be\u09b0\u09cd\u099a \u099a\u09be\u09b2\u09c1)"
    }

    private val running = AtomicBoolean(false)
    private val setupDone = AtomicBoolean(false)
    private val audioChunksSent = AtomicLong(0)
    private val audioBytesSent = AtomicLong(0)
    private val terminalErrorReported = AtomicBoolean(false)
    @Volatile private var setupCompleteReceived = false
    @Volatile private var websocketOpened = false
    @Volatile private var setupMessageSent = false
    @Volatile private var serverMessageCount = 0
    @Volatile private var lastServerMessageKeys = "none"
    /** Design 4 telemetry: answers "did the model ever ask us to search?" on the device. */
    @Volatile private var toolCallCount = 0
    @Volatile private var lastToolQuery = "none"
    @Volatile private var lastSearchOutcome = "none"
    /** Which search model produced (or last tried for) the most recent search. */
    @Volatile private var lastSearchModel = "none"
    /** 0.2.6: the mode in force, and the last close code/reason seen on this session. */
    @Volatile private var activeMode: VoiceSearchMode = VoiceSearchMode.VOICE_ONLY
    @Volatile private var lastCloseCode = 0
    @Volatile private var lastCloseReason = "none"
    /** What the setup frame declared, so the diagnostic block can prove tools were sent. */
    @Volatile private var setupDeclaredTool = "none"
    @Volatile private var microphoneInitialized = false
    @Volatile private var microphoneStarted = false
    @Volatile private var microphoneStateDetail = "not created"
    /** Which close callback fired last: onClosing / onClosed / onFailure / none. */
    @Volatile private var closeCallback = "none"
    /** Whether the close frame was echoed back, completing the closing handshake. */
    @Volatile private var closeEchoed = false
    /**
     * 0.2.12: the failure decisions (read error, bad frame, playback error, user stop) live in a
     * pure, unit-tested state machine so they can never drift apart again.
     */
    private val failureRouter = LiveFailureRouter()
    /** The last frame that was skipped because it could not be parsed (diagnostics only). */
    @Volatile private var lastSkippedFrame = "none"

    // ---- 0.2.13 (tasks 3 and 4): evidence for a send failure, and interruption timing ----
    /** When the last frame arrived from the server — "how long has this socket been silent?". */
    @Volatile private var lastServerMessageAtMillis: Long = 0L
    /** When `send()` last returned true. */
    @Volatile private var lastSendOkAtMillis: Long = 0L
    /** Number of `send()` calls that returned false (or hit the backlog limit) on this session. */
    @Volatile private var sendFailureCount: Int = 0
    /** One line per send failure: queue size, silence, last good send, network, foreground. */
    @Volatile private var lastSendFailureEvidence: String = "none"
    /** Network type at the previous send, so a change can be shown. */
    @Volatile private var lastSendNetworkType: String = "unknown"
    /** 0.2.13 (task 4): how many interruptions, and how long each lasted. */
    private val interruptions = InterruptionLog()

    // ---- Real-time intelligence state
    /** Whether the setup message asked for Google Search grounding. */
    @Volatile private var groundingRequested = true
    /** Cleared if the server rejects the tool, so the session can continue without it. */
    @Volatile private var groundingAvailable = true
    @Volatile private var groundingFallbackUsed = false
    /** Which tool the setup declares. Design 4 uses a function, never `googleSearch`. */
    @Volatile private var searchToolMode: LiveSearchTool = LiveSearchTool.NONE
    /** Cleared once the server rejects `NON_BLOCKING`, so later sessions declare a blocking function. */
    @Volatile private var nonBlockingAccepted = true

    /**
     * 0.2.8: the last Auto search, step by step, for the Diagnostic block. Written from the search
     * worker thread and the WebSocket thread, read by the UI — volatile + a short immutable String.
     */
    @Volatile private var lastAutoTrace: String = "none"
    @Volatile private var lastToolCallSeen: String = "none"
    @Volatile private var lastToolResponseDelivery: String = "none"

    // ---- 0.2.11: acknowledgement timing, ordered result delivery and the chat safety net ----
    /** Per-search timeline for the diagnostics block (task A). */
    private val searchTimeline = SearchTimeline(SearchTimeline.CAPACITY_DEFAULT)
    /** Results waiting for the model to be idle, in the order the searches finished (task C4). */
    private val pendingResults = PendingResultQueue()
    /** "Was the result actually spoken?" — one nudge at most (task C5). */
    private val announceTracker = IdleAnnouncementTracker()
    /** True while the model is producing a turn (result delivery must wait for idle). */
    @Volatile private var modelGenerating = false
    /** True when the pending result arrived after newer conversation (task C3 wording). */
    @Volatile private var answersInProgressWhenResultArrived = 0
    /** Set when the user asks something after a toolCall, so the later result names its question. */
    @Volatile private var newerQuestionSinceToolCall = false
    @Volatile private var firstAudioAfterToolCallMarked = false
    @Volatile private var awaitingFirstAudioForResult = false
    @Volatile private var currentResultCallId: String = ""
    @Volatile private var lastResultReminder: String = "none"
    /** callId -> the order the question was asked, so two results keep the user's order (task C4). */
    private val searchOrder = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val searchOrderCounter = java.util.concurrent.atomic.AtomicLong(0)

    /** Auto mode: one search per user question; reset after every model turn. */
    private val searchBudget = SearchBudget(MAX_SEARCHES_PER_QUESTION)
    /** Runs `search_web` calls off the WebSocket thread; one per live session. */
    @Volatile private var searchRunner: SearchToolRunner? = null
    /** Set once a tool-refusal retry starts, so the retry can never repeat. */
    private val retryInFlight = AtomicBoolean(false)

    // ---- 0.2.9: who closed the socket, what the phone was doing, and session resumption ----
    /**
     * The reason the *next* close of ours will carry. Written immediately before every
     * `WebSocket.close(...)` in this class, so `onFailure("Socket closed")` can never be mistaken
     * for a broken session again — OkHttp only says "Socket closed" when this process closed the
     * socket itself.
     */
    @Volatile private var closeInitiator: CloseInitiator = CloseInitiator.NONE
    /** Session resumption handle (https://ai.google.dev/gemini-api/docs/live-session). */
    private val resumption = SessionResumptionState()
    @Volatile private var sessionStartedAt: Long = 0L
    @Volatile private var reconnectAttempts: Int = 0
    @Volatile private var goAwaySeen: Boolean = false
    @Volatile private var lastReconnectCause: ReconnectCause = ReconnectCause.NONE
    @Volatile private var reconnectScheduled = false
    @Volatile private var lastReconnectStartedAt: Long = 0L
    @Volatile private var lastGoAwayMillis: Long = 0L
    @Volatile private var lastReconnectNote: String = "none"
    /** 0.2.10: network-aware reconnect budget/waiting state (pure, unit-tested). */
    private val reconnectPlan = ReconnectCoordinator()
    @Volatile private var waitingForNetworkSince: Long = 0L
    @Volatile private var reconnectHadHandle: Boolean = true
    @Volatile private var itWasAReconnect: Boolean = false
    private var networkWatchInstalled: Boolean = false
    /**
     * Injected on Android (see [SessionEnvironmentReader]); "unknown" until [init] runs, which is
     * exactly the right answer for a plain-JVM test.
     */
    private var envProvider: SessionEnvironmentProvider =
        SessionEnvironmentProvider { SessionEnvironment.UNKNOWN }
    private var netMonitor: NetworkMonitor = NetworkMonitor.NONE
    private var systemLine: () -> String = { "unknown" }

    init {
        // Constructor parameters are not properties here on purpose: the engine must stay
        // constructible from a plain-JVM test with no Android context.
        envProvider = environmentProvider
        netMonitor = networkMonitor
        systemLine = systemDiagnostics
    }
    /** True once this session actually produced grounding metadata. */
    @Volatile private var groundingSeen = false
    /** User speech accumulated for the current turn, used by the freshness classifier. */
    private val turnUserSpeech = StringBuilder()
    @Volatile private var groundingForTurn = false
    @Volatile private var noticeSentForTurn = false
    @Volatile private var lastTimeSyncMillis = 0L
    @Volatile private var lastTimeSyncDateKey = ""
    @Volatile private var timeSyncTask: ScheduledFuture<*>? = null
    private val setupTimer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "gemini-setup-timeout").apply { isDaemon = true }
    }
    @Volatile private var setupTimeout: ScheduledFuture<*>? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // 0.2.13 (task 3): only when the hidden experiment is switched on. `pingInterval` makes
        // OkHttp fail the socket when the peer stops answering pings, which is exactly the
        // half-open case that produced AUDIO_SEND_FAILED on a connection the server still had open.
        .apply { if (pingIntervalSeconds > 0) pingInterval(pingIntervalSeconds.toLong(), TimeUnit.SECONDS) }
        .build()

    /**
     * Handles the end of the current socket exactly once, from `onClosing`, `onClosed` or
     * `onFailure`. Using *both* close callbacks is what makes the fallback reachable: OkHttp only
     * calls `onClosed` after both peers close, so a server-initiated close would otherwise never be
     * decided on.
     */
    private val closeFlow = LiveCloseFlow(
        echoClose = { _, _ ->
            // Completes the closing handshake so OkHttp releases the connection, and so the
            // decision below is never blocked waiting for onClosed.
            closeEchoed = true
            if (closeInitiator == CloseInitiator.NONE) closeInitiator = CloseInitiator.SERVER_CLOSE_FRAME
            runCatching { socket?.close(1000, "close acknowledged") }
        },
        retryWithoutSearch = { source -> retryWithoutSearch(source) },
        reportFailure = reportFailure@{ code, reason ->
            // 0.2.6: keep the exact code/reason for the diagnostic block and tell the user what it
            // means — never a paraphrase.
            lastCloseCode = code
            lastCloseReason = reason.ifBlank { "none" }
            // 0.2.9: an unexpected end is retried first (session resumption); only the final
            // failure — or an end the app itself asked for — is ever shown to the user.
            if (tryAutomaticReconnect(ReconnectCause.SOCKET_FAILURE, code, reason)) {
                // Reconnect scheduled: the mic stays up and the user gets one short, calm line.
                return@reportFailure
            }
            if (closeInitiator.intentional) {
                // Our own close (stop button, lifecycle, new chat, provider off, retry) came back as
                // an IOException — OkHttp only says "Socket closed" for a socket WE closed. This is
                // not a failure and must not reach the chat as one.
                if (closeRunning()) {
                    onStatus("Live session বন্ধ হয়েছে")
                    onState(LiveState.ENDED)
                }
                return@reportFailure
            }
            runCatching {
                onSessionEnd(
                    SessionEndReport(
                        code = code,
                        reason = reason,
                        mode = activeMode,
                        model = LiveModels.modelFor(activeMode),
                        setupTools = setupDeclaredTool
                    )
                )
            }
            if (!setupCompleteReceived) {
                fail(
                    "Gemini setupComplete \u0986\u09b8\u09be\u09b0 \u0986\u0997\u09c7\u0987 server session \u09ac\u09a8\u09cd\u09a7 \u0995\u09b0\u09c7\u099b\u09c7 " +
                        "(code $code): ${reason.ifBlank { "no reason given" }}"
                )
            } else {
                closeRunning()
                if (terminalErrorReported.compareAndSet(false, true)) {
                    onError(
                        "Gemini session \u09ac\u09a8\u09cd\u09a7 (code $code): " +
                            "${reason.ifBlank { "no reason given" }}\n${diagnosticSummary()}"
                    )
                }
            }
        },
        reportStopped = {
            // 0.2.9: a clean close frame that follows a `goAway` (or arrives mid-conversation with
            // no request from us) is the server recycling the connection, not the end of the
            // conversation — the resumption reconnect takes over instead of ending the session.
            val handledByReconnect = !closeInitiator.intentional &&
                tryAutomaticReconnect(ReconnectCause.SERVER_CLOSE, lastCloseCode, lastCloseReason)
            if (!handledByReconnect && closeRunning()) {
                onStatus("Live session \u09ac\u09a8\u09cd\u09a7 \u09b9\u09df\u09c7\u099b\u09c7\n${diagnosticSummary()}")
                onState(LiveState.ENDED)
            }
        }
    )

    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var captureThread: Thread? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private val playbackActive = AtomicBoolean(false)
    @Volatile private var playbackThread: Thread? = null

    fun start() {
        if (apiKey.isBlank()) {
            onError("Gemini API Key যোগ করুন")
            return
        }
        if (!running.compareAndSet(false, true)) return
        failureRouter.onSessionStarted()
        // 0.2.13 (task 4): the diagnostics timeline is relative to this instant, and the
        // interruption counter starts from zero for every session.
        val now = System.currentTimeMillis()
        interruptions.onSessionStarted(now)
        reconnectPlan.startTimeline(now)
        lastServerMessageAtMillis = 0L
        lastSendOkAtMillis = 0L
        sendFailureCount = 0
        lastSendFailureEvidence = "none"
        lastSendNetworkType = "unknown"
        terminalErrorReported.set(false)
        setupDone.set(false)
        setupCompleteReceived = false
        websocketOpened = false
        setupMessageSent = false
        serverMessageCount = 0
        lastServerMessageKeys = "none"
        microphoneInitialized = false
        microphoneStarted = false
        microphoneStateDetail = "not created"
        audioChunksSent.set(0)
        audioBytesSent.set(0)
        // ---- Design 4 ----------------------------------------------------------------
        // The session now declares a `search_web` FUNCTION, not the `googleSearch` tool, so the
        // legacy "Live grounding was refused for this credential" memo must not disable it: the
        // memo guards a path that no longer exists, and honouring it would block working REST
        // search for six hours after an unrelated refusal. The memo is left intact for the legacy
        // fallback in retryWithoutSearch().
        activeMode = mode
        lastCloseCode = 0
        lastCloseReason = "none"
        // Mode A: Google searches inside the Live session, so there is no REST backend and no
        // function tool at all. Mode B: exactly the previous behaviour.
        val hasSearchBackend = !mode.usesBuiltInSearch && searchBackend != null
        groundingFallbackUsed = false
        searchToolMode = when {
            mode.usesBuiltInSearch -> LiveSearchTool.GOOGLE_SEARCH_BUILT_IN
            !hasSearchBackend -> LiveSearchTool.NONE
            nonBlockingAccepted -> LiveSearchTool.FUNCTION_NON_BLOCKING
            else -> LiveSearchTool.FUNCTION_BLOCKING
        }
        groundingRequested = searchToolMode != LiveSearchTool.NONE
        groundingAvailable = groundingRequested
        retryInFlight.set(false)
        closeFlow.reset()
        closeCallback = "none"
        closeEchoed = false
        groundingSeen = false
        groundingForTurn = false
        noticeSentForTurn = false
        turnUserSpeech.setLength(0)
        searchBudget.reset()
        lastAutoTrace = "none"
        lastToolCallSeen = "none"
        lastToolResponseDelivery = "none"
        searchTimeline.clear()
        pendingResults.clear()
        modelGenerating = false
        answersInProgressWhenResultArrived = 0
        newerQuestionSinceToolCall = false
        firstAudioAfterToolCallMarked = false
        awaitingFirstAudioForResult = false
        currentResultCallId = ""
        lastResultReminder = "none"
        searchOrder.clear()
        searchOrderCounter.set(0)
        // 0.2.9 session-end forensics + resumption, one session at a time.
        closeInitiator = CloseInitiator.NONE
        resumption.clear()
        sessionStartedAt = System.currentTimeMillis()
        reconnectAttempts = 0
        reconnectScheduled = false
        lastReconnectStartedAt = 0L
        goAwaySeen = false
        lastGoAwayMillis = 0L
        lastReconnectCause = ReconnectCause.NONE
        lastReconnectNote = "none"
        reconnectPlan.reset()
        waitingForNetworkSince = 0L
        reconnectHadHandle = true
        itWasAReconnect = false
        lastTimeSyncMillis = 0L
        lastTimeSyncDateKey = ""
        startSearchRunner()
        installNetworkWatch()
        onStatus("Gemini Live-এ সংযোগ হচ্ছে…")
        onState(LiveState.CONNECTING)
        startTimeSyncLoop()
        connect()
    }

    /** Opens the WebSocket and sends setup (called again, once, if grounding is rejected). */
    private fun connect() {
        // A new socket gets a fresh one-time guard; late callbacks from the previous socket are
        // dropped by the identity check in every callback below.
        closeFlow.reset()
        closeCallback = "none"
        closeEchoed = false
        // Nothing has closed *this* socket yet, and until its setupComplete arrives the capture
        // thread holds the audio instead of sending it into a half-open session.
        closeInitiator = CloseInitiator.NONE
        setupDone.set(false)
        val request = Request.Builder()
            .url("$WEBSOCKET_ENDPOINT?key=$apiKey")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket !== webSocket) return
                websocketOpened = true
                onStatus("WebSocket opened: HTTP ${response.code}, protocol ${response.protocol}; setup পাঠানো হচ্ছে…")
                val snapshot = TimeContext.snapshot()
                lastTimeSyncMillis = System.currentTimeMillis()
                lastTimeSyncDateKey = snapshot.dateKey
                val instruction = LiveSessionConfig.systemInstruction(
                    assistantName = assistantName,
                    time = snapshot,
                    searchTool = searchToolMode
                )
                val setup = LiveSessionConfig.setupJson(
                    model = LiveModels.modelFor(activeMode),
                    voiceName = VOICE_NAME,
                    systemInstruction = instruction,
                    searchTool = searchToolMode,
                    micSensitivity = micSensitivity,
                    // 0.2.9: resume the same conversation across a connection reset.
                    sessionResumptionHandle = resumption.handleForReconnect()
                )
                if (!webSocket.send(setup)) {
                    fail("Gemini setup পাঠানো যায়নি: WebSocket send queue বন্ধ")
                    return
                }
                setupMessageSent = true
                lastSendOkAtMillis = System.currentTimeMillis()
                setupDeclaredTool = when (searchToolMode) {
                    LiveSearchTool.NONE -> "none"
                    LiveSearchTool.GOOGLE_SEARCH_BUILT_IN -> "googleSearch(built-in)"
                    LiveSearchTool.FUNCTION_NON_BLOCKING -> "search_web/NON_BLOCKING"
                    LiveSearchTool.FUNCTION_BLOCKING -> "search_web/blocking"
                }
                lastSearchOutcome = "no toolCall yet"
                toolCallCount = 0
                lastToolQuery = "none"
                onStatus("Setup message পাঠানো হয়েছে (tools: $setupDeclaredTool); response-এর অপেক্ষায়…")
                setupTimeout?.cancel(false)
                setupTimeout = setupTimer.schedule({
                    if (running.get() && !setupDone.get()) {
                        fail("১৫ সেকেন্ডেও Gemini setupComplete আসেনি। নেটওয়ার্ক, model access এবং Live API response পরীক্ষা করুন।")
                    }
                }, 15, TimeUnit.SECONDS)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (socket !== webSocket) return
                onMessage(webSocket, LiveFrameDecoder.decode(bytes.toByteArray()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                // 0.2.13 (task 3): "how long has this socket been silent?" — the cheapest half-open
                // detector available without an experiment.
                lastServerMessageAtMillis = System.currentTimeMillis()
                try {
                    val root = JSONObject(text)
                    serverMessageCount += 1
                    lastServerMessageKeys = root.keys().asSequence().toList().joinToString(",").ifBlank { "empty JSON" }

                    val apiError = root.optJSONObject("error")
                    if (apiError != null) {
                        val code = apiError.optInt("code", 0)
                        val message = apiError.optString("message", "Unknown Gemini API error")
                        // A tool refusal can also arrive in-band as a text error frame.
                        val rejection = LiveRejection.classify(
                            code, message, toolsRequested = groundingAvailable
                        )
                        if (rejection == Rejection.TOOL_REFUSED &&
                            !setupDone.get() &&
                            groundingAvailable &&
                            !groundingFallbackUsed
                        ) {
                            retryWithoutSearch("text error frame")
                            return
                        }
                        fail("Gemini API error ${if (code > 0) "($code) " else ""}$message")
                        return
                    }

                    // ---- 0.2.9: session resumption (https://ai.google.dev/gemini-api/docs/live-session)
                    if (root.has("sessionResumptionUpdate")) {
                        SessionResumptionState.parseUpdate(text)?.let { update ->
                            resumption.onUpdate(update)
                            lastReconnectNote = "resumption update: handle=" +
                                (update.newHandle?.let { "present(${it.length})" } ?: "none") +
                                ", resumable=${update.resumable}"
                        }
                    }

                    // The server will disconnect soon: reconnect with the handle *before* it dies.
                    if (root.has("goAway")) {
                        goAwaySeen = true
                        val notice = SessionResumptionState.parseGoAway(text)
                        val timeLeft = notice?.timeLeftMillis ?: 0L
                        lastReconnectNote = "goAway received (timeLeft=${timeLeft}ms)"
                        scheduleGoAwayReconnect(timeLeft)
                    }

                    if (root.has("setupComplete")) {
                        setupDone.set(true)
                        setupCompleteReceived = true
                        // Task A: every timeline event is an offset from this moment.
                        searchTimeline.start(System.currentTimeMillis())
                        setupTimeout?.cancel(false)
                        setupTimeout = null
                        failureRouter.onReconnectSucceeded()
                        // 0.2.13 (task 4): the session runs again — close the interruption record.
                        interruptions.end(System.currentTimeMillis())
                        startAudioPlayback()
                        startAudioCapture()
                        // 0.2.10: after an automatic reconnect the user is told plainly what
                        // happened — "পুনরায় সংযুক্ত" with the handle, or that the context may be
                        // lost when there was no handle to resume with.
                        val reconnected = itWasAReconnect
                        if (reconnected) {
                            reconnectPlan.onAttemptSucceeded(System.currentTimeMillis())
                            reconnectAttempts = 0
                            itWasAReconnect = false
                        }
                        onStatus(
                            when {
                                reconnected && !reconnectHadHandle ->
                                    ReconnectCoordinator.STATUS_RECONNECTED + " — " +
                                        ReconnectCoordinator.STATUS_CONTEXT_MAY_BE_LOST
                                reconnected -> ReconnectCoordinator.STATUS_RECONNECTED
                                searchToolMode == LiveSearchTool.GOOGLE_SEARCH_BUILT_IN ->
                                    CONNECTED_WITH_BUILTIN_SEARCH_STATUS
                                groundingAvailable -> CONNECTED_WITH_SEARCH_STATUS
                                else -> CONNECTED_WITHOUT_SEARCH_STATUS
                            }
                        )
                        onState(
                            if (groundingAvailable) LiveState.CONNECTED_GROUNDED
                            else LiveState.CONNECTED_WITHOUT_SEARCH
                        )
                    }

                    // ---- Design 4: the model asks the APP to search ------------------
                    if (LiveToolProtocol.mightContainToolCall(text)) {
                        val cancelled = LiveToolProtocol.parseCancellations(text)
                        if (cancelled.isNotEmpty()) {
                            searchRunner?.cancel(cancelled)
                            onStatus("সার্চ বাতিল হয়েছে — আপনি কথা বলেছেন")
                        }
                        val calls = LiveToolProtocol.parseSearchCalls(text)
                        if (calls.isNotEmpty()) {
                            // Task A + B: the toolCall is the moment the acknowledgement was due.
                            searchTimeline.mark(
                                "toolCall received (n=${calls.size}, q=${calls.first().query.take(40)})"
                            )
                            // Task C3: anything the user asks from now on is "newer conversation",
                            // so a result arriving later must name its question.
                            newerQuestionSinceToolCall = false
                            firstAudioAfterToolCallMarked = false
                            toolCallCount += calls.size
                            calls.forEach { call ->
                                searchOrder.putIfAbsent(call.callId, searchOrderCounter.incrementAndGet())
                            }
                            lastToolQuery = calls.first().query.take(60)
                            lastToolCallSeen = "toolCall received (id=${calls.first().callId.take(24)}, " +
                                "q=${calls.first().query.take(40)}, model=${LiveModels.modelFor(activeMode)})"
                            lastAutoTrace = "Auto steps: $lastToolCallSeen"
                            onStatus("৩.৮ মডেল সার্চ চাইছে — দ্বিতীয় সেশন খোলা হচ্ছে…")
                            handleSearchCalls(calls)
                        }
                    }

                    if (GroundingParser.mightContainGrounding(text)) {
                        val report = GroundingParser.fromServerMessage(text)
                        if (report.hasSources) {
                            groundingForTurn = true
                            groundingSeen = true
                            onGrounding(report.sources)
                            GroundingPolicy.sourceSummary(GroundingPolicy.assess(report))
                                ?.let { onStatus(it) }
                        }
                    }

                    val serverContent = root.optJSONObject("serverContent")
                    if (serverContent != null) {
                        if (serverContent.optBoolean("interrupted", false)) {
                            playbackQueue.clear()
                            try { player?.pause(); player?.flush(); player?.play() } catch (_: Exception) {}
                            modelGenerating = false
                            if (pendingResults.size > 0) {
                                answersInProgressWhenResultArrived++
                                searchTimeline.mark("model turn interrupted (barge-in)")
                            }
                            onStatus("আপনার কথা শুনছি…")
                        }

                        serverContent.optJSONObject("inputTranscription")?.optString("text")
                            ?.takeIf { it.isNotBlank()}?.let { chunk ->
                                turnUserSpeech.append(' ').append(chunk)
                                onTranscript(chunk, false)
                                // The user is now talking about something else while a search is
                                // still running: the result that arrives later must name its own
                                // question instead of being dropped into this new topic.
                                if (pendingResults.size > 0 || searchRunner != null) {
                                    newerQuestionSinceToolCall = true
                                    searchTimeline.mark("user speech while search pending")
                                }
                            }

                        serverContent.optJSONObject("outputTranscription")?.optString("text")
                            ?.takeIf { it.isNotBlank() }?.let { onTranscript(it, true) }

                        val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
                        if (parts != null) {
                            var audioSeen = false
                            for (i in 0 until parts.length()) {
                                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData")
                                val encoded = inline?.optString("data").orEmpty()
                                if (encoded.isNotBlank()) {
                                    playbackQueue.offer(Base64.decode(encoded, Base64.DEFAULT))
                                    audioSeen = true
                                }
                            }
                            if (audioSeen) {
                                modelGenerating = true
                                // Task B evidence: did the model speak *before* the result came, or
                                // only alongside it? This mark is what the phone report needs.
                                if (!firstAudioAfterToolCallMarked && toolCallCount > 0) {
                                    firstAudioAfterToolCallMarked = true
                                    searchTimeline.mark("first model audio after toolCall")
                                }
                                if (awaitingFirstAudioForResult) {
                                    awaitingFirstAudioForResult = false
                                    searchTimeline.mark("first audio of the announced result")
                                }
                                announceTracker.onAudio(System.currentTimeMillis())
                            }
                        }
                        if (serverContent.optBoolean("turnComplete", false)) {
                            onStatus("শুনছি… আপনি কথা বলতে পারেন")
                            searchTimeline.mark("turn boundary (turnComplete)")
                            modelGenerating = false
                            finishTurn(webSocket)
                            // Task C: the model is idle — hand it the next pending result, one at a
                            // time and in order. This is the whole point of WHEN_IDLE.
                            deliverNextPendingResult("turnComplete")
                        }
                    }

                    if (root.has("goAway")) onStatus("সংযোগ পুনরায় চালু করতে হবে")
                } catch (e: Exception) {
                    // 0.2.12: one unparseable frame must never end a working conversation. The
                    // frame is skipped and counted (see Settings → Advanced → Diagnostics); only a
                    // session that has already ended ignores it silently.
                    val action = failureRouter.onFrameParseError()
                    if (action == LiveFailureRouter.Action.SKIP_FRAME) {
                        lastSkippedFrame = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                        if (failureRouter.skippedFrames == 1) {
                            onStatus("একটি অস্বাভাবিক সার্ভার ফ্রেম বাদ দেওয়া হয়েছে (সেশন চলছে)")
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                closeCallback = "onFailure"
                setupTimeout?.cancel(false)
                val httpCode = response?.code ?: 0
                val http = response?.let { "HTTP ${it.code}: ${it.message}" }
                val causes = generateSequence(t) { it.cause }
                    .take(5).joinToString(" <- ") { cause ->
                        "${cause.javaClass.simpleName}: ${cause.message ?: "no message"}"
                    }
                val message = "Gemini Live \u09b8\u0982\u09af\u09cb\u0997 \u09ac\u09cd\u09af\u09b0\u09cd\u09a5: " +
                    "${http ?: t.message ?: "network error"}\nCause: $causes\n${diagnosticSummary()}"
                // One decision, shared with the close-frame path. A 429 upgrade refusal while
                // search is declared is recoverable exactly once; everything else is terminal.
                closeFlow.handleFailure(
                    httpCode = httpCode,
                    message = message,
                    hadSearchTool = groundingRequested && groundingAvailable && setupMessageSent,
                    setupComplete = setupCompleteReceived,
                    fallbackUsed = groundingFallbackUsed
                )
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                closeCallback = "onClosing"
                setupTimeout?.cancel(false)
                // 0.2.13 (task 2): `1008 … already connected to an existing client` is transient —
                // the server has not let go of the previous connection yet. It gets its own calm,
                // slower schedule instead of the network retry budget.
                if (tryServerBusyRetry(code, reason)) return
                // THE FIX. OkHttp calls onClosed only after BOTH peers have closed, so a
                // server-initiated close (which is what Gemini sends) never reaches a handler that
                // lives on onClosed alone. Decide here instead: the flow echoes the close once and
                // then acts, so nothing waits indefinitely.
                if (LiveRejection.isNonBlockingRejection(reason) && !setupCompleteReceived) {
                    retryWithBlockingFunction("close frame $code")
                    return
                }
                closeFlow.handleCloseFrame(
                    code = code,
                    reason = reason,
                    hadSearchTool = groundingRequested && groundingAvailable && setupMessageSent,
                    setupComplete = setupCompleteReceived,
                    fallbackUsed = groundingFallbackUsed
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                closeCallback = "onClosed"
                setupTimeout?.cancel(false)
                // 0.2.13 (task 2): same transient 1008 handling as onClosing (the one-time guard in
                // the close flow makes the second call a no-op).
                if (tryServerBusyRetry(code, reason)) return
                // Same handler as onClosing. The one-time guard makes the second call a no-op, so a
                // close is never decided twice and never retried twice.
                if (LiveRejection.isNonBlockingRejection(reason) && !setupCompleteReceived) {
                    retryWithBlockingFunction("close frame $code")
                    return
                }
                closeFlow.handleCloseFrame(
                    code = code,
                    reason = reason,
                    hadSearchTool = groundingRequested && groundingAvailable && setupMessageSent,
                    setupComplete = setupCompleteReceived,
                    fallbackUsed = groundingFallbackUsed
                )
            }
        })
    }

    /**
     * Runs after every model turn: if the user asked something time-sensitive and the answer came
     * back with no live sources, ask the model to say so before moving on. Also keeps the date/time
     * context fresh.
     */
    private fun finishTurn(webSocket: WebSocket) {
        val spoken = turnUserSpeech.toString().trim()
        turnUserSpeech.setLength(0)
        searchBudget.reset()
        val needsLiveSources = FreshnessClassifier.needsLiveSources(spoken)
        if (needsLiveSources && !groundingForTurn && !noticeSentForTurn) {
            noticeSentForTurn = true
            webSocket.send(
                // 0.2.12 (B1): turnComplete = true, otherwise the server only accumulates this
                // text and never speaks it (https://ai.google.dev/api/live — "Otherwise, the
                // server awaits additional messages before starting generation").
                LiveSessionConfig.turnCompletingClientContentJson(
                    GroundingPolicy.fallbackNotice(groundingAvailable)
                )
            )
        }
        groundingForTurn = false
        noticeSentForTurn = false
        maybeSyncTimeContext(webSocket)
    }

    /**
     * Re-sends the current date/time when the session crosses midnight or has been running for a
     * while, so a long conversation never answers with a stale date.
     */
    private fun maybeSyncTimeContext(webSocket: WebSocket? = socket) {
        val target = webSocket ?: return
        if (!setupDone.get()) return
        val now = System.currentTimeMillis()
        val snapshot = TimeContext.snapshot()
        val dayChanged = snapshot.dateKey != lastTimeSyncDateKey
        val stale = now - lastTimeSyncMillis > TIME_SYNC_INTERVAL_MILLIS
        if (!dayChanged && !stale) return
        lastTimeSyncMillis = now
        lastTimeSyncDateKey = snapshot.dateKey
        runCatching {
            target.send(
                LiveSessionConfig.turnCompletingClientContentJson(TimeContext.refreshMessage(snapshot))
            )
        }
    }

    private fun startTimeSyncLoop() {
        timeSyncTask?.cancel(false)
        timeSyncTask = setupTimer.scheduleWithFixedDelay({
            if (running.get() && setupDone.get()) maybeSyncTimeContext()
        }, TIME_SYNC_INTERVAL_MILLIS, TIME_SYNC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun diagnosticSummary(): String {
        val chunks = audioChunksSent.get()
        val bytes = audioBytesSent.get()
        val micState = when (recorder?.recordingState) {
            AudioRecord.RECORDSTATE_RECORDING -> "recording"
            AudioRecord.RECORDSTATE_STOPPED -> "stopped"
            else -> "unavailable"
        }
        return "WebSocket opened: $websocketOpened | Setup sent: $setupMessageSent | " +
            "Server messages: $serverMessageCount | Last message keys: $lastServerMessageKeys | " +
            "SetupComplete: $setupCompleteReceived | Mic initialized: $microphoneInitialized | " +
            "Mic started: $microphoneStarted | Mic state: $micState ($microphoneStateDetail) | " +
            "Audio chunks: $chunks | Bytes: $bytes | Callback: $closeCallback | " +
            "Echo: $closeEchoed | Search: ${if (groundingAvailable) "on" else "off"} " +
            "(tool=$searchToolMode, legacyFallback=$groundingFallbackUsed) | " +
            "Setup tools: $setupDeclaredTool | toolCalls: $toolCallCount | " +
            "Skipped frames: ${failureRouter.skippedFrames} (last: $lastSkippedFrame) | " +
            failureRouter.describe() + " | " +
            "Send failures: $sendFailureCount (last: $lastSendFailureEvidence) | " +
            "Ping: ${LivePingSetting.describe(pingIntervalSeconds)} | " +
            "Server busy: ${reconnectPlan.serverBusy.describe(System.currentTimeMillis())} | " +
            diagnosticsFor(activeMode, searchToolMode, lastCloseCode, lastCloseReason) + " | " +
            "Last query: $lastToolQuery | Last search: $lastSearchOutcome | " +
            "Last search model: $lastSearchModel | ${searchBackend?.describe() ?: "Search models: none"} | " +
            "Mic sensitivity: ${micSensitivity.label} | Audio effects: ${audioEffectsSummary()} | " +
            "build: $FIX_TAG" + "\n" + lastAutoTrace +
            "\nSearch timeline: " + searchTimeline.text() +
            "\nPending results: " + (pendingResults.questions().ifEmpty { listOf("none") }.joinToString(" | ")) +
            " | Announcement: " + announceTracker.describe(System.currentTimeMillis()) +
            " | Reminder: $lastResultReminder" +
            "\n" + endDiagnostics()
    }

    // -------------------------------------------------------------------------------------
    // Design 4: the model calls `search_web` -> app searches over REST -> toolResponse
    // -------------------------------------------------------------------------------------

    /** The last Auto search's step trace, for Settings → Advanced → Diagnostics. */
    fun lastAutoSearchTrace(): String = lastAutoTrace

    /**
     * 0.2.9: who closed the socket, how long the session had been up, what the phone was doing, and
     * where the resumption/reconnect machinery stands. Shown in Settings → Advanced → Diagnostics
     * and appended to every error report.
     */
    fun endDiagnostics(): String = SessionDiagnostics.line(
        initiator = closeInitiator,
        environment = runCatching { envProvider.snapshot() }.getOrDefault(SessionEnvironment.UNKNOWN),
        sessionDurationMillis = if (sessionStartedAt == 0L) 0L else System.currentTimeMillis() - sessionStartedAt,
        closeCode = lastCloseCode,
        closeReason = lastCloseReason,
        reconnectsUsed = reconnectAttempts,
        resumption = resumption,
        goAwaySeen = goAwaySeen,
        reconnectCause = lastReconnectCause
    ) + "\nReconnect: " + lastReconnectNote +
        "\nReconnect timeline: " + reconnectPlan.timelineText() +
        "\n" + interruptions.line(System.currentTimeMillis()) +
        "\nSystem: " + runCatching { systemLine() }.getOrDefault("unknown (line failed)")

    /**
     * What the OS gives this app for echo/noise handling. The recorder uses
     * `MediaRecorder.AudioSource.VOICE_COMMUNICATION`
     * (https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#VOICE_COMMUNICATION),
     * which the platform routes through echo cancellation and noise suppression where the device
     * provides them; this line reports whether the effect APIs exist at all, so a device that
     * lacks them is visible instead of guessed at.
     */
    private fun audioEffectsSummary(): String = try {
        val aec = android.media.audiofx.AcousticEchoCanceler.isAvailable()
        val ns = android.media.audiofx.NoiseSuppressor.isAvailable()
        "source=VOICE_COMMUNICATION, AEC available=$aec, NS available=$ns"
    } catch (e: Throwable) {
        "source=VOICE_COMMUNICATION, effects unknown (${e.javaClass.simpleName})"
    }

    /**
     * Sends one `toolResponse` frame. This is the single send path used by the search runner; it
     * is deliberately narrow so the existing pause/reconnect/retry logic is untouched.
     *
     * @return `false` when the session is already over — the caller must not retry.
     */
    fun sendToolResponse(json: String): Boolean {
        if (!running.get()) return false
        val target = socket ?: return false
        return runCatching { target.send(json) }.getOrDefault(false)
    }

    /** Creates the per-session search runner. No-op when no backend was injected (voice only). */
    private fun startSearchRunner() {
        searchRunner?.shutdown()
        searchRunner = null
        // Mode A: Google searches inside the Live session; the app must not run a REST search.
        if (activeMode.usesBuiltInSearch) return
        val backend = searchBackend ?: return
        searchRunner = SearchToolRunner(
            backend = backend,
            onResult = { call, answer, scheduling ->
                // ---- 0.2.11 (task C) -------------------------------------------------------
                // The answer is no longer handed to the model unconditionally and no longer only
                // as a grown-up tool payload: it is (1) built with the user's own question, at most
                // three plain sentences and the source name, (2) always written into the chat, and
                // (3) sent to the 3.8 session with WHEN_IDLE — either now, if the model is idle, or
                // through the pending queue, so an answer in progress is never cut off.
                val now = System.currentTimeMillis()
                val item = SearchResultItem(
                    callId = call.callId,
                    question = call.query.trim().ifBlank { lastToolQuery },
                    answer = if (answer.isSuccess) SearchResultText.limitSentences(answer.text) else "",
                    sourceName = answer.sources.firstOrNull()?.title.orEmpty(),
                    success = answer.isSuccess,
                    askedAtMillis = 0L,
                    receivedAtMillis = now,
                    orderIndex = searchOrder[call.callId] ?: 0L,
                    askedBeforeResult = newerQuestionSinceToolCall || answersInProgressWhenResultArrived > 0
                )
                // Task C5 safety net #1: the result is in the chat even if the voice never says it.
                runCatching { onSearchReport(SearchResultText.chatLine(item)) }
                // Task A: the second session's own steps (setup sent / socket open / setupComplete /
                // question sent / first transcription / turnComplete, each with its own ms) come
                // straight from the search session's trace.
                searchTimeline.mark("search trace [${answer.trace.ifBlank { "none" }}]")
                searchTimeline.mark(
                    "search text received (ok=${answer.isSuccess}, ${answer.elapsedMillis}ms)"
                )
                lastSearchModel = answer.model.ifBlank { "unknown" } +
                    (if (answer.attempted.size > 1) " (tried ${answer.attempted.joinToString(">")})" else "") +
                    (if (answer.skipped.isNotEmpty()) " (skipped ${answer.skipped.joinToString(", ")})" else "")
                lastSearchOutcome = if (answer.isSuccess) {
                    "ok (${answer.sources.size} sources, ${answer.elapsedMillis}ms)"
                } else {
                    "${answer.failure}" +
                        (if (answer.statusCode > 0) " HTTP ${answer.statusCode}" else "") +
                        (if (answer.serverMessage.isNotBlank()) " google=[${answer.serverMessage}]" else "") +
                        (if (answer.detail.isNotBlank()) " [${answer.detail}]" else "") +
                        (if (answer.model.isNotBlank()) " model=${answer.model}" else "")
                }
                if (answer.isSuccess) {
                    if (answer.sources.isNotEmpty()) runCatching { onGrounding(answer.sources) }
                    runCatching { onStatus("সার্চ শেষ — ফল জানানো হচ্ছে") }
                } else {
                    runCatching { onStatus("সার্চ করা যায়নি — $lastSearchOutcome") }
                    // 0.2.4 kept: the technical reason stays selectable and copyable.
                    runCatching { onSearchReport(answer.failureReport()) }
                }
                lastAutoTrace = (answer.trace.ifBlank { "Auto steps: none" })
                if (modelGenerating) {
                    // The model is mid-answer: wait for the turn boundary instead of interrupting.
                    answersInProgressWhenResultArrived++
                    pendingResults.enqueue(item)
                    searchTimeline.mark("result queued (model busy, pending=${pendingResults.size})")
                    lastToolResponseDelivery = "queued until idle (id=${call.callId.take(24)}, " +
                        "pending=${pendingResults.size})"
                } else {
                    deliverResult(item, scheduling)
                }
            },
            onEvent = { line -> runCatching { onStatus(line) } }
        )
    }

    /**
     * Sends one result to the 3.8 session with the scheduling value the caller chose (always
     * `WHEN_IDLE` for search results, see [LiveToolProtocol.schedulingFor]) and arms the 8-second
     * "did it actually get spoken?" reminder.
     */
    private fun deliverResult(item: SearchResultItem, scheduling: String) {
        val sent = sendToolResponse(
            LiveToolProtocol.toolResponseJson(item.callId, SearchResultText.toolResponse(item), scheduling)
        )
        val now = System.currentTimeMillis()
        lastToolResponseDelivery = if (sent) {
            "toolResponse sent (id=${item.callId.take(24)}, scheduling=$scheduling, " +
                "askedBeforeResult=${item.askedBeforeResult}, 3.8 session open=true)"
        } else {
            "toolResponse NOT sent — the 3.8 session was already closed " +
                "(close=$lastCloseCode ${lastCloseReason.take(40)})"
        }
        searchTimeline.mark("toolResponse sent (scheduling=$scheduling, sent=$sent)")
        if (!sent) {
            runCatching {
                onSearchReport(
                    "সার্চের ফল ৩.৮ সেশনে পাঠানো যায়নি — সেশন বন্ধ ছিল " +
                        "(close=$lastCloseCode, ${lastCloseReason.take(60)})."
                )
            }
            return
        }
        currentResultCallId = item.callId
        awaitingFirstAudioForResult = true
        announceTracker.onResultSent(item.callId, now)
        // Task C5 safety net #2: one check, 8 s later, on the existing session timer. No new thread.
        runCatching {
            setupTimer.schedule({
                runCatching { checkAnnouncementReminder(item.callId) }
            }, IdleAnnouncementTracker.IDLE_AUDIO_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Called at every turn boundary (the model is idle). Hands over the oldest pending result, one
     * per idle turn, so two searches are announced one after the other instead of on top of each
     * other (task C4).
     */
    private fun deliverNextPendingResult(reason: String) {
        if (modelGenerating) return
        val next = pendingResults.poll() ?: return
        searchTimeline.mark("delivering queued result at $reason (pending=${pendingResults.size})")
        deliverResult(next, LiveToolProtocol.SCHEDULING_WHEN_IDLE)
        if (pendingResults.size == 0) {
            answersInProgressWhenResultArrived = 0
            newerQuestionSinceToolCall = false
        }
    }

    /**
     * Task C5: the result was accepted but nothing was said. Recorded in diagnostics, and the model
     * is nudged **at most once** — never a loop, never synthesised audio.
     */
    private fun checkAnnouncementReminder(callId: String) {
        val now = System.currentTimeMillis()
        if (!announceTracker.shouldRemind(now)) return
        lastResultReminder = "no audio ${IdleAnnouncementTracker.IDLE_AUDIO_GRACE_MILLIS}ms after " +
            "toolResponse id=${callId.take(24)} — one reminder sent"
        searchTimeline.mark("8s with no audio after the result — one reminder")
        sendToolResponse(
            LiveToolProtocol.toolResponseJson(
                callId = callId,
                result = "The search answer above has not been spoken yet. Say it now, briefly, in the " +
                    "user's language, and name the source.",
                scheduling = LiveToolProtocol.SCHEDULING_WHEN_IDLE
            )
        )
    }

    /**
     * Answers every `search_web` call. Never blocks the WebSocket reader thread: the runner only
     * schedules coroutines. With no backend injected the model is answered immediately so it is
     * never left waiting for a response that will never come.
     */
    private fun handleSearchCalls(calls: List<SearchFunctionCall>) {
        val runner = searchRunner
        if (runner == null) {
            calls.forEach { call ->
                sendToolResponse(
                    LiveToolProtocol.toolResponseJson(
                        callId = call.callId,
                        result = "Web search is not available in this session. Tell the user you " +
                            "cannot search right now, and do not invent facts or links."
                    )
                )
            }
            return
        }
        onStatus("ওয়েব সার্চ শুরু হচ্ছে…")
        calls.forEach { call ->
            if (searchBudget.tryStart()) {
                runner.handle(call)
            } else {
                // One search per question: tell the model to answer with what it already has
                // instead of spending a second session on the same question.
                onStatus("এই প্রশ্নে ইতিমধ্যে একবার সার্চ হয়েছে")
                sendToolResponse(
                    LiveToolProtocol.toolResponseJson(
                        callId = call.callId,
                        result = "A search has already been run for this question. Answer with " +
                            "what you have now and do not request another search."
                    )
                )
            }
        }
    }

    /**
     * Recovery when the server refuses `behavior: NON_BLOCKING`: reconnect once with the same
     * function declared as a normal (blocking) function, and report exactly what the server said.
     * Search keeps working; the user simply cannot talk over it. Runs at most once per process
     * because [nonBlockingAccepted] stays false afterwards.
     */
    private fun retryWithBlockingFunction(serverSaid: String) {
        if (!running.get()) return
        if (!nonBlockingAccepted) return
        if (!retryInFlight.compareAndSet(false, true)) return
        nonBlockingAccepted = false
        searchToolMode = LiveSearchTool.FUNCTION_BLOCKING
        onStatus("$NON_BLOCKING_UNSUPPORTED_STATUS (server: $serverSaid)")
        onState(LiveState.CONNECTING)
        setupTimeout?.cancel(false)
        setupTimeout = null
        setupCompleteReceived = false
        setupMessageSent = false
        websocketOpened = false
        serverMessageCount = 0
        lastServerMessageKeys = "none"
        searchRunner?.shutdown()
        searchRunner = null
        val rejected = socket
        socket = null
        runCatching { rejected?.close(1000, "retry with blocking function") }
        if (running.get()) connect()
    }

    /**
     * The single recovery path for a Google Search tool refusal (F2).
     *
     * Closes the rejected socket cleanly, then reconnects **exactly once** without the `tools`
     * field, and remembers the refusal for this credential so later sessions skip it. The
     * [retryInFlight] latch and [groundingFallbackUsed] make a second retry impossible, and no
     * caller can reach this after `setupComplete`.
     */
    private fun retryWithoutSearch(source: String) {
        if (!running.get()) return
        if (!retryInFlight.compareAndSet(false, true)) return
        groundingFallbackUsed = true
        groundingAvailable = false
        searchToolMode = LiveSearchTool.NONE
        GroundingAvailability.shared.markUnavailable(apiKey)
        setupTimeout?.cancel(false)
        setupTimeout = null
        // Fresh handshake state for the new socket; the old one is detached first so its
        // late callbacks are ignored.
        setupCompleteReceived = false
        setupMessageSent = false
        websocketOpened = false
        serverMessageCount = 0
        lastServerMessageKeys = "none"
        onStatus("$SEARCH_UNAVAILABLE_STATUS\n(refusal: $source)")
        onState(LiveState.CONNECTING)
        val rejected = socket
        socket = null
        closeInitiator = CloseInitiator.SEARCH_FALLBACK_RETRY
        runCatching { rejected?.close(1000, "retry without grounding") }
        if (running.get()) connect()
    }

    /** Ends the session and stops audio. Returns false when it was already finished. */
    private fun closeRunning(): Boolean {
        failureRouter.onSessionEnded()
        if (!running.getAndSet(false)) return false
        stopAudio()
        return true
    }

    private fun fail(message: String) {
        failureRouter.onSessionEnded()
        setupTimeout?.cancel(false)
        setupTimeout = null
        if (running.getAndSet(false)) {
            val detailedMessage = "$message\n${diagnosticSummary()}"
            terminalErrorReported.set(true)
            closeInitiator = CloseInitiator.SETUP_FAILURE
            try { socket?.close(1002, "Session setup failed") } catch (_: Exception) {}
            stopAudio()
            onState(LiveState.FAILED)
            onError(detailedMessage)
        }
    }

    // -------------------------------------------------------------------------------------
    // 0.2.9: automatic reconnect with the official session-resumption handle
    // -------------------------------------------------------------------------------------

    /**
     * The single entry point for **every** unexpected end of the socket: an `onFailure`, a close
     * frame from the peer, a `goAway`, and — new in 0.2.10 — a failed audio **send**, which is the
     * same failure one step earlier.
     *
     * 0.2.9 phone report: airplane mode toggled → the capture loop's `send()` returned false → it
     * called `fail()`, which is terminal and set `running=false`, so the reconnect path saw a
     * stopped engine and never ran (diagnostics said `Closed by: session failure (fail())` while
     * a perfectly good resume handle was sitting unused). Now the capture loop comes here instead.
     *
     * @return true when this end was handled (reconnect scheduled, or waiting for the network, or
     *   "gave up" shown), i.e. the caller must not also report a failure.
     */
    /**
     * 0.2.13 (task 3): one line of measured evidence for a failed (or alarming) audio send.
     *
     * Nothing here is guessed: every value comes from OkHttp or from the system reader. It answers
     * "why did `send()` fail on a socket the server still saw as connected?" — a half-open
     * connection shows up as a large silence, a stale last-send time and often a network change.
     */
    private fun evidence(socketSendOk: Boolean, backlogBytes: Long, reason: String): String {
        val now = System.currentTimeMillis()
        val silentFor = if (lastServerMessageAtMillis <= 0L) -1L else now - lastServerMessageAtMillis
        val sinceGoodSend = if (lastSendOkAtMillis <= 0L) -1L else now - lastSendOkAtMillis
        val network = runCatching { netMonitor.snapshot() }.getOrDefault(NetworkSnapshot.UNKNOWN)
        val networkChanged = network.type != lastSendNetworkType
        val env = runCatching { envProvider.snapshot() }.getOrDefault(SessionEnvironment.UNKNOWN)
        val foreground = when (env.appVisible) {
            true -> "foreground"
            false -> "background"
            null -> "unknown"
        }
        val screen = when (env.screenInteractive) {
            true -> "screen on"
            false -> "screen off"
            null -> "screen unknown"
        }
        lastSendNetworkType = network.type
        return "send=" + (if (socketSendOk) "ok" else "false") +
            ", queueSize=" + (if (backlogBytes < 0L) "unknown" else "${backlogBytes}B") +
            ", sinceLastServerMessage=" + (if (silentFor < 0L) "none yet" else humanMillis(silentFor)) +
            ", sinceLastGoodSend=" + (if (sinceGoodSend < 0L) "none yet" else humanMillis(sinceGoodSend)) +
            ", network=${network.type}" + (if (networkChanged) " (CHANGED)" else "") +
            ", app=$foreground ($screen)" +
            ", reason=$reason"
    }

    /** `12.8s` — used inside the evidence line. */
    private fun humanMillis(millis: Long): String = formatSeconds(millis) + "s"

    /**
     * 0.2.13 (task 2): `1008 "…already connected to an existing client"` is transient. It does not
     * touch the three normal attempts; it waits 3 s, 5 s, 8 s, 12 s (about 30 s in total) and then
     * ends the session cleanly. The status stays calm and the microphone keeps running, because the
     * user is still talking — only the audio frames are dropped while the socket handshakes.
     *
     * @return true when the condition was recognised and handled (the caller must not also report a
     *   failure); false when this is an ordinary close and the normal path applies.
     */
    private fun tryServerBusyRetry(code: Int, reason: String): Boolean {
        if (!CloseAlreadyConnected.matches(code, reason)) return false
        if (!running.get() || closeInitiator.intentional) return false
        if (terminalErrorReported.get()) return false
        interruptions.begin(System.currentTimeMillis(), CloseAlreadyConnected.describe(code, reason))
        val action = reconnectPlan.onServerBusy(System.currentTimeMillis())
        lastReconnectNote = "server busy (${CloseAlreadyConnected.describe(code, reason)}) -> " +
            when (action) {
                is ReconnectAction.RetryServerBusy -> "retry #${action.attempt} in ${action.delayMillis}ms"
                is ReconnectAction.GiveUp -> "gave up"
                else -> action.toString()
            }
        return when (action) {
            is ReconnectAction.RetryServerBusy -> {
                scheduleReconnect(
                    ReconnectCause.SERVER_BUSY,
                    code,
                    reason,
                    action.delayMillis,
                    attempt = 0,
                    statusOverride = ServerBusyRetry.STATUS_FIXING_CONNECTION
                )
                true
            }
            is ReconnectAction.GiveUp -> {
                giveUpAfterReconnects(code, reason, action.message)
                true
            }
            else -> false
        }
    }

    private fun tryAutomaticReconnect(cause: ReconnectCause, code: Int, reason: String): Boolean {
        // The intentional-close gate is the same one ReconnectPolicy uses and is unit-tested:
        // the user pressed stop, the lifecycle ended, a new chat started, the provider was turned
        // off, or this is our own retry — never reconnect after any of those.
        val gate = ReconnectPolicy.decide(
            running = running.get(),
            initiator = closeInitiator,
            cause = cause,
            attemptsUsed = reconnectAttempts
        )
        if (gate == ReconnectDecision.NO_RECONNECT_STOPPED ||
            gate == ReconnectDecision.NO_RECONNECT_INTENTIONAL
        ) {
            return false
        }
        if (reconnectScheduled) return true

        val now = System.currentTimeMillis()
        // 0.2.13 (task 4): the clock starts when the socket stops working and stops on setupComplete.
        interruptions.begin(now, cause.name)
        lastReconnectCause = cause
        // A connection that stayed healthy for a minute resets the budget, so a long conversation
        // survives the ~10-minute connection lifetime the docs describe.
        if (lastReconnectStartedAt > 0L && now - lastReconnectStartedAt > RECONNECT_BUDGET_RESET_MILLIS) {
            reconnectPlan.reset()
            reconnectAttempts = 0
        }

        val network = netMonitor.snapshot()
        val action = when (cause) {
            ReconnectCause.GO_AWAY -> reconnectPlan.onNetworkAvailable(now, network).let { probed ->
                // A goAway is not a failure: go straight to the scheduled reconnect.
                if (probed is ReconnectAction.Attempt) probed else
                    ReconnectAction.Attempt(attempt = 0, delayMillis = ReconnectPolicy.goAwayDelayMillis(lastGoAwayMillis))
            }
            else -> reconnectPlan.onUnexpectedFailure(now, network, "$cause: $reason")
        }

        return when (action) {
            is ReconnectAction.WaitForNetwork -> {
                waitingForNetworkSince = now
                reconnectAttempts = reconnectPlan.attemptsUsed
                lastReconnectNote = "waiting for network (no attempt spent, deadline=${ReconnectCoordinator.NETWORK_WAIT_SECONDS}s)"
                onStatus(ReconnectCoordinator.STATUS_WAITING_FOR_NETWORK)
                scheduleNetworkDeadline(now)
                true
            }

            is ReconnectAction.Attempt -> {
                reconnectAttempts = reconnectPlan.attemptsUsed
                scheduleReconnect(cause, code, reason, action.delayMillis, action.attempt)
                true
            }

            // 0.2.13: the busy schedule is produced by tryServerBusyRetry(), which schedules its own
            // reconnect. This branch only keeps the `when` exhaustive; reaching it here would mean
            // the policy returned a busy retry for a normal failure, handled the same way.
            is ReconnectAction.RetryServerBusy -> {
                scheduleReconnect(
                    cause,
                    code,
                    reason,
                    action.delayMillis,
                    attempt = 0,
                    statusOverride = ServerBusyRetry.STATUS_FIXING_CONNECTION
                )
                true
            }

            is ReconnectAction.GiveUp -> {
                giveUpAfterReconnects(code, reason, action.message)
                true
            }

            ReconnectAction.None -> false
        }
    }

    /** Schedules the 2-minute "still no network" check, so waiting never becomes forever. */
    private fun scheduleNetworkDeadline(waitingSince: Long) {
        runCatching {
            setupTimer.schedule({
                if (!running.get() || reconnectScheduled) return@schedule
                val action = reconnectPlan.checkNetworkDeadline(System.currentTimeMillis(), waitingSince)
                if (action is ReconnectAction.GiveUp) {
                    waitingForNetworkSince = 0L
                    giveUpAfterReconnects(0, "no network for ~2 minutes", action.message)
                }
            }, ReconnectCoordinator.NETWORK_WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun scheduleReconnect(
        cause: ReconnectCause,
        code: Int,
        reason: String,
        delay: Long,
        attempt: Int,
        /** 0.2.13: a caller-supplied calm line (used by the server-busy path). */
        statusOverride: String? = null
    ) {
        reconnectScheduled = true
        failureRouter.onReconnectScheduled()
        lastReconnectCause = cause
        lastReconnectStartedAt = System.currentTimeMillis()
        val handle = resumption.handleForReconnect()
        val handleState = if (handle != null) "yes" else "none"
        lastReconnectNote = "reconnect #$attempt in ${delay}ms (cause=$cause, code=$code, " +
            "reason=${reason.take(40)}, handle=$handleState, network=${netMonitor.snapshot().type})"
        // One short, non-alarming line. No error bubble, no "session ended".
        onStatus(
            statusOverride ?: if (attempt > 0) {
                "সংযোগ আবার জোড়া লাগছে… ($attempt/${ReconnectCoordinator.MAX_ATTEMPTS_DISPLAY})"
            } else {
                "নতুন নেটওয়ার্ক — আবার যুক্ত হচ্ছি…"
            }
        )
        runCatching {
            setupTimer.schedule({
                reconnectScheduled = false
                if (!running.get() || closeInitiator.intentional) return@schedule
                if (!netMonitor.snapshot().available) {
                    // The network vanished again while we were counting down: do not waste the
                    // attempt — go back to waiting.
                    waitingForNetworkSince = System.currentTimeMillis()
                    onStatus(ReconnectCoordinator.STATUS_WAITING_FOR_NETWORK)
                    scheduleNetworkDeadline(waitingForNetworkSince)
                    return@schedule
                }
                reconnectPlan.onAttemptStarted(System.currentTimeMillis(), attempt)
                openSocketForReconnect(attempt, handle != null)
            }, delay, TimeUnit.MILLISECONDS)
        }.onFailure { reconnectScheduled = false }
    }

    /**
     * Replaces the dead socket without touching the microphone: the recorder keeps running, the
     * playback track keeps its queue, and only the socket is rebuilt with the resumption handle.
     */
    private fun openSocketForReconnect(attempt: Int = 0, hasHandle: Boolean = true) {
        val old = socket
        // Detach first: from this instant the capture loop and every callback see `socket == null`
        // (or the new socket), so no audio and no toolResponse can reach the old connection.
        socket = null
        // 0.2.13 (task 1, the likely cause of the 1008 "already connected" loop): `close()` only
        // *starts* a graceful shutdown and waits for the peer's close frame. On a half-open
        // connection that answer never comes, so the old TCP connection — and with it the server's
        // session attachment — outlived us, and the resume handshake on the new socket was refused.
        // OkHttp documents the alternative: "Immediately and violently release resources held by
        // this web socket, discarding any enqueued messages" (`WebSocket.cancel()`). So: a
        // best-effort polite close first (the server sees an orderly 1000 and can clean up), then
        // cancel() to guarantee the reader/writer and the TCP connection are gone before the new
        // socket is opened.
        runCatching {
            closeInitiator = CloseInitiator.RECONNECT
            old?.close(1000, "reconnecting")
        }
        runCatching { old?.cancel() }
        closeInitiator = CloseInitiator.NONE
        setupDone.set(false)
        // Remembered so setupComplete can say the right thing: with a handle the conversation
        // continues; without one the context is gone and the user is told so.
        reconnectHadHandle = hasHandle
        itWasAReconnect = attempt > 0 || lastReconnectStartedAt > 0L
        // Frames captured while the new socket handshakes are dropped by the capture loop.
        connect()
    }

    /** Schedules the reconnect that a `goAway` asks for, before the connection is torn down. */
    private fun scheduleGoAwayReconnect(timeLeftMillis: Long) {
        lastGoAwayMillis = timeLeftMillis
        if (!running.get()) return
        tryAutomaticReconnect(ReconnectCause.GO_AWAY, 0, "goAway")
    }

    /**
     * A default network appeared. Two cases:
     *  * we were waiting for it → the first attempt runs ~1 s later, with the resume handle;
     *  * the session was healthy and the phone switched networks → one proactive reconnect, so the
     *    conversation is moved onto the working network instead of dying on the old one.
     */
    private fun onNetworkAvailable(snapshot: NetworkSnapshot) {
        if (!running.get() || closeInitiator.intentional) return
        val now = System.currentTimeMillis()
        if (reconnectPlan.waitingForNetwork) {
            val action = reconnectPlan.onNetworkAvailable(now, snapshot)
            if (action is ReconnectAction.Attempt) {
                waitingForNetworkSince = 0L
                reconnectAttempts = reconnectPlan.attemptsUsed
                onStatus("ইন্টারনেট ফিরে এসেছে — আবার যুক্ত হচ্ছি…")
                scheduleReconnect(
                    ReconnectCause.NETWORK_BACK, 0, "network available", action.delayMillis, action.attempt
                )
            }
            return
        }
        if (!setupDone.get() || socket == null) return
        val proactive = reconnectPlan.onNewNetworkWhileHealthy(now, snapshot)
        if (proactive is ReconnectAction.Attempt) {
            lastReconnectCause = ReconnectCause.NEW_NETWORK
            scheduleReconnect(
                ReconnectCause.NEW_NETWORK, 0, "new default network (${snapshot.type})",
                proactive.delayMillis, proactive.attempt
            )
        }
    }

    private fun onNetworkLost(snapshot: NetworkSnapshot) {
        if (!running.get()) return
        reconnectPlan.onNetworkLost(System.currentTimeMillis())
        lastReconnectNote = "network lost (type=${snapshot.type})"
        // The socket is usually dead a moment later; the failure path will show the calm line.
    }

    private fun giveUpAfterReconnects(code: Int, reason: String, message: String) {
        lastReconnectNote = "gave up after $reconnectAttempts attempts " +
            "(last close=$code, reason=${reason.take(40)})"
        reconnectPlan.reset()
        waitingForNetworkSince = 0L
        if (closeRunning()) {
            // closeRunning() also stops the microphone: after giving up, nothing keeps recording.
            if (terminalErrorReported.compareAndSet(false, true)) {
                onError(
                    message + "\n(code=$code, reason=${reason.ifBlank { "none" }})\n${diagnosticSummary()}"
                )
            } else {
                onStatus(message)
            }
            onState(LiveState.FAILED)
        }
    }

    private fun startAudioCapture() {
        // 0.2.9: a reconnect must NOT create a second AudioRecord — the first one is still live and
        // the user must not hear/feel the microphone restart.
        if (recorder != null && microphoneStarted) {
            setupDone.set(true)
            return
        }
        if (!running.get() || !setupDone.get()) return
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IllegalStateException("Microphone audio format unsupported")

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, CHUNK_BYTES * 4)
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                throw IllegalStateException("Microphone could not start")
            }
            recorder = audioRecord
            microphoneInitialized = true
            microphoneStateDetail = "initialized"
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord startRecording-এর পরও recording state active হয়নি")
            }
            microphoneStarted = true
            microphoneStateDetail = "recording started"
            onStatus("সংযুক্ত। মাইক্রোফোন চালু, অডিও পাঠানো হচ্ছে…")

            captureThread = Thread({
                val buffer = ByteArray(CHUNK_BYTES)
                while (running.get()) {
                    val count = audioRecord.read(buffer, 0, buffer.size)
                    if (count > 0 && running.get()) {
                        val payload = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
                        val message = JSONObject().put(
                            "realtimeInput",
                            JSONObject().put("audio", JSONObject()
                                .put("mimeType", "audio/pcm;rate=$INPUT_RATE")
                                .put("data", payload))
                        )
                        val activeSocket = socket
                        // 0.2.9: during a reconnect the socket is null or still handshaking on
                        // purpose. Dropping those frames is correct; failing the session is not.
                        if (activeSocket == null || !setupDone.get()) {
                            if (running.get()) continue else break
                        }
                        // 0.2.13 (task 1): `socket` may have been swapped between the read above and
                        // this line. Sending the chunk to the *previous* connection would be audio on
                        // a socket we just cancelled, so the chunk is dropped instead.
                        if (activeSocket !== socket || !running.get()) continue
                        // 0.2.13 (task 2): never let a stalled connection accumulate audio. The
                        // documented way to see the backlog is queueSize() ("Use queueSize to monitor
                        // backpressure before sending"); above a couple of seconds of PCM the
                        // reconnect is started instead of piling more frames into the queue.
                        val backlog = runCatching { activeSocket.queueSize() }.getOrDefault(0L)
                        if (backlog > MAX_SEND_QUEUE_BYTES) {
                            if (reconnectScheduled || !running.get()) continue
                            lastSendFailureEvidence = evidence(
                                socketSendOk = true, backlogBytes = backlog, reason = "queue too large"
                            )
                            sendFailureCount += 1
                            val handledBacklog = tryAutomaticReconnect(
                                ReconnectCause.AUDIO_SEND_FAILED, 0, "send queue backlog ${backlog}B"
                            )
                            if (handledBacklog) continue
                            fail("অডিও পাঠানোর queue বেড়েই চলছে (${backlog}B)। সংযোগ বন্ধ করছি।")
                            break
                        }
                        if (!activeSocket.send(message.toString())) {
                            // 0.2.13 (task 3): record *why* a send failed while the server still
                            // thought the connection was alive. Everything below is measured, never
                            // guessed: the send return value, OkHttp's own queue size, how long the
                            // socket has been silent, when the last send worked, the network type and
                            // whether it changed, and whether the app was in the foreground.
                            sendFailureCount += 1
                            lastSendFailureEvidence = evidence(
                                socketSendOk = false,
                                backlogBytes = runCatching { activeSocket.queueSize() }.getOrDefault(-1L),
                                reason = "send() returned false"
                            )
                            // 0.2.10 FIX for the phone report: this used to call fail(), which is
                            // terminal and set running=false - so the reconnect path saw a stopped
                            // engine and the resume handle was never used ("airplane mode on/off").
                            // A failed audio send is the socket failure one step earlier: hand it to
                            // the same reconnect path and keep the microphone running.
                            if (reconnectScheduled || !running.get()) continue
                            val handled = tryAutomaticReconnect(
                                ReconnectCause.AUDIO_SEND_FAILED,
                                0,
                                "microphone audio send failed (chunk ${audioChunksSent.get()})"
                            )
                            if (handled) continue
                            fail("মাইক্রোফোন অডিও WebSocket-এ পাঠানো যায়নি। পাঠানো chunk: ${audioChunksSent.get()}")
                            break
                        }
                        lastSendOkAtMillis = System.currentTimeMillis()
                        val chunks = audioChunksSent.incrementAndGet()
                        audioBytesSent.addAndGet(count.toLong())
                        if (chunks == 1L) onStatus("প্রথম microphone audio chunk পাঠানো হয়েছে")
                        if (chunks % 50L == 0L) {
                            onStatus("সংযুক্ত। মাইক্রোফোন থেকে $chunks audio chunks পাঠানো হয়েছে (${audioBytesSent.get()} bytes)")
                        }
                    } else if (count < 0) {
                        val reason = when (count) {
                            AudioRecord.ERROR -> "AudioRecord.ERROR"
                            AudioRecord.ERROR_BAD_VALUE -> "AudioRecord.ERROR_BAD_VALUE"
                            AudioRecord.ERROR_INVALID_OPERATION -> "AudioRecord.ERROR_INVALID_OPERATION"
                            AudioRecord.ERROR_DEAD_OBJECT -> "AudioRecord.ERROR_DEAD_OBJECT"
                            else -> "AudioRecord read error $count"
                        }
                        // 0.2.12 (A3): a dead microphone is the socket failure one step earlier, and
                        // Android usually kills the recorder when the network drops. Hand it to the
                        // same reconnect path as a failed send instead of ending the session, so the
                        // resume handle is actually used. The dead recorder is released here, and
                        // startAudioCapture() recreates it after the next setupComplete.
                        val action = failureRouter.onAudioReadError()
                        if (action == LiveFailureRouter.Action.RECONNECT) {
                            releaseDeadMicrophone(reason)
                            val handled = tryAutomaticReconnect(
                                ReconnectCause.AUDIO_READ_FAILED,
                                0,
                                "microphone read failed: $reason (chunk ${audioChunksSent.get()})"
                            )
                            if (!handled) {
                                fail("Microphone read ব্যর্থ: $reason। পাঠানো chunk: ${audioChunksSent.get()}")
                            }
                        } else if (running.get() && !reconnectScheduled) {
                            fail("Microphone read ব্যর্থ: $reason। পাঠানো chunk: ${audioChunksSent.get()}")
                        }
                        break
                    }
                }
            }, "gemini-live-mic").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            microphoneStateDetail = "error: ${e.message ?: "unknown"}"
            fail("Microphone error: ${e.message ?: "unknown"}")
        }
    }

    /**
     * 0.2.12 (A3): a recorder whose `read()` returned an error code is unusable
     * (`ERROR_DEAD_OBJECT` after a network drop is the usual case). It is released here — without
     * touching the socket or the session state — so `startAudioCapture()` can build a fresh
     * `AudioRecord` after the reconnect's `setupComplete`. Without this the reconnect would resume
     * the conversation with a dead microphone.
     */
    private fun releaseDeadMicrophone(reason: String) {
        microphoneStarted = false
        microphoneStateDetail = "released after a read error ($reason)"
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
    }

    private fun startAudioPlayback() {
        if (!playbackActive.compareAndSet(false, true)) return
        playbackThread = Thread({
            try {
                while (running.get() && playbackActive.get()) {
                    val pcm = playbackQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    try {
                        var track = player
                        if (track == null) {
                            val minBuffer = AudioTrack.getMinBufferSize(
                                OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                            )
                            track = AudioTrack.Builder()
                                .setAudioAttributes(AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                .setAudioFormat(AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(OUTPUT_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                                .setBufferSizeInBytes(maxOf(minBuffer * 2, OUTPUT_RATE))
                                .setTransferMode(AudioTrack.MODE_STREAM).build()
                            player = track
                            track.play()
                        }
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                    } catch (e: Exception) {
                        // 0.2.12 (A2): this used to call onError() only, which the UI treated as
                        // "drop the engine reference" while the microphone, the playback thread and
                        // the foreground service kept running — a ghost session that could talk to
                        // a second session started later. A failed playback means the session can
                        // no longer speak, so it is ended cleanly: fail() closes the socket, stops
                        // the audio, emits LiveState.FAILED and the UI stops the foreground service
                        // (LiveStateRouting.releaseEngine).
                        val action = failureRouter.onPlaybackError()
                        if (action == LiveFailureRouter.Action.STOP_TERMINAL) {
                            fail("Audio playback error: ${e.message ?: "unknown"}")
                        }
                        break
                    }
                }
            } catch (_: InterruptedException) {
                // Normal shutdown.
            }
        }, "gemini-live-playback").apply { isDaemon = true; start() }
    }

    /**
     * Ends the session deliberately.
     *
     * @param initiator who asked for it — recorded *before* the socket is closed so a following
     *   `onFailure("Socket closed")` is recognised as our own close instead of being shown to the
     *   user as "the session broke". It is also the flag that blocks an automatic reconnect.
     */
    fun stop(initiator: CloseInitiator = CloseInitiator.USER_STOP) {
        // A user stop is terminal by definition: no reconnect may follow it.
        failureRouter.onSessionEnded()
        closeInitiator = initiator
        if (!running.getAndSet(false)) return
        try {
            timeSyncTask?.cancel(false)
            timeSyncTask = null
            socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
            socket?.close(1000, "User stopped session")
        } catch (_: Exception) {}
        stopAudio()
        onStatus("Live voice \u09ac\u09a8\u09cd\u09a7")
        onState(LiveState.ENDED)
    }

    /**
     * 0.2.10: one listener pair per engine. `onAvailable` is what turns "ইন্টারনেটের অপেক্ষা করছি…"
     * into a reconnect, and what moves a healthy session onto a new network.
     */
    private fun installNetworkWatch() {
        if (networkWatchInstalled) return
        networkWatchInstalled = true
        runCatching {
            netMonitor.onAvailable { snapshot -> onNetworkAvailable(snapshot) }
            netMonitor.onLost { snapshot -> onNetworkLost(snapshot) }
        }
    }

    private fun stopAudio() {
        // Every terminal path (user stop, close frame, failure) goes through here, so outstanding
        // searches are cancelled and their late results can never reach a dead socket.
        searchRunner?.shutdown()
        searchRunner = null
        setupTimeout?.cancel(false)
        setupTimeout = null
        setupTimer.shutdownNow()
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        playbackActive.set(false)
        playbackQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null
        try { player?.pause(); player?.flush(); player?.release() } catch (_: Exception) {}
        player = null
        setupDone.set(false)
    }
}
