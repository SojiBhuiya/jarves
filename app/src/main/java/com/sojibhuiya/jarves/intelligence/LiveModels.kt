package com.sojibhuiya.jarves.intelligence

/**
 * The two ways Jarves can talk to Gemini Live. The user picks one in Settings; the choice is
 * persisted and is the *only* thing that decides which model and which tools are used.
 */
enum class VoiceSearchMode(
    /** What the Settings row says. */
    val label: String,
    /** What the Settings row explains underneath the label. */
    val detail: String,
    /** The Live model ID from [LiveModels]; never a literal at a call site. */
    val model: String,
    /** `true` = Mode A: Google runs the search inside the Live session (built-in `googleSearch`). */
    val usesBuiltInSearch: Boolean
) {
    /**
     * Mode A — "Search". The Live session declares `tools:[{"googleSearch":{}}]` and nothing else;
     * Google decides when to search and answers with current data. No REST call, no function tool.
     *
     * Docs: Grounding with Google Search is listed as **Supported** for Gemini 2.5 Flash Live
     * Preview (https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash-native-audio-preview-12-2025)
     * and Search is "Supported" for that model in the Live tool table
     * (https://ai.google.dev/gemini-api/docs/live-tools#tools-overview).
     */
    SEARCH(
        label = "Search",
        detail = "Google Search inside the Live session (gemini-2.5 native audio). Current facts, no extra REST call.",
        model = LiveModels.SEARCH_NATIVE_AUDIO,
        usesBuiltInSearch = true
    ),

    /**
     * Mode B — "Voice only". The previous behaviour: `gemini-3.8-live` with no `tools` at all.
     * Voice works; nothing is searched.
     */
    VOICE_ONLY(
        label = "Voice only",
        detail = "Voice conversation only (gemini-3.8-live, no search tools).",
        model = LiveModels.VOICE_ONLY,
        usesBuiltInSearch = false
    );

    companion object {
        /** Mode A is the default from 0.2.6 on. */
        val DEFAULT: VoiceSearchMode = SEARCH

        /** Lenient parse of a stored value; anything unknown falls back to [DEFAULT]. */
        fun fromStored(raw: String?): VoiceSearchMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Every Live model ID the app can use, in ONE place.
 *
 * Nothing else in the codebase may contain a Live model string: the diagnostics, the setup frame
 * and the tests all read it from here, so a model can be changed (or a preview retired) in exactly
 * one line.
 *
 * Verified in the official docs:
 *  * `gemini-3.8-live` — "Default Live API model for most low-latency voice agent experiences",
 *    https://ai.google.dev/gemini-api/docs/models (Gemini 3.8 Live) and the model comparison table
 *    in https://ai.google.dev/gemini-api/docs/live-guide#model-comparison.
 *  * `gemini-2.5-flash-native-audio-preview-12-2025` — official model page
 *    https://ai.google.dev/gemini-api/docs/models/gemini-2.5-flash-native-audio-preview-12-2025
 *    (versions list: "Preview: gemini-2.5-flash-native-audio-preview-12-2025"; no deprecation notice
 *    on that page). Audio + text input, audio output, Grounding with Google Search = Supported.
 */
object LiveModels {

    /** Mode B: voice only. */
    const val VOICE_ONLY = "gemini-3.8-live"

    /** Mode A: 2.5 native-audio preview with the built-in Google Search tool. */
    const val SEARCH_NATIVE_AUDIO = "gemini-2.5-flash-native-audio-preview-12-2025"

    /** Every model this app may open a Live session with. */
    val ALL: List<String> = listOf(SEARCH_NATIVE_AUDIO, VOICE_ONLY)

    /** The model for a mode. */
    fun modelFor(mode: VoiceSearchMode): String = mode.model

    /** True only for a model in [ALL]; anything else is a bug, not a fallback. */
    fun isKnown(model: String): Boolean = model in ALL

    /** The voice used by both modes: a prebuilt TTS voice name. */
    const val VOICE_NAME = "Aoede"
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
 * setup, so for those the report says plainly what the server said and points at Mode B.
 */
data class SessionEndReport(
    val code: Int,
    val reason: String,
    val mode: VoiceSearchMode,
    val model: String,
    val setupTools: String
) {
    /** Whether the user is better off on Mode B right now. */
    val suggestVoiceOnly: Boolean
        get() = mode.usesBuiltInSearch && code in ACTIONABLE_CODES

    /** One message for the chat bubble / diagnostic line. Never contains the key. */
    fun message(): String = buildString {
        append("Live সেশন বন্ধ — code=").append(code)
            .append(" | reason=").append(reason.ifBlank { "(none given)" })
        append('\n').append("mode=").append(mode.label)
            .append(" | model=").append(model)
            .append(" | tools=").append(setupTools)
        if (suggestVoiceOnly) {
            append('\n').append(
                "এই মোডে সার্চ টুল নিয়ে সেশন শুরু হচ্ছিলা না। Settings → Voice and search mode → " +
                    "\"Voice only\" বেছে নিলে ভয়েস আবার চালু হবে (সার্চ ছাড়া)।"
            )
        }
        append('\n').append("key=AIza****")
    }

    companion object {
        /** Close codes that mean "this setup was refused", i.e. switching mode can help. */
        val ACTIONABLE_CODES: Set<Int> = setOf(1007, 1008, 1011, 4400, 4401, 4403)
    }
}
