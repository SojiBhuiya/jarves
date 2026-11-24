package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Guards the exact payload sent to Gemini Live: the Design 4 tool architecture (a `search_web`
 * function, never the built-in `googleSearch` tool), the injected date/time context and the
 * honesty rules.
 */
class LiveSessionConfigTest {

    private val snapshot = TimeContext.snapshot(Instant.parse("2026-11-09T10:15:00Z"), ZoneId.of("Europe/Paris"))

    private fun setup(searchTool: LiveSearchTool): Map<*, *> {
        val json = LiveSessionConfig.setupJson(
            model = "gemini-3.8-live",
            voiceName = "Aoede",
            systemInstruction = LiveSessionConfig.systemInstruction("Jarves", snapshot, searchTool),
            searchTool = searchTool
        )
        return MiniJson.parse(json) as Map<*, *>
    }

    private fun setup(searchEnabled: Boolean): Map<*, *> = setup(
        if (searchEnabled) LiveSearchTool.FUNCTION_NON_BLOCKING else LiveSearchTool.NONE
    )

    @Test
    fun declaresSearchFunctionAndVideoFunctionsAndNeverGoogleSearch() {
        val setup = setup(true)["setup"] as Map<*, *>
        val tools = setup["tools"] as List<*>
        assertEquals("exactly one tool entry", 1, tools.size)
        val tool = tools.single() as Map<*, *>

        // Design 4: the only tool is functions the app answers itself.
        assertEquals(setOf("functionDeclarations"), tool.keys)
        assertFalse("the built-in Live search tool must never be declared", tool.containsKey("googleSearch"))
        assertNull(tool["googleSearch"])
        assertNull(tool["functionCalling"])

        // 0.2.20: search_web plus the three video functions, in one declarations list.
        val declarations = (tool["functionDeclarations"] as List<*>).map { it as Map<*, *> }
        assertEquals(
            listOf(
                LiveSessionConfig.FUNCTION_SEARCH_WEB,
                LiveSessionConfig.FUNCTION_SEARCH_VIDEOS,
                LiveSessionConfig.FUNCTION_PLAY_VIDEO,
                LiveSessionConfig.FUNCTION_CONTROL_PLAYBACK
            ),
            declarations.map { it["name"] }
        )
        val declaration = declarations.single { it["name"] == LiveSessionConfig.FUNCTION_SEARCH_WEB }
        assertEquals("search_web", declaration["name"])
        assertEquals("NON_BLOCKING", declaration["behavior"])

        // The parameter schema the app parses back out of `args`.
        val parameters = declaration["parameters"] as Map<*, *>
        assertEquals("OBJECT", parameters["type"])
        assertEquals(listOf("query"), parameters["required"])
        val query = (parameters["properties"] as Map<*, *>)["query"] as Map<*, *>
        assertEquals("STRING", query["type"])
        assertTrue((query["description"] as String).isNotBlank())

        // The video functions carry the arguments the runner parses.
        val searchVideos = declarations.single { it["name"] == LiveSessionConfig.FUNCTION_SEARCH_VIDEOS }
        assertEquals(
            listOf("query"),
            ((searchVideos["parameters"] as Map<*, *>)["required"] as List<*>)
        )
        val playVideo = declarations.single { it["name"] == LiveSessionConfig.FUNCTION_PLAY_VIDEO }
        assertEquals(
            listOf("videoId"),
            ((playVideo["parameters"] as Map<*, *>)["required"] as List<*>)
        )
        val control = declarations.single { it["name"] == LiveSessionConfig.FUNCTION_CONTROL_PLAYBACK }
        assertEquals(
            listOf("action"),
            ((control["parameters"] as Map<*, *>)["required"] as List<*>)
        )
    }

    @Test
    fun theBlockingFallbackKeepsTheFunctionButDropsTheBehaviorField() {
        val blocking = setup(LiveSearchTool.FUNCTION_BLOCKING)["setup"] as Map<*, *>
        val tool = (blocking["tools"] as List<*>).single() as Map<*, *>
        assertFalse(tool.containsKey("googleSearch"))
        val declarations = (tool["functionDeclarations"] as List<*>).map { it as Map<*, *> }
        assertEquals(4, declarations.size)
        val declaration = declarations.single { it["name"] == "search_web" }
        // No `behavior` key at all: the server then applies its blocking default.
        assertFalse("a blocking declaration must not carry behavior", declaration.containsKey("behavior"))
        declarations.forEach {
            assertFalse("no blocking declaration carries behavior", it.containsKey("behavior"))
        }
        // Same schema, so a retry changes nothing the model depends on.
        assertEquals(
            LiveSessionConfig.searchFunctionDeclaration(nonBlocking = true)["parameters"],
            declaration["parameters"]
        )
    }

    @Test
    fun onlyTheBuiltInModeDeclaresGoogleSearchAndTheFunctionModesNeverDo() {
        // 0.2.6 adds a fourth tool value, and the rule it must obey is exact:
        //  * GOOGLE_SEARCH_BUILT_IN sends `googleSearch` and no function,
        //  * the two function modes send `search_web` and no `googleSearch` (Design 4), because a
        //    session that declares the Live grounding tool is refused wholesale when the project
        //    has no Live grounding entitlement,
        //  * NONE sends no tools at all.
        LiveSearchTool.entries.forEach { mode ->
            val setup = setup(mode)["setup"] as Map<*, *>
            val encoded = MiniJson.encode(setup)
            when (mode) {
                LiveSearchTool.NONE -> {
                    assertNull("$mode must send no tools at all", setup["tools"])
                    assertFalse(encoded.contains("googleSearch"))
                }
                LiveSearchTool.GOOGLE_SEARCH_BUILT_IN -> {
                    assertTrue("$mode must declare googleSearch", encoded.contains("googleSearch"))
                    assertFalse("$mode must not declare a function", encoded.contains("functionDeclarations"))
                }
                LiveSearchTool.FUNCTION_NON_BLOCKING,
                LiveSearchTool.FUNCTION_BLOCKING -> {
                    assertNotNull("$mode must declare a function", setup["tools"])
                    assertFalse("$mode must not contain googleSearch", encoded.contains("googleSearch"))
                }
            }
        }
    }

    @Test
    fun payloadKeepsTheExistingModelVoiceAndAudioModality() {
        val setup = setup(true)["setup"] as Map<*, *>
        assertEquals("models/gemini-3.8-live", setup["model"])
        val generationConfig = setup["generationConfig"] as Map<*, *>
        assertEquals(listOf("AUDIO"), generationConfig["responseModalities"])
        val voice = ((generationConfig["speechConfig"] as Map<*, *>)["voiceConfig"] as Map<*, *>)["prebuiltVoiceConfig"] as Map<*, *>
        assertEquals("Aoede", voice["voiceName"])
        assertTrue(setup.containsKey("inputAudioTranscription"))
        assertTrue(setup.containsKey("outputAudioTranscription"))
    }

    @Test
    fun instructionCarriesTheCurrentDateAndDhakaTime() {
        val setup = setup(true)["setup"] as Map<*, *>
        val parts = (setup["systemInstruction"] as Map<*, *>)["parts"] as List<*>
        val text = (parts.single() as Map<*, *>)["text"] as String

        assertTrue(text.contains("9 November 2026"))
        assertTrue(text.contains("Asia/Dhaka"))
        assertTrue(text.contains("16:15"))              // Dhaka time for 10:15Z
        assertTrue(text.contains("Never state a date"))
        assertTrue(text.contains("Jarves"))
    }

    @Test
    fun searchInstructionRequiresCheckingFirstAndSayingSo() {
        val text = LiveSessionConfig.systemInstruction("Jarves", snapshot, LiveSearchTool.FUNCTION_NON_BLOCKING)
        assertTrue(LiveSessionConfig.searchEnabled(text))
        // 0.2.7: the function is named once, and only for current/time-sensitive facts.
        assertTrue(text.contains("`search_web` only for current or time-sensitive facts", ignoreCase = true))
        // The user must hear that a check is happening, not silence and not a guess.
        assertTrue(text.contains("দেখে নিচ্ছি"))
        assertTrue(text.contains("Do not guess before the result arrives", ignoreCase = true))
        assertTrue(text.contains("most recent sources", ignoreCase = true))
        assertTrue(text.contains("name the source you used", ignoreCase = true))
        // ...and small talk / arithmetic / definitions must be excluded in so many words.
        assertTrue(text.contains("Never call it for small talk, arithmetic or definitions", ignoreCase = true))
    }

    @Test
    fun instructionWithoutTheFunctionIsHonestAboutBeingUnableToVerify() {
        val text = LiveSessionConfig.systemInstruction("Jarves", snapshot, LiveSearchTool.NONE)
        assertFalse(LiveSessionConfig.searchEnabled(text))
        assertTrue(text.contains("Web search is NOT available", ignoreCase = true))
        assertTrue(text.contains("cannot verify live information", ignoreCase = true))
    }

    @Test
    fun withoutTheFunctionNoToolsKeyIsSent() {
        val setup = setup(false)["setup"] as Map<*, *>
        assertNull(setup["tools"])
    }

    @Test
    fun fallbackPayloadDiffersOnlyInToolsAndInstruction() {
        val grounded = setup(true)["setup"] as Map<*, *>
        val fallback = setup(false)["setup"] as Map<*, *>

        // The recovery must change nothing else: same model, same voice, same modalities, same
        // transcription flags.
        assertEquals(
            grounded.keys - setOf("tools", "systemInstruction"),
            fallback.keys - setOf("tools", "systemInstruction")
        )
        assertEquals(grounded["model"], fallback["model"])
        assertEquals(grounded["generationConfig"], fallback["generationConfig"])
        assertEquals(grounded["inputAudioTranscription"], fallback["inputAudioTranscription"])
        assertEquals(grounded["outputAudioTranscription"], fallback["outputAudioTranscription"])
        assertNotNull(grounded["tools"])
        assertNull(fallback["tools"])
    }

    // ---- 0.2.19: the re-verify rule ------------------------------------------------------

    @Test
    fun verifyRuleRequiresAFreshSearchInsteadOfAnAnswerFromMemory() {
        // The 0.2.18 phone report: "শিওর?" about a dollar rate got "আমি এই তথ্যটি এখন যাচাই
        // করতে পারছি না" and then a vague "technical problem" — both from memory, no re-check.
        val text = LiveSessionConfig.systemInstruction("Jarves", snapshot, LiveSearchTool.FUNCTION_NON_BLOCKING)
        assertTrue(text.contains(LiveSessionConfig.MARK_VERIFY_RULE))
        assertTrue("the Bengali challenge must be named", text.contains("শিওর?"))
        assertTrue("the English challenge must be named", text.contains("are you sure?"))
        assertTrue("the re-check must name the original question", text.contains("ORIGINAL question"))
        assertTrue("the re-check must be a fresh search", text.contains("forceRefresh: true"))
        assertTrue("the user must hear a re-check is happening", text.contains("আবার দেখে নিচ্ছি"))
        assertTrue("no answer from memory", text.contains("Never answer from memory"))
        assertTrue("no vague technical excuse", text.contains("technical problem"))
    }

    @Test
    fun searchWebDeclaresTheForceRefreshFlagButDoesNotRequireIt() {
        val declaration = LiveSessionConfig.searchFunctionDeclaration(nonBlocking = true)
        val parameters = declaration["parameters"] as Map<*, *>
        // Only the query is required: a normal search must not have to say anything about refresh.
        assertEquals(listOf("query"), parameters["required"])
        val properties = parameters["properties"] as Map<*, *>
        val forceRefresh = properties["forceRefresh"] as Map<*, *>
        assertEquals("BOOLEAN", forceRefresh["type"])
        assertTrue((forceRefresh["description"] as String).contains("verify", ignoreCase = true))
    }

    @Test
    fun builtInSearchModeAlsoReVerifiesInsteadOfAnsweringFromMemory() {
        val text = LiveSessionConfig.systemInstruction("Jarves", snapshot, LiveSearchTool.GOOGLE_SEARCH_BUILT_IN)
        assertTrue(text.contains("verify your previous answer", ignoreCase = true))
        assertTrue(text.contains("search again instead of answering from memory", ignoreCase = true))
    }

    // ---- 0.2.12 (B1): injected text must finish the turn ---------------------------------

    @Test
    fun injectedTextCompletesTheTurnSoTheServerActuallyGenerates() {
        // API reference (https://ai.google.dev/api/live): "If true, indicates that the server
        // content generation should start with the currently accumulated prompt. Otherwise, the
        // server awaits additional messages before starting generation."
        val json = LiveSessionConfig.turnCompletingClientContentJson(
            GroundingPolicy.fallbackNotice(groundingAvailable = true)
        )
        val clientContent = (MiniJson.parse(json) as Map<*, *>)["clientContent"] as Map<*, *>
        assertEquals("the injected text must start a generation", true, clientContent["turnComplete"])
        assertEquals(setOf("role", "parts"), ((clientContent["turns"] as List<*>).single() as Map<*, *>).keys)
    }

    @Test
    fun bothInjectedMessagesUseTheTurnCompletingHelper() {
        // The two paths that inject text into a running session: the "could not verify" notice and
        // the date/time refresh. Both are built through the same helper, so neither can silently
        // fall back to turnComplete = false again.
        val notice = LiveSessionConfig.turnCompletingClientContentJson(
            GroundingPolicy.fallbackNotice(groundingAvailable = false)
        )
        val timeSync = LiveSessionConfig.turnCompletingClientContentJson(
            TimeContext.refreshMessage(snapshot)
        )
        for (json in listOf(notice, timeSync)) {
            val clientContent = (MiniJson.parse(json) as Map<*, *>)["clientContent"] as Map<*, *>
            assertEquals(true, clientContent["turnComplete"])
            val text = (((clientContent["turns"] as List<*>).single() as Map<*, *>)["parts"] as List<*>)
                .single().let { (it as Map<*, *>)["text"] as String }
            assertTrue(text.isNotBlank())
        }
    }

    // ---- 0.2.12 (D1): transcription language hints ---------------------------------------

    @Test
    fun inputTranscriptionCarriesBengaliAndEnglishHints() {
        // API reference, AudioTranscriptionConfig (https://ai.google.dev/api/live):
        // "`languageCodes[]` — Optional. BCP-47 language codes providing hints about the languages
        //  present in the audio. If omitted or empty, defaults to automatic language detection."
        val setup = setup(true)["setup"] as Map<*, *>
        val transcription = setup["inputAudioTranscription"] as Map<*, *>
        assertEquals(listOf("bn-BD", "en-US"), transcription["languageCodes"])
        assertEquals(LiveSessionConfig.TRANSCRIPTION_LANGUAGE_CODES, transcription["languageCodes"])
        assertEquals(setOf("languageCodes"), transcription.keys)
    }

    @Test
    fun outputTranscriptionStaysUntouchedBecauseOnlyTheInstructionControlsSpeech() {
        val setup = setup(true)["setup"] as Map<*, *>
        // No languageCodes here: the docs offer no field that changes the *spoken* language, so the
        // LANGUAGE RULE in the system instruction remains the only lever. Adding a hint here would
        // only claim something the app cannot prove.
        assertEquals(emptyMap<String, Any?>(), setup["outputAudioTranscription"])
    }

    @Test
    fun theFallbackToNoSearchKeepsTheSameTranscriptionHints() {
        val grounded = setup(true)["setup"] as Map<*, *>
        val fallback = setup(false)["setup"] as Map<*, *>
        assertEquals(grounded["inputAudioTranscription"], fallback["inputAudioTranscription"])
    }

    // ---- 0.2.12 (D2): exactly one "say it first" rule ------------------------------------

    @Test
    fun thereIsExactlyOneRuleThatAsksForTheSpokenAcknowledgement() {
        val text = LiveSessionConfig.systemInstruction(
            "Jarves", snapshot, LiveSearchTool.FUNCTION_NON_BLOCKING
        )
        assertEquals("one acknowledgement rule, not two", 1, countOf(text, "say exactly one short line"))
        assertFalse(
            "the second, contradictory 'Before calling it, say one short line' rule is gone",
            text.contains("Before calling it, say one short line")
        )
        // ...and the wording the phone asked for is still offered.
        assertTrue(text.contains("দেখছি"))
        assertTrue(text.contains("দেখে নিচ্ছি"))
    }

    @Test
    fun theSearchInstructionIsShorterThanTheTwoRuleVersion() {
        val text = LiveSessionConfig.systemInstruction(
            "Jarves", snapshot, LiveSearchTool.FUNCTION_NON_BLOCKING
        )
        // The duplicate paragraph was ~230 characters of the instruction; the one-rule version must
        // stay below a ceiling so it cannot grow back by accident. 0.2.20 raised the ceiling
        // from 3,000 to 3,300: the VIDEOS rule adds ~280 characters and the instruction is
        // ~3,220. The guard still catches accidental growth.
        assertTrue("instruction length ${text.length}", text.length < 3_300)
    }

    @Test
    fun videoRulePresentOnlyInFunctionModes() {
        LiveSearchTool.entries.forEach { mode ->
            val text = LiveSessionConfig.systemInstruction("Jarves", snapshot, mode)
            if (mode.declaresFunction) {
                assertTrue("$mode must carry the video rule", text.contains(LiveSessionConfig.MARK_VIDEO_RULE))
                assertTrue("$mode must name search_videos", text.contains(LiveSessionConfig.FUNCTION_SEARCH_VIDEOS))
                assertTrue("$mode must name play_video", text.contains(LiveSessionConfig.FUNCTION_PLAY_VIDEO))
            } else {
                assertFalse("$mode must not name an undeclared video function", text.contains(LiveSessionConfig.FUNCTION_SEARCH_VIDEOS))
            }
        }
    }

    private fun countOf(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun clientContentMessagesAreWellFormed() {
        val json = LiveSessionConfig.clientContentJson(TimeContext.refreshMessage(snapshot))
        val root = MiniJson.parse(json) as Map<*, *>
        val clientContent = root["clientContent"] as Map<*, *>
        assertNotNull(clientContent)
        assertEquals(false, clientContent["turnComplete"])
        val turn = (clientContent["turns"] as List<*>).single() as Map<*, *>
        assertEquals("user", turn["role"])
        assertNotNull(turn["parts"])
    }
}

