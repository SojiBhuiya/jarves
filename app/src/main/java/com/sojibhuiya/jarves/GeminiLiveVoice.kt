package com.sojibhuiya.jarves

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.sojibhuiya.jarves.intelligence.GroundedSearchClient
import com.sojibhuiya.jarves.intelligence.LiveScheduler
import com.sojibhuiya.jarves.intelligence.LiveSocketFlight
import com.sojibhuiya.jarves.intelligence.LiveTask
import com.sojibhuiya.jarves.intelligence.MicCallbackGaps
import com.sojibhuiya.jarves.intelligence.NetworkDown
import com.sojibhuiya.jarves.system.AndroidLiveScheduler
import com.sojibhuiya.jarves.system.LiveMicrophone
import com.sojibhuiya.jarves.system.LiveSocketTransport
import com.sojibhuiya.jarves.system.LiveWakeLock
import com.sojibhuiya.jarves.system.OkHttpVoiceTransport
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.sojibhuiya.jarves.intelligence.FreshnessClassifier
import com.sojibhuiya.jarves.intelligence.GroundedSource
import com.sojibhuiya.jarves.intelligence.GroundingParser
import com.sojibhuiya.jarves.intelligence.CloseAlreadyConnected
import com.sojibhuiya.jarves.intelligence.InterruptionLog
import com.sojibhuiya.jarves.intelligence.LiveFailureRouter
import com.sojibhuiya.jarves.intelligence.InjectionLedger
import com.sojibhuiya.jarves.intelligence.FreshSessionFallback
import com.sojibhuiya.jarves.intelligence.StallTolerance
import com.sojibhuiya.jarves.intelligence.SessionTimeline
import com.sojibhuiya.jarves.intelligence.CompactDiagnostics
import com.sojibhuiya.jarves.intelligence.ServerMessageLog
import com.sojibhuiya.jarves.intelligence.SearchAnswerRules
import com.sojibhuiya.jarves.intelligence.NoticePolicy
import com.sojibhuiya.jarves.intelligence.LivePingSetting
import com.sojibhuiya.jarves.intelligence.SearchTurnGuard
import com.sojibhuiya.jarves.intelligence.TurnRules
import com.sojibhuiya.jarves.intelligence.TurnTranscript
import com.sojibhuiya.jarves.intelligence.formatSeconds
import com.sojibhuiya.jarves.intelligence.LiveSearchTool
import com.sojibhuiya.jarves.intelligence.LiveModels
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
import com.sojibhuiya.jarves.intelligence.Rejection
import com.sojibhuiya.jarves.intelligence.TimeContext

/**
 * Gemini Live audio-to-audio transport.
 * Mic: 16 kHz, mono, PCM16. Speaker: 24 kHz, mono, PCM16.
 */
internal class GeminiLiveVoice(
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
    /** Search result for chat (not a transport diagnostics dump). */
    private val onSearchReport: (String) -> Unit = {},
    /**
     * 0.2.6: which voice/search mode this session uses. The model ID and the setup tools both come
     * from the mode, so nothing at a call site can pick a model on its own.
     */
    private val mode: VoiceSearchMode = VoiceSearchMode.VOICE_ONLY,
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
    private val pingIntervalSeconds: Int = 0,
    private val wakeLock: LiveWakeLock = LiveWakeLock.NONE,
    private val clock: LiveScheduler = AndroidLiveScheduler(),
    private val transport: LiveSocketTransport = OkHttpVoiceTransport(pingIntervalSeconds),
    private val microphoneFactory: () -> LiveMicrophone = { androidMicrophone() }
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
        /**
         * 0.2.16 (task B): this is no longer a hard limit that tears the socket down — it is the
         * point where [StallTolerance] starts dropping the OLDEST audio. OkHttp's own outgoing
         * buffer is 16 MiB ("MAX_QUEUE_SIZE = 16L * 1024 * 1024" in RealWebSocket), so 64,000 B
         * (2 s of 16 kHz mono 16-bit PCM) is our own latency budget, not a transport limit.
         */
        const val MAX_SEND_QUEUE_BYTES = StallTolerance.SOFT_LIMIT_BYTES

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
        const val FIX_TAG = BuildConfig.VERSION_NAME
        internal const val WAKE_CAP_MILLIS = 60 * 60 * 1_000L
        private const val MIC_REPORT_MILLIS = 60_000L
        private const val SETUP_FAILED = "ভয়েস সেশনের সেটআপ ব্যর্থ হয়েছে।"
        private const val RECONNECT_STATUS = "ভয়েস সংযোগ ফেরানোর চেষ্টা করছি…"

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

    // ---- 0.2.15 (rules 1-5, 7): one answer = one turn ----
    /** Rules 1-3: while a search is pending, nothing may be injected and no apology is allowed. */
    private val searchTurnGuard = SearchTurnGuard()
    /** Rule 5: consecutive outputTranscription chunks of one turn, flushed only at a turn end. */
    private val assistantTurn = TurnTranscript()
    /** Rule 7: every clientContent / toolResponse the app sent, and whether the model reacted. */
    private val injectionLedger = InjectionLedger()
    /** Rule 5: how many chat messages the old per-chunk behaviour produced (diagnostics). */
    @Volatile private var assistantTurnFlushes: Int = 0
    /** Rule 1: `toolCallCount` at the start of the current turn, so a search is attributed to it. */
    @Volatile private var toolCallsAtTurnStart: Int = 0

    // ---- 0.2.16: stall tolerance, fresh-session fallback, timeline, compact diagnostics ----
    /** Task B: a backed-up outgoing queue is tolerated (audio is trimmed), never failed. */
    private val stallTolerance = StallTolerance()
    /** Six seconds after the first 1008 with a handle, then a fresh session (90 s total budget). */
    private val freshFallback = FreshSessionFallback()
    /** Task 1/C: every network change, stall, cancellation and reconnect attempt with t+seconds. */
    private val sessionTimeline = SessionTimeline(capacity = 256)
    /** Task 6: counts only (modelTurn / transcription), never every frame. */
    private val compactCounter = CompactDiagnostics.Counter()
    private val searchSummaries = java.util.concurrent.CopyOnWriteArrayList<CompactDiagnostics.SearchSummary>()
    /** Set when the current connection deliberately dropped its resumption handle. */
    @Volatile private var freshSessionInUse = false
    /** Task 6: the diagnostics block must stay under ~6,000 characters. */
    @Volatile private var lastCompactDiagnostics = ""
    /** Task C: the identity/transport of the default network we already counted. */
    @Volatile private var lastNetworkIdentity: Long = -1L
    @Volatile private var lastNetworkType: String = NetworkSnapshot.TYPE_UNKNOWN

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
    @Volatile private var lastGoAwayMillis: Long = 0L
    @Volatile private var lastReconnectNote: String = "none"
    /** 0.2.10: network-aware reconnect budget/waiting state (pure, unit-tested). */
    private val reconnectPlan = ReconnectCoordinator()
    private var activeNormalAttempt = 0
    private var pendingNormalAttempt = 0
    private var networkDeadlineTask: LiveTask? = null
    private var handleDeadlineTask: LiveTask? = null
    private var freshDeadlineTask: LiveTask? = null
    private val sessionLock = Any()
    private val micGaps = MicCallbackGaps()
    private val micReports = ArrayDeque<String>()
    private var lastFailureDetail = "none"
    private var hasStarted = false
    private var wakeCapReached = false
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
    private fun now(): Long = clock.nowMillis()

    private fun after(delay: Long, action: () -> Unit): LiveTask = clock.after(delay) {
        synchronized(sessionLock) { if (running.get()) action() }
    }

    private val flight = LiveSocketFlight(
        now = ::now,
        after = ::after,
        isTransportIdle = { transport.idle },
        log = { sessionTimeline.mark(now(), it) },
        onTimeout = { opening ->
            if (opening) {
                tryAutomaticReconnect(ReconnectCause.SOCKET_FAILURE, 0, "WebSocket open timeout", NetworkDown.CONNECT_TIMEOUT)
            } else {
                fail("১০ সেকেন্ডেও ভয়েস সেশনের সেটআপ শেষ হয়নি।", "setupComplete timeout")
            }
        },
        onOpenError = { e -> fail(SETUP_FAILED, "socket creation: ${e.javaClass.simpleName}") }
    )

    private val closeFlow = LiveCloseFlow(
        echoClose = { code, _ -> acknowledgeClose(code) },
        retryWithoutSearch = { source -> retryWithoutSearch(source) },
        reportFailure = { code, reason ->
            lastCloseCode = code
            lastCloseReason = reason.ifBlank { "none" }
            if (!setupCompleteReceived) {
                fail(SETUP_FAILED, "setup refused: $code $reason")
            } else {
                tryAutomaticReconnect(ReconnectCause.SERVER_CLOSE, code, reason)
            }
        },
        reportStopped = {
            if (setupCompleteReceived) {
                tryAutomaticReconnect(ReconnectCause.SERVER_CLOSE, lastCloseCode, lastCloseReason)
            } else {
                fail(SETUP_FAILED, "closed before setupComplete ($lastCloseCode)")
            }
        }
    )

    private val socket: WebSocket? get() = flight.socket
    @Volatile private var recorder: LiveMicrophone? = null
    @Volatile private var player: AudioTrack? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private val playbackActive = AtomicBoolean(false)
    @Volatile private var playbackThread: Thread? = null

    fun start(): Unit = synchronized(sessionLock) {
        if (hasStarted) return
        hasStarted = true
        if (apiKey.isBlank()) {
            onError("জেমিনি এপিআই কী যোগ করুন।")
            onState(LiveState.FAILED)
            return
        }
        running.set(true)
        failureRouter.onSessionStarted()
        // 0.2.13 (task 4): the diagnostics timeline is relative to this instant, and the
        // interruption counter starts from zero for every session.
        val now = now()
        interruptions.onSessionStarted(now)
        reconnectPlan.reset()
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
        goAwaySeen = false
        lastReconnectCause = ReconnectCause.NONE
        lastReconnectNote = "none"
        lastTimeSyncMillis = 0L
        lastTimeSyncDateKey = ""
        sessionTimeline.start(now)
        freshFallback.reset()
        startSearchRunner()
        lastNetworkIdentity = netMonitor.networkIdentity()
        lastNetworkType = netMonitor.snapshot().type
        netMonitor.onAvailable(networkAvailableListener)
        netMonitor.onLost(networkLostListener)
        transport.onIdle { synchronized(sessionLock) { flight.transportIdle() } }
        onStatus("ভয়েস সংযোগ হচ্ছে…")
        onState(LiveState.CONNECTING)
        startSessionPower()
        startTimeSyncLoop()
        flight.request(0L) { connect(attempt = 0, reconnecting = false) }
    }

    /** Only [flight] opens a socket. The resume handle is selected once per attempt, not onOpen. */
    private fun connect(attempt: Int, reconnecting: Boolean): WebSocket? {
        if (!running.get() || checkNetworkDeadline()) return null
        if (freshFallback.handleDeadline != null && (switchToFreshIfDue() || !freshFallback.takeHandleRetry())) return null
        if (reconnecting) stallTolerance.onSocketReplaced(now(), sessionTimeline.sessionStartMillis)
        activeNormalAttempt = attempt
        pendingNormalAttempt = 0
        closeFlow.reset()
        closeCallback = "none"
        closeEchoed = false
        closeInitiator = CloseInitiator.NONE
        setupDone.set(false)
        setupCompleteReceived = false
        setupMessageSent = false
        websocketOpened = false
        lastServerMessageAtMillis = 0L
        val fresh = freshSessionInUse
        val useHandle = if (fresh) null else resumption.handleForReconnect()
        val hadHandle = useHandle != null
        if (reconnecting) reconnectPlan.onAttemptStarted(now(), attempt)
        sessionTimeline.mark(now(), "opening ${if (fresh) "fresh" else "voice"} socket (handle=$hadHandle; ${netMonitor.snapshot().capabilitiesLine()})")
        val request = Request.Builder()
            .url("$WEBSOCKET_ENDPOINT?key=$apiKey")
            .build()

        return transport.open(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response): Unit = synchronized(sessionLock) {
                if (socket !== webSocket || checkNetworkDeadline() || switchToFreshIfDue()) return
                if (!flight.opened(webSocket, response.code)) return
                onEndpointReachable()
                websocketOpened = true
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
                    sessionResumptionHandle = useHandle
                )
                if (!webSocket.send(setup)) {
                    fail(SETUP_FAILED, "setup send returned false")
                    return
                }
                setupMessageSent = true
                flight.setupSent(hadHandle, fresh)
                lastSendOkAtMillis = now()
                setupDeclaredTool = when (searchToolMode) {
                    LiveSearchTool.NONE -> "none"
                    LiveSearchTool.GOOGLE_SEARCH_BUILT_IN -> "googleSearch(built-in)"
                    LiveSearchTool.FUNCTION_NON_BLOCKING -> "search_web/NON_BLOCKING"
                    LiveSearchTool.FUNCTION_BLOCKING -> "search_web/blocking"
                }
                lastSearchOutcome = "no toolCall yet"
                toolCallCount = 0
                lastToolQuery = "none"
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (socket !== webSocket) return
                onMessage(webSocket, LiveFrameDecoder.decode(bytes.toByteArray()))
            }

            override fun onMessage(webSocket: WebSocket, text: String): Unit = synchronized(sessionLock) {
                if (socket !== webSocket || switchToFreshIfDue()) return
                // 0.2.13 (task 3): "how long has this socket been silent?" — the cheapest half-open
                // detector available without an experiment.
                lastServerMessageAtMillis = now()
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
                        fail(if (setupCompleteReceived) "ভয়েস সেশন বন্ধ হয়েছে।" else SETUP_FAILED,
                            "Gemini error ($code): ${safeDetail(message)}")
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

                    if (root.has("setupComplete") && flight.setupComplete(webSocket)) {
                        setupDone.set(true)
                        setupCompleteReceived = true
                        activeNormalAttempt = 0
                        failureRouter.onReconnectSucceeded()
                        val connectedAt = now()
                        handleDeadlineTask?.cancel()
                        handleDeadlineTask = null
                        freshDeadlineTask?.cancel()
                        freshDeadlineTask = null
                        if (fresh) {
                            freshFallback.onFreshSessionConnected(connectedAt, sessionTimeline.sessionStartMillis)
                            sessionTimeline.mark(connectedAt, "fresh session connected (no handle)")
                        }
                        freshFallback.reset()
                        freshSessionInUse = false
                        if (reconnecting) reconnectPlan.onAttemptSucceeded(connectedAt)
                        reconnectAttempts = 0
                        interruptions.end(connectedAt)
                        searchTimeline.start(System.currentTimeMillis())
                        startAudioPlayback()
                        startAudioCapture()
                        if (!running.get()) return
                        onStatus(when {
                            fresh -> FreshSessionFallback.STATUS_CONTEXT_LOST
                            reconnecting && !hadHandle -> ReconnectCoordinator.STATUS_CONTEXT_MAY_BE_LOST
                            reconnecting -> ReconnectCoordinator.STATUS_RECONNECTED
                            searchToolMode == LiveSearchTool.GOOGLE_SEARCH_BUILT_IN -> CONNECTED_WITH_BUILTIN_SEARCH_STATUS
                            groundingAvailable -> CONNECTED_WITH_SEARCH_STATUS
                            else -> CONNECTED_WITHOUT_SEARCH_STATUS
                        })
                        onState(if (groundingAvailable) LiveState.CONNECTED_GROUNDED else LiveState.CONNECTED_WITHOUT_SEARCH)
                    }
                    if (socket !== webSocket || !setupDone.get()) return

                    // ---- Design 4: the model asks the APP to search ------------------
                    if (LiveToolProtocol.mightContainToolCall(text)) {
                        val cancelled = LiveToolProtocol.parseCancellations(text)
                        if (cancelled.isNotEmpty()) {
                            searchRunner?.cancel(cancelled)
                            liveStatus("সার্চ বাতিল হয়েছে — আপনি কথা বলেছেন")
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
                            // 0.2.15 (rule 1) + 0.2.16 (task 5): the counters are PER SEARCH, so
                            // five searches with one acknowledgement each report zero violations.
                            searchTurnGuard.startSearch(calls.first().callId)
                            searchTurnGuard.onToolCall()
                            sessionTimeline.mark(
                                now(),
                                "toolCall #${searchTurnGuard.toolCalls} (counter reset per call id)"
                            )
                            lastToolQuery = calls.first().query.take(60)
                            lastToolCallSeen = "toolCall received (id=${calls.first().callId.take(24)}, " +
                                "q=${calls.first().query.take(40)}, model=${LiveModels.modelFor(activeMode)})"
                            lastAutoTrace = "Auto steps: $lastToolCallSeen"
                            liveStatus("৩.৮ মডেল সার্চ চাইছে — দ্বিতীয় সেশন খোলা হচ্ছে…")
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
                                ?.let { liveStatus(it) }
                        }
                    }

                    // Task 6: count, do not print. modelTurn / outputTranscription counts plus the
                    // first-transcription time are all the compact block shows.
                    runCatching {
                        compactCounter.onMessage(
                            now(),
                            ServerMessageLog.classify(text),
                            root.optJSONObject("serverContent")
                                ?.optJSONObject("outputTranscription")
                                ?.optString("text")
                                .orEmpty()
                        )
                    }

                    val serverContent = root.optJSONObject("serverContent")
                    if (serverContent != null) {
                        if (serverContent.optBoolean("interrupted", false)) {
                            // Rule 5: an interruption is a real turn end as well.
                            flushAssistantTurn()
                            playbackQueue.clear()
                            try { player?.pause(); player?.flush(); player?.play() } catch (_: Exception) {}
                            modelGenerating = false
                            if (pendingResults.size > 0) {
                                answersInProgressWhenResultArrived++
                                searchTimeline.mark("model turn interrupted (barge-in)")
                            }
                            liveStatus("আপনার কথা শুনছি…")
                        }

                        serverContent.optJSONObject("inputTranscription")?.optString("text")
                            ?.takeIf { it.isNotBlank()}?.let { chunk ->
                                // Rule 5: the user started a new turn — whatever the model had
                                // said belongs to the previous one and is flushed as its own message.
                                flushAssistantTurn()
                                turnUserSpeech.append(' ').append(chunk)
                                onTranscript(chunk, false)
                                // The user is now talking about something else while a search is
                                // still running: the result that arrives later must name its own
                                // question instead of being dropped into this new topic.
                                if (!newerQuestionSinceToolCall && liveSearchPending()) {
                                    newerQuestionSinceToolCall = true
                                    searchTimeline.mark("user speech while search pending")
                                }
                            }

                        serverContent.optJSONObject("outputTranscription")?.optString("text")
                            ?.takeIf { it.isNotBlank() }?.let { chunk ->
                                // 0.2.15 (rule 5): accumulated, NOT emitted. 0.2.14 handed every
                                // chunk to the UI, so a pause longer than the UI's 1.2 s flush timer
                                // turned one answer into several chat bubbles ("দেখে" / "নিচ্ছি।…").
                                assistantTurn.append(chunk)
                                searchTurnGuard.onModelSpoke()
                                injectionLedger.onModelSpoke(System.currentTimeMillis())
                            }

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
                                searchTurnGuard.onModelSpoke()
                                injectionLedger.onModelSpoke(System.currentTimeMillis())
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
                            // Rule 5: the turn really ended — this is the one place the answer is
                            // handed to the chat.
                            flushAssistantTurn()
                            liveStatus("শুনছি… আপনি কথা বলতে পারেন")
                            searchTimeline.mark("turn boundary (turnComplete)")
                            modelGenerating = false
                            finishTurn(webSocket)
                            // Task C: the model is idle — hand it the next pending result, one at a
                            // time and in order. This is the whole point of WHEN_IDLE.
                            deliverNextPendingResult("turnComplete")
                        }
                    }

                } catch (e: Exception) {
                    // 0.2.12: one unparseable frame must never end a working conversation. The
                    // frame is skipped and counted (see Settings → Advanced → Diagnostics); only a
                    // session that has already ended ignores it silently.
                    val action = failureRouter.onFrameParseError()
                    if (action == LiveFailureRouter.Action.SKIP_FRAME) {
                        lastSkippedFrame = e.javaClass.simpleName
                        if (failureRouter.skippedFrames == 1) {
                            liveStatus("একটি অস্বাভাবিক সার্ভার ফ্রেম বাদ দেওয়া হয়েছে (সেশন চলছে)")
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?): Unit = synchronized(sessionLock) {
                try {
                    if (socket !== webSocket || checkNetworkDeadline() || switchToFreshIfDue()) return
                    closeCallback = "onFailure"
                    val http = response?.code
                    val down = if (http == null) NetworkDown.from(t, opening = flight.opening) else null
                    val causes = generateSequence(t) { it.cause }.take(5).joinToString(" <- ") { it.javaClass.simpleName }
                    // No exception messages/requests here: they may contain the credential URL.
                    val reason = when {
                        http != null -> "HTTP $http"
                        down == NetworkDown.CONNECT_TIMEOUT -> "timed out opening ($causes)"
                        down != null -> "$down ($causes)"
                        else -> causes
                    }
                    lastFailureDetail = reason
                    if (http != null) {
                        onEndpointReachable()
                        closeFlow.handleFailure(http, reason, groundingRequested && groundingAvailable && setupMessageSent,
                            setupCompleteReceived, groundingFallbackUsed)
                    } else {
                        tryAutomaticReconnect(ReconnectCause.SOCKET_FAILURE, 0, reason, down)
                    }
                } finally {
                    flight.terminal(webSocket)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String): Unit = synchronized(sessionLock) {
                if (socket === webSocket && !switchToFreshIfDue()) {
                    closeCallback = "onClosing"
                    onCloseFrame(code, reason, hadHandle, useHandle)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String): Unit = synchronized(sessionLock) {
                try {
                    if (socket === webSocket && !switchToFreshIfDue()) {
                        closeCallback = "onClosed"
                        onCloseFrame(code, reason, hadHandle, useHandle)
                    }
                } finally {
                    flight.terminal(webSocket)
                }
            }
        })
    }

    private fun onCloseFrame(code: Int, reason: String, hadHandle: Boolean, usedHandle: String?) {
        val clean = safeDetail(reason, usedHandle)
        lastCloseCode = code
        lastCloseReason = clean.ifBlank { "none" }
        if (CloseAlreadyConnected.matches(code, clean)) {
            acknowledgeClose(code)
            tryServerBusyRetry(code, clean, hadHandle)
        } else if (!setupCompleteReceived && LiveRejection.isNonBlockingRejection(clean)) {
            acknowledgeClose(code)
            retryWithBlockingFunction("close frame $code")
        } else {
            closeFlow.handleCloseFrame(
                code, clean, groundingRequested && groundingAvailable && setupMessageSent,
                setupCompleteReceived, groundingFallbackUsed
            )
        }
    }

    private fun acknowledgeClose(code: Int) {
        if (closeEchoed) return
        closeEchoed = true
        closeInitiator = CloseInitiator.SERVER_CLOSE_FRAME
        runCatching { socket?.close(if (code == 1000) code else 1000, "close acknowledged") }
    }

    private fun safeDetail(text: String, usedHandle: String? = null): String {
        var clean = GroundedSearchClient.scrubSecret(text)
        listOfNotNull(apiKey.takeIf { it.isNotBlank() }, usedHandle, resumption.handleForReconnect())
            .filter { it.isNotBlank() }.forEach { clean = clean.replace(it, "[redacted]") }
        return clean.replace(Regex("\\s+"), " ").take(200)
    }

    // Auto mode installs a runner for the whole conversation; its existence is not a tool call.
    private fun liveSearchPending(): Boolean = (searchRunner?.active ?: 0) > 0 || pendingResults.size > 0

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
            // 0.2.15 (rules 1-4). This block is what produced the phone report: while the search was
            // still running, every turn end injected "you could not verify" with turnComplete = true,
            // which *starts a generation* (API reference) — so Jarves apologised, interrupted its own
            // answer, repeated the apology and fragmented the transcript. Now:
            //  * nothing is injected while a search is pending or was attempted this turn (rule 3);
            //  * whatever is injected is SILENT context (turnComplete = false, rule 4), so it informs
            //    the next turn instead of starting one;
            //  * a real failure is announced once, from the toolResponse (rule 2).
            val searchPending = liveSearchPending()
            val searchedThisTurn = toolCallCount > toolCallsAtTurnStart
            val allowed = NoticePolicy.shouldInject(
                needsLiveSources = needsLiveSources,
                groundingForTurn = groundingForTurn,
                noticeSentForTurn = noticeSentForTurn,
                searchPending = searchPending,
                searchedThisTurn = searchedThisTurn,
                canInjectContext = searchTurnGuard.canInjectContext()
            )
            if (allowed) {
                noticeSentForTurn = true
                injectSilentContext(
                    webSocket,
                    kind = "no-sources notice",
                    text = GroundingPolicy.fallbackNotice(groundingAvailable)
                )
            } else {
                searchTimeline.mark(
                    NoticePolicy.describe(needsLiveSources, searchPending, searchedThisTurn, false) +
                        " (rule 3: no \"cannot verify\" text while a search is pending)"
                )
            }
        }
        toolCallsAtTurnStart = toolCallCount
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
        // 0.2.15 (rule 4): the time sync used to be sent with turnComplete = true, i.e. it *asked the
        // model to speak* every TIME_SYNC interval — the "speaks without being asked" report. It is
        // silent context now: the model sees the fresh date on its next turn.
        injectSilentContext(target, kind = "time sync", text = TimeContext.refreshMessage(snapshot))
    }

    /**
     * 0.2.15 (rules 4 and 7): the single exit for app-injected context.
     *
     * It always sends `turnComplete = false` (silent — see
     * [com.sojibhuiya.jarves.intelligence.LiveSessionConfig.silentContextJson]) and records the
     * injection in [injectionLedger], so the diagnostics can answer "did this make the model speak?"
     * with a measurement instead of a theory.
     */
    private fun injectSilentContext(target: WebSocket, kind: String, text: String) {
        if (!setupDone.get()) return
        val frame = LiveSessionConfig.silentContextJson(text)
        val sent = runCatching { target.send(frame) }.getOrDefault(false)
        injectionLedger.onClientContent(
            now = System.currentTimeMillis(),
            kind = kind,
            turnComplete = false,
            bytes = frame.length
        )
        if (sent) searchTurnGuard.onContextInjected()
        searchTimeline.mark("clientContent $kind (turnComplete=false, ${frame.length}B, sent=$sent)")
        if (!sent) lastSendFailureEvidence = "silent context '$kind' could not be sent"
    }

    /**
     * Rule 5: hands the accumulated text of ONE model turn to the chat, exactly once.
     *
     * Called on `turnComplete`, on `interrupted`, when a new user turn starts and when the session
     * ends — never per transcription chunk.
     */
    private fun flushAssistantTurn() {
        val text = assistantTurn.flush() ?: return
        assistantTurnFlushes += 1
        // 0.2.15 (rule 1): a finished turn while the search is pending is the acknowledgement, so the
        // guard can count them and the diagnostics can prove there was only one.
        searchTurnGuard.onAssistantTurnEnded()
        searchTimeline.mark("model turn flushed as one message (${text.length} chars)")
        runCatching { onTranscript(text, true) }
    }

    private fun startTimeSyncLoop() {
        after(TIME_SYNC_INTERVAL_MILLIS) {
            if (setupDone.get()) maybeSyncTimeContext()
            startTimeSyncLoop()
        }
    }

    private fun diagnosticSummary(): String {
        val chunks = audioChunksSent.get()
        val bytes = audioBytesSent.get()
        val micState = if (recorder?.recording == true) "recording" else "stopped"
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
            "Turn rules: ${searchTurnGuard.describe()}, model messages=$assistantTurnFlushes | " +
            diagnosticsFor(activeMode, searchToolMode, lastCloseCode, lastCloseReason) + " | " +
            "Last query: $lastToolQuery | Last search: $lastSearchOutcome | " +
            "Last search model: $lastSearchModel | ${searchBackend?.describe() ?: "Search models: none"} | " +
            "Mic sensitivity: ${micSensitivity.label} | Audio effects: ${audioEffectsSummary()} | " +
            "build: $FIX_TAG (${BuildConfig.VERSION_CODE})" + "\n" + lastAutoTrace +
            "\nSearch timeline: " + searchTimeline.text() +
            "\nPending results: " + (pendingResults.questions().ifEmpty { listOf("none") }.joinToString(" | ")) +
            " | Announcement: " + announceTracker.describe(System.currentTimeMillis()) +
            " | Reminder: $lastResultReminder" +
            "\nLast transport/setup failure: $lastFailureDetail"
    }

    /**
     * 0.2.16 (tasks 1, 6, 7): the session timeline and the per-search summary, bounded by
     * [CompactDiagnostics.MAX_CHARS].
     *
     * The network rule is the one the 0.2.15 log made necessary: the block used to say
     * `network=mobile (CHANGED)` while the phone stood still, because a capability update was counted
     * as a change. Now only a different default network object, a transport switch or `onLost` counts
     * — see [SessionTimeline] for the Android doc quotes.
     */
    private fun compactDiagnostics(): String {
        val now = now()
        val head = listOf(
            "— Session timeline (t+ from session start) —",
            "Network: ${netMonitor.snapshot().type} | ${netMonitor.snapshot().capabilitiesLine()}",
            sessionTimeline.summary(keep = 0),
            "Stalls: ${stallTolerance.describe(now)}",
            "Fresh-session fallback: ${freshFallback.describe(now)}",
            "Interruptions: ${interruptions.line(now)}"
        )
        lastCompactDiagnostics = CompactDiagnostics.block(
            head = head,
            searches = searchSummaries.toList(),
            tails = listOf(
                "Recent session events" to sessionTimeline.lines(),
                "Mic callback gaps" to micReports.toList(),
                "Stall timeline" to stallTolerance.timeline(),
                "Fresh-session timeline" to freshFallback.timeline(),
                "Search messages (counted)" to listOf(
                    "modelTurn=${compactCounter.modelTurn}, " +
                        "outputTranscription=${compactCounter.transcriptions}, " +
                        "last message before completion=" +
                        (if (compactCounter.lastBeforeCompletion() > 0L)
                            formatSeconds(compactCounter.lastBeforeCompletion()) + "ms" else "n/a")
                )
            )
        )
        return lastCompactDiagnostics
    }

    /** Task 6: the copied text must stay readable; this is what the tests and the UI check. */
    fun compactDiagnosticsLength(): Int =
        if (lastCompactDiagnostics.isEmpty()) 0 else lastCompactDiagnostics.length

    /** Task 1: the timeline the phone test (f) asks about — stalls, recoveries and reconnects. */
    fun stallSummary(): String = synchronized(sessionLock) { stallTolerance.describe(now()) }

    // -------------------------------------------------------------------------------------
    // Design 4: the model calls `search_web` -> app searches over REST -> toolResponse
    // -------------------------------------------------------------------------------------

    /** The last Auto search's step trace, for Settings → Advanced → Diagnostics. */
    fun lastAutoSearchTrace(): String = lastAutoTrace

    /** Hidden Settings → Advanced → Diagnostics only. */
    fun endDiagnostics(): String = synchronized(sessionLock) {
        diagnosticSummary() + "\n" + endEnvironment() + "\n" + compactDiagnostics() +
            "\nMic minute samples:\n" + micReports.joinToString("\n") +
            "\nDetailed session events:\n" + sessionTimeline.lines().joinToString("\n")
    }

    private fun endEnvironment(): String = SessionDiagnostics.line(
        initiator = closeInitiator,
        environment = runCatching { envProvider.snapshot() }.getOrDefault(SessionEnvironment.UNKNOWN),
        sessionDurationMillis = if (hasStarted) now() - sessionTimeline.sessionStartMillis else 0L,
        closeCode = lastCloseCode,
        closeReason = lastCloseReason,
        reconnectsUsed = reconnectAttempts,
        resumption = resumption,
        goAwaySeen = goAwaySeen,
        reconnectCause = lastReconnectCause
    ) + "\nReconnect: " + lastReconnectNote +
        "\nInjected context (rule 7, t+ from session start): " +
        injectionLedger.text(sessionStartedAt) +
        "\nWake lock held: ${wakeHeld()} (capReached=$wakeCapReached)" +
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
                    // 0.2.16 (task 4): the chat bubble keeps the FULL answer; only the spoken copy
                    // inside the toolResponse is bounded (SearchResultText.spokenText).
                    answer = if (answer.isSuccess) answer.text.trim() else "",
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
                    liveStatus("সার্চ শেষ — ফল জানানো হচ্ছে।")
                } else {
                    liveStatus("সার্চ করা যায়নি।")
                }
                // 0.2.16 (task 6): ONE compact line per search — counts and times, never the frames.
                runCatching {
                    searchSummaries += compactCounter.summary(
                        index = searchSummaries.size + 1,
                        endRule = answer.endRule.ifBlank { if (answer.isSuccess) "ok" else (answer.failure?.name ?: "failed") },
                        totalMillis = answer.elapsedMillis,
                        words = SearchAnswerRules.wordCount(answer.text),
                        sessionStart = sessionTimeline.sessionStartMillis
                    )
                    compactCounter.reset()
                }.onFailure {
                    searchTimeline.mark("compact summary failed: ${it.javaClass.simpleName}")
                }
                // 0.2.16 (task 4): log both word counts — the full answer (chat) and the copy the
                // 3.8 voice model receives — next to the transcript->generationComplete time the
                // search session already measured.
                lastAutoTrace = (answer.trace.ifBlank { "Auto steps: none" }) +
                    "\nSpoken copy: " + SearchResultText.spokenWords(item) + " words" +
                    " (full answer " + com.sojibhuiya.jarves.intelligence.SearchAnswerRules.wordCount(item.answer) +
                    " words)"
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
            onEvent = { line -> liveStatus(line.substringBefore(':')) }
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
        // 0.2.15 (rules 1-2): a failure is announced once, and only because the toolResponse itself
        // says so; a success closes the "be quiet" window instead.
        searchTurnGuard.onToolResponseSent(failed = !item.success)
        injectionLedger.onToolResponse(
            now = now,
            scheduling = scheduling,
            bytes = LiveToolProtocol.toolResponseJson(
                item.callId, SearchResultText.toolResponse(item), scheduling
            ).length
        )
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
                liveStatus("সার্চের ফল ভয়েস সেশনে পাঠানো যায়নি।")
            }
            return
        }
        currentResultCallId = item.callId
        awaitingFirstAudioForResult = true
        announceTracker.onResultSent(item.callId, now)
        // Task C5 safety net #2: one check, 8 s later, on the existing session timer. No new thread.
        synchronized(sessionLock) {
            if (running.get()) after(IdleAnnouncementTracker.IDLE_AUDIO_GRACE_MILLIS) {
                checkAnnouncementReminder(item.callId)
            }
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
        liveStatus("ওয়েব সার্চ শুরু হচ্ছে…")
        calls.forEach { call ->
            if (searchBudget.tryStart()) {
                runner.handle(call)
            } else {
                // One search per question: tell the model to answer with what it already has
                // instead of spending a second session on the same question.
                liveStatus("এই প্রশ্নে ইতিমধ্যে একবার সার্চ হয়েছে")
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
        sessionTimeline.mark(now(), "retry with blocking search: $serverSaid")
        onStatus(NON_BLOCKING_UNSUPPORTED_STATUS)
        onState(LiveState.CONNECTING)
        searchRunner?.shutdown()
        searchRunner = null
        flight.retire("search tool rejected")
        flight.request(0L) { connect(attempt = 0, reconnecting = false) }
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
        sessionTimeline.mark(now(), "retry without search: $source")
        onStatus(SEARCH_UNAVAILABLE_STATUS)
        onState(LiveState.CONNECTING)
        closeInitiator = CloseInitiator.SEARCH_FALLBACK_RETRY
        flight.retire("search tool refused")
        flight.request(0L) { connect(attempt = 0, reconnecting = false) }
    }

    /**
     * 0.2.13 (task 3): one line of measured evidence for a failed (or alarming) audio send.
     *
     * Nothing here is guessed: every value comes from OkHttp or from the system reader. It answers
     * "why did `send()` fail on a socket the server still saw as connected?" — a half-open
     * connection shows up as a large silence, a stale last-send time and often a network change.
     */
    private fun evidence(socketSendOk: Boolean, backlogBytes: Long, reason: String): String {
        val now = now()
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

    private fun tryServerBusyRetry(code: Int, reason: String, hadHandle: Boolean) {
        val now = now()
        interruptions.begin(now, ReconnectCause.SERVER_BUSY.name)
        lastReconnectCause = ReconnectCause.SERVER_BUSY
        sessionTimeline.mark(now, "1008 already connected (handle=$hadHandle)")
        flight.retire("close $code: $reason")
        setupDone.set(false)
        val next = freshFallback.onAlreadyConnected(now, hadHandle, sessionTimeline.sessionStartMillis)
        if (handleDeadlineTask == null) {
            freshFallback.handleDeadline?.let { deadline ->
                handleDeadlineTask = after((deadline - now).coerceAtLeast(0L)) { switchToFreshIfDue() }
            }
        }
        when (next.step) {
            FreshSessionFallback.Step.RETRY_FRESH -> {
                if (!next.hadHandle) {
                    freshSessionInUse = true
                    armFreshDeadline()
                }
                scheduleReconnect(ReconnectCause.SERVER_BUSY, next.delayMillis)
            }
            FreshSessionFallback.Step.WAIT_FOR_HANDLE_DEADLINE -> {
                if (!switchToFreshIfDue()) onState(LiveState.CONNECTING)
            }
            FreshSessionFallback.Step.GIVE_UP -> {
                if (next.delayMillis == 0L) {
                    giveUpAfterReconnects(FreshSessionFallback.MESSAGE_GAVE_UP, "fresh 1008 budget spent")
                } else {
                    armFreshDeadline()
                }
            }
        }
    }

    /** Cancels even a hung handle handshake when the six-second window is over. */
    private fun switchToFreshIfDue(): Boolean {
        val deadline = freshFallback.handleDeadline ?: return false
        val now = now()
        if (now < deadline || !freshFallback.beginFresh(now, sessionTimeline.sessionStartMillis)) return false
        handleDeadlineTask?.cancel()
        handleDeadlineTask = null
        freshSessionInUse = true
        // Do not resume the old context if a later GoAway arrives before the new handle update.
        resumption.clear()
        flight.retire("handle phase deadline")
        setupDone.set(false)
        armFreshDeadline()
        if (reconnectPlan.waitingForNetwork) {
            if (!flight.hasPending) scheduleReconnect(ReconnectCause.NETWORK_BACK, 4_000L, quiet = true)
        } else {
            scheduleReconnect(ReconnectCause.SERVER_BUSY, 0L)
        }
        return true
    }

    private fun armFreshDeadline() {
        if (freshDeadlineTask != null) return
        val deadline = freshFallback.freshDeadline ?: return
        freshDeadlineTask = after((deadline - now()).coerceAtLeast(0L)) {
            freshDeadlineTask = null
            // A genuine DNS/no-route outage gets its own network window. The next 1008 will
            // still see the original server-busy deadline; it cannot start a new 90 s budget.
            if (!reconnectPlan.waitingForNetwork) {
                giveUpAfterReconnects(FreshSessionFallback.MESSAGE_GAVE_UP, "fresh 1008 budget spent")
            }
        }
    }

    private fun tryAutomaticReconnect(
        cause: ReconnectCause,
        code: Int,
        reason: String,
        down: NetworkDown? = null
    ): Boolean {
        if (!running.get() || closeInitiator.intentional || terminalErrorReported.get()) return false
        val now = now()
        if (cause == ReconnectCause.GO_AWAY) {
            if (flight.ready && !flight.hasPending) {
                scheduleReconnect(cause, ReconnectPolicy.goAwayDelayMillis(lastGoAwayMillis))
            }
            return true
        }
        interruptions.begin(now, cause.name)
        lastReconnectCause = cause
        val failure = if (code == 0) reason else "close $code: $reason"
        lastFailureDetail = failure
        flight.retire("$cause: $failure")
        setupDone.set(false)
        val failedAttempt = if (activeNormalAttempt > 0) activeNormalAttempt else pendingNormalAttempt
        val net = netMonitor.snapshot()
        if (freshFallback.handleDeadline != null && down == null &&
            !net.indicatesNoUsableNetwork && !reconnectPlan.waitingForNetwork) {
            // No third handle attempt; keep the cutoff even if the second one fails without 1008.
            switchToFreshIfDue()
            return true
        }
        val action = reconnectPlan.onUnexpectedFailure(now, net, "$cause: $failure", down, failedAttempt)
        reconnectAttempts = reconnectPlan.attemptsUsed
        when (action) {
            is ReconnectAction.WaitForNetwork -> {
                if (action.first) {
                    onStatus(ReconnectCoordinator.STATUS_WAITING_FOR_NETWORK)
                    networkDeadlineTask = after((action.deadlineMillis - now).coerceAtLeast(0L)) {
                        checkNetworkDeadline()
                    }
                }
                scheduleReconnect(cause, action.delayMillis, quiet = true)
            }
            is ReconnectAction.Attempt -> scheduleReconnect(cause, action.delayMillis, action.attempt)
            is ReconnectAction.GiveUp -> giveUpAfterReconnects(action.message, action.reason.name)
            else -> Unit
        }
        return true
    }

    private fun checkNetworkDeadline(): Boolean {
        val action = reconnectPlan.checkNetworkDeadline(now()) as? ReconnectAction.GiveUp ?: return false
        giveUpAfterReconnects(action.message, "network deadline (${netMonitor.snapshot().capabilitiesLine()})")
        return true
    }

    private fun onEndpointReachable() {
        reconnectPlan.onNetworkReachable(now())
        networkDeadlineTask?.cancel()
        networkDeadlineTask = null
    }

    private fun scheduleReconnect(cause: ReconnectCause, delay: Long, attempt: Int = 0, quiet: Boolean = false) {
        if (!running.get()) return
        failureRouter.onReconnectScheduled()
        lastReconnectCause = cause
        pendingNormalAttempt = attempt
        lastReconnectNote = "$cause retry in ${delay}ms (${netMonitor.snapshot().capabilitiesLine()})"
        if (!quiet && !reconnectPlan.waitingForNetwork) onStatus(RECONNECT_STATUS)
        onState(LiveState.CONNECTING)
        flight.request(delay) {
            interruptions.begin(now(), cause.name)
            connect(attempt, reconnecting = true)
        }
    }

    private fun scheduleGoAwayReconnect(timeLeftMillis: Long) {
        lastGoAwayMillis = timeLeftMillis
        tryAutomaticReconnect(ReconnectCause.GO_AWAY, 0, "goAway")
    }

    private val networkAvailableListener: (NetworkSnapshot) -> Unit = { snapshot ->
        synchronized(sessionLock) { onNetworkAvailable(snapshot) }
    }
    private val networkLostListener: (NetworkSnapshot) -> Unit = { snapshot ->
        synchronized(sessionLock) { onNetworkLost(snapshot) }
    }

    private fun onNetworkAvailable(snapshot: NetworkSnapshot) {
        if (!running.get() || closeInitiator.intentional || checkNetworkDeadline()) return
        val now = now()
        val id = netMonitor.networkIdentity()
        val changed = sessionTimeline.onNetworkEvent(
            now, "available", id, lastNetworkIdentity, snapshot.type, lastNetworkType
        )
        lastNetworkIdentity = id
        lastNetworkType = snapshot.type
        sessionTimeline.mark(now, "default network available/updated (${snapshot.type}; ${snapshot.capabilitiesLine()})")
        if (reconnectPlan.waitingForNetwork) {
            // A probe already in progress is the single flight; never preempt it on a redundant
            // onAvailable/VALIDATED callback. Otherwise pull the next retry forward to now.
            if (snapshot.available && socket == null &&
                reconnectPlan.onNetworkAvailable(now, snapshot) is ReconnectAction.Attempt) {
                scheduleReconnect(ReconnectCause.NETWORK_BACK, 0L, quiet = true)
            }
        } else if (changed && flight.ready && !flight.hasPending) {
            val action = reconnectPlan.onNewNetworkWhileHealthy(now, snapshot) as? ReconnectAction.Attempt ?: return
            scheduleReconnect(ReconnectCause.NEW_NETWORK, action.delayMillis)
        }
    }

    private fun onNetworkLost(snapshot: NetworkSnapshot) {
        if (!running.get()) return
        val now = now()
        sessionTimeline.onNetworkEvent(now, "lost", -1L, lastNetworkIdentity, NetworkSnapshot.TYPE_NONE, lastNetworkType)
        sessionTimeline.mark(now, "default network lost (${snapshot.capabilitiesLine()})")
        lastNetworkIdentity = -1L
        lastNetworkType = NetworkSnapshot.TYPE_NONE
        reconnectPlan.onNetworkLost(now)
        if (!snapshot.available && !reconnectPlan.waitingForNetwork) {
            tryAutomaticReconnect(ReconnectCause.SOCKET_FAILURE, 0, "default network lost", NetworkDown.NO_DEFAULT_OR_UNVALIDATED)
        }
    }

    private fun giveUpAfterReconnects(message: String, reason: String) {
        lastReconnectNote = "gave up; normal attempts=$reconnectAttempts ($reason)"
        sessionTimeline.mark(now(), lastReconnectNote)
        fail(message, reason)
    }

    private fun wakeHeld(): Boolean = runCatching { wakeLock.held }.getOrDefault(false)

    private fun startSessionPower() {
        runCatching { wakeLock.acquire(WAKE_CAP_MILLIS) }
        sessionTimeline.mark(now(), "session start: wakeLockHeld=${wakeHeld()}")
        after(WAKE_CAP_MILLIS) {
            wakeCapReached = true
            releaseWakeLock("60-minute cap")
        }
        scheduleMicReport()
    }

    private fun scheduleMicReport() {
        after(MIC_REPORT_MILLIS) {
            reportMicGaps("minute")
            scheduleMicReport()
        }
    }

    private fun reportMicGaps(window: String) {
        val gap = micGaps.take()
        val env = runCatching { envProvider.snapshot() }.getOrDefault(SessionEnvironment.UNKNOWN)
        val max = gap.maxGapMillis?.let { "${it}ms" } ?: "n/a"
        micReports += "${formatRelativeMillis(now(), sessionTimeline.sessionStartMillis)} mic $window: " +
            "callbacks=${gap.callbacks}, maxGap=$max, wakeLockHeld=${wakeHeld()} | ${env.powerLine()}"
        if (micReports.size > 64) micReports.removeFirst()
    }

    private fun releaseWakeLock(reason: String) {
        runCatching { wakeLock.release() }
        sessionTimeline.mark(now(), "$reason: wakeLockHeld=${wakeHeld()}")
    }

    /** Connection diagnostics must never appear as chat messages or status text. */
    private fun fail(message: String, detail: String) = synchronized(sessionLock) {
        if (running.getAndSet(false)) {
            lastFailureDetail = safeDetail(detail)
            sessionTimeline.mark(now(), "terminal failure: $lastFailureDetail")
            terminalErrorReported.set(true)
            if (closeInitiator == CloseInitiator.NONE && !setupCompleteReceived) {
                closeInitiator = CloseInitiator.SETUP_FAILURE
            }
            stopAudio()
            onError(message)
            onState(LiveState.FAILED)
        }
    }

    private fun liveStatus(line: String) = synchronized(sessionLock) {
        if (running.get() && flight.ready && !flight.hasPending && !reconnectPlan.waitingForNetwork) onStatus(line)
    }

    private fun startAudioCapture() {
        // The recorder survives a socket replacement. Captured audio while handshaking is dropped.
        if (recorder != null || !running.get() || !setupDone.get()) return
        try {
            val mic = microphoneFactory()
            recorder = mic
            microphoneInitialized = true
            mic.start()
            check(mic.recording) { "AudioRecord did not start" }
            microphoneStarted = true
            microphoneStateDetail = "recording started"
            Thread({ capture(mic) }, "gemini-live-mic").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            microphoneStateDetail = e.javaClass.simpleName
            fail("মাইক্রোফোন চালু করা যায়নি।", "microphone start: ${e.javaClass.simpleName}")
        }
    }

    private fun capture(mic: LiveMicrophone) {
        val buffer = ByteArray(CHUNK_BYTES)
        val audioBuffer = ArrayDeque<ByteArray>()
        var bufferSocket: WebSocket? = null
        while (running.get() && recorder === mic) {
            val count = try { mic.read(buffer) } catch (_: Exception) { AudioRecord.ERROR }
            if (!running.get() || recorder !== mic) return
            if (count < 0) {
                synchronized(sessionLock) {
                    if (!running.get() || recorder !== mic) return
                    val reason = "AudioRecord.read error $count"
                    // Release even if a reconnect is already pending; setupComplete will replace it.
                    releaseMicrophone("released after $reason")
                    if (flight.ready && !flight.hasPending && failureRouter.onAudioReadError() == LiveFailureRouter.Action.RECONNECT) {
                        tryAutomaticReconnect(ReconnectCause.AUDIO_READ_FAILED, 0, reason)
                    }
                }
                return
            }
            if (count == 0) continue
            micGaps.onRead(now())
            val activeSocket = socket ?: continue
            if (!setupDone.get()) continue
            if (bufferSocket !== activeSocket) {
                audioBuffer.clear()
                bufferSocket = activeSocket
            }
            val backlog = runCatching { activeSocket.queueSize() }.getOrDefault(-1L)
            val skipSend = synchronized(sessionLock) {
                if (activeSocket !== socket || !running.get() || !setupDone.get()) return@synchronized true
                val now = now()
                val silentFor = now - lastServerMessageAtMillis
                when (stallTolerance.onSample(now, backlog, silentFor, sessionTimeline.sessionStartMillis)) {
                    StallTolerance.Verdict.DEAD -> {
                        lastSendFailureEvidence = evidence(true, backlog, "stall ${backlog}B silent ${silentFor}ms")
                        sendFailureCount++
                        tryAutomaticReconnect(ReconnectCause.AUDIO_SEND_FAILED, 0, "write stalled for at least 12s")
                        true
                    }
                    StallTolerance.Verdict.STALLED -> {
                        if (stallTolerance.shouldShowStatus(now)) liveStatus(StallTolerance.STATUS_STALLED)
                        val dropped = stallTolerance.trim(now, audioBuffer)
                        if (dropped > 0L) lastSendFailureEvidence = evidence(true, backlog, "trimmed oldest ${dropped}B")
                        false
                    }
                    StallTolerance.Verdict.HEALTHY -> false
                }
            }
            if (skipSend) continue
            // Preserve 0.2.16's bounded buffering and drain at most two chunks per read.
            audioBuffer.addLast(buffer.copyOf(count))
            var sent = true
            repeat(minOf(2, audioBuffer.size)) {
                if (activeSocket !== socket || !setupDone.get()) return@repeat
                if (sent) sent = activeSocket.send(frameFor(audioBuffer.removeFirst()))
            }
            synchronized(sessionLock) {
                if (activeSocket !== socket || !running.get()) return@synchronized
                if (!sent) {
                    val reason = "microphone audio send failed (chunk ${audioChunksSent.get()})"
                    lastSendFailureEvidence = evidence(false, backlog, reason)
                    sendFailureCount++
                    tryAutomaticReconnect(ReconnectCause.AUDIO_SEND_FAILED, 0, reason)
                } else {
                    lastSendOkAtMillis = now()
                    audioChunksSent.incrementAndGet()
                    audioBytesSent.addAndGet(count.toLong())
                }
            }
        }
    }

    private fun releaseMicrophone(detail: String) {
        val old = recorder
        recorder = null
        microphoneStarted = false
        microphoneStateDetail = detail
        micGaps.onRecorderEnded()
        runCatching { old?.stopAndRelease() }
    }

    /** One `realtimeInput` audio frame, identical to the 0.2.15 wire format. */
    private fun frameFor(chunk: ByteArray): String {
        val payload = Base64.encodeToString(chunk, Base64.NO_WRAP)
        return JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "audio", JSONObject()
                    .put("mimeType", "audio/pcm;rate=$INPUT_RATE")
                    .put("data", payload)
            )
        ).toString()
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
                            fail("অডিও চালানো যায়নি।", "playback: ${e.javaClass.simpleName}")
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
    fun stop(initiator: CloseInitiator = CloseInitiator.USER_STOP): Unit = synchronized(sessionLock) {
        if (!running.getAndSet(false)) return
        closeInitiator = initiator
        if (setupDone.get()) runCatching {
            socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
            socket?.close(1000, "User stopped session")
        }
        stopAudio()
        onStatus("ভয়েস সেশন বন্ধ হয়েছে।")
        onState(LiveState.ENDED)
    }

    private fun stopAudio() {
        failureRouter.onSessionEnded()
        setupDone.set(false)
        flight.stop()
        transport.onIdle(null)
        netMonitor.removeListeners(networkAvailableListener, networkLostListener)
        clock.shutdown()
        releaseWakeLock("session ended")
        stallTolerance.onSessionEnded(now(), sessionTimeline.sessionStartMillis, "session ended")
        reportMicGaps("final partial minute")
        searchRunner?.shutdown()
        searchRunner = null
        if (recorder != null) releaseMicrophone("stopped")
        playbackActive.set(false)
        playbackQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null
        runCatching { player?.pause(); player?.flush(); player?.release() }
        player = null
        resumption.clear()
    }
}

private fun androidMicrophone(): LiveMicrophone {
    val rate = GeminiLiveVoice.INPUT_RATE
    val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minBuffer > 0) { "Microphone audio format unsupported" }
    val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuffer * 2, GeminiLiveVoice.CHUNK_BYTES * 4))
    if (audio.state != AudioRecord.STATE_INITIALIZED) {
        audio.release()
        error("Microphone could not start")
    }
    return object : LiveMicrophone {
        override val recording: Boolean get() = audio.recordingState == AudioRecord.RECORDSTATE_RECORDING
        override fun start() = audio.startRecording()
        override fun read(buffer: ByteArray): Int = audio.read(buffer, 0, buffer.size)
        override fun stopAndRelease() {
            runCatching { audio.stop() }
            audio.release()
        }
    }
}
