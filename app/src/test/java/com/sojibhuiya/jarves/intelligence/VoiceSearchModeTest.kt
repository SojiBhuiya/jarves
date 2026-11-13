package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson
import com.sojibhuiya.jarves.GeminiLiveVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.6: the two voice/search modes, their Live model IDs, and the exact setup frames they send.
 *
 * The frames are asserted as parsed JSON, not as string prefixes, so a field that moves position
 * or gains a sibling cannot accidentally pass.
 */
class VoiceSearchModeTest {

    private fun instruction(mode: VoiceSearchMode): String =
        LiveSessionConfig.systemInstruction(
            assistantName = "Jarves",
            time = TimeContext.snapshot(),
            searchTool = if (mode.usesBuiltInSearch) {
                LiveSearchTool.GOOGLE_SEARCH_BUILT_IN
            } else {
                LiveSearchTool.NONE
            }
        )

    @Suppress("UNCHECKED_CAST")
    private fun setupOf(mode: VoiceSearchMode): Map<String, Any?> {
        val raw = LiveSessionConfig.setupJson(
            model = LiveModels.modelFor(mode),
            voiceName = LiveModels.VOICE_NAME,
            systemInstruction = instruction(mode),
            searchTool = if (mode.usesBuiltInSearch) {
                LiveSearchTool.GOOGLE_SEARCH_BUILT_IN
            } else {
                LiveSearchTool.NONE
            }
        )
        val root = MiniJson.parse(raw) as Map<String, Any?>
        return root["setup"] as Map<String, Any?>
    }

    // ---- one place for the model IDs -----------------------------------------------

    @Test
    fun everyLiveModelIdLivesInOnePlace() {
        assertEquals("gemini-2.5-flash-native-audio-preview-12-2025", LiveModels.SEARCH_NATIVE_AUDIO)
        assertEquals("gemini-3.8-live", LiveModels.VOICE_ONLY)
        assertEquals(2, LiveModels.ALL.size)
        LiveModels.ALL.forEach { assertTrue(LiveModels.isKnown(it)) }
        assertFalse(LiveModels.isKnown("gemini-2.0-flash-live-001"))
        // The engine's compatibility constant must not drift from the real one.
        assertEquals(LiveModels.VOICE_ONLY, GeminiLiveVoice.MODEL)
    }

    @Test
    fun eachModeMapsToItsOwnModel() {
        assertEquals(LiveModels.SEARCH_NATIVE_AUDIO, VoiceSearchMode.SEARCH.model)
        assertEquals(LiveModels.VOICE_ONLY, VoiceSearchMode.VOICE_ONLY.model)
        assertTrue(VoiceSearchMode.SEARCH.usesBuiltInSearch)
        assertFalse(VoiceSearchMode.VOICE_ONLY.usesBuiltInSearch)
        assertEquals(VoiceSearchMode.SEARCH, VoiceSearchMode.DEFAULT)
    }

    // ---- Mode A: built-in Google Search, and nothing else --------------------------

    @Test
    fun modeASetupDeclaresGoogleSearchAndNoFunctionTools() {
        val setup = setupOf(VoiceSearchMode.SEARCH)

        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", setup["model"])

        val tools = setup["tools"] as? List<*>
        assertNotNull("Mode A must declare a tool", tools)
        assertEquals(1, tools!!.size)
        val tool = tools[0] as Map<*, *>
        assertTrue("googleSearch must be present", tool.containsKey("googleSearch"))
        assertFalse("no functionDeclarations in Mode A", tool.containsKey("functionDeclarations"))
        assertEquals(emptyMap<String, Any?>(), tool["googleSearch"])

        // And nowhere in the frame may the REST-era function appear.
        val encoded = MiniJson.encode(setup)
        assertFalse(encoded.contains("functionDeclarations"))
        assertFalse(encoded.contains("search_web"))
    }

    @Test
    fun modeAInstructionTellsTheModelToUseGoogleSearch() {
        val text = instruction(VoiceSearchMode.SEARCH)

        assertTrue(text.contains("Google Search"))
        assertTrue(text.contains("today's date"))
        // The function-era protocol must be gone: no search_web, no "let me check" contract.
        assertFalse("no search_web function in Mode A", text.contains("search_web"))
        assertFalse("no 'checking' protocol in Mode A", text.contains("একটু দেখে নিচ্ছি"))
        // ...and it must not tell the model that search is unavailable.
        assertFalse(text.contains("Web search is NOT available"))
    }

    @Test
    fun modeAKeepsBothTranscriptionsAndAudioModality() {
        val setup = setupOf(VoiceSearchMode.SEARCH)

        assertTrue("input transcription must stay on", setup.containsKey("inputAudioTranscription"))
        assertTrue("output transcription must stay on", setup.containsKey("outputAudioTranscription"))
        val gen = setup["generationConfig"] as Map<*, *>
        assertEquals(listOf("AUDIO"), gen["responseModalities"])
        // Native-audio models only support AUDIO (Live capabilities guide, "Response modalities").
        val speech = gen["speechConfig"] as Map<*, *>
        val voice = ((speech["voiceConfig"] as Map<*, *>)["prebuiltVoiceConfig"] as Map<*, *>)["voiceName"]
        assertEquals(LiveModels.VOICE_NAME, voice)
        // No languageCode is ever sent: native-audio models switch language by themselves and the
        // docs restrict the language through the system instruction instead.
        assertFalse(MiniJson.encode(setup).contains("languageCode"))
    }

    // ---- Mode B: exactly the previous behaviour -----------------------------------

    @Test
    fun modeBSetupHasNoToolsAtAll() {
        val setup = setupOf(VoiceSearchMode.VOICE_ONLY)

        assertEquals("models/gemini-3.8-live", setup["model"])
        assertNull("Mode B declares no tools", setup["tools"])
        val encoded = MiniJson.encode(setup)
        assertFalse(encoded.contains("googleSearch"))
        assertFalse(encoded.contains("functionDeclarations"))
        assertTrue(encoded.contains("inputAudioTranscription"))
        assertTrue(encoded.contains("outputAudioTranscription"))
    }

    @Test
    fun modeBSaysPlainlyThatItCannotVerifyLiveFacts() {
        val text = instruction(VoiceSearchMode.VOICE_ONLY)
        assertTrue(text.contains("Web search is NOT available"))
    }

    // ---- the remembered choice ----------------------------------------------------

    @Test
    fun themodeDefaultsToSearchAndSurvivesARestart() {
        val store = InMemoryVoiceModeStore()
        assertEquals(VoiceSearchMode.SEARCH, VoiceSearchModeSettings(store).current())

        VoiceSearchModeSettings(store).save(VoiceSearchMode.VOICE_ONLY)
        // A new instance over the same store is what an app restart looks like.
        assertEquals(VoiceSearchMode.VOICE_ONLY, VoiceSearchModeSettings(store).current())
    }

    @Test
    fun anUnknownStoredValueFallsBackToSearch() {
        assertEquals(VoiceSearchMode.SEARCH, VoiceSearchModeSettings(InMemoryVoiceModeStore("garbage")).current())
        assertEquals(VoiceSearchMode.SEARCH, VoiceSearchModeSettings(InMemoryVoiceModeStore(null)).current())
        assertEquals(VoiceSearchMode.VOICE_ONLY, VoiceSearchModeSettings(InMemoryVoiceModeStore("voice_only")).current())
    }

    // ---- close codes -------------------------------------------------------------

    @Test
    fun actionableCloseCodesOfferModeBWithTheExactCodeAndReason() {
        listOf(1007, 1008, 1011).forEach { code ->
            val report = SessionEndReport(
                code = code,
                reason = "setup rejected by server",
                mode = VoiceSearchMode.SEARCH,
                model = LiveModels.SEARCH_NATIVE_AUDIO,
                setupTools = "googleSearch(built-in)"
            )
            assertTrue("$code should suggest Mode B", report.suggestVoiceOnly)
            val message = report.message()
            assertTrue(message.contains("code=$code"))
            assertTrue(message.contains("setup rejected by server"))
            assertTrue(message.contains(LiveModels.SEARCH_NATIVE_AUDIO))
            assertTrue(message.contains("googleSearch(built-in)"))
            assertTrue("must point at Settings", message.contains("Voice and search mode"))
            assertTrue(message.contains("AIza****"))
            assertFalse("never a real key", message.contains("AIzaS"))
        }
    }

    @Test
    fun aNormalEndOrAFailureInModeBDoesNotPushTheUserToAnotherMode() {
        val normalEnd = SessionEndReport(1000, "bye", VoiceSearchMode.SEARCH, LiveModels.SEARCH_NATIVE_AUDIO, "googleSearch(built-in)")
        assertFalse(normalEnd.suggestVoiceOnly)

        val modeBFailure = SessionEndReport(1011, "server error", VoiceSearchMode.VOICE_ONLY, LiveModels.VOICE_ONLY, "none")
        assertFalse(modeBFailure.suggestVoiceOnly)
        assertFalse(modeBFailure.message().contains("Voice and search mode"))
    }

    // ---- diagnostics -------------------------------------------------------------

    @Test
    fun theDiagnosticsLineCarriesModeModelToolsAndClose() {
        val line = GeminiLiveVoice.diagnosticsFor(
            mode = VoiceSearchMode.SEARCH,
            setupTool = LiveSearchTool.GOOGLE_SEARCH_BUILT_IN,
            closeCode = 1008,
            closeReason = "policy violation"
        )
        assertTrue(line.contains("Mode: SEARCH"))
        assertTrue(line.contains("Live model: ${LiveModels.SEARCH_NATIVE_AUDIO}"))
        assertTrue(line.contains("Tools: googleSearch(built-in)"))
        assertTrue(line.contains("Close: 1008 / policy violation"))

        val healthy = GeminiLiveVoice.diagnosticsFor(
            mode = VoiceSearchMode.VOICE_ONLY,
            setupTool = LiveSearchTool.NONE,
            closeCode = 0,
            closeReason = "none"
        )
        assertTrue(healthy.contains("Live model: ${LiveModels.VOICE_ONLY}"))
        assertTrue(healthy.contains("Tools: none"))
        assertTrue(healthy.contains("Close: none"))
    }
}
