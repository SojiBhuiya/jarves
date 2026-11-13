package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Anything that can answer one web search for the voice session.
 *
 * Two implementations exist on purpose:
 *  * [LiveSearchSession] — the 0.2.7 default: a second, short-lived Live session on the 2.5
 *    native-audio model with the built-in `googleSearch` tool;
 *  * [GroundedSearchClient] — the REST implementation from 0.2.5/0.2.6, kept in the code base but
 *    no longer on the default path.
 */
interface SearchBackend {
    suspend fun search(query: String): GroundedAnswer

    /** One secret-free line for the on-device diagnostic block. */
    fun describe(): String
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
    data object TurnComplete : LiveEvent
    data class Error(val message: String) : LiveEvent
    data class Closed(val code: Int, val reason: String) : LiveEvent
}

/**
 * Real transport. The key is sent the same way the working 3.8 engine sends it (`?key=`, the
 * documented Live auth); the URL is never printed in a log, a report or the diagnostics block.
 */
class OkHttpLiveSearchTransport(
    private val apiKey: String,
    private val endpoint: String = LiveSearchSession.WEBSOCKET_ENDPOINT
) : LiveSearchTransport {

    override fun open(model: String, listener: LiveSearchListener): LiveSearchConnection {
        val request = Request.Builder().url("$endpoint?key=$apiKey").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onServerMessage(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) =
                listener.onClosed(code, reason)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                listener.onClosed(code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onFailure(response?.code ?: 0, t.javaClass.simpleName + ": " + (t.message ?: ""))
        })
        return object : LiveSearchConnection {
            override fun send(json: String): Boolean = socket.send(json)

            override fun close(code: Int, reason: String) {
                runCatching { socket.close(code, reason) }
            }
        }
    }

    private companion object {
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }
}

/**
 * The 0.2.7 search engine: one short Live session, one question, one text answer.
 *
 * On this key the free-tier Google Search grounding pool exists for "Gemini 2.5" models but not for
 * "Gemini 3 and newer" ones, while the voice quality the user wants is `gemini-3.8-live`. So the
 * conversation stays on 3.8 and the search is delegated to a second session on the 2.5 model.
 *
 * Message shape, verified in the docs (https://ai.google.dev/gemini-api/docs/live-guide):
 *  * setup — `model`, `generationConfig.responseModalities:["AUDIO"]` (native-audio models "only
 *    support AUDIO response modality"), `systemInstruction`, `tools:[{"googleSearch":{}}]`, and
 *    `outputAudioTranscription` — the same page says to use the output audio transcription when the
 *    response is needed as text;
 *  * input — one text turn (`clientContent.turns=[{role:"user",parts:[{text:…}]}]`,
 *    `turnComplete=true`); the model card lists **text** as a valid input
 *    (https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash-native-audio-preview-12-2025);
 *  * output — `serverContent.outputTranscription.text` chunks until `serverContent.turnComplete`,
 *    plus `groundingMetadata` when Google attached sources;
 *  * audio chunks are discarded: only the transcription is used.
 */
class LiveSearchSession(
    private val transport: LiveSearchTransport,
    val model: String = LiveModels.SEARCH_NATIVE_AUDIO,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : SearchBackend {

    override fun describe(): String =
        "Live search session: model=$model, tools=[googleSearch], timeout=${timeoutMillis}ms, key=AIza****"

    override suspend fun search(query: String): GroundedAnswer = withContext(dispatcher) {
        val trimmed = query.trim().take(LiveToolProtocol.MAX_QUERY_CHARS)
        if (trimmed.isEmpty()) {
            return@withContext answer(
                trimmed, GroundedSearchFailure.EMPTY, 0, "empty query", 0, 0, 0, 0
            )
        }
        val started = clock()
        val queue = ArrayBlockingQueue<LiveEvent>(64)
        val connection = try {
            transport.open(model, QueueListener(queue))
        } catch (e: Exception) {
            return@withContext answer(
                trimmed, GroundedSearchFailure.INTERNAL, 0, e.javaClass.simpleName, 0, 0, clock() - started, 0
            )
        }

        fun closeQuietly() = runCatching { connection.close(1000, "search done") }

        try {
            if (!connection.send(setupFrame())) {
                closeQuietly()
                return@withContext answer(
                    trimmed, GroundedSearchFailure.INTERNAL, 0, "setup could not be sent",
                    0, 0, clock() - started, 0
                )
            }

            var connectMillis = 0L
            var firstTranscriptMillis = 0L
            var closeCode = 0
            var closeReason = ""
            var serverError = ""
            var sendFailure = ""
            var settled = false
            val text = StringBuilder()
            val sources = LinkedHashMap<String, GroundedSource>()
            val deadline = started + timeoutMillis

            while (!settled) {
                val remaining = deadline - clock()
                if (remaining <= 0) break
                val event = queue.poll(remaining.coerceAtMost(250L), TimeUnit.MILLISECONDS) ?: continue
                when (event) {
                    is LiveEvent.Open -> {
                        if (connectMillis == 0L) connectMillis = clock() - started
                        if (!connection.send(textTurn(trimmed))) {
                            sendFailure = "query could not be sent"
                            settled = true
                        }
                    }
                    is LiveEvent.SetupComplete -> if (connectMillis == 0L) connectMillis = clock() - started
                    is LiveEvent.Transcript -> {
                        if (firstTranscriptMillis == 0L) firstTranscriptMillis = clock() - started
                        text.append(event.text)
                    }
                    is LiveEvent.Sources -> event.sources.forEach { sources[it.url] = it }
                    is LiveEvent.TurnComplete -> settled = true
                    is LiveEvent.Error -> {
                        serverError = event.message
                        settled = true
                    }
                    is LiveEvent.Closed -> {
                        closeCode = event.code
                        closeReason = event.reason
                        settled = true
                    }
                }
            }
            closeQuietly()

            val elapsed = clock() - started
            val spoken = text.toString().trim()
            when {
                spoken.isNotEmpty() -> GroundedAnswer(
                    query = trimmed,
                    text = spoken,
                    sources = sources.values.toList(),
                    elapsedMillis = elapsed,
                    detail = if (closeCode != 0) "closed $closeCode ${closeReason.take(80)}" else "",
                    model = model,
                    closeCode = closeCode,
                    connectMillis = connectMillis,
                    firstTranscriptMillis = firstTranscriptMillis,
                    textLength = spoken.length
                )
                closeCode != 0 -> answer(
                    trimmed, failureFor(closeCode), closeCode, closeReason, connectMillis,
                    firstTranscriptMillis, elapsed, 0
                )
                serverError.isNotBlank() -> answer(
                    trimmed, GroundedSearchFailure.HTTP, 0, serverError, connectMillis,
                    firstTranscriptMillis, elapsed, 0, serverMessage = serverError
                )
                sendFailure.isNotBlank() -> answer(
                    trimmed, GroundedSearchFailure.INTERNAL, 0, sendFailure, connectMillis,
                    firstTranscriptMillis, elapsed, 0
                )
                elapsed >= timeoutMillis -> answer(
                    trimmed, GroundedSearchFailure.TIMEOUT, 0, "no answer within ${timeoutMillis}ms",
                    connectMillis, firstTranscriptMillis, elapsed, 0
                )
                else -> answer(
                    trimmed, GroundedSearchFailure.EMPTY, 0, "no transcription text", connectMillis,
                    firstTranscriptMillis, elapsed, 0
                )
            }
        } catch (e: Exception) {
            closeQuietly()
            answer(trimmed, GroundedSearchFailure.INTERNAL, 0, e.javaClass.simpleName, 0, 0, clock() - started, 0)
        }
    }

    private fun answer(
        query: String,
        failure: GroundedSearchFailure,
        closeCode: Int,
        detail: String,
        connectMillis: Long,
        firstTranscriptMillis: Long,
        elapsedMillis: Long,
        textLength: Int,
        serverMessage: String = ""
    ): GroundedAnswer = GroundedAnswer(
        query = query,
        text = "",
        failure = failure,
        elapsedMillis = elapsedMillis,
        detail = detail.take(200),
        serverMessage = when {
            serverMessage.isNotBlank() -> serverMessage
            closeCode != 0 -> "close $closeCode"
            else -> ""
        },
        model = model,
        closeCode = closeCode,
        connectMillis = connectMillis,
        firstTranscriptMillis = firstTranscriptMillis,
        textLength = textLength
    )

    /** Setup frame: built-in Google Search only, no function declarations. */
    fun setupFrame(): String =
        LiveSessionConfig.searchSessionSetupJson(model = model, voiceName = LiveModels.VOICE_NAME)

    /** One text turn with `turnComplete = true`. */
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
            return entry.answer.copy(detail = (entry.answer.detail + " (cached)").trim())
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
