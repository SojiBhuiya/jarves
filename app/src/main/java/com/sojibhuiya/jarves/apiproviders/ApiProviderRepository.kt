package com.sojibhuiya.jarves.apiproviders

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Persistent storage for the serialized provider list (DataStore in production). */
interface ApiProviderStorage {
    suspend fun read(): String?

    suspend fun write(value: String)
}

/** Which form field a validation failure belongs to. */
enum class ApiProviderField { NAME, API_KEY, CATEGORY }

sealed interface ApiProviderResult {
    /** [records] is the full, freshly persisted list; [changed] is the affected record. */
    data class Success(
        val records: List<ApiProviderRecord>,
        val changed: ApiProviderRecord? = null
    ) : ApiProviderResult

    data class ValidationFailed(val field: ApiProviderField, val message: String) : ApiProviderResult

    data class Failed(val message: String, val cause: Throwable? = null) : ApiProviderResult

    val isSuccess: Boolean get() = this is Success
}

sealed interface CredentialResult {
    data class Success(val apiKey: String, val record: ApiProviderRecord) : CredentialResult

    data class Failure(val message: String) : CredentialResult
}

/**
 * Single source of truth for saved API providers.
 *
 * Responsibilities:
 * * validate user input,
 * * encrypt credentials *before* they touch storage,
 * * keep records uniquely identified (uuid) so equal names never overwrite each other,
 * * decrypt a credential only when an integration asks for it.
 *
 * The repository never logs or persists a plaintext key.
 */
class ApiProviderRepository(
    private val storage: ApiProviderStorage,
    private val cipher: CredentialCipher,
    private val registry: ApiProviderRegistry = defaultProviderRegistry(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {

    private val mutex = Mutex()

    val providerRegistry: ApiProviderRegistry get() = registry

    suspend fun load(): ApiProviderResult = mutex.withLock { readRecords() }

    suspend fun add(
        name: String,
        apiKey: String,
        category: ApiCategory
    ): ApiProviderResult = mutex.withLock {
        val trimmedName = name.trim()
        val trimmedKey = apiKey.trim()
        val nameError = validateName(trimmedName)
        if (nameError != null) return@withLock nameError
        if (trimmedKey.isEmpty()) {
            return@withLock ApiProviderResult.ValidationFailed(
                ApiProviderField.API_KEY,
                "API Key is required"
            )
        }
        if (trimmedKey.any { it.isWhitespace() }) {
            return@withLock ApiProviderResult.ValidationFailed(
                ApiProviderField.API_KEY,
                "API Key must not contain spaces"
            )
        }
        val current = when (val loaded = readRecords()) {
            is ApiProviderResult.Success -> loaded.records
            is ApiProviderResult.Failed -> return@withLock loaded
            is ApiProviderResult.ValidationFailed -> return@withLock loaded
        }
        val encrypted = try {
            cipher.encrypt(trimmedKey)
        } catch (e: CredentialSecurityException) {
            return@withLock ApiProviderResult.Failed(
                "API Key could not be encrypted on this device",
                e
            )
        }
        val now = clock()
        val providerId = registry.resolveProviderIdForName(trimmedName, category)
        val record = ApiProviderRecord(
            id = idGenerator(),
            name = trimmedName,
            category = category,
            encryptedApiKey = encrypted,
            keyHint = ApiProviderMasking.hintFor(trimmedKey),
            enabled = true,
            providerId = providerId,
            metadata = metadataFor(providerId, category, current),
            createdAt = now,
            updatedAt = now
        )
        persist(current + record, record)
    }

    /**
     * Updates name/category and optionally replaces the credential.
     *
     * A blank [replacementApiKey] keeps the stored key, so the edit form never has to show a
     * decrypted credential.
     */
    suspend fun update(
        id: String,
        name: String,
        category: ApiCategory,
        replacementApiKey: String? = null
    ): ApiProviderResult = mutex.withLock {
        val current = when (val loaded = readRecords()) {
            is ApiProviderResult.Success -> loaded.records
            is ApiProviderResult.Failed -> return@withLock loaded
            is ApiProviderResult.ValidationFailed -> return@withLock loaded
        }
        val existing = current.firstOrNull { it.id == id }
            ?: return@withLock ApiProviderResult.Failed("Saved API not found")
        val trimmedName = name.trim()
        val nameError = validateName(trimmedName)
        if (nameError != null) return@withLock nameError
        val trimmedKey = replacementApiKey?.trim().orEmpty()
        if (trimmedKey.isNotEmpty() && trimmedKey.any { it.isWhitespace() }) {
            return@withLock ApiProviderResult.ValidationFailed(
                ApiProviderField.API_KEY,
                "API Key must not contain spaces"
            )
        }
        val encrypted = if (trimmedKey.isEmpty()) {
            existing.encryptedApiKey
        } else {
            try {
                cipher.encrypt(trimmedKey)
            } catch (e: CredentialSecurityException) {
                return@withLock ApiProviderResult.Failed(
                    "API Key could not be encrypted on this device",
                    e
                )
            }
        }
        val hint = if (trimmedKey.isEmpty()) existing.keyHint else ApiProviderMasking.hintFor(trimmedKey)
        val providerId = registry.resolveProviderIdForName(trimmedName, category)
        val remaining = current.filterNot { it.id == id }
        val updated = existing.copy(
            name = trimmedName,
            category = category,
            encryptedApiKey = encrypted,
            keyHint = hint,
            providerId = providerId,
            metadata = metadataFor(providerId, category, remaining),
            updatedAt = clock()
        )
        persist(current.map { if (it.id == id) updated else it }, updated)
    }

    suspend fun delete(id: String): ApiProviderResult = mutex.withLock {
        val current = when (val loaded = readRecords()) {
            is ApiProviderResult.Success -> loaded.records
            is ApiProviderResult.Failed -> return@withLock loaded
            is ApiProviderResult.ValidationFailed -> return@withLock loaded
        }
        if (current.none { it.id == id }) {
            return@withLock ApiProviderResult.Failed("Saved API not found")
        }
        persist(current.filterNot { it.id == id }, null)
    }

    /** Enabling/disabling never touches the stored credential. */
    suspend fun setEnabled(id: String, enabled: Boolean): ApiProviderResult = mutex.withLock {
        val current = when (val loaded = readRecords()) {
            is ApiProviderResult.Success -> loaded.records
            is ApiProviderResult.Failed -> return@withLock loaded
            is ApiProviderResult.ValidationFailed -> return@withLock loaded
        }
        val existing = current.firstOrNull { it.id == id }
            ?: return@withLock ApiProviderResult.Failed("Saved API not found")
        val updated = existing.copy(enabled = enabled, updatedAt = clock())
        persist(current.map { if (it.id == id) updated else it }, updated)
    }

    /** Decrypts a credential for an authorized integration. Never call this for display. */
    suspend fun decryptApiKey(id: String): CredentialResult = mutex.withLock {
        val current = when (val loaded = readRecords()) {
            is ApiProviderResult.Success -> loaded.records
            is ApiProviderResult.Failed -> return@withLock CredentialResult.Failure(loaded.message)
            is ApiProviderResult.ValidationFailed -> return@withLock CredentialResult.Failure(loaded.message)
        }
        val record = current.firstOrNull { it.id == id }
            ?: return@withLock CredentialResult.Failure("Saved API not found")
        decrypt(record)
    }

    fun decrypt(record: ApiProviderRecord): CredentialResult {
        if (record.encryptedApiKey.isBlank()) {
            return CredentialResult.Failure("No API key saved for this provider")
        }
        return try {
            CredentialResult.Success(cipher.decrypt(record.encryptedApiKey), record)
        } catch (e: CredentialSecurityException) {
            CredentialResult.Failure(
                "Saved API key could not be read on this device. Please enter the key again."
            )
        }
    }

    /**
     * Picks the credential the existing Gemini Live engine must use: an *enabled*, integrated
     * Gemini Live record, preferring the primary record and then the oldest one.
     */
    suspend fun resolveGeminiLiveCredential(): CredentialResult = mutex.withLock {
        val current = when (val loaded = readRecords()) {
            is ApiProviderResult.Success -> loaded.records
            is ApiProviderResult.Failed -> return@withLock CredentialResult.Failure(loaded.message)
            is ApiProviderResult.ValidationFailed -> return@withLock CredentialResult.Failure(loaded.message)
        }
        val candidate = selectGeminiLiveRecord(current)
            ?: return@withLock CredentialResult.Failure("Gemini Live API Key যোগ করুন")
        decrypt(candidate)
    }

    fun integrationStatus(record: ApiProviderRecord): IntegrationStatus =
        registry.integrationStatusFor(record)

    fun maskedKey(record: ApiProviderRecord): String = record.maskedApiKey()

    // ---------------------------------------------------------------- internals

    private fun selectGeminiLiveRecord(records: List<ApiProviderRecord>): ApiProviderRecord? =
        records
            .filter { it.enabled && it.providerId == GeminiLiveAdapter.PROVIDER_ID }
            .filter { registry.integrationStatusFor(it) == IntegrationStatus.INTEGRATED }
            .sortedWith(
                compareByDescending<ApiProviderRecord> { it.metadata[ApiProviderMetadataKeys.PRIMARY] == "true" }
                    .thenBy { it.createdAt }
            )
            .firstOrNull()

    private suspend fun readRecords(): ApiProviderResult =
        try {
            ApiProviderResult.Success(ApiProviderCodec.decode(storage.read()))
        } catch (e: StorageFormatException) {
            ApiProviderResult.Failed(
                "Saved API providers could not be read. Existing data was left untouched.",
                e
            )
        } catch (e: Exception) {
            ApiProviderResult.Failed("Saved API providers could not be read", e)
        }

    private suspend fun persist(
        records: List<ApiProviderRecord>,
        changed: ApiProviderRecord?
    ): ApiProviderResult = try {
        storage.write(ApiProviderCodec.encode(records))
        ApiProviderResult.Success(records, changed)
    } catch (e: Exception) {
        ApiProviderResult.Failed("API provider changes could not be saved", e)
    }

    private fun validateName(name: String): ApiProviderResult.ValidationFailed? = when {
        name.isEmpty() -> ApiProviderResult.ValidationFailed(
            ApiProviderField.NAME,
            "API Name is required"
        )
        name.length > ApiProviderLimits.MAX_NAME_LENGTH -> ApiProviderResult.ValidationFailed(
            ApiProviderField.NAME,
            "API Name must be at most ${ApiProviderLimits.MAX_NAME_LENGTH} characters"
        )
        else -> null
    }

    /**
     * Descriptive metadata copied onto the record. Only real values are stored: providers
     * without an adapter get no endpoint and no model, because none is known.
     */
    private fun metadataFor(
        providerId: String?,
        category: ApiCategory,
        existing: List<ApiProviderRecord>
    ): Map<String, String> {
        val metadata = registry.metadataFor(providerId) ?: return emptyMap()
        if (metadata.defaultCategory != category) return emptyMap()
        val result = linkedMapOf(
            ApiProviderMetadataKeys.PROVIDER_ID to metadata.providerId,
            ApiProviderMetadataKeys.AUTHENTICATION to metadata.authentication.name,
            ApiProviderMetadataKeys.CAPABILITIES to metadata.capabilities.joinToString(",") { it.name }
        )
        metadata.endpoint?.let { result[ApiProviderMetadataKeys.ENDPOINT] = it }
        metadata.defaultModel?.let { result[ApiProviderMetadataKeys.MODEL] = it }
        if (providerId == GeminiLiveAdapter.PROVIDER_ID &&
            existing.none { it.providerId == GeminiLiveAdapter.PROVIDER_ID }
        ) {
            result[ApiProviderMetadataKeys.PRIMARY] = "true"
        }
        return result
    }
}

/** Keys used inside [ApiProviderRecord.metadata]; intentionally string-based for extensibility. */
object ApiProviderMetadataKeys {
    const val PROVIDER_ID = "providerId"
    const val ENDPOINT = "endpoint"
    const val MODEL = "model"
    const val CAPABILITIES = "capabilities"
    const val AUTHENTICATION = "authentication"
    const val PRIMARY = "primary"
    const val MIGRATED_FROM = "migratedFrom"
}
