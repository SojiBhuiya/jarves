package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.8 — the strict language rule and the mic-sensitivity (VAD) settings.
 *
 * Docs: https://ai.google.dev/gemini-api/docs/live-guide#supported-languages (native audio models
 * switch language on their own; the system instruction restricts them, Bengali = `bn`) and
 * https://ai.google.dev/gemini-api/docs/live-guide#automatic-vad-configuration
 * (start/end sensitivity, prefixPaddingMs, silenceDurationMs, all supported on every model here).
 */
class LanguageAndMicTest {

    private val snapshot = TimeContext.snapshot()

    private fun instruction(tool: LiveSearchTool) =
        LiveSessionConfig.systemInstruction("Jarves", snapshot, tool)

    @Suppress("UNCHECKED_CAST")
    private fun setup(mode: VoiceSearchMode, mic: MicSensitivity = MicSensitivity.DEFAULT): Map<*, *> {
        val raw = LiveSessionConfig.setupJson(
            model = mode.model,
            voiceName = LiveModels.VOICE_NAME,
            systemInstruction = instruction(mode.searchTool),
            searchTool = mode.searchTool,
            micSensitivity = mic
        )
        return (MiniJson.parse(raw) as Map<*, *>)["setup"] as Map<*, *>
    }

    // ---- the language rule is in every mode -----------------------------------------

    @Test
    fun everyModeCarriesTheStrictLanguageRule() {
        listOf(
            VoiceSearchMode.AUTO,
            VoiceSearchMode.VOICE_ONLY,
            VoiceSearchMode.SEARCH_ONLY
        ).forEach { mode ->
            val text = instruction(mode.searchTool)
            assertTrue("${mode.name} must carry the rule", text.contains(LiveSessionConfig.MARK_LANGUAGE_RULE))
            assertTrue(text.contains("Speak ONLY Bengali"))
            assertTrue("English is allowed only when the user speaks English", text.contains("English only when the user"))
            assertTrue("background noise must not switch language", text.contains("background noise"))
            assertTrue("must stay silent on unclear audio", text.contains("stay silent"))
            assertTrue("asks once, in Bengali", text.contains("একটু আবার বলবেন?"))
        }
    }

    @Test
    fun theRuleNeverAsksTheModelToGuessInAnotherLanguage() {
        val text = instruction(LiveSearchTool.FUNCTION_NON_BLOCKING)
        assertFalse(text.contains("in the language the user uses"))
        assertTrue(text.contains("NEVER switch language"))
    }

    @Test
    fun noSpeechLanguageCodeIsEverSent() {
        // The spoken language is restricted through the instruction only: the docs offer no config
        // field that changes it, so none may appear (in particular not inside speechConfig).
        listOf(VoiceSearchMode.AUTO, VoiceSearchMode.VOICE_ONLY, VoiceSearchMode.SEARCH_ONLY).forEach { mode ->
            val setupMap = setup(mode)
            val speechConfig = (setupMap["generationConfig"] as Map<*, *>)["speechConfig"] as Map<*, *>
            assertFalse("speechConfig must not carry a language code", speechConfig.containsKey("languageCode"))
            assertFalse(
                "no top-level languageCode either",
                setupMap.containsKey("languageCode") || setupMap.containsKey("languageCodes")
            )
        }
        assertFalse(
            LiveSessionConfig.searchSessionSetupJson(LiveModels.SEARCH_NATIVE_AUDIO, LiveModels.VOICE_NAME)
                .contains("languageCode")
        )
    }

    @Test
    fun theOnlyLanguageHintIsTheDocumentedTranscriptionHint() {
        // 0.2.12 (D1): AudioTranscriptionConfig.languageCodes is the one documented language field
        // (https://ai.google.dev/api/live#AudioTranscriptionConfig) and it steers the *transcription*
        // only. It must be a hint list, Bengali first, and never a forced single code.
        val setupMap = setup(VoiceSearchMode.AUTO)
        val transcription = setupMap["inputAudioTranscription"] as Map<*, *>
        assertEquals(listOf("bn-BD", "en-US"), transcription["languageCodes"])
        assertEquals(setOf("languageCodes"), transcription.keys)
        // The output side stays untouched, so nothing claims to control the spoken language.
        assertEquals(emptyMap<String, Any?>(), setupMap["outputAudioTranscription"])
    }

    // ---- mic sensitivity -------------------------------------------------------------

    @Test
    fun lowIsTheDefaultAndSendsTheDocumentedConservativeValues() {
        assertEquals(MicSensitivity.LOW, MicSensitivity.DEFAULT)

        val config = (setup(VoiceSearchMode.AUTO)[RealtimeKey] as Map<*, *>)
        val vad = config["automaticActivityDetection"] as Map<*, *>
        assertEquals(false, vad["disabled"])
        assertEquals("START_SENSITIVITY_LOW", vad["startOfSpeechSensitivity"])
        assertEquals("END_SENSITIVITY_LOW", vad["endOfSpeechSensitivity"])
        assertEquals(100, (vad["prefixPaddingMs"] as Number).toInt())
        assertEquals("500–800 ms is the documented useful range", 800, (vad["silenceDurationMs"] as Number).toInt())
    }

    @Test
    fun normalSendsNoRealtimeInputConfigAtAll() {
        assertNull(MicSensitivity.NORMAL.realtimeInputConfigJson())
        assertFalse("NORMAL must leave the server defaults alone", setup(VoiceSearchMode.AUTO, MicSensitivity.NORMAL).containsKey(RealtimeKey))
    }

    @Test
    fun highIsMoreEagerButStaysInsideTheDocumentedRange() {
        val vad = ((setup(VoiceSearchMode.VOICE_ONLY, MicSensitivity.HIGH)[RealtimeKey] as Map<*, *>)
            ["automaticActivityDetection"]) as Map<*, *>

        assertEquals("START_SENSITIVITY_HIGH", vad["startOfSpeechSensitivity"])
        assertEquals("END_SENSITIVITY_HIGH", vad["endOfSpeechSensitivity"])
        assertEquals(20, (vad["prefixPaddingMs"] as Number).toInt())
        assertEquals(500, (vad["silenceDurationMs"] as Number).toInt())
        assertTrue("silence must stay in the documented 500–800 ms band", (vad["silenceDurationMs"] as Number).toInt() in 500..800)
    }

    @Test
    fun everyChoiceOnlyUsesDocumentedEnumValuesAndFieldNames() {
        val documentedSensitivities = setOf(
            "START_SENSITIVITY_LOW", "START_SENSITIVITY_HIGH",
            "END_SENSITIVITY_LOW", "END_SENSITIVITY_HIGH"
        )
        val documentedFields = setOf(
            "disabled", "startOfSpeechSensitivity", "endOfSpeechSensitivity",
            "prefixPaddingMs", "silenceDurationMs"
        )
        MicSensitivity.entries.forEach { choice ->
            val config = choice.realtimeInputConfigJson() ?: return@forEach
            assertEquals("only automaticActivityDetection may be sent", setOf("automaticActivityDetection"), config.keys)
            val vad = config["automaticActivityDetection"] as Map<*, *>
            assertTrue("undocumented field in $choice", documentedFields.containsAll(vad.keys))
            assertTrue(vad["startOfSpeechSensitivity"] in documentedSensitivities)
            assertTrue(vad["endOfSpeechSensitivity"] in documentedSensitivities)
        }
    }

    @Test
    fun bothModelsSupportAutomaticVadAndUnknownModelsDoNot() {
        assertTrue(MicVadSupport.supportsAutomaticVad(LiveModels.VOICE))
        assertTrue(MicVadSupport.supportsAutomaticVad(LiveModels.SEARCH_NATIVE_AUDIO))
        assertFalse("nothing unverified may receive a VAD config", MicVadSupport.supportsAutomaticVad("gemini-9.9-live"))
        assertFalse(MicVadSupport.shouldSend("gemini-9.9-live", MicSensitivity.LOW))
        assertFalse("NORMAL never sends anything", MicVadSupport.shouldSend(LiveModels.VOICE, MicSensitivity.NORMAL))
        assertTrue(MicVadSupport.shouldSend(LiveModels.VOICE, MicSensitivity.LOW))
    }

    @Test
    fun theSearchOnlyAndVoiceOnlySetupsAlsoCarryTheMicChoice() {
        listOf(VoiceSearchMode.VOICE_ONLY, VoiceSearchMode.SEARCH_ONLY).forEach { mode ->
            assertTrue(
                "${mode.name} must carry the mic choice",
                setup(mode, MicSensitivity.LOW).containsKey(RealtimeKey)
            )
            assertFalse(
                "${mode.name} must send nothing for NORMAL",
                setup(mode, MicSensitivity.NORMAL).containsKey(RealtimeKey)
            )
        }
    }

    @Test
    fun theMicChoiceSurvivesARestartAndFallsBackToLow() {
        val store = InMemoryMicSensitivityStore()
        assertEquals(MicSensitivity.LOW, MicSensitivitySettings(store).current())

        MicSensitivitySettings(store).save(MicSensitivity.HIGH)
        assertEquals(MicSensitivity.HIGH, MicSensitivitySettings(store).current())

        assertEquals(MicSensitivity.LOW, MicSensitivitySettings(InMemoryMicSensitivityStore("junk")).current())
        assertEquals(MicSensitivity.NORMAL, MicSensitivitySettings(InMemoryMicSensitivityStore("normal")).current())
    }

    @Test
    fun theDiagnosticsLabelIsHumanReadableAndNeverEmpty() {
        MicSensitivity.entries.forEach {
            assertNotNull(it.label)
            assertTrue(it.label.isNotBlank())
            assertTrue("needs a Bengali explanation", it.detail.any { c -> c.code in 0x0980..0x09FF })
        }
    }

    private companion object {
        const val RealtimeKey = "realtimeInputConfig"
    }
}
