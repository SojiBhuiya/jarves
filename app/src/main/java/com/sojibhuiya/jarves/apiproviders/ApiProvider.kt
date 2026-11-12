package com.sojibhuiya.jarves.apiproviders

/**
 * The two API categories exposed to the user.
 *
 * [VOICE] providers can carry a real-time voice conversation. [GENERAL_AI] providers cover
 * every other capability (text, chat, coding, image, video, …). A [GENERAL_AI] record is
 * never implicitly treated as a voice provider — selection is always explicit.
 */
enum class ApiCategory(val storageId: String, val label: String, val description: String) {
    VOICE(
        storageId = "voice",
        label = "Voice API",
        description = "Real-time voice: listening, speaking, audio streaming, live conversations."
    ),
    GENERAL_AI(
        storageId = "general_ai",
        label = "General AI API",
        description = "Text, chat, coding, image and video capabilities. Not used for live voice."
    );

    companion object {
        fun fromStorageId(id: String?): ApiCategory? = entries.firstOrNull { it.storageId == id }
    }
}

/** Capabilities a provider adapter may declare. Purely descriptive metadata. */
enum class ProviderCapability(val label: String) {
    VOICE_CONVERSATION("Voice conversation"),
    TEXT_GENERATION("Text generation"),
    CHAT("Chat"),
    CODING("Coding"),
    IMAGE_GENERATION("Image generation"),
    IMAGE_EDITING("Image editing"),
    VIDEO_GENERATION("Video generation"),
    VIDEO_EDITING("Video editing")
}

/** How a provider authenticates. Descriptive only — adapters implement the real flow. */
enum class AuthenticationMethod(val label: String) {
    API_KEY_QUERY_PARAMETER("API key query parameter"),
    BEARER_TOKEN("Bearer token"),
    CUSTOM_HEADER("Custom header"),
    UNKNOWN("Unknown")
}

/** Whether Jarves actually has a working client for this saved record. */
enum class IntegrationStatus(val label: String) {
    INTEGRATED("Integrated"),
    NOT_INTEGRATED("Saved – Not Integrated")
}

/**
 * One saved API credential.
 *
 * [encryptedApiKey] is the only persisted form of the credential. [keyHint] holds the last
 * four characters so cards can be rendered without ever decrypting the key.
 */
data class ApiProviderRecord(
    val id: String,
    val name: String,
    val category: ApiCategory,
    val encryptedApiKey: String,
    val keyHint: String,
    val enabled: Boolean,
    val providerId: String?,
    val metadata: Map<String, String>,
    val createdAt: Long,
    val updatedAt: Long
) {
    val hasCredential: Boolean get() = encryptedApiKey.isNotBlank()

    /** Never exposes the raw key: "••••••••••••1234". */
    fun maskedApiKey(): String = ApiProviderMasking.mask(keyHint)

    fun metadataValue(key: String): String? = metadata[key]?.takeIf { it.isNotBlank() }
}

/** Masking helpers shared by UI and tests. */
object ApiProviderMasking {
    private const val DOTS = "••••••••••••"
    private const val HINT_LENGTH = 4

    /** Number of trailing characters kept visible. */
    fun hintLength(): Int = HINT_LENGTH

    fun hintFor(rawKey: String): String {
        val trimmed = rawKey.trim()
        return if (trimmed.length >= 8) trimmed.takeLast(HINT_LENGTH) else ""
    }

    fun mask(keyHint: String): String =
        if (keyHint.isBlank()) DOTS else DOTS + keyHint.takeLast(HINT_LENGTH)
}

/** Input limits shared by the form and the repository validation. */
object ApiProviderLimits {
    const val MAX_NAME_LENGTH = 60
}
