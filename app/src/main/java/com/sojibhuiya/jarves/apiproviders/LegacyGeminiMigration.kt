package com.sojibhuiya.jarves.apiproviders

/** Old, pre-provider-store Gemini configuration (DataStore Preferences). */
interface LegacyGeminiKeyStore {
    /** Ciphertext stored under the legacy `gemini_key_encrypted` preference. */
    suspend fun legacyEncryptedKey(): String?

    /** Mirrors the legacy `gemini_enabled` preference so the migrated record keeps the state. */
    suspend fun legacyGeminiEnabled(): Boolean

    /** Removes the legacy ciphertext after a verified migration. */
    suspend fun clearLegacyEncryptedKey()
}

sealed interface MigrationResult {
    data object NothingToMigrate : MigrationResult

    /** A Gemini Live record already existed; the legacy value was left untouched. */
    data object SkippedExistingGeminiRecord : MigrationResult

    data class Migrated(val records: List<ApiProviderRecord>, val record: ApiProviderRecord) : MigrationResult

    data class Failed(val message: String, val cause: Throwable? = null) : MigrationResult
}

/**
 * One-time migration of the existing Gemini API key into the provider store.
 *
 * Safety rules implemented here:
 * 1. the legacy ciphertext is decrypted with the legacy Keystore alias,
 * 2. it is re-encrypted with the provider-store cipher and written through the repository,
 * 3. the new record is read back from storage and decrypted; only if the plaintext matches
 *    is the legacy preference removed,
 * 4. an existing Gemini Live record is never duplicated,
 * 5. on any failure the legacy value is kept so the user cannot lose their key.
 */
class LegacyGeminiMigration(
    private val repository: ApiProviderRepository,
    private val legacyCipher: CredentialCipher,
    private val legacyStore: LegacyGeminiKeyStore
) {

    companion object {
        const val MIGRATED_PROVIDER_NAME = GeminiLiveAdapter.DISPLAY_NAME
        const val LEGACY_ALIAS_MARKER = "gemini_key_encrypted_v1"
    }

    suspend fun migrate(): MigrationResult {
        val legacyBlob = try {
            legacyStore.legacyEncryptedKey()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            return MigrationResult.Failed("Existing Gemini key could not be read", e)
        } ?: return MigrationResult.NothingToMigrate

        val existingRecords = when (val loaded = repository.load()) {
            is ApiProviderResult.Success -> loaded.records
            is ApiProviderResult.Failed -> return MigrationResult.Failed(
                "Saved API providers could not be read; migration skipped",
                loaded.cause
            )
            is ApiProviderResult.ValidationFailed -> return MigrationResult.Failed(loaded.message)
        }
        if (existingRecords.any { it.providerId == GeminiLiveAdapter.PROVIDER_ID }) {
            // Never duplicate: an existing Gemini Live entry wins. The legacy value is kept
            // so it can still serve as a fallback and no credential is ever lost.
            return MigrationResult.SkippedExistingGeminiRecord
        }

        val plaintextKey = try {
            legacyCipher.decrypt(legacyBlob)
        } catch (e: CredentialSecurityException) {
            return MigrationResult.Failed(
                "Existing Gemini API key could not be decrypted, so it was not migrated",
                e
            )
        } ?: return MigrationResult.Failed("Existing Gemini API key was empty")

        if (plaintextKey.isBlank()) {
            return MigrationResult.Failed("Existing Gemini API key was empty")
        }

        val legacyEnabled = runCatching { legacyStore.legacyGeminiEnabled() }.getOrDefault(true)
        val added = repository.add(MIGRATED_PROVIDER_NAME, plaintextKey, ApiCategory.VOICE)
        val records = when (added) {
            is ApiProviderResult.Success -> added.records
            is ApiProviderResult.Failed -> return MigrationResult.Failed(
                "Gemini key could not be migrated: ${added.message}",
                added.cause
            )
            is ApiProviderResult.ValidationFailed -> return MigrationResult.Failed(added.message)
        }
        val created = added.changed
            ?: return MigrationResult.Failed("Gemini key could not be migrated")

        // Verify against what was actually persisted before touching the legacy value.
        val reloaded = when (val loaded = repository.load()) {
            is ApiProviderResult.Success -> loaded.records
            is ApiProviderResult.Failed -> null
            is ApiProviderResult.ValidationFailed -> null
        }
        val storedRecord = reloaded?.firstOrNull { it.id == created.id }
        if (storedRecord == null) {
            repository.delete(created.id)
            return MigrationResult.Failed("Migrated Gemini key could not be verified")
        }
        val verified = repository.decryptApiKey(storedRecord.id)
        if (verified !is CredentialResult.Success || verified.apiKey != plaintextKey) {
            repository.delete(created.id)
            return MigrationResult.Failed("Migrated Gemini key could not be verified")
        }

        var finalRecords = reloaded
        var finalRecord = storedRecord
        if (!legacyEnabled) {
            when (val disabled = repository.setEnabled(storedRecord.id, false)) {
                is ApiProviderResult.Success -> {
                    finalRecords = disabled.records
                    finalRecord = disabled.changed ?: storedRecord
                }
                else -> Unit // keep enabled; not a data-loss condition
            }
        }

        val cleared = runCatching {
            legacyStore.clearLegacyEncryptedKey()
            true
        }.getOrDefault(false)
        if (!cleared) {
            // Not fatal: the verified record exists, the stale preference is simply ignored.
            return MigrationResult.Migrated(finalRecords, finalRecord)
        }
        return MigrationResult.Migrated(finalRecords, finalRecord)
    }
}
