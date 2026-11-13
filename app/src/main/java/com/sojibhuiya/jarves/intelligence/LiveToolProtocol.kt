package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson

/** One `search_web` call requested by the model. */
data class SearchFunctionCall(
    /** Correlation id supplied by the server; echoed back verbatim in the `toolResponse`. */
    val callId: String,
    val query: String
)

/**
 * Wire format for Design 4: parsing the model's `toolCall` and building the `toolResponse`.
 *
 * Everything here is a pure function over strings, parsed with the app's own [MiniJson] rather
 * than `org.json`, so the whole protocol is covered by real JVM unit tests:
 *
 * ```
 * server -> {"toolCall":{"functionCalls":[{"id":"c1","name":"search_web","args":{"query":"…"}}]}}
 * client -> {"toolResponse":{"functionResponses":[{"id":"c1","name":"search_web",
 *            "response":{"result":"…"},"scheduling":"INTERRUPT"}]}}
 * ```
 */
object LiveToolProtocol {

    const val SCHEDULING_INTERRUPT = "INTERRUPT"
    const val SCHEDULING_WHEN_IDLE = "WHEN_IDLE"
    const val SCHEDULING_SILENT = "SILENT"

    private const val KEY_TOOL_CALL = "toolCall"
    private const val KEY_FUNCTION_CALLS = "functionCalls"
    private const val KEY_FUNCTION_RESPONSES = "functionResponses"
    private const val KEY_TOOL_CANCELLATION = "toolCallCancellation"
    private const val KEY_IDS = "ids"

    /** Cheap pre-check so ordinary audio frames are never parsed twice. */
    fun mightContainToolCall(message: String): Boolean =
        message.contains(KEY_TOOL_CALL) || message.contains(KEY_TOOL_CANCELLATION)

    /**
     * Extracts every `search_web` call from a server message. Other function names, missing ids and
     * malformed frames are ignored rather than guessed at — an unanswered call simply times out on
     * the server side, which is safer than inventing a correlation id.
     */
    fun parseSearchCalls(message: String?): List<SearchFunctionCall> {
        if (message.isNullOrBlank() || !message.contains(KEY_TOOL_CALL)) return emptyList()
        val root = runCatching { MiniJson.parse(message) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val toolCall = root[KEY_TOOL_CALL] as? Map<*, *> ?: return emptyList()
        val calls = toolCall[KEY_FUNCTION_CALLS] as? List<*> ?: return emptyList()
        return calls.mapNotNull { entry ->
            val call = entry as? Map<*, *> ?: return@mapNotNull null
            val name = call["name"] as? String
            if (name != LiveSessionConfig.FUNCTION_SEARCH_WEB) return@mapNotNull null
            val id = (call["id"] as? String)?.trim().orEmpty()
            if (id.isEmpty()) return@mapNotNull null
            // `args` is documented as an object; a pre-encoded string is accepted too rather than
            // dropping a legitimate call.
            val args = when (val raw = call["args"]) {
                is Map<*, *> -> raw
                is String -> runCatching { MiniJson.parse(raw) }.getOrNull() as? Map<*, *>
                else -> null
            }
            val query = (args?.get("query") as? String)?.trim().orEmpty()
            if (query.isEmpty()) return@mapNotNull null
            SearchFunctionCall(callId = id, query = query.take(MAX_QUERY_CHARS))
        }
    }

    /** Ids the server asked us to cancel (the user interrupted the turn). */
    fun parseCancellations(message: String?): List<String> {
        if (message.isNullOrBlank() || !message.contains(KEY_TOOL_CANCELLATION)) return emptyList()
        val root = runCatching { MiniJson.parse(message) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val cancellation = root[KEY_TOOL_CANCELLATION] as? Map<*, *> ?: return emptyList()
        val ids = cancellation[KEY_IDS] as? List<*> ?: return emptyList()
        return ids.mapNotNull { (it as? String)?.trim()?.takeIf { id -> id.isNotEmpty() } }
    }

    /**
     * The `toolResponse` frame. [result] must already be a secret-free, human-readable summary;
     * [scheduling] decides whether the model speaks immediately or waits.
     */
    fun toolResponseJson(
        callId: String,
        result: String,
        scheduling: String = SCHEDULING_INTERRUPT
    ): String = MiniJson.encode(
        linkedMapOf<String, Any?>(
            "toolResponse" to linkedMapOf<String, Any?>(
                KEY_FUNCTION_RESPONSES to listOf(
                    linkedMapOf<String, Any?>(
                        "id" to callId,
                        "name" to LiveSessionConfig.FUNCTION_SEARCH_WEB,
                        "response" to linkedMapOf<String, Any?>("result" to result),
                        "scheduling" to scheduling
                    )
                )
            )
        )
    )

    /**
     * `INTERRUPT` for a short result the user is waiting for; `WHEN_IDLE` for a long one, so a
     * multi-paragraph answer does not interrupt a sentence mid-sentence.
     */
    /**
     * Which `FunctionResponse.scheduling` to use for a search result.
     *
     * Docs (https://ai.google.dev/gemini-api/docs/live-tools → "Asynchronous function calling",
     * "supported on `gemini-3.8-live`"):
     *  * `INTERRUPT` — "Interrupt what it's doing and tell you about the response it got right away";
     *  * `WHEN_IDLE` — "Wait until it's finished with what it's currently doing";
     *  * `SILENT` — "do nothing and use that knowledge later on in the discussion".
     *
     * A short search answer is what the user is waiting for and the model has already said
     * "দেখে নিচ্ছি", so it is announced immediately (INTERRUPT). A long result must not cut off what
     * the model is currently saying, so it waits (WHEN_IDLE).
     */
    fun schedulingFor(result: String, longThresholdChars: Int = LONG_RESULT_CHARS): String =
        if (result.length > longThresholdChars) SCHEDULING_WHEN_IDLE else SCHEDULING_INTERRUPT

    /** Upper bound on a query we are willing to send to the REST model. */
    const val MAX_QUERY_CHARS = 512

    /** Results longer than this are announced when the model goes idle instead of interrupting. */
    const val LONG_RESULT_CHARS = 700
}
