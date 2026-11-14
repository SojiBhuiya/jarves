package com.sojibhuiya.jarves.intelligence

/**
 * How eagerly the Live API treats microphone input as speech.
 *
 * The 20.2.7 phone report: faint sounds / nearby voices made Jarves answer in random languages.
 * Two documented VAD knobs explain that, and both are supported on every model this app uses:
 *
 *  * `realtimeInputConfig.automaticActivityDetection` with
 *    `startOfSpeechSensitivity`, `endOfSpeechSensitivity`, `prefixPaddingMs`, `silenceDurationMs` —
 *    https://ai.google.dev/gemini-api/docs/live-guide → "Automatic VAD configuration" and
 *    https://ai.google.dev/api/live#RealtimeInputConfig
 *  * `START_SENSITIVITY_LOW` = "Automatic detection will detect the start of speech less often",
 *    `END_SENSITIVITY_LOW` = "ends speech less often" —
 *    https://ai.google.dev/api/live#StartSensitivity · https://ai.google.dev/api/live#EndSensitivity
 *  * `prefixPaddingMs`: "A value of `0` may cause the beginning of words to be clipped";
 *    `silenceDurationMs`: recommended 500–800 ms, server default ≈800 ms, and 100–200 ms
 *    "splits a single utterance into multiple small audio fragments … resulting in lower
 *    transcription and response quality" —
 *    https://ai.google.dev/gemini-api/docs/live-guide#understanding-vad-parameters-and-their-impact-on-quality
 *
 * `NORMAL` deliberately sends **no** `realtimeInputConfig`, i.e. the server defaults. That keeps
 * the 0.2.7 behaviour available and guarantees we never post a field the server has to reject.
 */
enum class MicSensitivity(
    val label: String,
    val detail: String
) {
    /** Fewest accidental turns: start/end detection at LOW, 100 ms of look-back, 800 ms of silence. */
    LOW(
        label = "Low",
        detail = "কম সংবেদনশীল — পাশের শব্দ বা দূরের কণ্ঠে সহজে সাড়া দেবে না"
    ),

    /** Exactly the server defaults (no `realtimeInputConfig` sent at all). */
    NORMAL(
        label = "Normal",
        detail = "ডিফল্ট — সার্ভারের নিজের সেটিং"
    ),

    /** Most eager: detects quiet speech, ends turns quickly. */
    HIGH(
        label = "High",
        detail = "বেশি সংবেদনশীল — খুব নিচু গলার স্বরও ধরবে, তবে বেশি ভুল ট্রিগার হতে পারে"
    );

    /**
     * `realtimeInputConfig` for the setup frame, or `null` when the server defaults should be used
     * unchanged. Field names are the documented camelCase JSON of `RealtimeInputConfig`.
     */
    fun realtimeInputConfigJson(): Map<String, Any?>? = when (this) {
        NORMAL -> null
        LOW -> linkedMapOf(
            "automaticActivityDetection" to linkedMapOf<String, Any?>(
                "disabled" to false,
                "startOfSpeechSensitivity" to START_LOW,
                "endOfSpeechSensitivity" to END_LOW,
                "prefixPaddingMs" to 100,
                "silenceDurationMs" to 800
            )
        )
        HIGH -> linkedMapOf(
            "automaticActivityDetection" to linkedMapOf<String, Any?>(
                "disabled" to false,
                "startOfSpeechSensitivity" to START_HIGH,
                "endOfSpeechSensitivity" to END_HIGH,
                "prefixPaddingMs" to 20,
                "silenceDurationMs" to 500
            )
        )
    }

    companion object {
        /** Conservative by default: the user hears random answers from noise far more often than lag. */
        val DEFAULT: MicSensitivity = LOW

        const val START_LOW = "START_SENSITIVITY_LOW"
        const val START_HIGH = "START_SENSITIVITY_HIGH"
        const val END_LOW = "END_SENSITIVITY_LOW"
        const val END_HIGH = "END_SENSITIVITY_HIGH"

        fun fromStored(raw: String?): MicSensitivity {
            val value = raw?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
        }
    }
}

/**
 * Which models accept the automatic-VAD fields.
 *
 * The Live capabilities guide's model comparison table marks "Automatic VAD configuration" as
 * Supported for Gemini 3.8 Live, 3.8 Live Extended Thinking, 3.1 Flash Live Preview and the
 * 2.5 Flash Live Preview (native audio) used here —
 * https://ai.google.dev/gemini-api/docs/live-guide#model-comparison
 *
 * The gate is kept as code (not just a constant) so a future model added to [LiveModels] can never
 * receive a field nobody verified it accepts.
 */
object MicVadSupport {

    private val verified: Set<String> = setOf(
        LiveModels.VOICE,
        LiveModels.SEARCH_NATIVE_AUDIO
    )

    fun supportsAutomaticVad(model: String): Boolean = model in verified

    /** True when a `realtimeInputConfig` may be sent for this model with this choice. */
    fun shouldSend(model: String, sensitivity: MicSensitivity): Boolean =
        sensitivity != MicSensitivity.NORMAL && supportsAutomaticVad(model)
}

/** Persistence seam for [MicSensitivity]. */
interface MicSensitivityStore {
    fun read(): String?

    fun write(value: String)
}

class InMemoryMicSensitivityStore(private var value: String? = null) : MicSensitivityStore {
    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }
}

class MicSensitivitySettings(private val store: MicSensitivityStore = InMemoryMicSensitivityStore()) {

    fun current(): MicSensitivity = MicSensitivity.fromStored(store.read())

    fun save(value: MicSensitivity) {
        store.write(value.name)
    }
}
