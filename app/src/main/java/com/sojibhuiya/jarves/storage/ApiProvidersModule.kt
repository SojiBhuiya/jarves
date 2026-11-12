package com.sojibhuiya.jarves.storage

import android.content.Context
import com.sojibhuiya.jarves.apiproviders.AesGcmCredentialCipher
import com.sojibhuiya.jarves.apiproviders.ApiProviderRepository
import com.sojibhuiya.jarves.apiproviders.CredentialCipher
import com.sojibhuiya.jarves.apiproviders.LegacyGeminiMigration
import com.sojibhuiya.jarves.apiproviders.defaultProviderRegistry

/**
 * Small manual DI container: one repository instance per process so every screen observes the
 * same list, and one migration entry point used on app start.
 */
object ApiProvidersModule {

    @Volatile
    private var repositoryRef: ApiProviderRepository? = null

    @Volatile
    private var migrationRef: LegacyGeminiMigration? = null

    /** Encrypts provider credentials with the provider-store Keystore alias. */
    fun credentialCipher(): CredentialCipher =
        AesGcmCredentialCipher(AndroidKeystoreKeyProvider(KeystoreAliases.API_PROVIDERS))

    /** Decrypts values written by older versions with the legacy Gemini Keystore alias. */
    fun legacyCipher(): CredentialCipher =
        AesGcmCredentialCipher(AndroidKeystoreKeyProvider(KeystoreAliases.LEGACY_GEMINI))

    fun repository(context: Context): ApiProviderRepository {
        repositoryRef?.let { return it }
        return synchronized(this) {
            repositoryRef ?: ApiProviderRepository(
                storage = DataStoreApiProviderStorage(context.applicationContext),
                cipher = credentialCipher(),
                registry = defaultProviderRegistry()
            ).also { repositoryRef = it }
        }
    }

    fun migration(context: Context): LegacyGeminiMigration {
        migrationRef?.let { return it }
        return synchronized(this) {
            migrationRef ?: LegacyGeminiMigration(
                repository = repository(context),
                legacyCipher = legacyCipher(),
                legacyStore = DataStoreLegacyGeminiStore(context.applicationContext)
            ).also { migrationRef = it }
        }
    }
}
