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
 * Design 4 timing guarantees, tested for real on the JVM dispatcher.
 *
 * The transport is a fake that blocks on a latch, which is exactly how a slow HTTP call behaves
 * from the runner's point of view. Each assertion waits for a latch or polls a bounded condition.
 */
class SearchToolRunnerTest {

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A transport that blocks until the test releases it, then returns a fixed answer. */
    private class GatedTransport(
        private val body: String = """{"candidates":[{"content":{"parts":[{"text":"32°C"}]}}]}"""
    ) : RestTransport {
        val entered = CountDownLatch(1)
        @Volatile var release: CountDownLatch = CountDownLatch(0)
        val requests: MutableList<RestRequest> = Collections.synchronizedList(mutableListOf())

        override fun post(request: RestRequest): RestResponse {
            requests += request
            entered.countDown()
            release.await(20, TimeUnit.SECONDS)
            return RestResponse(200, body)
        }
    }

    private fun client(transport: RestTransport) =
        GroundedSearchClient(apiKey = "UNIT-TEST-runner-key", transport = transport)

    private fun awaitTrue(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    // ---- never blocks the WebSocket thread ---------------------------------------------

    @Test
    fun handleReturnsImmediatelySoTheSocketThreadIsNeverBlocked() {
        // The transport is held shut for the whole test, so the search genuinely cannot finish:
        // anything that showed up in `results` early would have to be invented.
        val transport = GatedTransport()
        val release = CountDownLatch(1)
        transport.release = release
        val results = Collections.synchronizedList(mutableListOf<GroundedAnswer>())
        val runner = SearchToolRunner(
            client = client(transport),
            scope = scope(),
            onResult = { _, answer, _ -> results += answer }
        )

        val started = System.nanoTime()
        runner.handle(SearchFunctionCall("c1", "slow query"))
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue("handle() must return at once, took ${elapsedMillis}ms", elapsedMillis < 200)
        assertTrue("the search itself must still be running", awaitTrue { transport.requests.size == 1 })
        assertEquals("no answer may exist yet", 0, results.size)
        assertEquals(1, runner.active)

        release.countDown()
        assertTrue("the answer must arrive once the search completes", awaitTrue { results.size == 1 })
        assertTrue(results.single().isSuccess)
        runner.shutdown()
    }

    // ---- concurrency cap ---------------------------------------------------------------

    @Test
    fun atMostTwoSearchesRunAtOnceAndTheThirdIsToldHonestly() {
        val transport = GatedTransport()
        val transportRelease = CountDownLatch(1)
        transport.release = transportRelease
        val results = Collections.synchronizedList(mutableListOf<Pair<String, GroundedAnswer>>())
        val runner = SearchToolRunner(
            client = client(transport),
            scope = scope(),
            onResult = { call, answer, _ -> results += call.callId to answer }
        )

        runner.handle(SearchFunctionCall("c1", "one"))
        runner.handle(SearchFunctionCall("c2", "two"))
        runner.handle(SearchFunctionCall("c3", "three"))

        // The third is refused synchronously, without a network call.
        assertTrue("the third call must be answered at once", awaitTrue { results.any { it.first == "c3" } })
        val third = results.first { it.first == "c3" }.second
        assertEquals(GroundedSearchFailure.BUSY, third.failure)
        assertFalse(third.isSuccess)
        assertTrue(third.asToolResult().contains("next"))

        // Both admitted searches must actually be in flight before the cap is counted.
        assertTrue("both admitted searches must reach the network", awaitTrue { transport.requests.size == 2 })
        assertEquals(2, runner.active)
        Thread.sleep(100)
        assertEquals("only two searches may reach the network", 2, transport.requests.size)

        transportRelease.countDown()
        assertTrue("both real searches must still be answered", awaitTrue { results.size == 3 })
        runner.shutdown()
    }

    @Test
    fun aDuplicateCallIdIsAnsweredOnceOnly() {
        val transport = GatedTransport()
        transport.release = CountDownLatch(0)
        val results = Collections.synchronizedList(mutableListOf<String>())
        val runner = SearchToolRunner(
            client = client(transport),
            scope = scope(),
            onResult = { _, _, _ -> results += "answer" }
        )

        val call = SearchFunctionCall("same-id", "q")
        runner.handle(call)
        runner.handle(call)
        runner.handle(call)

        assertTrue(awaitTrue { results.size >= 1 })
        Thread.sleep(150)
        assertEquals("one callId must produce exactly one toolResponse", 1, results.size)
        assertEquals(1, transport.requests.size)
        runner.shutdown()
    }

    // ---- timeout -----------------------------------------------------------------------

    @Test
    fun aSearchThatNeverFinishesIsAnsweredWithAnHonestTimeout() {
        // The latch is never counted down during the assertion, so this is a genuine timeout path.
        val transport = GatedTransport()
        transport.release = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<GroundedAnswer>())
        val runner = SearchToolRunner(
            client = client(transport),
            scope = scope(),
            timeoutMillis = 250L,
            onResult = { _, answer, _ -> results += answer }
        )

        runner.handle(SearchFunctionCall("c1", "hangs forever"))

        assertTrue("the timeout must deliver a reply", awaitTrue(5_000) { results.size == 1 })
        val answer = results.single()
        assertEquals(GroundedSearchFailure.TIMEOUT, answer.failure)
        assertFalse(answer.isSuccess)
        assertTrue(answer.asToolResult().contains("did not finish in time"))
        assertFalse("a timeout must not carry an invented answer", answer.asToolResult().contains("32°C"))

        // The slot is released, so a later search still works.
        assertTrue(awaitTrue { runner.active == 0 })
        runner.shutdown()
    }

    @Test
    fun schedulingIsChosenFromTheResultLength() {
        val transport = GatedTransport(
            body = """{"candidates":[{"content":{"parts":[{"text":"${"লম্বা উত্তর ".repeat(200)}"}]}}]}"""
        )
        transport.release = CountDownLatch(0)
        val schedulings = Collections.synchronizedList(mutableListOf<String>())
        val runner = SearchToolRunner(
            client = client(transport),
            scope = scope(),
            onResult = { _, _, scheduling -> schedulings += scheduling }
        )
        runner.handle(SearchFunctionCall("c1", "long"))
        assertTrue(awaitTrue { schedulings.size == 1 })
        assertEquals(LiveToolProtocol.SCHEDULING_WHEN_IDLE, schedulings.single())
        runner.shutdown()
    }

    // ---- cancellation and shutdown -----------------------------------------------------

    @Test
    fun aCancelledSearchSendsNothingBecauseTheServerAlreadyDroppedIt() {
        val transport = GatedTransport()
        transport.release = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<String>())
        val runner = SearchToolRunner(
            client = client(transport),
            scope = scope(),
            onResult = { call, _, _ -> results += call.callId }
        )

        runner.handle(SearchFunctionCall("c1", "q"))
        assertTrue(awaitTrue { runner.active == 1 })
        runner.cancel(listOf("c1"))

        assertEquals(0, runner.active)
        Thread.sleep(200)
        assertTrue("a cancelled call must never be answered", results.isEmpty())
        runner.shutdown()
    }

    @Test
    fun cancellingAnUnknownIdIsHarmless() {
        val transport = GatedTransport()
        transport.release = CountDownLatch(0)
        val runner = SearchToolRunner(
            client = client(transport),
            scope = scope(),
            onResult = { _, _, _ -> }
        )
        runner.cancel(listOf("never-existed"))
        assertEquals(0, runner.active)
        runner.shutdown()
    }

    @Test
    fun shutdownStopsLateAnswersFromReachingADeadSession() {
        val transport = GatedTransport()
        val release = CountDownLatch(1)
        transport.release = release
        val results = Collections.synchronizedList(mutableListOf<String>())
        val runner = SearchToolRunner(
            client = client(transport),
            scope = scope(),
            onResult = { call, _, _ -> results += call.callId }
        )

        runner.handle(SearchFunctionCall("c1", "q"))
        assertTrue(awaitTrue { transport.requests.size == 1 })
        runner.shutdown()
        assertEquals(0, runner.active)

        release.countDown()
        Thread.sleep(300)
        assertTrue("no toolResponse may be sent after shutdown", results.isEmpty())
    }

    // ---- the answer the model receives is well formed -----------------------------------

    @Test
    fun theDeliveredResultContainsNoSecretAndNoMarkup() {
        val transport = GatedTransport(
            body = """{"candidates":[{"content":{"parts":[{"text":"Answer with a source."}]},
                "groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://ok.example","title":"OK"}}]}}]}"""
        )
        transport.release = CountDownLatch(0)
        val results = Collections.synchronizedList(mutableListOf<String>())
        val runner = SearchToolRunner(
            client = client(transport),
            scope = scope(),
            onResult = { call, answer, scheduling ->
                results += LiveToolProtocol.toolResponseJson(call.callId, answer.asToolResult(), scheduling)
            }
        )
        runner.handle(SearchFunctionCall("c9", "q"))
        assertTrue(awaitTrue { results.size == 1 })

        val frame = results.single()
        val response = ((MiniJson.parse(frame) as Map<*, *>)["toolResponse"] as Map<*, *>)
            .let { it["functionResponses"] as List<*> }.single() as Map<*, *>
        assertEquals("c9", response["id"])
        assertEquals("search_web", response["name"])

        assertFalse("the key must never reach the model", frame.contains("UNIT-TEST-runner-key"))
        assertTrue(frame.contains("https://ok.example"))
        runner.shutdown()
    }
}
