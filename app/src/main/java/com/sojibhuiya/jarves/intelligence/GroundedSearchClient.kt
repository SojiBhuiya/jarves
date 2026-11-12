package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Why a grounded search did not produce an answer. Never contains anything secret. */
enum class GroundedSearchFailure {
    /** The 25 s budget expired. */
    TIMEOUT,

    /** Socket/DNS/TLS problem. */
    NETWORK,

    /** The key or project was not accepted. */
    AUTH,

    /** Quota / billing refusal. */
    QUOTA,

    /** The model or the `google_search` tool is not available for this key. */
    UNSUPPORTED,

    /** Any other non-2xx response. */
    HTTP,

    /** 2xx, but no usable text came back. */
    EMPTY,

    /** Too many searches were already running; this one was refused on purpose. */
    BUSY,

    /**
     * The search failed inside the app (a bug or an unreadable response), NOT on the network.
     * Kept separate from [NETWORK] so an app fault never gets reported to the user as "you have
     * no internet". This is the value that would have exposed the gzip/EOFException bug instead
     * of hiding it behind a network message.
     */
    INTERNAL
}

/**
 * The REST answer. [sources] are the real URLs reported in `groundingMetadata`; they are never
 * invented, and an empty list means the answer was not grounded.
 */
data class GroundedAnswer(
    val query: String,
    val text: String,
    val sources: List<GroundedSource> = emptyList(),
    val failure: GroundedSearchFailure? = null,
    val statusCode: Int = 0,
    val elapsedMillis: Long = 0L,
    /** Short, secret-free technical detail (exception class or parse note) for diagnostics only. */
    val detail: String = "",
    /** Google's own error text, verbatim but scrubbed of anything key-shaped. Empty when unknown. */
    val serverMessage: String = "",
    /** Which model produced this answer (or the last one tried). */
    val model: String = ""
) {
    /**
     * The report shown in the chat bubble when a search fails: what failed, the real HTTP status
     * and Google's own words. The credential never appears — only the fixed mask.
     */
    fun failureReport(): String = buildString {
        append("সার্চ ব্যর্থ — type=").append(failure?.name ?: "UNKNOWN")
        append(" | HTTP=").append(if (statusCode > 0) statusCode.toString() else "none")
        append(" | model=").append(model.ifBlank { "unknown" })
        if (serverMessage.isNotBlank()) {
            append('\n').append("Google: ").append(serverMessage)
        }
        if (detail.isNotBlank() && detail != serverMessage) {
            append('\n').append("Detail: ").append(detail)
        }
        if (serverMessage.isBlank() && detail.isBlank()) {
            append('\n').append("Google: (no error text returned)")
        }
        append('\n').append("key=AIza****")
    }
    val isSuccess: Boolean get() = failure == null && text.isNotBlank()

    /**
     * What the live model receives. Deliberately plain: no JSON, no headers, no key. The model is
     * told to announce it in the user's language.
     */
    fun asToolResult(): String = when {
        failure != null -> unableMessage(failure)
        text.isBlank() -> unableMessage(GroundedSearchFailure.EMPTY)
        sources.isEmpty() -> "$text\n\n(No source URLs were returned, so do not cite a link.)"
        else -> buildString {
            append(text)
            append("\n\nSources:")
            sources.take(MAX_RESULT_SOURCES).forEach { append("\n- ").append(it.title).append(": ").append(it.url) }
            append("\n\nTell the user this briefly in their language and name the source you used.")
        }
    }

    private fun unableMessage(failure: GroundedSearchFailure): String = when (failure) {
        GroundedSearchFailure.TIMEOUT ->
            "The web search did not finish in time. Tell the user in their language that you could " +
                "not search right now, and do not invent facts or links."
        GroundedSearchFailure.NETWORK ->
            "The web search failed: no network connection. Tell the user in their language that you " +
                "could not search right now, and do not invent facts or links."
        GroundedSearchFailure.AUTH ->
            "The web search was refused: the API key is not accepted. Tell the user in their " +
                "language that live search is unavailable and the key needs checking."
        GroundedSearchFailure.QUOTA ->
            "The web search was refused because the search quota is used up. Tell the user in their " +
                "language that you could not search right now, and do not invent facts or links."
        GroundedSearchFailure.UNSUPPORTED ->
            "The web search is not available for this model or key. Tell the user in their language " +
                "that live search is unavailable, and do not invent facts or links."
        GroundedSearchFailure.HTTP ->
            "The web search failed with a server error. Tell the user in their language that you " +
                "could not search right now, and do not invent facts or links."
        GroundedSearchFailure.EMPTY ->
            "The web search returned nothing usable. Tell the user in their language that you could " +
                "not find live results, and do not invent facts or links."
        GroundedSearchFailure.INTERNAL ->
            "The web search failed inside the app before it could reach the network. Tell the user " +
                "in their language that the search could not run, and do not invent facts or links."
        GroundedSearchFailure.BUSY ->
            "Two searches were already running, so this one was not started. Tell the user in their " +
                "language, in one short sentence, that you are still looking at the earlier question " +
                "and will answer this one next."
    }

    companion object {
        const val MAX_RESULT_SOURCES = 3
    }
}

/** A minimal, fakeable HTTP POST. Only the fields the client actually sets. */
data class RestRequest(val url: String, val headers: Map<String, String>, val body: String)

data class RestResponse(val code: Int, val body: String)

/** Seam so the whole client can be unit-tested without a socket. */
fun interface RestTransport {
    fun post(request: RestRequest): RestResponse
}

/**
 * Real transport. The credential is only ever placed in the `x-goog-api-key` header — never in the
 * URL, never in a message, never logged. No redirects, no retries (a retry would double a bill).
 */
class OkHttpRestTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val maxBodyChars: Int = 1 shl 20
) : RestTransport {

    override fun post(request: RestRequest): RestResponse {
        val builder = Request.Builder().url(request.url).post(request.body.toRequestBody(JSON))
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        return client.newCall(builder.build()).execute().use { response ->
            // `string()` reads the body through OkHttp's decompressing source until EOF.
            //
            // Do NOT "optimise" this into readByteArray(contentLength()): when the length is
            // unknown (-1, as for chunked or gzip responses — and Google gzips its responses) the
            // fallback demanded 1 MiB from a few-hundred-byte body, and okio's readByteArray(count)
            // requires exactly that many bytes, so it threw EOFException. That is the bug that made
            // every search in 0.2.2 fail and get reported to the user as "no internet connection".
            val body = response.body?.string().orEmpty()
            RestResponse(response.code, if (body.length > maxBodyChars) body.take(maxBodyChars) else body)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Design 4 retrieval: a normal REST `generateContent` call with Google Search grounding, on a
 * non-live model, using the same Gemini API key.
 *
 * Why REST rather than the Live `googleSearch` tool: the Live setup is refused as a whole when a
 * project has no Live grounding entitlement, so instead the *app* performs the search and hands
 * the result to the live session as a function response.
 */
class GroundedSearchClient(
    private val apiKey: String,
    private val transport: RestTransport = OkHttpRestTransport(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * Models tried in order. Both entries are the only models whose Google Search grounding is
     * free on the Gemini free tier (500 RPD, one shared pool) per the official pricing table, so
     * the fallback never silently moves the user onto a paid-only path.
     */
    private val models: List<String> = listOf(MODEL, FALLBACK_MODEL)
) {
    /** The first (preferred) model. */
    val model: String get() = models.first()

    /** Runs on [ioDispatcher] so the WebSocket thread and the audio threads stay free. */
    suspend fun search(query: String): GroundedAnswer = withContext(ioDispatcher) {
        val started = clock()
        val trimmed = query.trim().take(LiveToolProtocol.MAX_QUERY_CHARS)
        if (trimmed.isEmpty()) {
            return@withContext GroundedAnswer("", "", failure = GroundedSearchFailure.EMPTY)
        }
        var last: GroundedAnswer? = null
        models.forEachIndexed { index, candidate ->
            val attempt = attempt(trimmed, candidate, started)
            if (attempt.isSuccess) return@withContext attempt
            last = attempt
            // Only a model-side problem is worth another model; a key/quota/timeout problem is not.
            if (index == models.lastIndex || !isWorthAnotherModel(attempt.failure)) {
                return@withContext attempt
            }
        }
        last ?: GroundedAnswer(trimmed, "", failure = GroundedSearchFailure.EMPTY)
    }

    /** One model, one attempt. Never throws: every path becomes an answer. */
    private suspend fun attempt(query: String, model: String, started: Long): GroundedAnswer {
        // `runInterruptible` is what makes the 25 s budget real: a plain blocking call inside a
        // coroutine cannot be cancelled, so `withTimeoutOrNull` in SearchToolRunner would wait for
        // the socket instead of delivering an honest TIMEOUT. With it, cancelling the coroutine
        // interrupts the HTTP thread and OkHttp aborts the request.
        val response = try {
            runInterruptible(ioDispatcher) { transport.post(buildRequest(query, model)) }
        } catch (e: Exception) {
            // If the coroutine was cancelled (timeout or session ended), the cancellation must win:
            // otherwise a timeout would be mislabelled as a network failure.
            currentCoroutineContext().ensureActive()
            return GroundedAnswer(
                query = query,
                text = "",
                failure = classifyException(e),
                elapsedMillis = clock() - started,
                detail = e.javaClass.simpleName + (e.message?.let { ": ${it.take(120)}" } ?: ""),
                model = model
            )
        }
        return parseResponse(query, response.code, response.body, model)
            .copy(elapsedMillis = clock() - started)
    }

    /**
     * A model-side refusal is worth retrying on the next model; a credential, quota, timeout or
     * network problem is not (the fallback shares the same key and, on the free tier, the same
     * grounding pool).
     */
    fun isWorthAnotherModel(failure: GroundedSearchFailure?): Boolean = when (failure) {
        GroundedSearchFailure.UNSUPPORTED,
        GroundedSearchFailure.HTTP,
        GroundedSearchFailure.EMPTY -> true
        else -> false
    }

    /** The exact HTTP request, including the credential header. Exposed for tests. */
    fun buildRequest(query: String, model: String = this.model): RestRequest = RestRequest(
        url = "$baseUrl/models/$model:generateContent",
        headers = linkedMapOf(
            "Content-Type" to "application/json; charset=utf-8",
            // The only place the key appears. Query-string auth is avoided on purpose.
            "x-goog-api-key" to apiKey
        ),
        body = requestBody(query)
    )

    /** `tools:[{"google_search":{}}]` — the REST tool name is snake_case (Live uses `googleSearch`). */
    fun requestBody(query: String): String = MiniJson.encode(
        linkedMapOf<String, Any?>(
            "contents" to listOf(
                linkedMapOf<String, Any?>(
                    "role" to "user",
                    "parts" to listOf(
                        linkedMapOf<String, Any?>(
                            "text" to (
                                "Answer this concisely using Google Search results, then list the " +
                                    "sources you used. If you cannot verify it, say so. Question: $query"
                                )
                        )
                    )
                )
            ),
            "tools" to listOf(linkedMapOf<String, Any?>("google_search" to emptyMap<String, Any?>())),
            "generationConfig" to linkedMapOf<String, Any?>(
                "temperature" to 0.2,
                "maxOutputTokens" to 512
            )
        )
    )

    /** Secret-free description for diagnostics and tests. */
    fun describe(): String =
        "POST $baseUrl/models/$model:generateContent (fallback: ${models.drop(1).joinToString(",")}) key=AIza****"

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val MODEL = "gemini-2.5-flash"

        /**
         * Fallback. Per the official pricing table, gemini-2.5-flash and gemini-2.5-flash-lite are
         * the ONLY models whose Grounding with Google Search is available on the Free Tier
         * (500 requests/day, one shared pool); every Gemini 3.x entry reads "Not available" there.
         * So the fallback stays inside the free allowance instead of silently moving to a paid path.
         */
        const val FALLBACK_MODEL = "gemini-2.5-flash-lite"

        /** Removes anything key-shaped from text that came back from the network. */
        fun scrubSecret(text: String): String =
            text.replace(Regex("AIza[0-9A-Za-z_-]{10,}"), "AIza****")


        /**
         * Google's own words for a failed call: `error.message` from the response JSON, scrubbed of
         * anything key-shaped and clipped so a bubble stays readable. Empty when the body is not
         * JSON (a proxy page, an empty body) — in that case the caller falls back to raw text.
         */
        fun serverMessage(body: String, maxChars: Int = 300): String {
            val map = runCatching { MiniJson.parse(body) }.getOrNull() as? Map<*, *>
            val error = map?.get("error") as? Map<*, *>
            val raw = (error?.get("message") as? String)
                ?: (map?.get("message") as? String)
                ?: return ""
            val clean = scrubSecret(raw).replace(Regex("\\s+"), " ").trim()
            return if (clean.length > maxChars) clean.take(maxChars) + "…" else clean
        }

        /**
         * Pure parser: HTTP status + body -> [GroundedAnswer]. Kept separate from the socket so
         * every branch is unit-tested.
         *
         * Every failure carries [GroundedAnswer.serverMessage] (Google's text) and the [model] that
         * produced it, because that is what the 0.2.4 chat report prints. Nothing here guesses: if
         * Google said nothing, the field stays empty and the report says so.
         */
        fun parseResponse(
            query: String,
            statusCode: Int,
            body: String,
            model: String = ""
        ): GroundedAnswer {
            val google = serverMessage(body)
            if (statusCode !in 200..299) {
                return GroundedAnswer(
                    query = query,
                    text = "",
                    failure = classifyHttp(statusCode, body),
                    statusCode = statusCode,
                    serverMessage = google,
                    model = model
                )
            }
            val root = runCatching { MiniJson.parse(body) }.getOrNull() as? Map<*, *>
                ?: return GroundedAnswer(
                    query, "", failure = GroundedSearchFailure.EMPTY, statusCode = statusCode,
                    serverMessage = google, model = model
                )

            // A 2xx can still carry an error object (e.g. a blocked prompt).
            (root["error"] as? Map<*, *>)?.let { error ->
                val code = (error["code"] as? Number)?.toInt() ?: statusCode
                val message = (error["message"] as? String) ?: ""
                return GroundedAnswer(
                    query, "", failure = classifyHttp(code, message), statusCode = statusCode,
                    serverMessage = google, model = model
                )
            }

            val candidate = (root["candidates"] as? List<*>)?.firstOrNull() as? Map<*, *>
                ?: return GroundedAnswer(
                    query, "", failure = GroundedSearchFailure.EMPTY, statusCode = statusCode,
                    serverMessage = google, model = model
                )
            val content = candidate["content"] as? Map<*, *>
            val parts = (content?.get("parts") as? List<*>).orEmpty()

            val text = parts.mapNotNull { part ->
                val map = part as? Map<*, *> ?: return@mapNotNull null
                if (map["thought"] == true) return@mapNotNull null       // reasoning parts are not the answer
                (map["text"] as? String)?.takeIf { it.isNotBlank() }
            }.joinToString("\n").trim()

            val metadata = candidate["groundingMetadata"] as? Map<*, *>
            val report = metadata?.let { GroundingParser.fromMetadata(it) } ?: GroundingReport.EMPTY
            val sources = report.sources.distinctBy { it.url }

            // A blocked/empty candidate explains itself in finishReason, not in error.message.
            val finish = (candidate["finishReason"] as? String).orEmpty()
            val emptyNote = if (text.isBlank() && finish.isNotBlank()) "finishReason=$finish" else ""

            return GroundedAnswer(
                query = query,
                text = text,
                sources = sources,
                failure = if (text.isBlank()) GroundedSearchFailure.EMPTY else null,
                statusCode = statusCode,
                detail = emptyNote,
                serverMessage = google,
                model = model
            )
        }

        /**
         * Maps a thrown exception to the honest failure.
         *
         * Only genuine connectivity problems may become [GroundedSearchFailure.NETWORK];
         * `EOFException` is checked first and becomes [GroundedSearchFailure.INTERNAL], because
         * okio/OkHttp signal "the body ended before the count I asked for" that way — an app-side
         * read error that must never read as "you have no internet".
         */
        fun classifyException(e: Exception): GroundedSearchFailure = when (e) {
            is java.io.EOFException -> GroundedSearchFailure.INTERNAL

            is java.net.SocketTimeoutException,
            is java.io.InterruptedIOException -> GroundedSearchFailure.TIMEOUT

            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.NoRouteToHostException,
            is java.net.PortUnreachableException,
            is javax.net.ssl.SSLException,
            is java.net.SocketException -> GroundedSearchFailure.NETWORK

            // Remaining I/O failures are ordinary transport problems.
            is java.io.IOException -> GroundedSearchFailure.NETWORK

            else -> GroundedSearchFailure.INTERNAL
        }

        /** Maps a status/reason to the failure the model is told about. */
        fun classifyHttp(statusCode: Int, bodyOrMessage: String): GroundedSearchFailure {
            val text = bodyOrMessage.lowercase()
            val quota = listOf("quota", "exhausted", "billing", "payment required", "insufficient")
            val auth = listOf("api key not valid", "api_key_invalid", "permission denied", "unauthorized",
                "unauthenticated", "forbidden")
            val unsupported = listOf("not found", "not supported", "unsupported", "does not support",
                "invalid tool", "unknown name")
            return when {
                statusCode == 402 -> GroundedSearchFailure.QUOTA
                statusCode == 429 -> GroundedSearchFailure.QUOTA
                statusCode == 401 || statusCode == 403 ->
                    if (quota.any { it in text }) GroundedSearchFailure.QUOTA else GroundedSearchFailure.AUTH
                auth.any { it in text } -> GroundedSearchFailure.AUTH
                quota.any { it in text } -> GroundedSearchFailure.QUOTA
                unsupported.any { it in text } -> GroundedSearchFailure.UNSUPPORTED
                else -> GroundedSearchFailure.HTTP
            }
        }
    }
}
