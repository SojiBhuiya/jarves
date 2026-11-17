package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.LiveFrameDecoder
import com.sojibhuiya.jarves.apiproviders.MiniJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Anything that can answer one web search for the voice session.
 *
 * Two implementations exist on purpose:
 *  * [LiveSearchSession] — the default since 0.2.7: a second, short-lived Live session on the 2.5
 *    native-audio model with the built-in `googleSearch` tool;
 *  * [GroundedSearchClient] — the REST implementation from 0.2.5/0.2.6, kept in the code base but
 *    no longer on the default path.
 */
interface SearchBackend {
    suspend fun search(query: String): GroundedAnswer

    /** One secret-free line for the on-device diagnostic block. */
    fun describe(): String
}

/** The steps of one Auto search, in order. A failure names the step it happened at. */
enum class SearchStep(val label: String) {
    TRANSPORT_OPEN("socket open"),
    SETUP_SENT("setup sent"),
    SETUP_COMPLETE("setupComplete"),
    TEXT_TURN_SENT("question sent"),
    FIRST_TRANSCRIPT("first transcription"),
    GENERATION_COMPLETE("generation complete"),
    CLOSED("closed")
}

// ---------------------------------------------------------------------------------------------
// Transport seam: the WebSocket is injected, so the whole protocol is unit-testable.
// ---------------------------------------------------------------------------------------------

/** Called by the transport on the OkHttp thread. Implementations must be cheap. */
interface LiveSearchListener {
    fun onOpen()
    fun onServerMessage(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(code: Int, message: String)
}

/** One open socket. */
interface LiveSearchConnection {
    fun send(json: String): Boolean

    fun close(code: Int, reason: String)
}

/** Opens a socket to the Live endpoint for a given model. */
fun interface LiveSearchTransport {
    fun open(model: String, listener: LiveSearchListener): LiveSearchConnection
}

/** Everything that can happen on the search socket, in one closed set (testable). */
sealed interface LiveEvent {
    data object Open : LiveEvent
    data object SetupComplete : LiveEvent
    data class Transcript(val text: String) : LiveEvent
    data class Sources(val sources: List<GroundedSource>) : LiveEvent
    data object GenerationComplete : LiveEvent
    data object TurnComplete : LiveEvent
    data class Error(val message: String) : LiveEvent
    data class Closed(val code: Int, val reason: String) : LiveEvent
}

/**
 * Real transport. The key is sent the same way the working 3.8 engine sends it (`?key=`, the
 * documented Live auth); the URL is never printed in a log, a report or the diagnostics block.
 *
 * **0.2.8 fix:** the Live API delivers its messages as **binary** WebSocket frames, so the byte
 * callback must be handled as well as the text one. 0.2.7 only implemented `onMessage(text)`, which
 * is why the Auto search session never saw `setupComplete`, never saw a transcription and always
 * ended in a 20 s timeout. The main 3.8 engine always had both callbacks
 * (`GeminiLiveVoice.onMessage(WebSocket, ByteString)`), which is exactly why "Search only" worked
 * while "Auto" did not.
 */
class OkHttpLiveSearchTransport(
    private val apiKey: String,
    private val endpoint: String = LiveSearchSession.WEBSOCKET_ENDPOINT
) : LiveSearchTransport {

    override fun open(model: String, listener: LiveSearchListener): LiveSearchConnection {
        val request = Request.Builder().url("$endpoint?key=$apiKey").build()
        val socket = client.newWebSocket(request, FrameRelay(listener))
        return object : LiveSearchConnection {
            override fun send(json: String): Boolean = socket.send(json)

            override fun close(code: Int, reason: String) {
                runCatching { socket.close(code, reason) }
            }
        }
    }

    /**
     * OkHttp callbacks -> [LiveSearchListener]. A named class on purpose: the unit test
     * `AutoSearchFlowTest.theRealTransportHandlesBinaryFramesAsWellAsText` asserts that **both**
     * `onMessage` overloads are implemented, because dropping the `ByteString` one is exactly the
     * 0.2.7 bug that stopped Auto mode from ever seeing `setupComplete`.
     */
    internal class FrameRelay(private val listener: LiveSearchListener) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
            listener.onServerMessage(LiveFrameDecoder.decode(bytes.toByteArray()))

        override fun onMessage(webSocket: WebSocket, text: String) = listener.onServerMessage(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) =
            listener.onClosed(code, reason)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            listener.onClosed(code, reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            listener.onFailure(response?.code ?: 0, t.javaClass.simpleName + ": " + (t.message ?: ""))
    }

    private companion object {
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }
}

/**
 * The Auto-mode search engine: one short Live session, one question, one text answer.
 *
 * Message order, straight from the API reference
 * (https://ai.google.dev/api/live#BidiGenerateContentSetup):
 * "Message to be sent in the first (and only in the first) `BidiGenerateContentClientMessage` …
 * **Clients should wait for a `BidiGenerateContentSetupComplete` message before sending any
 * additional messages.**"
 *
 * 0.2.7 sent the question on socket-open, i.e. *before* `setupComplete`; 0.2.8 waits for it and
 * fails visibly (`SETUP_TIMEOUT`) if it never arrives.
 *
 * Output shape (https://ai.google.dev/gemini-api/docs/live-guide → Limitations → Response
 * modalities): native-audio models "only support AUDIO response modality. If you need the model
 * response as text, use the output audio transcription feature" — so the setup asks for
 * `AUDIO` + `outputAudioTranscription` and the audio chunks are discarded. The turn is finished at
 * `generationComplete` (or `turnComplete`), per
 * https://ai.google.dev/api/live#BidiGenerateContentServerContent.
 */
class LiveSearchSession(
    private val transport: LiveSearchTransport,
    val model: String = LiveModels.SEARCH_NATIVE_AUDIO,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val setupTimeoutMillis: Long = DEFAULT_SETUP_TIMEOUT_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : SearchBackend {

    override fun describe(): String =
        "Auto search: model=$model, tools=[googleSearch], waitSetup=true, " +
            "timeout=${timeoutMillis}ms, key=AIza****"

    override suspend fun search(query: String): GroundedAnswer = withContext(dispatcher) {
        val trimmed = query.trim().take(LiveToolProtocol.MAX_QUERY_CHARS)
        if (trimmed.isEmpty()) {
            return@withContext failure(
                trimmed, GroundedSearchFailure.EMPTY, SearchStep.TEXT_TURN_SENT,
                0, "empty query", 0, 0, 0, 0, emptyList()
            )
        }
        val started = clock()
        fun since() = clock() - started
        val steps = mutableListOf<String>()
        val queue = ArrayBlockingQueue<LiveEvent>(128)

        val connection = try {
            transport.open(model, QueueListener(queue))
        } catch (e: Exception) {
            return@withContext failure(
                trimmed, GroundedSearchFailure.INTERNAL, SearchStep.TRANSPORT_OPEN, 0,
                e.javaClass.simpleName, 0, 0, since(), 0, steps
            )
        }
        fun closeQuietly() = runCatching { connection.close(1000, "search done") }

        try {
            if (!connection.send(setupFrame())) {
                closeQuietly()
                return@withContext failure(
                    trimmed, GroundedSearchFailure.INTERNAL, SearchStep.SETUP_SENT, 0,
                    "setup could not be sent", 0, 0, since(), 0, steps
                )
            }
            steps += "setup sent"

            var openedAt = 0L
            var setupCompleteAt = 0L
            var turnSentAt = 0L
            var firstTranscriptAt = 0L
            var finishedAt = 0L
            var closeCode = 0
            var closeReason = ""
            var serverError = ""
            var stepFailure: SearchStep? = null
            var failureDetail = ""
            val text = StringBuilder()
            val sources = LinkedHashMap<String, GroundedSource>()
            val deadline = started + timeoutMillis

            while (finishedAt == 0L && stepFailure == null) {
                // Checked *before* the wait, so an idle socket that never answers the setup is
                // reported at the setup step instead of slowly turning into a 20 s timeout.
                if (setupCompleteAt == 0L && since() >= setupTimeoutMillis) {
                    stepFailure = SearchStep.SETUP_COMPLETE
                    failureDetail = "no setupComplete within ${setupTimeoutMillis}ms"
                    break
                }
                val remaining = deadline - clock()
                if (remaining <= 0) break
                val event = queue.poll(remaining.coerceAtMost(200L), TimeUnit.MILLISECONDS) ?: continue
                when (event) {
                    is LiveEvent.Open -> {
                        if (openedAt == 0L) {
                            openedAt = since()
                            steps += "socket open ${openedAt}ms"
                        }
                        // Deliberately nothing else: the question waits for setupComplete.
                    }
                    is LiveEvent.SetupComplete -> {
                        if (setupCompleteAt == 0L) {
                            setupCompleteAt = since()
                            steps += "setupComplete ${setupCompleteAt}ms"
                        }
                        if (turnSentAt == 0L) {
                            // Only now may a second client message be sent (API reference).
                            if (!connection.send(textTurn(trimmed))) {
                                stepFailure = SearchStep.TEXT_TURN_SENT
                                failureDetail = "question could not be sent"
                            } else {
                                turnSentAt = since()
                                steps += "question sent ${turnSentAt}ms"
                            }
                        }
                    }
                    is LiveEvent.Transcript -> {
                        if (firstTranscriptAt == 0L) {
                            firstTranscriptAt = since()
                            steps += "first transcription ${firstTranscriptAt}ms"
                        }
                        text.append(event.text)
                    }
                    is LiveEvent.Sources -> event.sources.forEach { sources[it.url] = it }
                    is LiveEvent.GenerationComplete, is LiveEvent.TurnComplete -> {
                        if (finishedAt == 0L) {
                            finishedAt = since()
                            steps += "${if (event is LiveEvent.GenerationComplete) "generationComplete" else "turnComplete"} ${finishedAt}ms"
                        }
                    }
                    is LiveEvent.Error -> {
                        serverError = event.message
                        stepFailure = SearchStep.SETUP_COMPLETE
                    }
                    is LiveEvent.Closed -> {
                        closeCode = event.code
                        closeReason = event.reason
                        if (finishedAt == 0L) stepFailure = SearchStep.CLOSED
                    }
                }
            }
            closeQuietly()
            val elapsed = since()
            val spoken = text.toString().trim()
            // 0.2.14 (task A): the two numbers the phone measurement needed — how long the answer
            // is, and how long the model spent between the first transcript and generationComplete
            // (0.2.13 measured 8.6 s there for a ~30-word answer plus a source line).
            val words = SearchAnswerRules.wordCount(spoken)
            val transcriptToComplete =
                if (firstTranscriptAt > 0L && finishedAt > firstTranscriptAt) finishedAt - firstTranscriptAt else 0L
            steps += "answer $words words"
            if (transcriptToComplete > 0L) {
                steps += "transcript->complete ${formatSeconds(transcriptToComplete)}s"
            }
            steps += "total ${elapsed}ms" + if (closeCode != 0) ", close $closeCode" else ""

            when {
                spoken.isNotEmpty() -> GroundedAnswer(
                    query = trimmed,
                    text = spoken,
                    sources = sources.values.toList(),
                    elapsedMillis = elapsed,
                    detail = if (closeCode != 0) "closed $closeCode ${closeReason.take(80)}" else "",
                    model = model,
                    closeCode = closeCode,
                    connectMillis = setupCompleteAt,
                    firstTranscriptMillis = firstTranscriptAt,
                    textLength = spoken.length,
                    answerWords = words,
                    transcriptToCompleteMillis = transcriptToComplete,
                    trace = traceLine(steps) + "\n" + SearchAnswerRules.metricsLine(
                        totalMillis = elapsed,
                        connectMillis = setupCompleteAt,
                        firstTranscriptMillis = firstTranscriptAt,
                        transcriptToCompleteMillis = transcriptToComplete,
                        words = words,
                        chars = spoken.length
                    )
                )
                closeCode != 0 -> failure(
                    trimmed, failureFor(closeCode), SearchStep.CLOSED, closeCode,
                    closeReason.ifBlank { "closed by server" }, setupCompleteAt, firstTranscriptAt,
                    elapsed, 0, steps
                )
                serverError.isNotBlank() -> failure(
                    trimmed, GroundedSearchFailure.HTTP, SearchStep.SETUP_COMPLETE, 0, serverError,
                    setupCompleteAt, firstTranscriptAt, elapsed, 0, steps, googleMessage = serverError
                )
                stepFailure != null -> failure(
                    trimmed,
                    if (stepFailure == SearchStep.SETUP_COMPLETE) GroundedSearchFailure.TIMEOUT
                    else GroundedSearchFailure.INTERNAL,
                    stepFailure, 0,
                    failureDetail.ifBlank { "stopped at ${stepFailure.label}" },
                    setupCompleteAt, firstTranscriptAt, elapsed, 0, steps
                )
                elapsed >= timeoutMillis -> failure(
                    trimmed, GroundedSearchFailure.TIMEOUT, SearchStep.FIRST_TRANSCRIPT, 0,
                    "no answer within ${timeoutMillis}ms", setupCompleteAt, firstTranscriptAt,
                    elapsed, 0, steps
                )
                else -> failure(
                    trimmed, GroundedSearchFailure.EMPTY, SearchStep.FIRST_TRANSCRIPT, 0,
                    "no transcription text", setupCompleteAt, firstTranscriptAt, elapsed, 0, steps
                )
            }
        } catch (e: Exception) {
            closeQuietly()
            failure(
                trimmed, GroundedSearchFailure.INTERNAL, SearchStep.TRANSPORT_OPEN, 0,
                e.javaClass.simpleName, 0, 0, since(), 0, steps
            )
        }
    }

    private fun traceLine(steps: List<String>): String =
        if (steps.isEmpty()) "" else "Auto steps: " + steps.joinToString(" -> ")

    private fun failure(
        query: String,
        failure: GroundedSearchFailure,
        step: SearchStep,
        closeCode: Int,
        detail: String,
        connectMillis: Long,
        firstTranscriptMillis: Long,
        elapsedMillis: Long,
        textLength: Int,
        steps: List<String>,
        googleMessage: String = ""
    ): GroundedAnswer = GroundedAnswer(
        query = query,
        text = "",
        failure = failure,
        elapsedMillis = elapsedMillis,
        detail = detail.take(200),
        serverMessage = if (closeCode != 0) "close $closeCode" else googleMessage,
        model = model,
        closeCode = closeCode,
        connectMillis = connectMillis,
        firstTranscriptMillis = firstTranscriptMillis,
        textLength = textLength,
        step = step.label,
        trace = traceLine(steps + "FAILED at ${step.label} after ${elapsedMillis}ms")
    )

    /** Setup frame: built-in Google Search only, no function declarations. */
    fun setupFrame(): String =
        LiveSessionConfig.searchSessionSetupJson(model = model, voiceName = LiveModels.VOICE_NAME)

    /** One text turn with `turnComplete = true`, sent only after `setupComplete`. */
    fun textTurn(query: String): String = LiveSessionConfig.clientContentJson(query, turnComplete = true)

    private class QueueListener(private val queue: ArrayBlockingQueue<LiveEvent>) : LiveSearchListener {
        override fun onOpen() {
            queue.offer(LiveEvent.Open)
        }

        override fun onServerMessage(text: String) {
            LiveSearchProtocol.events(text).forEach { queue.offer(it) }
        }

        override fun onClosed(code: Int, reason: String) {
            queue.offer(LiveEvent.Closed(code, reason))
        }

        override fun onFailure(code: Int, message: String) {
            queue.offer(LiveEvent.Closed(code, message))
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L

        /** A socket that never answers the setup is reported long before the 20 s answer deadline. */
        const val DEFAULT_SETUP_TIMEOUT_MILLIS = 8_000L

        const val WEBSOCKET_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** Close code -> the failure the voice model is told about. */
        fun failureFor(closeCode: Int): GroundedSearchFailure = when (closeCode) {
            1008, 4403 -> GroundedSearchFailure.QUOTA
            1007, 4400 -> GroundedSearchFailure.UNSUPPORTED
            1011 -> GroundedSearchFailure.HTTP
            1000, 1001, 1005 -> GroundedSearchFailure.EMPTY
            1006 -> GroundedSearchFailure.NETWORK
            else -> GroundedSearchFailure.HTTP
        }
    }
}

/** Pure parser for one Live server frame of the search session. Unit-tested. */
object LiveSearchProtocol {

    fun events(text: String): List<LiveEvent> {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val out = mutableListOf<LiveEvent>()

        if (root.containsKey("setupComplete")) out += LiveEvent.SetupComplete

        (root["error"] as? Map<*, *>)?.let { error ->
            val message = (error["message"] as? String).orEmpty()
            val code = (error["code"] as? Number)?.toInt() ?: 0
            out += LiveEvent.Error("${if (code > 0) "($code) " else ""}$message".take(200))
        }

        (root["serverContent"] as? Map<*, *>)?.let { serverContent ->
            (serverContent["outputTranscription"] as? Map<*, *>)?.let { transcription ->
                (transcription["text"] as? String)?.takeIf { it.isNotEmpty() }?.let {
                    out += LiveEvent.Transcript(it)
                }
            }
            val report = GroundingParser.fromServerMessage(text)
            if (report.sources.isNotEmpty()) out += LiveEvent.Sources(report.sources)
            if (serverContent["generationComplete"] == true) out += LiveEvent.GenerationComplete
            if (serverContent["turnComplete"] == true) out += LiveEvent.TurnComplete
        }
        return out
    }
}

/**
 * Short-lived cache so a repeated question does not open a second session. Failures are never
 * cached. Small on purpose: this is not a search history.
 */
class CachedSearchBackend(
    private val inner: SearchBackend,
    private val ttlMillis: Long = 120_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : SearchBackend {

    private data class Entry(val answer: GroundedAnswer, val at: Long)

    private val entries = LinkedHashMap<String, Entry>()

    override fun describe(): String = inner.describe() + " | cache=${ttlMillis / 1000}s"

    override suspend fun search(query: String): GroundedAnswer {
        val key = normalize(query)
        val now = clock()
        entries.entries.removeAll { now - it.value.at >= ttlMillis }
        entries[key]?.let { entry ->
            return entry.answer.copy(
                detail = (entry.answer.detail + " (cached)").trim(),
                trace = "Auto steps: cache hit (no session opened)"
            )
        }
        val answer = inner.search(query)
        if (answer.isSuccess) {
            if (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
            entries[key] = Entry(answer, now)
        }
        return answer
    }

    private fun normalize(query: String): String = query.lowercase().replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val MAX_ENTRIES = 8
    }
}
