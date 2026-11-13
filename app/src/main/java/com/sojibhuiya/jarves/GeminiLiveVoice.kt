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
import com.sojibhuiya.jarves.intelligence.GroundedSearchClient
import com.sojibhuiya.jarves.intelligence.GroundingParser
import com.sojibhuiya.jarves.intelligence.LiveSearchTool
import com.sojibhuiya.jarves.intelligence.LiveModels
import com.sojibhuiya.jarves.intelligence.SessionEndReport
import com.sojibhuiya.jarves.intelligence.VoiceSearchMode
import com.sojibhuiya.jarves.intelligence.LiveToolProtocol
import com.sojibhuiya.jarves.intelligence.SearchFunctionCall
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
    private val searchClient: GroundedSearchClient? = null,
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
    private val onSessionEnd: (SessionEndReport) -> Unit = {}
) {
    companion object {
        /**
         * Endpoint, model and audio settings are the already working Gemini Live configuration.
         * They are constants so the provider adapter and the unit tests reference the exact
         * same values instead of duplicating (and drifting from) them.
         */
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
        const val FIX_TAG = "design4-4"

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
    /** Runs `search_web` calls off the WebSocket thread; one per live session. */
    @Volatile private var searchRunner: SearchToolRunner? = null
    /** Set once a tool-refusal retry starts, so the retry can never repeat. */
    private val retryInFlight = AtomicBoolean(false)
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
            runCatching { socket?.close(1000, "close acknowledged") }
        },
        retryWithoutSearch = { source -> retryWithoutSearch(source) },
        reportFailure = { code, reason ->
            // 0.2.6: keep the exact code/reason for the diagnostic block and tell the user what it
            // means — never a paraphrase.
            lastCloseCode = code
            lastCloseReason = reason.ifBlank { "none" }
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
            if (closeRunning()) {
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
        val hasSearchBackend = !mode.usesBuiltInSearch && searchClient != null
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
        lastTimeSyncMillis = 0L
        lastTimeSyncDateKey = ""
        startSearchRunner()
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
                    searchTool = searchToolMode
                )
                if (!webSocket.send(setup)) {
                    fail("Gemini setup পাঠানো যায়নি: WebSocket send queue বন্ধ")
                    return
                }
                setupMessageSent = true
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

                    if (root.has("setupComplete")) {
                        setupDone.set(true)
                        setupCompleteReceived = true
                        setupTimeout?.cancel(false)
                        setupTimeout = null
                        startAudioPlayback()
                        startAudioCapture()
                        onStatus(
                            when {
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
                            toolCallCount += calls.size
                            lastToolQuery = calls.first().query.take(60)
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
                            onStatus("আপনার কথা শুনছি…")
                        }

                        serverContent.optJSONObject("inputTranscription")?.optString("text")
                            ?.takeIf { it.isNotBlank() }?.let { chunk ->
                                turnUserSpeech.append(' ').append(chunk)
                                onTranscript(chunk, false)
                            }

                        serverContent.optJSONObject("outputTranscription")?.optString("text")
                            ?.takeIf { it.isNotBlank() }?.let { onTranscript(it, true) }

                        val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData")
                                val encoded = inline?.optString("data").orEmpty()
                                if (encoded.isNotBlank()) playbackQueue.offer(Base64.decode(encoded, Base64.DEFAULT))
                            }
                        }
                        if (serverContent.optBoolean("turnComplete", false)) {
                            onStatus("শুনছি… আপনি কথা বলতে পারেন")
                            finishTurn(webSocket)
                        }
                    }

                    if (root.has("goAway")) onStatus("সংযোগ পুনরায় চালু করতে হবে")
                } catch (e: Exception) {
                    fail("Live response পড়তে সমস্যা: ${e.message ?: "unknown"}")
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
        val needsLiveSources = FreshnessClassifier.needsLiveSources(spoken)
        if (needsLiveSources && !groundingForTurn && !noticeSentForTurn) {
            noticeSentForTurn = true
            webSocket.send(
                LiveSessionConfig.clientContentJson(
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
        runCatching { target.send(LiveSessionConfig.clientContentJson(TimeContext.refreshMessage(snapshot))) }
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
            diagnosticsFor(activeMode, searchToolMode, lastCloseCode, lastCloseReason) + " | " +
            "Last query: $lastToolQuery | Last search: $lastSearchOutcome | " +
            "Last search model: $lastSearchModel | ${searchClient?.diagnosticsLine() ?: "Search models: none"} | " +
            "build: $FIX_TAG"
    }

    // -------------------------------------------------------------------------------------
    // Design 4: the model calls `search_web` -> app searches over REST -> toolResponse
    // -------------------------------------------------------------------------------------

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
        val backend = searchClient ?: return
        searchRunner = SearchToolRunner(
            client = backend,
            onResult = { call, answer, scheduling ->
                // The result is always sent, success or failure: a silent call would leave the
                // model waiting and the user without an answer.
                sendToolResponse(
                    LiveToolProtocol.toolResponseJson(call.callId, answer.asToolResult(), scheduling)
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
                    // The technical reason is shown too: without it a user cannot tell a genuine
                    // connection problem from an app fault, which is exactly what went wrong in 0.2.2.
                    runCatching { onStatus("সার্চ করা যায়নি — $lastSearchOutcome") }
                    // 0.2.4: the same facts, in a place the user can actually select and copy.
                    runCatching { onSearchReport(answer.failureReport()) }
                }
            },
            onEvent = { line -> runCatching { onStatus(line) } }
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
        calls.forEach { runner.handle(it) }
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
        runCatching { rejected?.close(1000, "retry without grounding") }
        if (running.get()) connect()
    }

    /** Ends the session and stops audio. Returns false when it was already finished. */
    private fun closeRunning(): Boolean {
        if (!running.getAndSet(false)) return false
        stopAudio()
        return true
    }

    private fun fail(message: String) {
        setupTimeout?.cancel(false)
        setupTimeout = null
        if (running.getAndSet(false)) {
            val detailedMessage = "$message\n${diagnosticSummary()}"
            terminalErrorReported.set(true)
            try { socket?.close(1002, "Session setup failed") } catch (_: Exception) {}
            stopAudio()
            onState(LiveState.FAILED)
            onError(detailedMessage)
        }
    }

    private fun startAudioCapture() {
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
                        if (activeSocket == null || !activeSocket.send(message.toString())) {
                            fail("মাইক্রোফোন অডিও WebSocket-এ পাঠানো যায়নি। পাঠানো chunk: ${audioChunksSent.get()}")
                            break
                        }
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
                        fail("Microphone read ব্যর্থ: $reason। পাঠানো chunk: ${audioChunksSent.get()}")
                        break
                    }
                }
            }, "gemini-live-mic").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            microphoneStateDetail = "error: ${e.message ?: "unknown"}"
            fail("Microphone error: ${e.message ?: "unknown"}")
        }
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
                        onError("Audio playback error: ${e.message ?: "unknown"}")
                    }
                }
            } catch (_: InterruptedException) {
                // Normal shutdown.
            }
        }, "gemini-live-playback").apply { isDaemon = true; start() }
    }

    fun stop() {
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
