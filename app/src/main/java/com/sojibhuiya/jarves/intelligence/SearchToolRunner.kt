package com.sojibhuiya.jarves.intelligence

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs `search_web` calls off the WebSocket thread.
 *
 * Guarantees (each one unit-tested):
 *  * [handle] returns immediately — the OkHttp reader thread is never blocked by a search;
 *  * at most [maxConcurrent] searches run at once; a third concurrent call gets an honest
 *    "too many searches" answer instead of being silently dropped;
 *  * every call gets a reply inside [timeoutMillis], failure included, so the model is never left
 *    waiting for a response that will not come;
 *  * a cancelled call (user interrupted) is dropped: no `toolResponse` is sent for it;
 *  * a callId is answered at most once.
 */
class SearchToolRunner(
    private val client: GroundedSearchClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxConcurrent: Int = 2,
    private val timeoutMillis: Long = 25_000L,
    /** Delivered on a background thread: `(call, answer, scheduling)`. */
    private val onResult: (SearchFunctionCall, GroundedAnswer, String) -> Unit,
    /** Short, secret-free status lines for the UI. */
    private val onEvent: (String) -> Unit = {}
) {

    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private var activeCount = 0

    /** How many searches are running right now (diagnostics and tests). */
    val active: Int get() = synchronized(lock) { activeCount }

    /** Why a call was not started. */
    private enum class Refusal { DUPLICATE, OVER_CAPACITY }

    /**
     * Starts a search for [call]. Non-blocking: it only schedules the coroutine. If the call is
     * already known (duplicate frame) it is ignored.
     */
    fun handle(call: SearchFunctionCall) {
        // LAZY is the whole trick: `launch` hands back a Job immediately but runs nothing, so the
        // callId can be reserved in one atomic step *before* the body starts. Without it, two
        // duplicate frames delivered in the same millisecond both passed the containsKey() check
        // and produced two searches and two toolResponses for one id (caught by the unit test).
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                onEvent("সার্চ চলছে: ${call.query}")
                val answer = withTimeoutOrNull(timeoutMillis) {
                    client.search(call.query)
                } ?: GroundedAnswer(
                    query = call.query,
                    text = "",
                    failure = GroundedSearchFailure.TIMEOUT
                )
                val result = answer.asToolResult()
                deliver(call, answer, LiveToolProtocol.schedulingFor(result))
            } finally {
                // Decrement only if this job is still the registered one: a cancel()/shutdown()
                // that already removed the entry has already released the slot.
                synchronized(lock) {
                    if (jobs.remove(call.callId) != null) {
                        activeCount = (activeCount - 1).coerceAtLeast(0)
                    }
                }
            }
        }

        val refusal = synchronized(lock) {
            when {
                jobs.containsKey(call.callId) -> Refusal.DUPLICATE
                activeCount >= maxConcurrent -> Refusal.OVER_CAPACITY
                else -> {
                    jobs[call.callId] = job
                    activeCount += 1
                    null
                }
            }
        }
        if (refusal != null) {
            job.cancel()                     // never started, so nothing ran
            if (refusal == Refusal.OVER_CAPACITY) {
                onEvent("একসাথে বেশি সার্চ — অপেক্ষা করতে বলা হচ্ছে")
                deliver(
                    call,
                    GroundedAnswer(query = call.query, text = "", failure = GroundedSearchFailure.BUSY),
                    LiveToolProtocol.SCHEDULING_INTERRUPT
                )
            } else {
                // A duplicate callId is answered once, by the run that already owns it.
                onEvent("একই সার্চ আবার এসেছে — উপেক্ষা করা হচ্ছে")
            }
            return
        }
        job.start()
    }

    /**
     * The user interrupted the turn: drop the work and send nothing. The server has already
     * cancelled the call, so a late `toolResponse` would be an orphan.
     */
    fun cancel(callIds: List<String>) {
        val toCancel = synchronized(lock) {
            callIds.mapNotNull { id -> jobs.remove(id)?.also { activeCount = (activeCount - 1).coerceAtLeast(0) } }
        }
        toCancel.forEach { it.cancel() }
        if (toCancel.isNotEmpty()) onEvent("সার্চ বাতিল হয়েছে")
    }

    private fun deliver(call: SearchFunctionCall, answer: GroundedAnswer, scheduling: String) {
        runCatching { onResult(call, answer, scheduling) }
    }

    /** Stops everything (session ended). */
    fun shutdown() {
        val running = synchronized(lock) {
            val copy = jobs.values.toList()
            jobs.clear()
            activeCount = 0
            copy
        }
        running.forEach { it.cancel() }
        scope.cancel()
    }
}
