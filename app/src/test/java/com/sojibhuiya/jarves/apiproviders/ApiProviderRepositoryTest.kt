package com.sojibhuiya.jarves.apiproviders

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end behaviour of the provider store: add / save / reload / edit / type change /
 * key replacement / delete / enable / disable / counts / duplicate names / validation /
 * encryption at rest.
 */
class ApiProviderRepositoryTest {

    private val storage = FakeApiProviderStorage()
    private val repository = testRepository(storage)

    private fun success(result: ApiProviderResult): ApiProviderResult.Success {
        if (result !is ApiProviderResult.Success) {
            throw AssertionError("Expected Success but was $result")
        }
        return result
    }

    private fun addGemini() = success(
        runBlocking { repository.add("Gemini Live", "gemini-key-1234", ApiCategory.VOICE) }
    ).changed!!

    /** The record produced by the last operation, regardless of how many records exist. */
    private fun changed(result: ApiProviderResult): ApiProviderRecord = success(result).changed!!

    @Test
    fun addApiPersistsRecordWithMaskedKey() = runBlocking {
        val result = success(repository.add("Gemini Live", "gemini-key-1234", ApiCategory.VOICE))
        assertEquals(1, result.records.size)
        val record = result.records.single()
        assertEquals("Gemini Live", record.name)
        assertEquals(ApiCategory.VOICE, record.category)
        assertTrue(record.enabled)
        assertEquals("••••••••••••1234", record.maskedApiKey())
        assertEquals(GeminiLiveAdapter.PROVIDER_ID, record.providerId)
        assertEquals(IntegrationStatus.INTEGRATED, repository.integrationStatus(record))
        assertTrue(record.createdAt > 0 && record.updatedAt > 0)
    }

    @Test
    fun savedApiIsAvailableAfterRestart() = runBlocking {
        val first = addGemini()
        // A fresh repository over the same storage == app restart.
        val restarted = testRepository(storage)
        val reloaded = success(restarted.load()).records
        assertEquals(1, reloaded.size)
        assertEquals(first.id, reloaded.single().id)
        assertEquals("Gemini Live", reloaded.single().name)
        val decrypted = restarted.decryptApiKey(reloaded.single().id)
        assertTrue(decrypted is CredentialResult.Success)
        assertEquals("gemini-key-1234", (decrypted as CredentialResult.Success).apiKey)
    }

    @Test
    fun editApiNameKeepsCredential() = runBlocking {
        val id = addGemini().id
        val updated = success(repository.update(id, "My Gemini", ApiCategory.VOICE, null))
        assertEquals("My Gemini", updated.records.single().name)
        val decrypted = repository.decryptApiKey(id)
        assertEquals("gemini-key-1234", (decrypted as CredentialResult.Success).apiKey)
        // Renaming away from a known provider keeps the record but drops integration.
        assertEquals(null, updated.records.single().providerId)
        assertEquals(IntegrationStatus.NOT_INTEGRATED, repository.integrationStatus(updated.records.single()))
    }

    @Test
    fun changeApiTypeToGeneralAi() = runBlocking {
        val id = addGemini().id
        val updated = success(repository.update(id, "Gemini Live", ApiCategory.GENERAL_AI, null))
        val record = updated.records.single()
        assertEquals(ApiCategory.GENERAL_AI, record.category)
        // A General AI record is never treated as the voice integration.
        assertNull(record.providerId)
        assertEquals(IntegrationStatus.NOT_INTEGRATED, repository.integrationStatus(record))
        assertNull(repository.resolveGeminiLiveCredential() as? CredentialResult.Success)
    }

    @Test
    fun replaceApiKey() = runBlocking {
        val id = addGemini().id
        val before = success(repository.load()).records.single().encryptedApiKey
        val updated = success(repository.update(id, "Gemini Live", ApiCategory.VOICE, "brand-new-key-9999"))
        val after = updated.records.single()
        assertEquals("••••••••••••9999", after.maskedApiKey())
        assertFalse(before == after.encryptedApiKey)
        assertEquals(
            "brand-new-key-9999",
            (repository.decryptApiKey(id) as CredentialResult.Success).apiKey
        )
    }

    @Test
    fun blankReplacementKeyKeepsExistingCredential() = runBlocking {
        val id = addGemini().id
        val before = success(repository.load()).records.single().encryptedApiKey
        val updated = success(repository.update(id, "Gemini Live", ApiCategory.VOICE, "   "))
        assertEquals(before, updated.records.single().encryptedApiKey)
        assertEquals("gemini-key-1234", (repository.decryptApiKey(id) as CredentialResult.Success).apiKey)
    }

    @Test
    fun deleteApiRemovesRecordAndKey() = runBlocking {
        val id = addGemini().id
        val afterDelete = success(repository.delete(id))
        assertEquals(0, afterDelete.records.size)
        assertEquals(0, success(repository.load()).records.size)
        assertTrue(repository.decryptApiKey(id) is CredentialResult.Failure)
        // The ciphertext is gone from storage as well.
        assertFalse(storage.value.orEmpty().contains(id))
    }

    @Test
    fun disableApiKeepsInformation() = runBlocking {
        val id = addGemini().id
        val disabled = success(repository.setEnabled(id, false))
        val record = disabled.records.single()
        assertFalse(record.enabled)
        assertTrue(record.hasCredential)
        assertEquals("••••••••••••1234", record.maskedApiKey())
        assertEquals("gemini-key-1234", (repository.decryptApiKey(id) as CredentialResult.Success).apiKey)
    }

    @Test
    fun enableApiAgain() = runBlocking {
        val id = addGemini().id
        repository.setEnabled(id, false)
        val enabled = success(repository.setEnabled(id, true))
        assertTrue(enabled.records.single().enabled)
    }

    @Test
    fun apiCountTracksEveryOperation() = runBlocking {
        assertEquals(0, success(repository.load()).records.size)
        repository.add("One", "1111222233334444", ApiCategory.VOICE)
        repository.add("Two", "5555666677778888", ApiCategory.GENERAL_AI)
        assertEquals(2, success(repository.load()).records.size)
        val id = success(repository.load()).records.first().id
        repository.delete(id)
        assertEquals(1, success(repository.load()).records.size)
    }

    @Test
    fun duplicateNamesCreateSeparateRecords() = runBlocking {
        val first = changed(repository.add("Gemini Live", "first-key-0001", ApiCategory.VOICE))
        val second = changed(repository.add("Gemini Live", "second-key-0002", ApiCategory.VOICE))
        assertNotNull(first.id)
        assertNotNull(second.id)
        assertFalse(first.id == second.id)
        val records = success(repository.load()).records
        assertEquals(2, records.size)
        assertEquals(2, records.map { it.id }.distinct().size)
        // Each keeps its own credential.
        assertEquals("first-key-0001", (repository.decryptApiKey(first.id) as CredentialResult.Success).apiKey)
        assertEquals("second-key-0002", (repository.decryptApiKey(second.id) as CredentialResult.Success).apiKey)
    }

    @Test
    fun duplicateNamesUpdateOnlyTheTargetRecord() = runBlocking {
        val first = changed(repository.add("Gemini Live", "first-key-0001", ApiCategory.VOICE))
        val second = changed(repository.add("Gemini Live", "second-key-0002", ApiCategory.VOICE))
        val updated = success(repository.update(second.id, "Renamed", ApiCategory.GENERAL_AI, null))
        val records = updated.records.sortedBy { it.createdAt }
        assertEquals(2, records.size)
        assertEquals("Gemini Live", records.first { it.id == first.id }.name)
        assertEquals("Renamed", records.first { it.id == second.id }.name)
        assertEquals("first-key-0001", (repository.decryptApiKey(first.id) as CredentialResult.Success).apiKey)
    }

    @Test
    fun emptyNameIsRejected() = runBlocking {
        val result = repository.add("   ", "some-key-12345", ApiCategory.VOICE)
        assertTrue(result is ApiProviderResult.ValidationFailed)
        assertEquals(ApiProviderField.NAME, (result as ApiProviderResult.ValidationFailed).field)
        assertEquals(0, success(repository.load()).records.size)
        assertNull(storage.value)
    }

    @Test
    fun tooLongNameIsRejected() = runBlocking {
        val result = repository.add("n".repeat(ApiProviderLimits.MAX_NAME_LENGTH + 1), "key-12345", ApiCategory.VOICE)
        assertTrue(result is ApiProviderResult.ValidationFailed)
    }

    @Test
    fun emptyKeyIsRejected() = runBlocking {
        val result = repository.add("Gemini Live", "   ", ApiCategory.VOICE)
        assertTrue(result is ApiProviderResult.ValidationFailed)
        assertEquals(ApiProviderField.API_KEY, (result as ApiProviderResult.ValidationFailed).field)
        assertEquals(0, success(repository.load()).records.size)
    }

    @Test
    fun keyWithInnerSpacesIsRejected() = runBlocking {
        val result = repository.add("Gemini Live", "key with spaces", ApiCategory.VOICE)
        assertTrue(result is ApiProviderResult.ValidationFailed)
    }

    @Test
    fun categoryIsRequiredAndAlwaysPresent() = runBlocking {
        // ApiCategory is a non-null parameter: a record can never exist without a valid type.
        val voice = changed(repository.add("Voice one", "voice-key-1111", ApiCategory.VOICE))
        val general = changed(repository.add("Text one", "text-key-2222", ApiCategory.GENERAL_AI))
        assertEquals(ApiCategory.VOICE, voice.category)
        assertEquals(ApiCategory.GENERAL_AI, general.category)
    }

    @Test
    fun apiKeyIsNeverStoredInPlaintext() = runBlocking {
        addGemini()
        val stored = storage.value.orEmpty()
        assertFalse("plaintext key leaked into storage", stored.contains("gemini-key-1234"))
        assertTrue(stored.contains("\"encryptedKey\""))
        assertTrue(stored.contains("••••••••••••1234") || stored.contains("1234"))
    }

    @Test
    fun generalAiProviderIsNotTreatedAsVoice() = runBlocking {
        val record = success(
            repository.add("My Custom AI", "custom-key-5678", ApiCategory.GENERAL_AI)
        ).records.single()
        assertNull(record.providerId)
        assertEquals(IntegrationStatus.NOT_INTEGRATED, repository.integrationStatus(record))
        // No Gemini credential is resolvable from a General AI record.
        assertTrue(repository.resolveGeminiLiveCredential() is CredentialResult.Failure)
        // …and the key is still usable by a future adapter.
        assertEquals("custom-key-5678", (repository.decryptApiKey(record.id) as CredentialResult.Success).apiKey)
    }

    @Test
    fun geminiNamedGeneralAiRecordDoesNotHijackVoiceIntegration() = runBlocking {
        val record = success(repository.add("Gemini", "text-only-key-7777", ApiCategory.GENERAL_AI)).records.single()
        assertNull(record.providerId)
        assertEquals(IntegrationStatus.NOT_INTEGRATED, repository.integrationStatus(record))
        assertTrue(repository.resolveGeminiLiveCredential() is CredentialResult.Failure)
    }

    @Test
    fun geminiLiveCredentialResolutionPrefersPrimaryAndSkipsDisabled() = runBlocking {
        val primary = changed(repository.add("Gemini Live", "primary-key-0001", ApiCategory.VOICE))
        val secondary = changed(repository.add("Gemini Live", "secondary-key-0002", ApiCategory.VOICE))
        assertEquals("true", primary.metadata[ApiProviderMetadataKeys.PRIMARY])
        assertNull(secondary.metadata[ApiProviderMetadataKeys.PRIMARY])
        assertEquals("primary-key-0001", (repository.resolveGeminiLiveCredential() as CredentialResult.Success).apiKey)

        repository.setEnabled(primary.id, false)
        assertEquals("secondary-key-0002", (repository.resolveGeminiLiveCredential() as CredentialResult.Success).apiKey)

        repository.setEnabled(secondary.id, false)
        assertTrue(repository.resolveGeminiLiveCredential() is CredentialResult.Failure)
    }

    @Test
    fun storedProviderMetadataIsExtensible() = runBlocking {
        val record = addGemini()
        assertEquals(GeminiLiveAdapter.PROVIDER_ID, record.metadataValue(ApiProviderMetadataKeys.PROVIDER_ID))
        assertEquals(
            com.sojibhuiya.jarves.GeminiLiveVoice.WEBSOCKET_ENDPOINT,
            record.metadataValue(ApiProviderMetadataKeys.ENDPOINT)
        )
        assertEquals(
            "models/" + com.sojibhuiya.jarves.GeminiLiveVoice.MODEL,
            record.metadataValue(ApiProviderMetadataKeys.MODEL)
        )
        assertEquals(AuthenticationMethod.API_KEY_QUERY_PARAMETER.name, record.metadataValue(ApiProviderMetadataKeys.AUTHENTICATION))
        assertEquals(
            ProviderCapability.VOICE_CONVERSATION.name,
            record.metadataValue(ApiProviderMetadataKeys.CAPABILITIES)
        )
    }

    @Test
    fun corruptStoredDocumentIsReportedAndNotOverwritten() = runBlocking {
        storage.value = "{ this is not json"
        val result = repository.load()
        assertTrue(result is ApiProviderResult.Failed)
        assertEquals("{ this is not json", storage.value)
        // Mutations must not blow away unreadable data either.
        assertTrue(repository.add("X", "key-12345678", ApiCategory.VOICE) is ApiProviderResult.Failed)
        assertEquals("{ this is not json", storage.value)
    }

    @Test
    fun corruptCredentialIsHandledSafely() = runBlocking {
        val id = addGemini().id
        // Simulate a Keystore reset / corrupted ciphertext.
        storage.value = storage.value!!.replace("\"encryptedKey\":\"", "\"encryptedKey\":\"corrupt")
        val decrypted = repository.decryptApiKey(id)
        assertTrue(decrypted is CredentialResult.Failure)
        assertTrue((decrypted as CredentialResult.Failure).message.isNotBlank())
        // The record itself is still listed.
        assertEquals(1, success(repository.load()).records.size)
    }

    @Test
    fun recordWithoutCredentialIsNeverClaimedAsUsable() = runBlocking {
        val repositoryWithEmpty = testRepository(storage)
        storage.value = ApiProviderCodec.encode(
            listOf(
                ApiProviderRecord(
                    id = "x",
                    name = "Gemini Live",
                    category = ApiCategory.VOICE,
                    encryptedApiKey = "",
                    keyHint = "",
                    enabled = true,
                    providerId = GeminiLiveAdapter.PROVIDER_ID,
                    metadata = emptyMap(),
                    createdAt = 1,
                    updatedAt = 1
                )
            )
        )
        val record = success(repositoryWithEmpty.load()).records.single()
        assertFalse(record.hasCredential)
        assertTrue(repositoryWithEmpty.resolveGeminiLiveCredential() is CredentialResult.Failure)
    }

    @Test
    fun updateAndDeleteOfUnknownIdFailGracefully() = runBlocking {
        assertTrue(repository.update("missing", "Name", ApiCategory.VOICE, null) is ApiProviderResult.Failed)
        assertTrue(repository.delete("missing") is ApiProviderResult.Failed)
        assertTrue(repository.setEnabled("missing", true) is ApiProviderResult.Failed)
    }

    @Test
    fun updatesBumpUpdatedAtOnly() = runBlocking {
        val created = addGemini()
        val updated = success(repository.update(created.id, "Gemini Live 2", ApiCategory.VOICE, null)).records.single()
        assertEquals(created.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt >= created.updatedAt)
        assertEquals(created.id, updated.id)
    }
}
