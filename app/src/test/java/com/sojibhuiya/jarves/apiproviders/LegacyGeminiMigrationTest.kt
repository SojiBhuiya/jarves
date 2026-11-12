package com.sojibhuiya.jarves.apiproviders

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeLegacyGeminiStore(
    var encrypted: String?,
    var enabled: Boolean = true
) : LegacyGeminiKeyStore {
    var clearCalls = 0
        private set

    override suspend fun legacyEncryptedKey(): String? = encrypted

    override suspend fun legacyGeminiEnabled(): Boolean = enabled

    override suspend fun clearLegacyEncryptedKey() {
        clearCalls++
        encrypted = null
    }
}

/** Migration of the pre-existing Gemini credential, including every failure path. */
class LegacyGeminiMigrationTest {

    private val storage = FakeApiProviderStorage()

    private fun migration(
        legacyStore: FakeLegacyGeminiStore,
        legacyCipher: CredentialCipher = testCipher(),
        repository: ApiProviderRepository = testRepository(storage)
    ) = Triple(
        LegacyGeminiMigration(repository, legacyCipher, legacyStore),
        repository,
        legacyStore
    )

    @Test
    fun migratesExistingGeminiKeyAndClearsLegacyValue() = runBlocking {
        val legacyPlain = "legacy-gemini-key-4321"
        val legacyStore = FakeLegacyGeminiStore(encrypted = testCipher().encrypt(legacyPlain))
        val (migration, repository, _) = migration(legacyStore)

        val result = migration.migrate()
        assertTrue(result is MigrationResult.Migrated)
        val record = (result as MigrationResult.Migrated).record
        assertEquals("Gemini Live", record.name)
        assertEquals(ApiCategory.VOICE, record.category)
        assertEquals(GeminiLiveAdapter.PROVIDER_ID, record.providerId)
        assertEquals(IntegrationStatus.INTEGRATED, repository.integrationStatus(record))
        assertEquals(legacyPlain, (repository.decryptApiKey(record.id) as CredentialResult.Success).apiKey)
        assertEquals("••••••••••••4321", record.maskedApiKey())
        assertNull(legacyStore.encrypted)
        assertEquals(1, legacyStore.clearCalls)
        assertFalse(storage.value.orEmpty().contains(legacyPlain))
    }

    @Test
    fun secondRunDoesNotDuplicateTheGeminiEntry() = runBlocking {
        val legacyStore = FakeLegacyGeminiStore(encrypted = testCipher().encrypt("legacy-key-0001"))
        val (migration, repository, _) = migration(legacyStore)
        migration.migrate()
        val again = migration.migrate()
        assertTrue(again is MigrationResult.NothingToMigrate)
        assertEquals(1, repository.load().let { (it as ApiProviderResult.Success).records.size })
    }

    @Test
    fun existingGeminiRecordIsNeverDuplicated() = runBlocking {
        val repository = testRepository(storage)
        val existing = repository.add("Gemini Live", "user-key-8888", ApiCategory.VOICE)
        assertTrue(existing is ApiProviderResult.Success)
        val legacyStore = FakeLegacyGeminiStore(encrypted = testCipher().encrypt("legacy-key-0001"))
        val (migration, repo, store) = migration(legacyStore, repository = repository)

        val result = migration.migrate()
        assertTrue(result is MigrationResult.SkippedExistingGeminiRecord)
        val records = (repo.load() as ApiProviderResult.Success).records
        assertEquals(1, records.size)
        assertEquals("user-key-8888", (repo.decryptApiKey(records.single().id) as CredentialResult.Success).apiKey)
        // Legacy value intentionally preserved (no data loss).
        assertNotNull(store.encrypted)
    }

    @Test
    fun legacyValueIsKeptWhenVerificationFails() = runBlocking {
        val legacyStore = FakeLegacyGeminiStore(encrypted = testCipher().encrypt("legacy-key-0001"))
        val lossyStorage = FakeApiProviderStorage().apply { dropWrites = true }
        val repository = testRepository(lossyStorage)
        val migration = LegacyGeminiMigration(repository, testCipher(), legacyStore)

        val result = migration.migrate()
        assertTrue(result is MigrationResult.Failed)
        assertNotNull("legacy credential must not be deleted", legacyStore.encrypted)
        assertEquals(0, legacyStore.clearCalls)
    }

    @Test
    fun legacyValueIsKeptWhenItCannotBeDecrypted() = runBlocking {
        val legacyStore = FakeLegacyGeminiStore(encrypted = testCipher(seed = 42).encrypt("legacy-key-0001"))
        val repository = testRepository(storage)
        val migration = LegacyGeminiMigration(repository, testCipher(seed = 7), legacyStore)

        val result = migration.migrate()
        assertTrue(result is MigrationResult.Failed)
        assertNotNull(legacyStore.encrypted)
        assertEquals(0, (repository.load() as ApiProviderResult.Success).records.size)
    }

    @Test
    fun nothingToMigrateWhenNoLegacyKeyExists() = runBlocking {
        val legacyStore = FakeLegacyGeminiStore(encrypted = null)
        val (migration, _, store) = migration(legacyStore)
        assertTrue(migration.migrate() is MigrationResult.NothingToMigrate)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun disabledLegacySwitchProducesDisabledRecord() = runBlocking {
        val legacyStore = FakeLegacyGeminiStore(encrypted = testCipher().encrypt("legacy-key-0001"), enabled = false)
        val (migration, repository, _) = migration(legacyStore)
        val result = migration.migrate()
        assertTrue(result is MigrationResult.Migrated)
        assertFalse((result as MigrationResult.Migrated).record.enabled)
        assertEquals(1, repository.load().let { (it as ApiProviderResult.Success).records.size })
    }

    @Test
    fun migratedRecordCanImmediatelyStartAGeminiSession() = runBlocking {
        val legacyStore = FakeLegacyGeminiStore(encrypted = testCipher().encrypt("legacy-key-0001"))
        val (migration, repository, _) = migration(legacyStore)
        migration.migrate()
        val credential = repository.resolveGeminiLiveCredential()
        assertTrue(credential is CredentialResult.Success)
        assertEquals("legacy-key-0001", (credential as CredentialResult.Success).apiKey)
    }
}
