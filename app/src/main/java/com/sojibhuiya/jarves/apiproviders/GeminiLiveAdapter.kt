package com.sojibhuiya.jarves.apiproviders

/**
 * Adapter for the Gemini Live voice provider.
 *
 * This adapter does not re-implement the transport: it points at the exact endpoint, model and
 * audio settings used by the existing, working [com.sojibhuiya.jarves.GeminiLiveVoice]
 * engine. Keeping the values in `GeminiLiveVoice.Companion` and referencing them here means
 * the adapter and the tested transport can never drift apart.
 */
class GeminiLiveAdapter : VoiceProviderAdapter {

    companion object {
        const val PROVIDER_ID = "gemini_live"
        const val DISPLAY_NAME = "Gemini Live"
    }

    override val metadata: ProviderMetadata = ProviderMetadata(
        providerId = PROVIDER_ID,
        displayName = DISPLAY_NAME,
        defaultCategory = ApiCategory.VOICE,
        capabilities = setOf(ProviderCapability.VOICE_CONVERSATION),
        authentication = AuthenticationMethod.API_KEY_QUERY_PARAMETER,
        endpoint = com.sojibhuiya.jarves.GeminiLiveVoice.WEBSOCKET_ENDPOINT,
        defaultModel = "models/" + com.sojibhuiya.jarves.GeminiLiveVoice.MODEL,
        integrated = true,
        aliases = listOf("gemini live", "gemini live api", "gemini")
    )
}

/** Default registry used by the app: only the shipped, working integration is registered. */
fun defaultProviderRegistry(): ApiProviderRegistry = ApiProviderRegistry(listOf(GeminiLiveAdapter()))
