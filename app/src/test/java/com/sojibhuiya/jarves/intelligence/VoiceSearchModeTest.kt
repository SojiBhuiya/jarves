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
 * 0.2.7: the three voice/search modes, their Live model IDs, and the exact setup frames they send.
 *
 * The frames are asserted as parsed JSON, not as string prefixes, so a field that moves position
 * or gains a sibling cannot accidentally pass.
 */
class VoiceSearchModeTest {

    private fun instruction(mode: VoiceSearchMode): String =
        LiveSessionConfig.systemInstruction(
            assistantName = "Jarves",
            time = TimeContext.snapshot(),
            searchTool = mode.searchTool
        )

    @Suppress("UNCHECKED_CAST")
    private fun setupOf(mode: VoiceSearchMode): Map<String, Any?> {
        val raw = LiveSessionConfig.setupJson(
            model = LiveModels.modelFor(mode),
            voiceName = LiveModels.VOICE_NAME,
            systemInstruction = instruction(mode),
            searchTool = mode.searchTool
        )
        val root = MiniJson.parse(raw) as Map<String, Any?>
        return root["setup"] as Map<String, Any?>
    }

    // ---- one place for the model IDs -----------------------------------------------

    @Test
    fun everyLiveModelIdLivesInOnePlace() {
        assertEquals("gemini-2.5-flash-native-audio-preview-12-2025", LiveModels.SEARCH_NATIVE_AUDIO)
        assertEquals("gemini-3.8-live", LiveModels.VOICE)
        assertEquals(2, LiveModels.ALL.size)
        LiveModels.ALL.forEach { assertTrue(LiveModels.isKnown(it)) }
        assertFalse(LiveModels.isKnown("gemini-2.0-flash-live-001"))
        // The engine's compatibility constant must not drift from the real one.
        assertEquals(LiveModels.VOICE, GeminiLiveVoice.MODEL)
    }

    @Test
    fun eachModeMapsToItsOwnModelAndTool() {
        assertEquals(LiveModels.VOICE, VoiceSearchMode.AUTO.model)
        assertEquals(LiveModels.VOICE, VoiceSearchMode.VOICE_ONLY.model)
        assertEquals(LiveModels.SEARCH_NATIVE_AUDIO, VoiceSearchMode.SEARCH_ONLY.model)

        assertTrue("Auto calls search_web", VoiceSearchMode.AUTO.usesFunctionSearch)
        assertTrue("Auto searches in a second session", VoiceSearchMode.AUTO.usesSecondSearchSession)
        assertFalse("Auto never uses built-in search in the 3.8 session", VoiceSearchMode.AUTO.usesBuiltInSearch)

        assertFalse(VoiceSearchMode.VOICE_ONLY.usesFunctionSearch)
        assertFalse(VoiceSearchMode.VOICE_ONLY.usesBuiltInSearch)
        assertFalse(VoiceSearchMode.VOICE_ONLY.usesSecondSearchSession)

        assertTrue(VoiceSearchMode.SEARCH_ONLY.usesBuiltInSearch)
        assertFalse(VoiceSearchMode.SEARCH_ONLY.usesFunctionSearch)
        assertFalse(VoiceSearchMode.SEARCH_ONLY.usesSecondSearchSession)

        assertEquals(VoiceSearchMode.AUTO, VoiceSearchMode.DEFAULT)
    }

    // ---- Auto: 3.8 voice + search_web answered elsewhere ---------------------------

    @Test
    fun autoSetupDeclaresSearchWebWithNonBlockingBehaviourAndNoBuiltInSearch() {
        val setup = setupOf(VoiceSearchMode.AUTO)

        assertEquals("models/gemini-3.8-live", setup["model"])

        val tools = setup["tools"] as? List<*>
        assertNotNull("Auto must declare the search_web function", tools)
        assertEquals(1, tools!!.size)
        val tool = tools[0] as Map<*, *>
        assertFalse("no built-in googleSearch on 3.8", tool.containsKey("googleSearch"))

        val declarations = tool["functionDeclarations"] as? List<*>
        assertNotNull("functionDeclarations must be present", declarations)
        val declaration = declarations!![0] as Map<*, *>
        assertEquals(LiveSessionConfig.FUNCTION_SEARCH_WEB, declaration["name"])
        assertEquals("NON_BLOCKING", declaration["behavior"])
        assertNotNull("the function needs parameters", declaration["parameters"])
    }

    @Test
    fun autoInstructionIsShortAndNamesTheTriggerPhrase() {
        val text = instruction(VoiceSearchMode.AUTO)

        assertTrue(text.contains("weather"))
        assertTrue(text.contains("দেখে নিচ্ছি"))
        assertTrue(text.contains(LiveSessionConfig.FUNCTION_SEARCH_WEB))
        assertTrue("it must forbid searching for small talk", text.contains("small talk"))
        assertFalse(text.contains("Web search is NOT available"))
    }

    // ---- Search only: the old Mode A (built-in Google Search, nothing else) --------

    @Test
    fun searchOnlySetupDeclaresGoogleSearchAndNoFunctionTools() {
        val setup = setupOf(VoiceSearchMode.SEARCH_ONLY)

        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", setup["model"])

        val tools = setup["tools"] as? List<*>
        assertNotNull("Search only must declare a tool", tools)
        assertEquals(1, tools!!.size)
        val tool = tools[0] as Map<*, *>
        assertTrue("googleSearch must be present", tool.containsKey("googleSearch"))
        assertFalse("no functionDeclarations in Search only", tool.containsKey("functionDeclarations"))
        assertEquals(emptyMap<String, Any?>(), tool["googleSearch"])

        val encoded = MiniJson.encode(setup)
        assertFalse(encoded.contains("functionDeclarations"))
        assertFalse(encoded.contains("search_web"))
    }

    @Test
    fun searchOnlyInstructionTellsTheModelToUseGoogleSearch() {
        val text = instruction(VoiceSearchMode.SEARCH_ONLY)

        assertTrue(text.contains("Google Search"))
        assertTrue(text.contains("today's date"))
        assertFalse("no search_web function in Search only", text.contains("search_web"))
        assertFalse("no 'checking' protocol in Search only", text.contains("দেখে নিচ্ছি"))
        assertFalse(text.contains("Web search is NOT available"))
    }

    @Test
    fun searchOnlyKeepsBothTranscriptionsAndAudioModality() {
        val setup = setupOf(VoiceSearchMode.SEARCH_ONLY)

        assertTrue("input transcription must stay on", setup.containsKey("inputAudioTranscription"))
        assertTrue("output transcription must stay on", setup.containsKey("outputAudioTranscription"))
        val gen = setup["generationConfig"] as Map<*, *>
        assertEquals(listOf("AUDIO"), gen["responseModalities"])
        // Native-audio models only support AUDIO (Live capabilities guide, "Response modalities").
        val speech = gen["speechConfig"] as Map<*, *>
        val voice = ((speech["voiceConfig"] as Map<*, *>)["prebuiltVoiceConfig"] as Map<*, *>)["voiceName"]
        assertEquals(LiveModels.VOICE_NAME, voice)
        assertFalse(MiniJson.encode(setup).contains("languageCode"))
    }

    // ---- Voice only ----------------------------------------------------------------

    @Test
    fun voiceOnlySetupHasNoToolsAtAll() {
        val setup = setupOf(VoiceSearchMode.VOICE_ONLY)

        assertEquals("models/gemini-3.8-live", setup["model"])
        assertNull("Voice only declares no tools", setup["tools"])
        val encoded = MiniJson.encode(setup)
        assertFalse(encoded.contains("googleSearch"))
        assertFalse(encoded.contains("functionDeclarations"))
        assertTrue(encoded.contains("inputAudioTranscription"))
        assertTrue(encoded.contains("outputAudioTranscription"))
    }

    @Test
    fun voiceOnlySaysPlainlyThatItCannotVerifyLiveFacts() {
        assertTrue(instruction(VoiceSearchMode.VOICE_ONLY).contains("Web search is NOT available"))
    }

    // ---- the second search session (Auto) -----------------------------------------

    @Test
    fun autoSearchSessionSetupIsAudioTranscribedAndSearchOnly() {
        val raw = LiveSessionConfig.searchSessionSetupJson(
            model = LiveModels.SEARCH_NATIVE_AUDIO,
            voiceName = LiveModels.VOICE_NAME
        )
        val setup = (MiniJson.parse(raw) as Map<*, *>)["setup"] as Map<*, *>

        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", setup["model"])
        // Native-audio models only support AUDIO — the text comes from the transcription.
        val gen = setup["generationConfig"] as Map<*, *>
        assertEquals(listOf("AUDIO"), gen["responseModalities"])
        assertTrue(setup.containsKey("outputAudioTranscription"))

        val tools = setup["tools"] as List<*>
        assertEquals(1, tools.size)
        val tool = tools[0] as Map<*, *>
        assertTrue(tool.containsKey("googleSearch"))
        assertFalse("the search session declares no functions", tool.containsKey("functionDeclarations"))
        val encoded = MiniJson.encode(setup)
        assertFalse(encoded.contains("functionDeclarations"))
        assertFalse(encoded.contains("search_web"))
        assertNotNull("the search session needs its own short instruction", setup["systemInstruction"])
    }

    // ---- the remembered choice ----------------------------------------------------

    @Test
    fun theModeDefaultsToAutoAndSurvivesARestart() {
        val store = InMemoryVoiceModeStore()
        assertEquals(VoiceSearchMode.AUTO, VoiceSearchModeSettings(store).current())

        VoiceSearchModeSettings(store).save(VoiceSearchMode.VOICE_ONLY)
        assertEquals(VoiceSearchMode.VOICE_ONLY, VoiceSearchModeSettings(store).current())
    }

    @Test
    fun anUnknownStoredValueFallsBackToAutoAndOldSearchKeepsWorking() {
        assertEquals(VoiceSearchMode.AUTO, VoiceSearchModeSettings(InMemoryVoiceModeStore("garbage")).current())
        assertEquals(VoiceSearchMode.AUTO, VoiceSearchModeSettings(InMemoryVoiceModeStore(null)).current())
        assertEquals(
            VoiceSearchMode.VOICE_ONLY,
            VoiceSearchModeSettings(InMemoryVoiceModeStore("voice_only")).current()
        )
        // 0.2.6 stored "SEARCH" for the built-in-search mode; that choice must survive as SEARCH_ONLY.
        assertEquals(
            VoiceSearchMode.SEARCH_ONLY,
            VoiceSearchModeSettings(InMemoryVoiceModeStore("SEARCH")).current()
        )
    }

    // ---- close codes -------------------------------------------------------------

    @Test
    fun actionableCloseCodesOfferVoiceOnlyWithTheExactCodeAndReason() {
        listOf(1007, 1008, 1011).forEach { code ->
            val report = SessionEndReport(
                code = code,
                reason = "setup rejected by server",
                mode = VoiceSearchMode.SEARCH_ONLY,
                model = LiveModels.SEARCH_NATIVE_AUDIO,
                setupTools = "googleSearch(built-in)"
            )
            assertTrue("$code should suggest Voice only", report.suggestVoiceOnly)
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
    fun aNormalEndOrAFailureInVoiceOnlyDoesNotPushTheUserToAnotherMode() {
        val normalEnd = SessionEndReport(
            1000, "bye", VoiceSearchMode.SEARCH_ONLY, LiveModels.SEARCH_NATIVE_AUDIO, "googleSearch(built-in)"
        )
        assertFalse(normalEnd.suggestVoiceOnly)

        val voiceOnlyFailure = SessionEndReport(
            1011, "server error", VoiceSearchMode.VOICE_ONLY, LiveModels.VOICE, "none"
        )
        assertFalse(voiceOnlyFailure.suggestVoiceOnly)
        assertFalse(voiceOnlyFailure.message().contains("Voice and search mode"))
    }

    // ---- diagnostics -------------------------------------------------------------

    @Test
    fun theDiagnosticsLineCarriesModeModelToolsAndClose() {
        val line = GeminiLiveVoice.diagnosticsFor(
            mode = VoiceSearchMode.SEARCH_ONLY,
            setupTool = LiveSearchTool.GOOGLE_SEARCH_BUILT_IN,
            closeCode = 1008,
            closeReason = "policy violation"
        )
        assertTrue(line.contains("Mode: SEARCH_ONLY"))
        assertTrue(line.contains("Live model: ${LiveModels.SEARCH_NATIVE_AUDIO}"))
        assertTrue(line.contains("Tools: googleSearch(built-in)"))
        assertTrue(line.contains("Close: 1008 / policy violation"))

        val healthy = GeminiLiveVoice.diagnosticsFor(
            mode = VoiceSearchMode.VOICE_ONLY,
            setupTool = LiveSearchTool.NONE,
            closeCode = 0,
            closeReason = "none"
        )
        assertTrue(healthy.contains("Live model: ${LiveModels.VOICE}"))
        assertTrue(healthy.contains("Tools: none"))
        assertTrue(healthy.contains("Close: none"))
    }
}
