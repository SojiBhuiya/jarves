package com.sojibhuiya.jarves.intelligence

import com.sojibhuiya.jarves.apiproviders.MiniJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The behaviour the whole design exists for: **while a search is running, the user can say
 * something else and Jarves answers it — and only afterwards announces the search result.**
 *
 * This is a MOCK test. [VirtualLiveSession] stands in for the WebSocket: it speaks the real
 * protocol frames (via [LiveToolProtocol]) and runs the real [SearchToolRunner] against the real
 * [GroundedSearchClient] HTTP client, with only the transport faked. No real API call is made, so
 * this is labelled TESTED-MOCK — it proves ordering and non-blocking behaviour, not Google's.
 */
class MidSearchInterjectionTest {

    /** A scripted stand-in for Gemini Live. */
    private class VirtualLiveSession(
        client: GroundedSearchClient,
        scope: CoroutineScope
    ) {
        /** Everything the user would have heard, in order. */
        val spoken: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** Frames this session sent back to the server, in order. */
        val sentFrames: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val runner = SearchToolRunner(
            client = client,
            scope = scope,
            onResult = { call, answer, scheduling ->
                send(LiveToolProtocol.toolResponseJson(call.callId, answer.asToolResult(), scheduling))
            }
        )

        /** The app's side of `onMessage`: identical control flow to `handleSearchCalls`, no socket. */
        fun onServerFrame(frame: String) {
            val cancelled = LiveToolProtocol.parseCancellations(frame)
            if (cancelled.isNotEmpty()) {
                runner.cancel(cancelled)
                return
            }
            LiveToolProtocol.parseSearchCalls(frame).forEach { runner.handle(it) }
        }

        /** Called by the (fake) model when WebSocket data arrives. */
        fun send(frame: String) {
            sentFrames += frame
            val response = ((MiniJson.parse(frame) as Map<*, *>)["toolResponse"] as Map<*, *>)
                .let { it["functionResponses"] as List<*> }.single() as Map<*, *>
            val result = (response["response"] as Map<*, *>)["result"] as String
            spoken += "announced:${result.lineSequence().first().take(40)}"
        }

        /** A user turn the model can answer without any search (small talk, a follow-up). */
        fun userAsksSomethingAnswerableNow(question: String) {
            spoken += "answered:$question"
        }

        fun shutdown() = runner.shutdown()
    }

    private class GatedTransport(val body: String) : RestTransport {
        @Volatile var release: CountDownLatch = CountDownLatch(1)
        val requests: MutableList<RestRequest> = Collections.synchronizedList(mutableListOf())
        override fun post(request: RestRequest): RestResponse {
            requests += request
            release.await(20, TimeUnit.SECONDS)
            return RestResponse(200, body)
        }
    }

    private fun awaitTrue(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private val goldPriceBody = """
        {"candidates":[{
          "content":{"role":"model","parts":[{"text":"আজ ২২ ক্যারেট সোনার ভরি প্রায় ১,৯৮,০০০ টাকা।"}]},
          "groundingMetadata":{"groundingChunks":[
            {"web":{"uri":"https://news.example.com/gold","title":"Example News"}}]}
        }]}
    """.trimIndent()

    @Test
    fun answersASecondQuestionWhileTheSearchIsStillRunningThenAnnouncesTheResult() {
        val transport = GatedTransport(goldPriceBody)
        val session = VirtualLiveSession(
            client = GroundedSearchClient(apiKey = "MOCK-key-placeholder", transport = transport),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )

        // 1) The user asks for something that needs the live web.
        val toolCall = """
            {"toolCall":{"functionCalls":[
              {"id":"call-gold","name":"search_web","args":{"query":"আজকের সোনার দাম"}}]}}
        """.trimIndent()

        val started = System.nanoTime()
        session.onServerFrame(toolCall)
        val handleMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("handling a toolCall must not block the session (took ${handleMillis}ms)", handleMillis < 200)
        assertTrue("the search must really be running", awaitTrue { transport.requests.size == 1 })
        assertTrue("nothing may be announced before the result exists", session.spoken.isEmpty())

        // 2) Mid-search, the user says something else.
        session.userAsksSomethingAnswerableNow("তুমি কি এখনো আমার সাথে আছো?")

        // 3) The search finishes and the result is handed back.
        transport.release.countDown()
        assertTrue("the result must be delivered", awaitTrue { session.spoken.size >= 2 })

        // 4) Order is the whole point.
        val answeredIndex = session.spoken.indexOfFirst { it.startsWith("answered:") }
        val announcedIndex = session.spoken.indexOfFirst { it.startsWith("announced:") }
        assertTrue("the interjection must be answered", answeredIndex >= 0)
        assertTrue("the result must be announced", announcedIndex >= 0)
        assertTrue(
            "the second question must be answered BEFORE the search result is announced, was: ${session.spoken}",
            answeredIndex < announcedIndex
        )
        assertFalse(
            "the interjection must not wait for the search",
            session.spoken[answeredIndex].contains("সোনার")
        )

        // 5) The frame really was a valid toolResponse carrying the real source.
        val frame = session.sentFrames.single()
        val response = ((MiniJson.parse(frame) as Map<*, *>)["toolResponse"] as Map<*, *>)
            .let { it["functionResponses"] as List<*> }.single() as Map<*, *>
        assertEquals("call-gold", response["id"])
        assertEquals("search_web", response["name"])
        assertTrue(frame.contains("https://news.example.com/gold"))
        assertFalse("the key must never be in a frame", frame.contains("MOCK-key-placeholder"))

        session.shutdown()
    }

    @Test
    fun ifTheUserInterruptsTheSearchNothingIsAnnounced() {
        val transport = GatedTransport(goldPriceBody)
        val session = VirtualLiveSession(
            client = GroundedSearchClient(apiKey = "MOCK-key-placeholder", transport = transport),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )

        session.onServerFrame(
            """{"toolCall":{"functionCalls":[{"id":"call-gold","name":"search_web","args":{"query":"gold"}}]}}"""
        )
        assertTrue(awaitTrue { transport.requests.size == 1 })

        // The user talks over it; the server cancels the call.
        session.onServerFrame("""{"toolCallCancellation":{"ids":["call-gold"]}}""")
        session.userAsksSomethingAnswerableNow("থাক, দরকার নেই")

        transport.release.countDown()
        Thread.sleep(400)

        assertTrue("no toolResponse may be sent for a cancelled call", session.sentFrames.isEmpty())
        assertTrue("nothing about gold may be announced", session.spoken.none { it.contains("সোনার") })
        assertEquals(0, session.runner.active)
        session.shutdown()
    }

    @Test
    fun twoSearchesInOneFrameBothGetTheirOwnResponse() {
        val transport = GatedTransport(goldPriceBody)
        transport.release = CountDownLatch(0)
        val session = VirtualLiveSession(
            client = GroundedSearchClient(apiKey = "MOCK-key-placeholder", transport = transport),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )

        session.onServerFrame(
            """{"toolCall":{"functionCalls":[
                 {"id":"a","name":"search_web","args":{"query":"gold"}},
                 {"id":"b","name":"search_web","args":{"query":"silver"}}]}}"""
        )
        assertTrue("both must be answered", awaitTrue { session.sentFrames.size == 2 })

        val ids = session.sentFrames.map { frame ->
            (((MiniJson.parse(frame) as Map<*, *>)["toolResponse"] as Map<*, *>)
                ["functionResponses"] as List<*>).single().let { (it as Map<*, *>)["id"] }
        }
        assertEquals(setOf("a", "b"), ids.toSet())
        session.shutdown()
    }
}
