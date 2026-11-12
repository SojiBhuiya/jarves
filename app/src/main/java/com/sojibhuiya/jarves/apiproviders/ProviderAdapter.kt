package com.sojibhuiya.jarves.apiproviders

/**
 * Static, extensible description of an AI provider.
 *
 * Everything here is metadata. A provider is only usable by Jarves when a concrete adapter
 * implements its real authentication, endpoint, request and response handling — saving a key
 * never enables a capability on its own.
 */
data class ProviderMetadata(
    val providerId: String,
    val displayName: String,
    val defaultCategory: ApiCategory,
    val capabilities: Set<ProviderCapability>,
    val authentication: AuthenticationMethod,
    /** Real endpoint of the provider's API, or `null` while no adapter exists. */
    val endpoint: String? = null,
    /** Model identifier used by the adapter, if any. */
    val defaultModel: String? = null,
    /** True only when a working adapter for this provider ships in the app. */
    val integrated: Boolean = false,
    /** Alternative names users may type in the API Name field. */
    val aliases: List<String> = emptyList()
) {
    fun integrationStatus(): IntegrationStatus =
        if (integrated) IntegrationStatus.INTEGRATED else IntegrationStatus.NOT_INTEGRATED
}

/** Base contract for every provider integration. */
interface ProviderAdapter {
    val metadata: ProviderMetadata

    val category: ApiCategory get() = metadata.defaultCategory

    fun integrationStatus(): IntegrationStatus = metadata.integrationStatus()
}

/** Adapter for real-time voice providers (the only implemented voice adapter is Gemini Live). */
interface VoiceProviderAdapter : ProviderAdapter {
    override val metadata: ProviderMetadata

    /** Category is fixed for voice adapters; a voice adapter can never serve General AI slots. */
    val voiceCategory: ApiCategory get() = ApiCategory.VOICE
}

/**
 * Contract for future text / chat / coding providers.
 *
 * Not implemented by any shipped provider yet: each provider needs its own endpoint,
 * authentication, request and response format, so no default implementation is provided.
 */
interface TextGenerationAdapter : ProviderAdapter {
    fun generateText(request: TextGenerationRequest, credential: String): TextGenerationResult
}

data class TextGenerationRequest(val prompt: String, val model: String?, val systemPrompt: String? = null)

data class TextGenerationResult(val text: String)

/** Contract for future image providers (generation and editing are provider-specific). */
interface ImageGenerationAdapter : ProviderAdapter {
    fun generateImage(request: ImageGenerationRequest, credential: String): ImageGenerationResult
}

data class ImageGenerationRequest(val prompt: String, val model: String?)

data class ImageGenerationResult(val imageUrls: List<String>, val base64Payloads: List<String> = emptyList())

/** Contract for future video providers. */
interface VideoGenerationAdapter : ProviderAdapter {
    fun generateVideo(request: VideoGenerationRequest, credential: String): VideoGenerationResult
}

data class VideoGenerationRequest(val prompt: String, val model: String?)

data class VideoGenerationResult(val videoUrls: List<String>, val operationId: String? = null)

/**
 * Registry of known providers.
 *
 * Providers that are not registered stay fully usable as saved credentials and are shown as
 * "Saved – Not Integrated". Future providers only need to register metadata (and an adapter
 * when the real integration lands); the API management system does not change.
 */
class ApiProviderRegistry(adapters: List<ProviderAdapter> = emptyList()) {

    private val registered: List<ProviderAdapter> = adapters

    private val adaptersById: Map<String, ProviderAdapter> = registered.associateBy { it.metadata.providerId }

    /** Names users type in the API Name field that map onto a provider with a live adapter. */
    private val knownNames: Map<String, String> = buildMap {
        for (adapter in registered) {
            put(adapter.metadata.displayName.lowercase(), adapter.metadata.providerId)
            adapter.metadata.aliases.forEach { alias -> put(alias.lowercase(), adapter.metadata.providerId) }
        }
    }

    fun adapterFor(providerId: String?): ProviderAdapter? = providerId?.let { adaptersById[it] }

    fun metadataFor(providerId: String?): ProviderMetadata? = adapterFor(providerId)?.metadata

    /**
     * Resolves the providerId for a user-supplied name. Only names of *integrated* providers
     * resolve, and only for the provider's own category, so a General AI record can never be
     * hijacked as the voice provider.
     */
    fun resolveProviderIdForName(name: String, category: ApiCategory): String? {
        val providerId = knownNames[name.trim().lowercase()] ?: return null
        val metadata = metadataFor(providerId) ?: return null
        if (!metadata.integrated) return null
        return if (metadata.defaultCategory == category) providerId else null
    }

    fun integrationStatusFor(record: ApiProviderRecord): IntegrationStatus {
        val metadata = metadataFor(record.providerId) ?: return IntegrationStatus.NOT_INTEGRATED
        return if (record.category == metadata.defaultCategory) {
            metadata.integrationStatus()
        } else {
            IntegrationStatus.NOT_INTEGRATED
        }
    }

    fun metadataForRecord(record: ApiProviderRecord): ProviderMetadata? =
        metadataFor(record.providerId)?.takeIf { it.defaultCategory == record.category }

    fun registeredMetadata(): List<ProviderMetadata> = registered.map { it.metadata }
}
