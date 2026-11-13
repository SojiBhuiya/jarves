package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson

/**
 * Which search tool (if any) the Live session declares.
 *
 * Design 4: the Live session no longer asks Google to run the search itself. It declares a
 * **function** (`search_web`) that the app answers from its own REST grounding call. The built-in
 * `googleSearch` tool is deliberately NOT sent, because a project without Live grounding
 * entitlement has the whole session refused when that tool is present.
 */
enum class LiveSearchTool {
    /** No tools at all: voice only, no search. */
    NONE,

    /**
     * `search_web` declared with `behavior: NON_BLOCKING`, so the model keeps talking while the
     * app searches, and the result is announced when it arrives.
     */
    FUNCTION_NON_BLOCKING,

    /**
     * `search_web` declared without `behavior` (server default = blocking). Used only when the
     * server rejects NON_BLOCKING for this model; the model then waits for the result before
     * speaking. Degraded, but still working search.
     */
    FUNCTION_BLOCKING,

    /**
     * Mode A (0.2.6): Google's own search, run **inside** the Live session.
     * `tools:[{"googleSearch":{}}]` and nothing else — no `functionDeclarations`, so the app never
     * runs a REST search in this mode.
     *
     * Docs: `Tool.googleSearch` — https://ai.google.dev/api/generate-content#Resource:Tool —
     * and the Live tool table, where Search is "Supported" for Gemini 2.5 Flash Live Preview:
     * https://ai.google.dev/gemini-api/docs/live-tools#tools-overview
     */
    GOOGLE_SEARCH_BUILT_IN;

    val declaresFunction: Boolean get() = this == FUNCTION_NON_BLOCKING || this == FUNCTION_BLOCKING

    /** True when the session asks Google to search for us. */
    val usesBuiltInSearch: Boolean get() = this == GOOGLE_SEARCH_BUILT_IN
}

/**
 * Builds the Gemini Live session payload and its instruction text.
 *
 * **Tool architecture (Design 4).** The session declares exactly one tool: a function
 * `search_web`. Retrieval happens in [GroundedSearchClient] over REST with
 * `tools:[{"google_search":{}}]` on `gemini-2.5-flash`, and the result is handed back to the live
 * session as a `toolResponse`. Consequences:
 *  * the Live setup never contains `googleSearch`, so the quota refusal that kills the whole
 *    session cannot happen;
 *  * the user keeps talking while the search runs (`behavior: NON_BLOCKING`);
 *  * the app decides when a search happens, so it is controllable and cheap.
 */
object LiveSessionConfig {

    /** Name used in the function declaration and in every `toolResponse`. */
    const val FUNCTION_SEARCH_WEB = "search_web"

    /** Kept for the legacy-compatibility test only: this string must never appear in a setup frame. */
    const val TOOL_GOOGLE_SEARCH = "googleSearch"

    const val BEHAVIOR_NON_BLOCKING = "NON_BLOCKING"

    /** Marker that tells the instruction builder (and the tests) a search tool is available. */
    private const val MARK_SEARCH = "You can search the live web"

    fun searchEnabled(systemInstruction: String): Boolean = systemInstruction.contains(MARK_SEARCH)

    /**
     * The complete system instruction: identity, freshness rules, source rules, honesty rules and
     * the current date/time block.
     */
    fun systemInstruction(
        assistantName: String,
        time: TimeContext.Snapshot,
        searchTool: LiveSearchTool
    ): String = buildString {
        appendLine(
            "You are $assistantName, a warm and capable personal voice assistant. " +
                "Speak naturally and conversationally. Understand and respond in Bengali, English, " +
                "Banglish, or the language the user uses. Prefer natural Bangla when the user speaks " +
                "Bangla. Keep responses clear, useful and concise. This is a live voice conversation. " +
                "Do not say you are reading a transcript."
        )
        appendLine()
        appendLine("CURRENT INFORMATION RULES:")
        if (searchTool.usesBuiltInSearch) {
            // Mode A. Google Search is built into the session: the model searches by itself, so
            // there is no `search_web` function and no "let me check" protocol to run.
            appendLine(
                "You have Google Search available inside this session. Use it for every current or " +
                    "time-sensitive fact: today's date and time, news, weather, prices, exchange " +
                    "rates, sports results, holders of an office (Prime Minister, President, Chief " +
                    "Adviser, ministers, CEOs), laws, events, or anything the user calls 'latest' or " +
                    "'now'. Search first and answer from what the search returns. Never answer such " +
                    "a question from memory alone."
            )
            appendLine(
                "Answer in one go, in the user's language, and name the source you used when the " +
                    "search returned one. If the search returns nothing usable, say plainly that " +
                    "you could not verify it and do not invent facts or links."
            )
        } else if (searchTool.declaresFunction) {
            // Short on purpose (0.2.7): a long instruction was making the model chatty.
            appendLine(
                "You can search the live web by calling `$FUNCTION_SEARCH_WEB` only for current or " +
                    "time-sensitive facts: weather, news, prices, exchange rates, sports scores, " +
                    "today's date, or \"who is the current …\". Never call it for small talk, " +
                    "arithmetic or definitions."
            )
            appendLine(
                "Before calling it, say one short line in the user's language: \"দেখে নিচ্ছি\". Do not " +
                    "guess before the result arrives. Then give the answer in a few plain Bengali " +
                    "sentences and name the source you used. If the result says the search failed, say " +
                    "so plainly. Never invent facts or links."
            )
        } else {
            appendLine(
                "Web search is NOT available in this session. For any question about current affairs, " +
                    "current office holders, today's date, news, prices, weather or anything 'latest', " +
                    "say plainly that you cannot verify live information right now, and do not present " +
                    "remembered facts as confirmed current information."
            )
        }
        appendLine(
            "Prefer the most recent sources. When a fact is disputed, compare the sources, report the " +
                "disagreement instead of choosing one side silently, and separate what is confirmed from " +
                "what is uncertain. Name the sources you used, briefly, in the spoken answer."
        )
        appendLine(
            "Stable knowledge (history, definitions, fixed past events, mathematics) does not need a " +
                "search; answer it directly and accurately."
        )
        appendLine(
            "Never state a date, time or 'current' fact that you have not been given or verified in " +
                "this session. If you are unsure, say so."
        )
        appendLine()
        append(TimeContext.instructionBlock(time))
    }

    /**
     * One `search_web` declaration. [nonBlocking] adds `behavior: NON_BLOCKING`, which makes the
     * model continue the conversation instead of waiting for the result.
     */
    fun searchFunctionDeclaration(nonBlocking: Boolean): Map<String, Any?> = linkedMapOf<String, Any?>(
        "name" to FUNCTION_SEARCH_WEB,
        "description" to (
            "Search the live web. Use for any current or time-sensitive question. " +
                "Returns current facts with source URLs."
            ),
        "parameters" to linkedMapOf<String, Any?>(
            "type" to "OBJECT",
            "properties" to linkedMapOf<String, Any?>(
                "query" to linkedMapOf<String, Any?>(
                    "type" to "STRING",
                    "description" to "The search query, in the language of the user's question."
                )
            ),
            "required" to listOf("query")
        )
    ).also { declaration ->
        if (nonBlocking) declaration["behavior"] = BEHAVIOR_NON_BLOCKING
    }

    /** The `tools` array for a given mode; empty for [LiveSearchTool.NONE]. */
    fun toolsJson(searchTool: LiveSearchTool): List<Map<String, Any?>> = when (searchTool) {
        LiveSearchTool.NONE -> emptyList()
        // Mode A: the built-in tool and NOTHING else. The Live API reference types this field as
        // Tool.googleSearch, serialized camelCase in the JSON setup frame.
        LiveSearchTool.GOOGLE_SEARCH_BUILT_IN -> listOf(
            linkedMapOf<String, Any?>("googleSearch" to emptyMap<String, Any?>())
        )
        LiveSearchTool.FUNCTION_NON_BLOCKING -> listOf(
            linkedMapOf<String, Any?>(
                "functionDeclarations" to listOf(searchFunctionDeclaration(nonBlocking = true))
            )
        )
        LiveSearchTool.FUNCTION_BLOCKING -> listOf(
            linkedMapOf<String, Any?>(
                "functionDeclarations" to listOf(searchFunctionDeclaration(nonBlocking = false))
            )
        )
    }

    /**
     * The setup message, serialized with the app's dependency-free JSON writer so the exact
     * payload is unit-tested.
     */
    fun setupJson(
        model: String,
        voiceName: String,
        systemInstruction: String,
        searchTool: LiveSearchTool,
        inputTranscription: Boolean = true,
        outputTranscription: Boolean = true
    ): String {
        val generationConfig = linkedMapOf<String, Any?>(
            "responseModalities" to listOf("AUDIO"),
            "speechConfig" to linkedMapOf<String, Any?>(
                "voiceConfig" to linkedMapOf<String, Any?>(
                    "prebuiltVoiceConfig" to linkedMapOf<String, Any?>("voiceName" to voiceName)
                )
            )
        )
        val setup = linkedMapOf<String, Any?>(
            "model" to "models/$model",
            "generationConfig" to generationConfig,
            "systemInstruction" to linkedMapOf<String, Any?>(
                "parts" to listOf(linkedMapOf<String, Any?>("text" to systemInstruction))
            )
        )
        // Exactly one function tool, and never `googleSearch` (Design 4).
        val tools = toolsJson(searchTool)
        if (tools.isNotEmpty()) setup["tools"] = tools
        if (inputTranscription) setup["inputAudioTranscription"] = emptyMap<String, Any?>()
        if (outputTranscription) setup["outputAudioTranscription"] = emptyMap<String, Any?>()

        return MiniJson.encode(linkedMapOf<String, Any?>("setup" to setup))
    }

    /**
     * Setup frame for the **second, short-lived search session** (Auto mode).
     *
     * It is deliberately separate from [setupJson]: no voice instruction, no function tools, and
     * `outputAudioTranscription` is mandatory because a native-audio model answers in audio and the
     * transcription is the only text the app can get.
     *
     * Docs: https://ai.google.dev/gemini-api/docs/live-guide — native audio models "only support
     * AUDIO response modality", and the output audio transcription is how the text is obtained.
     */
    fun searchSessionSetupJson(model: String, voiceName: String): String {
        val setup = linkedMapOf<String, Any?>(
            "model" to "models/$model",
            "generationConfig" to linkedMapOf<String, Any?>(
                "responseModalities" to listOf("AUDIO"),
                "speechConfig" to linkedMapOf<String, Any?>(
                    "voiceConfig" to linkedMapOf<String, Any?>(
                        "prebuiltVoiceConfig" to linkedMapOf<String, Any?>("voiceName" to voiceName)
                    )
                )
            ),
            "systemInstruction" to linkedMapOf<String, Any?>(
                "parts" to listOf(
                    linkedMapOf<String, Any?>(
                        "text" to (
                            "You are a web search engine for a voice assistant. Use Google Search for " +
                                "the question you are given. Answer in at most three short sentences, in " +
                                "the language of the question, and state the source name. If you cannot " +
                                "verify it, say so in one sentence. Never invent facts or links."
                            )
                    )
                )
            ),
            // The built-in tool and nothing else: no functionDeclarations in this session.
            "tools" to listOf(linkedMapOf<String, Any?>("googleSearch" to emptyMap<String, Any?>())),
            // Mandatory: this is how the app reads the answer.
            "outputAudioTranscription" to emptyMap<String, Any?>()
        )
        return MiniJson.encode(linkedMapOf<String, Any?>("setup" to setup))
    }

    /** clientContent message carrying free text into the running session. */
    fun clientContentJson(text: String, turnComplete: Boolean = false): String =
        MiniJson.encode(
            linkedMapOf<String, Any?>(
                "clientContent" to linkedMapOf<String, Any?>(
                    "turns" to listOf(
                        linkedMapOf<String, Any?>(
                            "role" to "user",
                            "parts" to listOf(linkedMapOf<String, Any?>("text" to text))
                        )
                    ),
                    "turnComplete" to turnComplete
                )
            )
        )
}
