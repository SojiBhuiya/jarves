package com.sojibhuiya.jarves.apiproviders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApiProviderCodecTest {

    private val record = ApiProviderRecord(
        id = "id-1",
        name = "Gemini Live 🌐 \"quoted\"",
        category = ApiCategory.VOICE,
        encryptedApiKey = "ZW5jcnlwdGVk/cGVybWl0dGVkKys=",
        keyHint = "1234",
        enabled = true,
        providerId = GeminiLiveAdapter.PROVIDER_ID,
        metadata = mapOf(
            ApiProviderMetadataKeys.ENDPOINT to "wss://example.invalid/ws",
            ApiProviderMetadataKeys.MODEL to "models/test",
            ApiProviderMetadataKeys.CAPABILITIES to "VOICE_CONVERSATION",
            ApiProviderMetadataKeys.PRIMARY to "true"
        ),
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_100_000
    )

    @Test
    fun roundTripsRecords() {
        val decoded = ApiProviderCodec.decode(ApiProviderCodec.encode(listOf(record)))
        assertEquals(listOf(record), decoded)
    }

    @Test
    fun encodesEmptyList() {
        assertEquals(emptyList<ApiProviderRecord>(), ApiProviderCodec.decode(ApiProviderCodec.encode(emptyList())))
    }

    @Test
    fun nullAndBlankDecodeToEmpty() {
        assertEquals(emptyList<ApiProviderRecord>(), ApiProviderCodec.decode(null))
        assertEquals(emptyList<ApiProviderRecord>(), ApiProviderCodec.decode("  "))
    }

    @Test
    fun corruptDocumentThrowsStorageFormatException() {
        try {
            ApiProviderCodec.decode("{\"version\":1,\"providers\":")
            fail("Expected StorageFormatException")
        } catch (e: StorageFormatException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun newerFormatVersionIsRejectedInsteadOfMisread() {
        try {
            ApiProviderCodec.decode("{\"version\":99,\"providers\":[]}")
            fail("Expected StorageFormatException")
        } catch (e: StorageFormatException) {
            assertTrue(e.message!!.contains("newer"))
        }
    }

    @Test
    fun unknownCategoryRecordsAreSkippedWithoutLosingOthers() {
        val valid = ApiProviderCodec.encode(listOf(record))
        val mixed = valid.replaceFirst("{\"providers\":[", "{\"providers\":[{\"id\":\"x\",\"name\":\"y\",\"category\":\"alien\"},")
        val decoded = ApiProviderCodec.decode(mixed)
        assertEquals(listOf(record), decoded)
    }

    @Test
    fun maskingNeverRevealsTheFullKey() {
        assertEquals("••••••••••••1234", ApiProviderMasking.mask("1234"))
        assertEquals("••••••••••••", ApiProviderMasking.mask(""))
        assertEquals("1234", ApiProviderMasking.hintFor("a-very-long-api-key-1234"))
        // Short keys produce no hint at all rather than a mostly-visible secret.
        assertEquals("", ApiProviderMasking.hintFor("short"))
    }
}
