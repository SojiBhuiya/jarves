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
    fun declaresExactlyOneSearchFunctionAndNeverGoogleSearch() {
        val setup = setup(true)["setup"] as Map<*, *>
        val tools = setup["tools"] as List<*>
        assertEquals("exactly one tool entry", 1, tools.size)
        val tool = tools.single() as Map<*, *>

        // Design 4: the only tool is a function the app answers itself.
        assertEquals(setOf("functionDeclarations"), tool.keys)
        assertFalse("the built-in Live search tool must never be declared", tool.containsKey("googleSearch"))
        assertNull(tool["googleSearch"])
        assertNull(tool["functionCalling"])

        val declaration = (tool["functionDeclarations"] as List<*>).single() as Map<*, *>
        assertEquals(LiveSessionConfig.FUNCTION_SEARCH_WEB, declaration["name"])
        assertEquals("search_web", declaration["name"])
        assertEquals("NON_BLOCKING", declaration["behavior"])

        // The parameter schema the app parses back out of `args`.
        val parameters = declaration["parameters"] as Map<*, *>
        assertEquals("OBJECT", parameters["type"])
        assertEquals(listOf("query"), parameters["required"])
        val query = (parameters["properties"] as Map<*, *>)["query"] as Map<*, *>
        assertEquals("STRING", query["type"])
        assertTrue((query["description"] as String).isNotBlank())
    }

    @Test
    fun theBlockingFallbackKeepsTheFunctionButDropsTheBehaviorField() {
        val blocking = setup(LiveSearchTool.FUNCTION_BLOCKING)["setup"] as Map<*, *>
        val tool = (blocking["tools"] as List<*>).single() as Map<*, *>
        assertFalse(tool.containsKey("googleSearch"))
        val declaration = (tool["functionDeclarations"] as List<*>).single() as Map<*, *>
        assertEquals("search_web", declaration["name"])
        // No `behavior` key at all: the server then applies its blocking default.
        assertFalse("a blocking declaration must not carry behavior", declaration.containsKey("behavior"))
        // Same schema, so a retry changes nothing the model depends on.
        assertEquals(
            LiveSessionConfig.searchFunctionDeclaration(nonBlocking = true)["parameters"],
            declaration["parameters"]
        )
    }

    @Test
    fun everySetupPayloadOmitsGoogleSearchInAllThreeModes() {
        LiveSearchTool.entries.forEach { mode ->
            val setup = setup(mode)["setup"] as Map<*, *>
            val encoded = MiniJson.encode(setup)
            assertFalse("$mode must not contain googleSearch", encoded.contains("googleSearch"))
            if (mode == LiveSearchTool.NONE) {
                assertNull("$mode must send no tools at all", setup["tools"])
            } else {
                assertNotNull("$mode must declare a function", setup["tools"])
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
        assertTrue(text.contains("search_web` first", ignoreCase = true) ||
            text.contains("search first", ignoreCase = true))
        // The user must hear that a check is happening, not silence and not a guess.
        assertTrue(text.contains("একটু দেখে নিচ্ছি"))
        assertTrue(text.contains("Do not guess an answer before the result arrives", ignoreCase = true))
        assertTrue(text.contains("most recent sources", ignoreCase = true))
        assertTrue(text.contains("name the source you used", ignoreCase = true))
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
