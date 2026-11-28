package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.GeminiLiveVoice
import com.sojibhuiya.jarves.apiproviders.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.28: the Settings voice picker — Aoede (current, breezy) stays the default,
 * and the three soft female voices Sojeeb asked for (Achernar, Vindemiatrix,
 * Sulafat) can be picked, persist, and reach both Live sessions.
 */
class LiveVoiceTest {

    @Suppress("UNCHECKED_CAST")
    private fun voiceNameInSetupJson(raw: String): String {
        val root = MiniJson.parse(raw) as Map<String, Any?>
        val setup = root["setup"] as Map<String, Any?>
        val generationConfig = setup["generationConfig"] as Map<String, Any?>
        val speechConfig = generationConfig["speechConfig"] as Map<String, Any?>
        val voiceConfig = speechConfig["voiceConfig"] as Map<String, Any?>
        val prebuilt = voiceConfig["prebuiltVoiceConfig"] as Map<String, Any?>
        return prebuilt["voiceName"] as String
    }

    @Test
    fun `default voice is Aoede everywhere`() {
        assertEquals(LiveVoice.AOEDE, LiveVoice.DEFAULT)
        assertEquals("Aoede", LiveVoice.DEFAULT.voiceName)
        // The old single-source constants must not drift from the default.
        assertEquals("Aoede", LiveModels.VOICE_NAME)
        assertEquals("Aoede", GeminiLiveVoice.VOICE_NAME)
        assertEquals(LiveModels.VOICE_NAME, LiveVoice.DEFAULT.voiceName)
    }

    @Test
    fun `four voices offered and each has its own name`() {
        assertEquals(4, LiveVoice.entries.size)
        assertEquals(
            listOf("Aoede", "Achernar", "Vindemiatrix", "Sulafat"),
            LiveVoice.entries.map { it.voiceName }
        )
    }

    @Test
    fun `unknown or blank stored voice falls back to Aoede`() {
        assertEquals(LiveVoice.AOEDE, LiveVoice.fromStored(null))
        assertEquals(LiveVoice.AOEDE, LiveVoice.fromStored(""))
        assertEquals(LiveVoice.AOEDE, LiveVoice.fromStored("   "))
        assertEquals(LiveVoice.AOEDE, LiveVoice.fromStored("NotARealVoice"))
        assertEquals(LiveVoice.AOEDE, LiveVoiceSettings().current())
    }

    @Test
    fun `each voice name resolves including case differences`() {
        assertEquals(LiveVoice.ACHERNAR, LiveVoice.fromStored("Achernar"))
        assertEquals(LiveVoice.VINDEMIATRIX, LiveVoice.fromStored("Vindemiatrix"))
        assertEquals(LiveVoice.SULAFAT, LiveVoice.fromStored("sulafat"))
        assertEquals(LiveVoice.AOEDE, LiveVoice.fromStored("aoede"))
    }

    @Test
    fun `choice persists and survives a new settings instance`() {
        val store = InMemoryLiveVoiceStore()
        val settings = LiveVoiceSettings(store)
        assertEquals(LiveVoice.AOEDE, settings.current())
        settings.save(LiveVoice.SULAFAT)
        assertEquals(LiveVoice.SULAFAT, settings.current())
        assertEquals(LiveVoice.SULAFAT, LiveVoiceSettings(store).current())
        settings.save(LiveVoice.ACHERNAR)
        assertEquals(LiveVoice.ACHERNAR, LiveVoiceSettings(store).current())
    }

    @Test
    fun `each voice reaches the voice session setup frame`() {
        for (voice in LiveVoice.entries) {
            val raw = LiveSessionConfig.setupJson(
                model = LiveModels.VOICE,
                voiceName = voice.voiceName,
                systemInstruction = "test",
                searchTool = LiveSearchTool.NONE
            )
            assertEquals(voice.voiceName, voiceNameInSetupJson(raw))
        }
    }

    @Test
    fun `each voice reaches the search session setup frame`() {
        for (voice in LiveVoice.entries) {
            val raw = LiveSessionConfig.searchSessionSetupJson(
                LiveModels.SEARCH_NATIVE_AUDIO,
                voice.voiceName
            )
            assertEquals(voice.voiceName, voiceNameInSetupJson(raw))
        }
    }

    @Test
    fun `voice details describe the soft voices`() {
        assertTrue(LiveVoice.ACHERNAR.detail.contains("নরম"))
        assertTrue(LiveVoice.VINDEMIATRIX.detail.contains("কোমল"))
        assertTrue(LiveVoice.SULAFAT.detail.contains("মিষ্টি"))
    }
}
