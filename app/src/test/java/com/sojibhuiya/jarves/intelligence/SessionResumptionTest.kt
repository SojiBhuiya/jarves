package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.9 — the phone's "Socket closed" report, and the machinery that must prevent a repeat.
 *
 * Everything here is TESTED-MOCK: the rules are pure logic, so the whole policy is provable without
 * a device or a Gemini key.
 */
class SessionResumptionTest {

    private fun update(json: String) = SessionResumptionState.parseUpdate(json)

    // ---- parsing the two server frames -------------------------------------------------

    @Test
    fun aResumptionUpdateIsParsedWithItsTwoDocumentedFields() {
        val parsed = update("""{"sessionResumptionUpdate":{"newHandle":"h-123","resumable":true}}""")

        assertNotNull(parsed)
        assertEquals("h-123", parsed!!.newHandle)
        assertTrue(parsed.resumable)
    }

    @Test
    fun anEmptyNewHandleMeansTemporarilyNotResumable() {
        // Docs: "New handle … Empty if resumable=false"; this is temporary (e.g. while generating).
        val parsed = update("""{"sessionResumptionUpdate":{"resumable":false}}""")
        assertNotNull(parsed)
        assertNull(parsed!!.newHandle)
        assertFalse(parsed.resumable)
    }

    @Test
    fun unrelatedAndMalformedFramesNeverThrow() {
        listOf(
            """{"serverContent":{}}""",
            """{"sessionResumptionUpdate":{}}""",
            """not json at all""",
            """""",
            """{"sessionResumptionUpdate":"oops"}"""
        ).forEach { frame ->
            runCatching { SessionResumptionState.parseUpdate(frame) }
                .onFailure { fail("parseUpdate threw on: $frame") }
        }
    }

    @Test
    fun goAwayTimeLeftIsAProtobufDurationString() {
        // https://protobuf.dev/reference/protobuf/google.protobuf/#duration — "9.5s"
        val notice = SessionResumptionState.parseGoAway("""{"goAway":{"timeLeft":"9.5s"}}""")
        assertNotNull(notice)
        assertEquals(9_500L, notice!!.timeLeftMillis)
    }

    @Test
    fun goAwayWithoutTimeLeftIsIgnoredInsteadOfGuessed() {
        assertNull(SessionResumptionState.parseGoAway("""{"goAway":{}}"""))
        assertNull(SessionResumptionState.parseGoAway("""{"serverContent":{}}"""))
        assertEquals(0L, SessionResumptionState.parseGoAway("""{"goAway":{"timeLeft":"0s"}}""")!!.timeLeftMillis)
    }

    // ---- keeping the newest usable handle ----------------------------------------------

    @Test
    fun onlyAUsableHandleIsStoredAndUsedForSetup() {
        val state = SessionResumptionState()
        assertNull("nothing to resume yet", state.setupField())

        assertTrue(state.onUpdate(ResumptionUpdate("h-1", resumable = true)))
        assertEquals("h-1", state.handleForReconnect())
        @Suppress("UNCHECKED_CAST")
        val field = state.setupField() as Map<String, Any?>
        assertEquals("h-1", field["handle"])
    }

    @Test
    fun aNotResumableUpdateDoesNotThrowAwayTheLastGoodHandle() {
        // The docs describe resumable=false as "not possible at this point", not "worthless".
        val state = SessionResumptionState()
        state.onUpdate(ResumptionUpdate("h-1", resumable = true))
        assertFalse(state.onUpdate(ResumptionUpdate(null, resumable = false)))

        assertEquals("h-1", state.handle)
        assertFalse("but we must not advertise it as resumable", state.resumable)
        // A reconnect still tries the last good handle rather than starting from zero.
        assertEquals("h-1", state.handleForReconnect())
    }

    @Test
    fun theNewestHandleWins() {
        val state = SessionResumptionState()
        state.onUpdate(ResumptionUpdate("h-1", true))
        state.onUpdate(ResumptionUpdate("h-2", true))
        state.onUpdate(ResumptionUpdate("h-3", true))
        assertEquals("h-3", state.handleForReconnect())
        assertEquals(3, state.updates)
    }

    @Test
    fun theDiagnosticNeverPrintsTheHandleItself() {
        val state = SessionResumptionState()
        state.onUpdate(ResumptionUpdate("super-secret-handle-value", true))
        val line = state.describe()
        assertFalse("the handle must not appear: $line", line.contains("super-secret-handle-value"))
        assertTrue(line.contains("present(25)"))
    }

    @Test
    fun aFreshSessionSendsAnEmptyResumptionObjectSoUpdatesStart() {
        val setup = LiveSessionConfig.setupJson(
            model = LiveModels.VOICE,
            voiceName = LiveModels.VOICE_NAME,
            systemInstruction = "x",
            searchTool = LiveSearchTool.NONE
        )
        val root = MiniJson.parse(setup) as Map<*, *>
        val body = root["setup"] as Map<*, *>
        assertTrue("sessionResumption must be present to enable updates", body.containsKey("sessionResumption"))
        assertFalse("and must carry no handle for a new session", MiniJson.encode(body["sessionResumption"]).contains("handle"))
    }

    @Test
    fun aResumedSessionPutsTheHandleInTheSetupFrame() {
        val setup = LiveSessionConfig.setupJson(
            model = LiveModels.VOICE,
            voiceName = LiveModels.VOICE_NAME,
            systemInstruction = "x",
            searchTool = LiveSearchTool.FUNCTION_NON_BLOCKING,
            sessionResumptionHandle = "h-42"
        )
        val body = (MiniJson.parse(setup) as Map<*, *>)["setup"] as Map<*, *>
        val resumption = body["sessionResumption"] as Map<*, *>
        assertEquals("h-42", resumption["handle"])
        // Documented name check: BidiGenerateContentSetup.sessionResumption.handle
        assertEquals(setOf("handle"), resumption.keys)
    }

    // ---- the reconnect policy ----------------------------------------------------------

    @Test
    fun anUnexpectedFailureReconnectsButAUserStopNeverDoes() {
        assertEquals(
            ReconnectDecision.RECONNECT,
            ReconnectPolicy.decide(true, CloseInitiator.NONE, ReconnectCause.SOCKET_FAILURE, 0)
        )
        assertEquals(
            "the 0.2.8 report looked exactly like this — but if the app closed the socket, it is not a failure",
            ReconnectDecision.NO_RECONNECT_INTENTIONAL,
            ReconnectPolicy.decide(true, CloseInitiator.USER_STOP, ReconnectCause.SOCKET_FAILURE, 0)
        )
        assertEquals(
            ReconnectDecision.NO_RECONNECT_STOPPED,
            ReconnectPolicy.decide(false, CloseInitiator.NONE, ReconnectCause.SOCKET_FAILURE, 0)
        )
    }

    @Test
    fun everyIntentionalCloseBlocksAnAutomaticReconnect() {
        CloseInitiator.INTENTIONAL.forEach { initiator ->
            assertFalse(
                "$initiator must never reconnect",
                ReconnectPolicy.decide(true, initiator, ReconnectCause.SOCKET_FAILURE, 0).automatic
            )
        }
        listOf(CloseInitiator.NONE, CloseInitiator.SERVER_CLOSE_FRAME, CloseInitiator.SETUP_FAILURE)
            .forEach { initiator ->
                assertTrue(
                    "$initiator may reconnect",
                    ReconnectPolicy.decide(true, initiator, ReconnectCause.SOCKET_FAILURE, 0).automatic
                )
            }
    }

    @Test
    fun atMostThreeAttemptsWithBackoffThenAGiveUp() {
        assertEquals(3, ReconnectPolicy.MAX_ATTEMPTS)
        assertEquals(1_000L, ReconnectPolicy.backoffMillis(1))
        assertEquals(2_000L, ReconnectPolicy.backoffMillis(2))
        assertEquals(4_000L, ReconnectPolicy.backoffMillis(3))
        assertEquals("never unbounded", 4_000L, ReconnectPolicy.backoffMillis(9))

        (0 until ReconnectPolicy.MAX_ATTEMPTS).forEach { used ->
            assertTrue(ReconnectPolicy.decide(true, CloseInitiator.NONE, ReconnectCause.SOCKET_FAILURE, used).automatic)
        }
        assertEquals(
            ReconnectDecision.GIVE_UP,
            ReconnectPolicy.decide(true, CloseInitiator.NONE, ReconnectCause.SOCKET_FAILURE, 3)
        )
    }

    @Test
    fun goAwayIsHandledBeforeTheSocketDies() {
        assertEquals(
            ReconnectDecision.RECONNECT_AFTER_GO_AWAY,
            ReconnectPolicy.decide(true, CloseInitiator.NONE, ReconnectCause.GO_AWAY, 0)
        )
        // Half of the server's remaining time, clamped so we neither rush nor wait it out.
        assertEquals(4_500L, ReconnectPolicy.goAwayDelayMillis(9_000))
        assertEquals(500L, ReconnectPolicy.goAwayDelayMillis(200))
        assertEquals(5_000L, ReconnectPolicy.goAwayDelayMillis(60_000))
    }

    @Test
    fun theGiveUpMessageIsBengaliAndSaysWhatToDo() {
        val message = ReconnectPolicy.giveUpMessage(3)
        assertTrue(message.any { it.code in 0x0980..0x09FF })
        assertTrue(message.contains("3"))
    }
}

private fun fail(message: String): Nothing = throw AssertionError(message)
