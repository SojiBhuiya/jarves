package com.sojibhuiya.jarves.apiproviders

import com.sojibhuiya.jarves.GeminiLiveVoice
import com.sojibhuiya.jarves.LiveFrameDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the existing, working Gemini Live configuration against accidental changes while the
 * provider layer is added. If any of these values must change, it has to be a deliberate,
 * reviewed decision — not a side effect of the API management feature.
 */
class GeminiLiveCompatibilityTest {

    @Test
    fun liveEndpointIsUnchanged() {
        assertEquals(
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            GeminiLiveVoice.WEBSOCKET_ENDPOINT
        )
    }

    @Test
    fun liveModelAndAudioSettingsAreUnchanged() {
        assertEquals("gemini-3.8-live", GeminiLiveVoice.MODEL)
        assertEquals(16_000, GeminiLiveVoice.INPUT_RATE)
        assertEquals(24_000, GeminiLiveVoice.OUTPUT_RATE)
        assertEquals(640, GeminiLiveVoice.CHUNK_BYTES)
        assertEquals("Aoede", GeminiLiveVoice.VOICE_NAME)
    }

    @Test
    fun adapterPointsAtTheSameEndpointAndModel() {
        val metadata = GeminiLiveAdapter().metadata
        assertEquals(GeminiLiveVoice.WEBSOCKET_ENDPOINT, metadata.endpoint)
        assertEquals("models/" + GeminiLiveVoice.MODEL, metadata.defaultModel)
        assertEquals(AuthenticationMethod.API_KEY_QUERY_PARAMETER, metadata.authentication)
        assertEquals(setOf(ProviderCapability.VOICE_CONVERSATION), metadata.capabilities)
        assertEquals(ApiCategory.VOICE, metadata.defaultCategory)
        assertEquals(IntegrationStatus.INTEGRATED, metadata.integrationStatus())
    }

    @Test
    fun onlyTheShippedProviderIsRegisteredAsIntegrated() {
        val registry = defaultProviderRegistry()
        val integrated = registry.registeredMetadata().filter { it.integrated }
        assertEquals(1, integrated.size)
        assertEquals(GeminiLiveAdapter.PROVIDER_ID, integrated.single().providerId)
        assertTrue(registry.registeredMetadata().all { it.endpoint == null || it.integrated })
    }

    @Test
    fun voiceNameResolutionOnlyMatchesVoiceCategory() {
        val registry = defaultProviderRegistry()
        assertEquals(
            GeminiLiveAdapter.PROVIDER_ID,
            registry.resolveProviderIdForName("Gemini Live", ApiCategory.VOICE)
        )
        assertEquals(
            GeminiLiveAdapter.PROVIDER_ID,
            registry.resolveProviderIdForName("  gemini  ", ApiCategory.VOICE)
        )
        assertNull(registry.resolveProviderIdForName("Gemini Live", ApiCategory.GENERAL_AI))
        assertNull(registry.resolveProviderIdForName("OpenAI", ApiCategory.GENERAL_AI))
        assertNull(registry.resolveProviderIdForName("My Custom API", ApiCategory.VOICE))
    }

    @Test
    fun unknownProviderHasNoInventedEndpoint() {
        val record = ApiProviderRecord(
            id = "1",
            name = "OpenAI",
            category = ApiCategory.GENERAL_AI,
            encryptedApiKey = "ciphertext",
            keyHint = "1234",
            enabled = true,
            providerId = null,
            metadata = emptyMap(),
            createdAt = 1,
            updatedAt = 1
        )
        val registry = defaultProviderRegistry()
        assertEquals(IntegrationStatus.NOT_INTEGRATED, registry.integrationStatusFor(record))
        assertNull(registry.metadataForRecord(record))
        assertEquals("Saved – Not Integrated", IntegrationStatus.NOT_INTEGRATED.label)
    }

    @Test
    fun binaryFrameDecodingIsStillUtf8() {
        val json = """{"setupComplete":{}}"""
        assertEquals(json, LiveFrameDecoder.decode(json.toByteArray(Charsets.UTF_8)))
        assertNotNull(LiveFrameDecoder.decode("{}".toByteArray()))
    }
}
