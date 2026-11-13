package com.sojibhuiya.jarves.intelligence

/**
 * Every Live model ID the app can use, in ONE place. Nothing else may contain a Live model string.
 *
 * Verified in the official docs (2026-11):
 *  * `gemini-3.8-live` — the default Live model in
 *    https://ai.google.dev/gemini-api/docs/live-tools (model comparison table) and
 *    https://ai.google.dev/gemini-api/docs/models
 *  * `gemini-2.5-flash-native-audio-preview-12-2025` — model page
 *    https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash-native-audio-preview-12-2025
 *    (Grounding with Google Search = Supported; inputs include **text**, so a text turn is valid)
 */
object LiveModels {

    /** The voice model. Always used for the conversation in Auto and Voice-only modes. */
    const val VOICE = "gemini-3.8-live"

    /** The search model: 2.5 native audio with the built-in Google Search tool. */
    const val SEARCH_NATIVE_AUDIO = "gemini-2.5-flash-native-audio-preview-12-2025"

    /** Every model this app may open a Live session with. */
    val ALL: List<String> = listOf(VOICE, SEARCH_NATIVE_AUDIO)

    fun isKnown(model: String): Boolean = model in ALL

    /** The model that runs the conversation for [mode]. The single entry point for call sites. */
    fun modelFor(mode: VoiceSearchMode): String = mode.voiceModel

    /** The prebuilt voice used by both sessions (speechConfig.voiceConfig.prebuiltVoiceConfig). */
    const val VOICE_NAME = "Aoede"

    /** Compat aliases so older call sites keep one source of truth. */
    const val VOICE_ONLY: String = VOICE
    const val SEARCH_NATIVE_AUDIO_MODEL: String = SEARCH_NATIVE_AUDIO
}

/**
 * The three ways voice and search can be combined. The user picks one in Settings; it is persisted
 * and is the only thing that decides models and tools.
 */
enum class VoiceSearchMode(
    val label: String,
    val detail: String,
    /** The model that runs the *conversation*. */
    val voiceModel: String,
    /** Conversation declares the `search_web` function (Design 4). */
    val usesFunctionSearch: Boolean,
    /** The conversation session declares Google's built-in `googleSearch` tool. */
    val usesBuiltInSearch: Boolean,
    /** Search runs in a second, short-lived 2.5 session and is returned as the tool response. */
    val usesSecondSearchSession: Boolean
) {
    /**
     * Auto (0.2.7, default): the conversation stays on `gemini-3.8-live` — the voice the user
     * wants — and the model calls `search_web` when a fact must be current. The app answers that
     * call from a second, short-lived Live session on the 2.5 native-audio model, which has
     * free-tier Google Search grounding.
     */
    AUTO(
        label = "Auto",
        detail = "3.8 voice + Google Search in a second short session. Best voice, real search.",
        voiceModel = LiveModels.VOICE,
        usesFunctionSearch = true,
        usesBuiltInSearch = false,
        usesSecondSearchSession = true
    ),

    /** Voice only: the 3.8 model, no tools at all. */
    VOICE_ONLY(
        label = "Voice only",
        detail = "3.8 voice, no search tools at all.",
        voiceModel = LiveModels.VOICE,
        usesFunctionSearch = false,
        usesBuiltInSearch = false,
        usesSecondSearchSession = false
    ),

    /** Search only: the previous Mode A — the 2.5 model with built-in Google Search. */
    SEARCH_ONLY(
        label = "Search only",
        detail = "2.5 native-audio with built-in Google Search (slower voice, simplest path).",
        voiceModel = LiveModels.SEARCH_NATIVE_AUDIO,
        usesFunctionSearch = false,
        usesBuiltInSearch = true,
        usesSecondSearchSession = false
    );

    /** Compat alias: the model this mode runs on. */
    val model: String get() = voiceModel

    /** The session's search tool. */
    val searchTool: LiveSearchTool
        get() = when {
            usesBuiltInSearch -> LiveSearchTool.GOOGLE_SEARCH_BUILT_IN
            usesFunctionSearch -> LiveSearchTool.FUNCTION_NON_BLOCKING
            else -> LiveSearchTool.NONE
        }

    companion object {
        val DEFAULT: VoiceSearchMode = AUTO

        /**
         * Lenient parse. `SEARCH` was the 0.2.6 name of the built-in-search mode and is mapped to
         * [SEARCH_ONLY] so an existing choice survives the upgrade.
         */
        fun fromStored(raw: String?): VoiceSearchMode {
            val value = raw?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: if (value.equals("SEARCH", ignoreCase = true)) SEARCH_ONLY else DEFAULT
        }
    }
}

/** Persistence seam for the chosen [VoiceSearchMode]. SharedPreferences on device. */
interface VoiceModeStore {
    fun read(): String?

    fun write(value: String)
}

/** Test/no-op store. */
class InMemoryVoiceModeStore(private var value: String? = null) : VoiceModeStore {
    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }
}

/** Reads/writes the mode through a [VoiceModeStore], always returning a usable mode. */
class VoiceSearchModeSettings(private val store: VoiceModeStore = InMemoryVoiceModeStore()) {

    fun current(): VoiceSearchMode = VoiceSearchMode.fromStored(store.read())

    fun save(mode: VoiceSearchMode) {
        store.write(mode.name)
    }
}

/**
 * Why a Live session ended, in terms the user can act on.
 *
 * Close codes 1007 (invalid payload — e.g. the setup was rejected), 1008 (policy violation — e.g.
 * quota/entitlement) and 1011 (server internal error) are the ones that can follow a search-tool
 * setup, so the report says plainly what the server said and points at a mode that needs no search.
 */
data class SessionEndReport(
    val code: Int,
    val reason: String,
    val mode: VoiceSearchMode,
    val model: String,
    val setupTools: String
) {
    val suggestVoiceOnly: Boolean
        get() = mode != VoiceSearchMode.VOICE_ONLY && code in ACTIONABLE_CODES

    /** One message for the chat bubble / diagnostic line. Never contains the key. */
    fun message(): String = buildString {
        append("Live সেশন বন্ধ — code=").append(code)
            .append(" | reason=").append(reason.ifBlank { "(none given)" })
        append('\n').append("mode=").append(mode.label)
            .append(" | model=").append(model)
            .append(" | tools=").append(setupTools)
        if (suggestVoiceOnly) {
            append('\n').append(
                "Settings → Voice and search mode → \"Voice only\" বেছে নিলে ভয়েস আবার চালু হবে (সার্চ ছাড়া)।"
            )
        }
        append('\n').append("key=AIza****")
    }

    companion object {
        val ACTIONABLE_CODES: Set<Int> = setOf(1007, 1008, 1011, 4400, 4401, 4403)
    }
}
