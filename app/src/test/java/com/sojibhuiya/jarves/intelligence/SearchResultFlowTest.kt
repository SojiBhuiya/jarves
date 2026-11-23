package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 0.2.11 — the two behaviour bugs from the phone, proved with a scripted stand-in for the 3.8
 * session.
 *
 * **Problem 1** the model stayed silent while searching and only said "দেখছি" together with the
 * result. **Problem 2** a result that arrived while another answer was being spoken cut that
 * answer off (0.2.10 sent short results with `scheduling=INTERRUPT`).
 *
 * This is TESTED-MOCK: the frames, the JSON and the ordering are real app code
 * ([LiveToolProtocol], [SearchToolRunner], [GroundedSearchClient], [PendingResultQueue],
 * [IdleAnnouncementTracker], [SearchResultText]); only the HTTP transport and the model are faked.
 * Whether the real model actually speaks first and actually names the question is NOT-RUN here and
 * can only be proven on a phone.
 */
class SearchResultFlowTest {

    private class GatedTransport(val body: String) : RestTransport {
        @Volatile var release: CountDownLatch = CountDownLatch(1)
        val requests: MutableList<RestRequest> = Collections.synchronizedList(mutableListOf())
        override fun post(request: RestRequest): RestResponse {
            requests += request
            release.await(20, TimeUnit.SECONDS)
            return RestResponse(200, body)
        }
    }

    /**
     * The voice side, with the same control flow as the voice session: the runner reports a result,
     * the result goes into the chat immediately, and it reaches the model only with `WHEN_IDLE` —
     * now if the model is idle, otherwise at the next turn boundary.
     */
    private class VirtualVoice(client: GroundedSearchClient, scope: CoroutineScope) {

        /** Everything the model was handed, in order, as (callId, payload, scheduling). */
        val delivered: MutableList<Triple<String, String, String>> =
            Collections.synchronizedList(mutableListOf())

        /** Everything that reached the chat. */
        val chat: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** What the user heard the model say (the fake model's speech). */
        val spoken: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val timeline = SearchTimeline(40)
        val pending = PendingResultQueue()
        val tracker = IdleAnnouncementTracker()

        @Volatile var generating = false
        @Volatile var newerQuestion = false
        @Volatile var resultWasSent = false
        /** Question order, exactly like the real session's toolCall counter. */
        private val order = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val counter = java.util.concurrent.atomic.AtomicLong(0)
        var reminders = 0
        var lastScheduling: String = ""

        val runner = SearchToolRunner(
            backend = client,
            scope = scope,
            onResult = { call, answer, _ ->
                val item = SearchResultItem(
                    callId = call.callId,
                    question = call.query,
                    answer = if (answer.isSuccess) SearchResultText.limitSentences(answer.text) else "",
                    sourceName = answer.sources.firstOrNull()?.title.orEmpty(),
                    success = answer.isSuccess,
                    askedBeforeResult = newerQuestion,
                    orderIndex = order[call.callId] ?: 0L
                )
                chat += SearchResultText.chatLine(item)
                if (generating) {
                    pending.enqueue(item)
                } else {
                    deliver(item)
                }
            }
        )

        fun deliver(item: SearchResultItem) {
            // Always WHEN_IDLE: the one value documented as "without interrupting ongoing generation".
            val scheduling = LiveToolProtocol.schedulingFor(SearchResultText.toolResponse(item))
            lastScheduling = scheduling
            delivered += Triple(
                item.callId,
                SearchResultText.toolResponse(item),
                scheduling
            )
            resultWasSent = true
            tracker.onResultSent(item.callId, 0L)
        }

        fun onToolCallFrame(frame: String) {
            LiveToolProtocol.parseCancellations(frame).takeIf { it.isNotEmpty() }?.let {
                runner.cancel(it)
                return
            }
            LiveToolProtocol.parseSearchCalls(frame).forEach { call ->
                order.putIfAbsent(call.callId, counter.incrementAndGet())
                runner.handle(call)
            }
        }

        /** A user turn that needs no search, spoken in full, ending with the turn boundary. */
        fun userAsksAnswerableNow(question: String) {
            generating = true
            newerQuestion = true
            spoken += "answered:$question"
            turnComplete()
        }

        /** `serverContent.turnComplete` — the model is idle again. */
        fun turnComplete() {
            generating = false
            if (pending.size > 0) deliver(pending.poll()!!)
        }

        fun interruption() {
            // Barge-in: the model's current generation is dropped, nothing new is announced.
            generating = false
        }

        fun shutdown() = runner.shutdown()
    }

    private fun awaitTrue(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun voice(transport: RestTransport) = VirtualVoice(
        client = GroundedSearchClient(apiKey = "MOCK-key-placeholder", transport = transport),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private fun toolCall(id: String, query: String) = """
        {"toolCall":{"functionCalls":[{"id":"$id","name":"search_web","args":{"query":"$query"}}]}}
    """.trimIndent()

    private val goldBody = """
        {"candidates":[{
          "content":{"role":"model","parts":[{"text":"আজ ২২ ক্যারেট সোনার ভরি প্রায় ১,৯৮,০০০ টাকা। দাম গত সপ্তাহে বেড়েছে।"}]},
          "groundingMetadata":{"groundingChunks":[
            {"web":{"uri":"https://news.example.com/gold","title":"Example News"}}]}
        }]}
    """.trimIndent()

    // ---- problem 2: the result waits its turn ------------------------------------------------

    @Test
    fun aResultThatArrivesDuringAnAnswerWaitsForTheTurnBoundaryAndNeverInterrupts() {
        val transport = GatedTransport(goldBody)
        val session = voice(transport)

        session.onToolCallFrame(toolCall("call-gold", "আজকের সোনার দাম"))
        assertTrue("the search must really run", awaitTrue { transport.requests.size == 1 })
        assertEquals("nothing is delivered while the search is still running", 0, session.delivered.size)

        // The user asks something else; the model answers it while the result is still pending.
        session.generating = true
        session.newerQuestion = true
        session.spoken += "answered:এক যোগ এক কত?"
        assertFalse(session.generating.not())

        // The search finishes mid-answer: it must be queued, not sent.
        transport.release.countDown()
        assertTrue("the result must reach the app", awaitTrue { session.chat.size == 1 })
        Thread.sleep(50)
        assertTrue("nothing may be sent while the model is speaking", session.delivered.isEmpty())
        assertEquals("but it must be waiting in the queue", 1, session.pending.size)

        // The answer finishes; only now is the result handed over.
        session.turnComplete()
        assertEquals(1, session.delivered.size)
        val (id, payload, scheduling) = session.delivered.single()
        assertEquals("call-gold", id)
        assertEquals("WHEN_IDLE", scheduling)
        assertTrue(
            "the payload must name the question that was asked before the newer one: $payload",
            payload.contains("আজকের সোনার দাম")
        )
        assertTrue(
            "and tell the model to name it aloud: $payload",
            payload.contains("জানতে চেয়েছিলেন")
        )
        assertTrue("the source name must travel with the answer", payload.contains("Example News"))
        session.shutdown()
    }

    @Test
    fun twoPendingResultsAreAnnouncedOneAtATimeInOrder() {
        val transport = GatedTransport(goldBody)
        val session = voice(transport)
        session.generating = true

        session.onToolCallFrame(toolCall("call-1", "প্রথম প্রশ্ন"))
        assertTrue(awaitTrue { transport.requests.size == 1 })
        session.onToolCallFrame(toolCall("call-2", "দ্বিতীয় প্রশ্ন"))
        assertTrue(awaitTrue { transport.requests.size == 2 })

        transport.release.countDown()
        assertTrue("both results must reach the app", awaitTrue { session.chat.size == 2 })
        assertTrue("neither may be sent while the model is busy", session.delivered.isEmpty())
        assertEquals(2, session.pending.size)

        // First idle turn -> first result only.
        session.turnComplete()
        assertEquals("one result per idle turn", 1, session.delivered.size)
        // Second idle turn -> the second result.
        session.turnComplete()
        assertEquals(2, session.delivered.size)

        assertEquals(listOf("call-1", "call-2"), session.delivered.map { it.first })
        assertTrue(session.delivered.all { it.third == "WHEN_IDLE" })
        session.shutdown()
    }

    @Test
    fun aResultThatArrivesWhileTheModelIsIdleIsSentImmediatelyWithWhenIdle() {
        val transport = GatedTransport(goldBody)
        transport.release = CountDownLatch(0)
        val session = voice(transport)

        session.onToolCallFrame(toolCall("call-now", "আজকের দাম"))
        assertTrue(awaitTrue { session.delivered.size == 1 })

        val (id, payload, scheduling) = session.delivered.single()
        assertEquals("call-now", id)
        assertEquals("WHEN_IDLE", scheduling)
        assertTrue("no newer question, so no 'you asked earlier' wording", !payload.contains("জানতে চেয়েছিলেন"))
        session.shutdown()
    }

    @Test
    fun aUserInterruptionSendsNothingExtraAndKeepsTheQueueIntact() {
        val transport = GatedTransport(goldBody)
        val session = voice(transport)
        session.generating = true
        session.onToolCallFrame(toolCall("call-x", "কিছু একটা"))
        assertTrue(awaitTrue { transport.requests.size == 1 })
        transport.release.countDown()
        assertTrue(awaitTrue { session.pending.size == 1 })

        session.interruption()
        assertEquals("the barge-in must not trigger a delivery", 0, session.delivered.size)
        session.turnComplete()
        assertEquals("the result is still delivered once the user finishes", 1, session.delivered.size)
        session.shutdown()
    }

    @Test
    fun aCancelledSearchSendsNoResultAtAll() {
        val transport = GatedTransport(goldBody)
        val session = voice(transport)
        session.onToolCallFrame(toolCall("call-cancel", "বাতিল প্রশ্ন"))
        assertTrue(awaitTrue { transport.requests.size == 1 })

        session.onToolCallFrame(
            """{"toolCallCancellation":{"ids":["call-cancel"]}}"""
        )
        transport.release.countDown()
        Thread.sleep(150)
        assertTrue("a cancelled call must not be answered", session.delivered.isEmpty())
        session.shutdown()
    }

    // ---- honesty and the chat safety net ------------------------------------------------------

    @Test
    fun aFailedSearchProducesAShortHonestMessageAndAChatLine() {
        val item = SearchResultItem(
            callId = "c1",
            question = "আজকের সোনার দাম",
            answer = "",
            success = false
        )
        val payload = SearchResultText.toolResponse(item)
        assertTrue(payload.contains("failed"))
        assertTrue(payload.contains("Do not invent facts or links"))
        assertTrue("the question is named even on failure", payload.contains("আজকের সোনার দাম"))

        val chat = SearchResultText.chatLine(item)
        assertTrue(chat.contains("সার্চ করা যায়নি"))
        assertTrue(chat.contains("আজকের সোনার দাম"))
    }

    @Test
    fun everyResultIsInTheChatEvenIfTheVoiceSaysNothing() {
        val success = SearchResultItem(
            callId = "c2",
            question = "আজকের সোনার দাম",
            answer = "প্রায় ১,৯৮,০০০ টাকা।",
            sourceName = "Example News"
        )
        val chat = SearchResultText.chatLine(success)
        assertTrue(chat.contains("প্রশ্ন:"))
        assertTrue(chat.contains("প্রশ্ন: আজকের সোনার দাম"))
        assertTrue(chat.contains("সূত্র: Example News"))
    }

    @Test
    fun theChatLineShowsWhenTheAnswerWasChecked() {
        // 0.2.19: the 0.2.18 phone report showed a bare "১২৪.৩১ টাকা" with no way to tell
        // whether it was checked just now or an hour ago.
        val checked = SearchResultItem(
            callId = "c2",
            question = "এক ডলারের দাম কত?",
            answer = "এক ডলার ১২৪ টাকা ৪০ পয়সা।",
            sourceName = "Example News",
            receivedAtMillis = java.time.Instant.parse("2026-11-23T06:42:00Z").toEpochMilli()
        )
        val chat = SearchResultText.chatLine(checked)
        assertTrue("the check time must be shown", chat.contains("যাচাই:"))
        assertTrue(
            "HH:MM in the device zone",
            Regex("যাচাই: \\d{2}:\\d{2}").containsMatchIn(chat)
        )

        val unchecked = SearchResultItem(
            callId = "c3",
            question = "এক ডলারের দাম কত?",
            answer = "এক ডলার ১২৪ টাকা ৪০ পয়সা।",
            sourceName = "Example News"
        )
        assertFalse(
            "no invented time when nothing was recorded",
            SearchResultText.chatLine(unchecked).contains("যাচাই:")
        )
    }

    @Test
    fun theToolResponseCarriesTheCheckTimeForTimeSensitiveFacts() {
        val checked = SearchResultItem(
            callId = "c4",
            question = "এক ডলারের দাম কত?",
            answer = "এক ডলার ১২৪ টাকা ৪০ পয়সা।",
            sourceName = "Example News",
            receivedAtMillis = java.time.Instant.parse("2026-11-23T06:42:00Z").toEpochMilli()
        )
        val payload = SearchResultText.toolResponse(checked)
        assertTrue(Regex("Checked at \\d{2}:\\d{2}").containsMatchIn(payload))

        val unchecked = SearchResultItem(
            callId = "c5",
            question = "এক ডলারের দাম কত?",
            answer = "এক ডলার ১২৪ টাকা ৪০ পয়সা।",
            sourceName = "Example News"
        )
        assertFalse(SearchResultText.toolResponse(unchecked).contains("Checked at"))
    }

    @Test
    fun theAnswerIsLimitedToThreePlainSentences() {
        val long = (1..8).joinToString(" ") { "বাক্য $it।" }
        val limited = SearchResultText.limitSentences(long)
        assertEquals(3, limited.split("।").size - 1)
        assertFalse(limited.contains("বাক্য 4"))
    }

    @Test
    fun theEightSecondReminderFiresOnceAndOnlyWithoutAudio() {
        val tracker = IdleAnnouncementTracker()
        tracker.onResultSent("call-1", now = 1_000)
        assertFalse("too early", tracker.shouldRemind(now = 5_000))
        assertTrue("8 s with no audio", tracker.shouldRemind(now = 9_000))
        assertFalse("never twice", tracker.shouldRemind(now = 30_000))

        val spoken = IdleAnnouncementTracker()
        spoken.onResultSent("call-2", now = 1_000)
        spoken.onAudio(now = 2_000)
        assertFalse("audio arrived, so no reminder", spoken.shouldRemind(now = 20_000))
        assertEquals(8_000L, IdleAnnouncementTracker.IDLE_AUDIO_GRACE_MILLIS)
    }

    @Test
    fun theQueueIgnoresDuplicatesAndKeepsOrder() {
        val queue = PendingResultQueue()
        val first = SearchResultItem("a", "প্রথম", "এক")
        val second = SearchResultItem("b", "দ্বিতীয়", "দুই")
        assertTrue(queue.enqueue(first))
        assertTrue(queue.enqueue(second))
        assertFalse("the same callId is never announced twice", queue.enqueue(first))
        assertEquals(listOf("প্রথম", "দ্বিতীয়"), queue.questions())
        assertEquals(first, queue.poll())
        assertEquals(second, queue.poll())
        assertEquals(null, queue.poll())
    }

    // ---- problem 1: the acknowledgement ------------------------------------------------------

    @Test
    fun theInstructionTellsTheModelToSpeakBeforeItSearchesAndToNameTheQuestionLater() {
        val snapshot = TimeContext.snapshot(
            java.time.Instant.parse("2026-11-09T10:15:00Z"),
            java.time.ZoneId.of("Europe/Paris")
        )
        val instruction = LiveSessionConfig.systemInstruction(
            "Jarves", snapshot, LiveSearchTool.FUNCTION_NON_BLOCKING
        )
        val step1 = instruction.indexOf("STEP 1")
        val step2 = instruction.indexOf("STEP 2")
        assertTrue("both steps present", step1 >= 0 && step2 > step1)
        assertTrue("the short phrase is required", instruction.contains("দেখছি"))
        assertTrue("and it must vary", instruction.contains("একটু সময় দিন"))
        val stepOne = instruction.substring(step1, step2)
        assertTrue(
            "STEP 1 must order the spoken line before the tool call: $stepOne",
            stepOne.indexOf("\"দেখছি\"") in 0 until stepOne.indexOf("Call the tool")
        )
        assertTrue(stepOne.contains("BEFORE calling `search_web`"))
        assertTrue(
            "the naming rule is part of the same instruction",
            instruction.contains("জানতে চেয়েছিলেন")
        )
        assertTrue(
            "an answer in progress must never be cut off",
            instruction.contains("Never cut off")
        )
    }

    // ---- the frame itself --------------------------------------------------------------------

    @Test
    fun theDeliveredFrameCarriesWhenIdleInTheOneDocumentedPlace() {
        val payload = SearchResultText.toolResponse(
            SearchResultItem("call-1", "আজকের দাম", "১,৯৮,০০০ টাকা।", "Example News")
        )
        val json = LiveToolProtocol.toolResponseJson(
            "call-1", payload, LiveToolProtocol.schedulingFor(payload)
        )
        val response = ((MiniJson.parse(json) as Map<*, *>)["toolResponse"] as Map<*, *>)
            .let { it["functionResponses"] as List<*> }.single() as Map<*, *>
        assertEquals("WHEN_IDLE", response["scheduling"])
        // 0.2.12 (B7): one place only — the field the API reference defines on FunctionResponse
        // (https://ai.google.dev/api/generate-content#FunctionResponse). `response` is a free-form
        // Struct and carries the result alone.
        assertFalse(
            "no second, undocumented scheduling copy inside the result object",
            (response["response"] as Map<*, *>).containsKey("scheduling")
        )
        val result = (response["response"] as Map<*, *>)["result"] as String
        assertNotNull(result)
        assertTrue(result.contains("আজকের দাম"))
        assertFalse("the key never appears in a frame", json.contains("MOCK-key-placeholder"))
    }

    // ---- the timeline (task A) ---------------------------------------------------------------

    @Test
    fun theTimelineRecordsTheSearchStagesInOrder() {
        val timeline = SearchTimeline(20)
        timeline.start(now = 1_000)
        timeline.mark("toolCall received", now = 1_200)
        timeline.mark("first model audio after toolCall", now = 1_300)
        timeline.mark("search text received", now = 4_100)
        timeline.mark("toolResponse sent (scheduling=WHEN_IDLE, sent=true)", now = 4_150)
        timeline.mark("turn boundary (turnComplete)", now = 4_900)
        timeline.mark("first audio of the announced result", now = 5_300)

        val text = timeline.text()
        listOf(
            "toolCall received",
            "first model audio after toolCall",
            "search text received",
            "toolResponse sent (scheduling=WHEN_IDLE, sent=true)",
            "turn boundary (turnComplete)",
            "first audio of the announced result"
        ).forEach { stage -> assertTrue("missing stage: $stage in $text", text.contains(stage)) }
        assertTrue("offsets are relative to setupComplete", text.startsWith("0.2s toolCall received"))
        assertEquals(6, timeline.events().size)
    }
}

