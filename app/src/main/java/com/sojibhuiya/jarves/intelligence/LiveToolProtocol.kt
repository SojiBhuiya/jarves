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
        scheduling: String = SCHEDULING_WHEN_IDLE
    ): String = MiniJson.encode(
        linkedMapOf<String, Any?>(
            "toolResponse" to linkedMapOf<String, Any?>(
                KEY_FUNCTION_RESPONSES to listOf(
                    linkedMapOf<String, Any?>(
                        "id" to callId,
                        "name" to LiveSessionConfig.FUNCTION_SEARCH_WEB,
                        // The result object. It carries the human-readable summary and nothing else.
                        "response" to linkedMapOf<String, Any?>("result" to result),
                        // 0.2.12 (B7): `scheduling` is sent in exactly one place — the place the
                        // API reference defines it in. `FunctionResponse` field table,
                        // https://ai.google.dev/api/generate-content#FunctionResponse:
                        //   "**scheduling**: string enum — Optional. Specifies how the response
                        //    should be scheduled in the conversation. Only applicable to
                        //    NON_BLOCKING function calls, is ignored otherwise. Defaults to
                        //    WHEN_IDLE."
                        // 0.2.11 also repeated it *inside* `response`, because the SDK snippets in
                        // the live-tools guide build `response={... , "scheduling": ...}`. The
                        // reference types `response` as a free-form Struct with no such field, so
                        // the copy was an undocumented extra key. It is gone; and because the
                        // documented default is already WHEN_IDLE, the wire behaviour we rely on
                        // cannot change by removing it.
                        "scheduling" to scheduling
                    )
                )
            )
        )
    )

    /**
     * Which `FunctionResponse.scheduling` to use for a search result: **always `WHEN_IDLE`**.
     *
     * 0.2.10 and earlier used `INTERRUPT` for results shorter than `LONG_RESULT_CHARS`, which is
     * exactly the 0.2.11 phone report: the user asked Q1 (search), then Q2 before the result came;
     * when Q1's result arrived it interrupted Q2's answer and Q2 was lost.
     *
     * What the docs say (`FunctionResponse.scheduling`,
     * https://ai.google.dev/api/generate-content#FunctionResponse):
     *  * "Only applicable to NON_BLOCKING function calls, is ignored otherwise. **Defaults to
     *    WHEN_IDLE**."
     *  * `SILENT` — "Only add the result to the conversation context, do not interrupt or trigger
     *    generation."
     *  * `WHEN_IDLE` — "Add the result to the conversation context, and prompt to generate output
     *    **without interrupting ongoing generation**."
     *  * `INTERRUPT` — "Add the result to the conversation context, **interrupt ongoing
     *    generation** and prompt to generate output."
     *
     * and the guide (https://ai.google.dev/gemini-api/docs/live-tools → "Asynchronous function
     * calling", supported on `gemini-3.8-live`): `INTERRUPT` = "Interrupt what it's doing and tell
     * you about the response it got right away", `WHEN_IDLE` = "Wait until it's finished with
     * what it's currently doing".
     *
     * So `WHEN_IDLE` is the documented value that waits for the current output to finish, which is
     * precisely what the phone needs. There is no length threshold any more: the value is the same
     * for every search result.
     */
    fun schedulingFor(result: String, longThresholdChars: Int = LONG_RESULT_CHARS): String =
        SCHEDULING_WHEN_IDLE

    /** Upper bound on a query we are willing to send to the REST model. */
    const val MAX_QUERY_CHARS = 512

    /** Results longer than this are announced when the model goes idle instead of interrupting. */
    const val LONG_RESULT_CHARS = 700
}
