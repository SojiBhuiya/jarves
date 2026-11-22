package com.sojibhuiya.jarves

import com.sojibhuiya.jarves.intelligence.FreshSessionFallback
import com.sojibhuiya.jarves.intelligence.GroundedAnswer
import com.sojibhuiya.jarves.intelligence.LiveScheduler
import com.sojibhuiya.jarves.intelligence.LiveState
import com.sojibhuiya.jarves.intelligence.LiveTask
import com.sojibhuiya.jarves.intelligence.NetworkMonitor
import com.sojibhuiya.jarves.intelligence.NetworkSnapshot
import com.sojibhuiya.jarves.intelligence.ReconnectCoordinator
import com.sojibhuiya.jarves.intelligence.SearchBackend
import com.sojibhuiya.jarves.intelligence.VoiceSearchMode
import com.sojibhuiya.jarves.system.LiveMicrophone
import com.sojibhuiya.jarves.system.LiveSocketTransport
import com.sojibhuiya.jarves.system.LiveWakeLock
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.UnknownHostException
import java.util.PriorityQueue
import java.util.concurrent.CountDownLatch

/** TESTED-MOCK: real GeminiLiveVoice wiring, fake clock, default-network events, socket, mic and power. */
class GeminiLiveRecoveryTest {
    @Test fun dnsForTwoMinutesWithValidatedMobileThenImmediateRecovery() = withHarness {
        connect()
        val firstDns = beginDnsOutage()
        // Host lookup fails even though Android keeps INTERNET and VALIDATED throughout.
        repeat(30) { i ->
            clock.advance(if (i == 0) 1_999 else 3_999)
            assertTrue(transport.idle)
            clock.advance(1)
            transport.current.fail(dns())
        }
        clock.advance(2_000)
        assertEquals(120_000L, clock.nowMillis() - firstDns)
        assertTrue(transport.idle)
        assertTrue(mic.recording)
        assertTrue(errors.isEmpty())
        assertEquals(1, status.count { it == ReconnectCoordinator.STATUS_WAITING_FOR_NETWORK })
        assertTrue(voice.endDiagnostics().contains("Reconnects: 0/3"))
        assertTrue(voice.endDiagnostics().contains("NET_CAPABILITY_VALIDATED=true"))

        network.signalAvailable()
        transport.current.openAndSetup()
        assertEquals(LiveState.CONNECTED_WITHOUT_SEARCH, states.last())
        assertEquals(1, mic.starts) // the session resumed; no second recorder
        assertTrue(status.none { it.contains("ইন্টারনেট ঠিক আছে") })
    }

    @Test fun dnsForMoreThanThreeMinutesGivesUpOnceAndStopsMicAndWakeLock() = withHarness {
        connect()
        val firstDns = beginDnsOutage()
        repeat(45) { i ->
            clock.advance(if (i == 0) 2_000 else 4_000)
            transport.current.fail(dns())
        }
        assertEquals(178_000L, clock.nowMillis() - firstDns)
        assertTrue(mic.recording)
        clock.advance(2_000)
        val opens = transport.opens.size
        assertEquals(LiveState.FAILED, states.last())
        assertFalse(mic.recording)
        assertFalse(wake.held)
        assertEquals(1, mic.stops)
        assertEquals(1, wake.releases)
        val line = errors.single()
        assertTrue(line.contains("নাম সমাধান ব্যর্থ"))
        assertTrue(line.contains("আর চেষ্টা করা হবে না"))
        assertFalse(line.contains('\n'))
        assertFalse(line.contains("Exception"))
        network.signalAvailable()
        clock.advance(30_000)
        assertEquals(opens, transport.opens.size)
    }

    @Test fun deadlineStopsMicEvenIfCancelledDnsHasNotDrained() = withHarness {
        connect()
        transport.current.fail(EOFException())
        clock.advance(1_000)
        val stuck = transport.current
        clock.advance(10_000)
        assertTrue(stuck.cancelled)
        // The resolver found a DNS error after the open deadline, but the dispatcher is busy.
        stuck.fail(dns(), drain = false)
        clock.advance(179_999)
        assertEquals(2, transport.opens.size)
        assertTrue(mic.recording)
        clock.advance(1)
        assertEquals(LiveState.FAILED, states.last())
        assertTrue(errors.single().contains("নাম সমাধান ব্যর্থ"))
        assertFalse(mic.recording)
        assertFalse(wake.held)
        assertFalse(transport.idle)
        stuck.drain()
        assertEquals(2, transport.opens.size)
    }

    @Test fun mobileDataOffForFortySecondsThenValidationWakesNextRetry() = withHarness {
        connect()
        val original = transport.current
        network.loseDefault()
        assertTrue(original.cancelled)
        original.fail(EOFException())
        repeat(10) { i ->
            clock.advance(if (i == 0) 2_000 else 4_000)
            transport.current.fail(dns())
        }
        clock.advance(2_000) // 40 s since onLost; the next scheduled retry is two seconds away
        assertTrue(transport.idle)
        network.validate()
        transport.current.openAndSetup()
        assertTrue(mic.recording)
        assertEquals(1, status.count { it == ReconnectCoordinator.STATUS_WAITING_FOR_NETWORK })
        assertTrue(errors.isEmpty())
    }

    @Test fun proactiveReconnectAndLateCallbacksWaitForBothTerminalAndTransportIdle() = withHarness {
        connect()
        clock.advance(20_000)
        val old = transport.current
        network.signalAvailable(newDefault = true)
        // A GoAway in the same interval cannot schedule a second reconnect.
        old.message("""{"goAway":{"timeLeft":"20s"}}""")
        clock.advance(1_000)
        assertTrue(old.cancelled)
        assertEquals(1, transport.opens.size)
        old.fail(IOException("cancellation"), drain = false)
        clock.advance(2_000)
        assertEquals(1, transport.opens.size) // onFailure is not the idle barrier by itself
        old.drain()
        assertEquals(2, transport.opens.size)
        val next = transport.current
        network.signalAvailable(newDefault = true)
        old.message("""{"setupComplete":{}}""")
        clock.advance(1_000)
        assertEquals(2, transport.opens.size)
        next.openAndSetup()
        assertFalse(next.cancelled)
        assertTrue(errors.isEmpty())
    }

    @Test fun timeoutCancelsHungOpenAndSetupHasItsOwnTenSeconds() = withHarness {
        voice.start()
        val hanging = transport.current
        clock.advance(9_999)
        assertFalse(hanging.cancelled)
        clock.advance(1)
        assertTrue(hanging.cancelled)
        clock.advance(2_000)
        assertEquals(1, transport.opens.size) // no retry until the cancelled attempt finishes
        hanging.fail(IOException("cancelled"), drain = false)
        assertEquals(1, transport.opens.size)
        hanging.drain()
        val setup = transport.current
        setup.open()
        clock.advance(9_999)
        assertFalse(setup.cancelled)
        clock.advance(1)
        assertTrue(setup.cancelled)
        assertEquals(LiveState.FAILED, states.last())
        val details = voice.endDiagnostics()
        assertTrue(details.contains("t+10.0s attempt #1 timed out (TCP/TLS/WebSocket open)"))
        assertTrue(details.contains("attempt #2 socket open"))
        assertTrue(details.contains("attempt #2 setup sent"))
        assertTrue(details.contains("attempt #2 timed out (setupComplete)"))
        setup.fail(IOException("cancelled"))
        clock.advance(60_000)
        assertEquals(2, transport.opens.size)
        assertEquals(1, errors.size)
        assertFalse(wake.held)
    }

    @Test fun twoHandleRefusalsInSixSecondsThenFreshAndOneContextLostLine() = withHarness {
        connectWithHandle()
        val first1008 = clock.nowMillis()
        transport.current.reject1008()
        clock.advance(3_000)
        transport.current.open()
        assertTrue(transport.current.sent.single().contains("\"handle\""))
        transport.current.reject1008()
        clock.advance(2_999)
        assertEquals(3, transport.opens.size) // original session plus two handle attempts
        clock.advance(1)
        assertEquals(6_000L, clock.nowMillis() - first1008)
        assertEquals(4, transport.opens.size)
        val fresh = transport.current
        fresh.open()
        assertFalse(fresh.sent.single().contains("\"handle\""))
        fresh.message("""{"setupComplete":{}}""")
        assertEquals(1, status.count { it == FreshSessionFallback.STATUS_CONTEXT_LOST })
        assertTrue(mic.recording)
        assertEquals(1, mic.starts)
        assertTrue(errors.isEmpty())
    }

    @Test fun handleWindowCancelsEvenAHungRetryButDoesNotOpenFreshUntilItDrains() = withHarness {
        connectWithHandle()
        transport.current.reject1008()
        clock.advance(3_000)
        val hanging = transport.current
        clock.advance(3_000)
        assertTrue(hanging.cancelled)
        assertEquals(3, transport.opens.size)
        hanging.fail(IOException("cancelled"))
        val fresh = transport.current
        fresh.open()
        assertFalse(fresh.sent.single().contains("\"handle\""))
        fresh.message("""{"setupComplete":{}}""")
        assertEquals(FreshSessionFallback.STATUS_CONTEXT_LOST, status.last())
    }

    @Test fun repeatedFreshRefusalsEndAtTheOriginalNinetySecondDeadline() = withHarness {
        connectWithHandle()
        transport.current.reject1008()
        clock.advance(6_000) // the second handle flight hung; retire it, then enter the fresh phase
        transport.current.fail(IOException("cancelled"))
        val delays = listOf(0L, 3_000L, 5_000L, 8_000L, 12_000L, 15_000L, 15_000L, 15_000L)
        delays.forEach {
            clock.advance(it)
            val fresh = transport.current
            fresh.open()
            assertFalse(fresh.sent.single().contains("\"handle\""))
            fresh.reject1008()
        }
        clock.advance(10_999) // t+89.999 s from first 1008
        assertTrue(mic.recording)
        assertTrue(errors.isEmpty())
        clock.advance(1)
        assertEquals(FreshSessionFallback.MESSAGE_GAVE_UP, errors.single())
        assertFalse(mic.recording)
        assertFalse(wake.held)
    }

    @Test fun anOutageDoesNotRestartTheExpiredFreshServerBudget() = withHarness {
        connectWithHandle()
        transport.current.reject1008()
        clock.advance(6_000)
        transport.current.fail(IOException("cancelled"))
        transport.current.fail(dns())
        repeat(30) { i ->
            clock.advance(if (i == 0) 2_000 else 4_000)
            transport.current.fail(dns())
        }
        clock.advance(2_000)
        assertTrue(mic.recording) // 120 s into the DNS window, past the 90 s server budget
        network.signalAvailable()
        transport.current.open()
        transport.current.reject1008() // Gemini is reachable but still refuses it
        assertEquals(FreshSessionFallback.MESSAGE_GAVE_UP, errors.single())
        assertFalse(mic.recording)
    }

    @Test fun actualFreshSetupFailureIsTerminalAndRedacted() = withHarness {
        connectWithHandle()
        transport.current.reject1008()
        clock.advance(6_000)
        transport.current.fail(IOException("cancelled"))
        transport.current.open()
        transport.current.message("""{"error":{"code":401,"message":"refused unit-test-placeholder"}}""")
        assertEquals(LiveState.FAILED, states.last())
        assertFalse(errors.single().contains("refused"))
        assertFalse(voice.endDiagnostics().contains("unit-test-placeholder"))
        assertFalse(mic.recording)
        val opens = transport.opens.size
        transport.current.fail(IOException("cancelled"))
        clock.advance(180_000)
        assertEquals(opens, transport.opens.size)
    }

    @Test fun manualRestartWaitsForTheCancelledPreviousEngine() = withHarness {
        voice.start()
        val old = transport.current
        voice.stop()
        assertTrue(old.cancelled)
        val restarted = Harness(auto = false, transport = transport)
        try {
            restarted.voice.start()
            old.fail(IOException("cancelled"), drain = false)
            restarted.clock.advance(9_999)
            assertEquals(1, transport.opens.size)
            old.drain()
            assertEquals(2, transport.opens.size)
            transport.current.openAndSetup()
            restarted.clock.advance(1)
            assertTrue(restarted.errors.isEmpty())
        } finally {
            restarted.voice.stop()
        }
    }

    @Test fun userStopCancelsWaitingRetryAndReleasesMicAndWakeLockWithoutError() = withHarness {
        connect()
        beginDnsOutage()
        voice.stop()
        assertEquals(LiveState.ENDED, states.last())
        assertFalse(mic.recording)
        assertFalse(wake.held)
        val opens = transport.opens.size
        network.signalAvailable()
        clock.advance(200_000)
        assertEquals(opens, transport.opens.size)
        assertTrue(errors.isEmpty())
    }

    @Test fun wakeLockIsAcquiredAtStartThenReleasedAtSixtyMinutesWithoutStoppingVoice() = withHarness {
        voice.start()
        assertTrue(wake.held)
        assertEquals(listOf(3_600_000L), wake.acquiredFor)
        transport.current.openAndSetup()
        clock.advance(3_599_999)
        assertTrue(wake.held)
        clock.advance(1)
        assertFalse(wake.held)
        assertTrue(mic.recording)
        val details = voice.endDiagnostics()
        assertTrue(details.contains("60-minute cap: wakeLockHeld=false"))
        assertTrue(details.contains("mic minute: callbacks="))
        assertTrue(details.contains("isDeviceIdleMode="))
    }

    @Test fun idleAutoSearchRunnerDoesNotMarkOrdinarySpeechAsSearchPending() = withHarness(auto = true) {
        connect()
        repeat(13) { transport.current.message("""{"serverContent":{"inputTranscription":{"text":"hello"}}}""") }
        val details = voice.endDiagnostics()
        assertTrue(details.contains("toolCalls: 0"))
        assertTrue(details.contains("pending=false"))
        assertFalse(details.contains("user speech while search pending"))
    }

    private fun withHarness(auto: Boolean = false, test: Harness.() -> Unit) {
        val harness = Harness(auto)
        try { harness.test() } finally { harness.voice.stop() }
    }

    private class Harness(auto: Boolean, val transport: FakeTransport = FakeTransport()) {
        val clock = FakeClock()
        val network = FakeNetwork()
        val mic = FakeMic()
        val wake = FakeWakeLock()
        val errors = mutableListOf<String>()
        val states = mutableListOf<LiveState>()
        val status = mutableListOf<String>()
        val voice = GeminiLiveVoice(
            apiKey = "unit-test-placeholder",
            assistantName = "Jarves",
            clock = clock,
            transport = transport,
            microphoneFactory = { mic },
            networkMonitor = network,
            wakeLock = wake,
            onStatus = { status += it },
            onError = { errors += it },
            onState = { states += it },
            onTranscript = { _, _ -> },
            mode = if (auto) VoiceSearchMode.AUTO else VoiceSearchMode.VOICE_ONLY,
            searchBackend = if (auto) object : SearchBackend {
                override suspend fun search(query: String): GroundedAnswer = error("no tool call was expected")
                override fun describe() = "idle test search backend"
            } else null
        )

        fun connect() {
            voice.start()
            transport.current.openAndSetup()
            assertTrue(mic.recording)
        }

        fun beginDnsOutage(): Long {
            transport.current.fail(EOFException())
            clock.advance(1_000)
            transport.current.fail(dns())
            assertTrue(transport.idle)
            return clock.nowMillis()
        }

        fun connectWithHandle() {
            connect()
            transport.current.message("""{"sessionResumptionUpdate":{"resumable":true,"newHandle":"fixture-handle"}}""")
            transport.current.fail(EOFException())
            clock.advance(1_000)
            transport.current.open()
            assertTrue(transport.current.sent.single().contains("\"handle\""))
        }
    }

    private class FakeClock : LiveScheduler {
        private class Entry(val due: Long, val id: Int, val run: () -> Unit) { var cancelled = false }
        private val pending = PriorityQueue(compareBy<Entry> { it.due }.thenBy { it.id })
        @Volatile private var now = 1_000L
        private var sequence = 0
        private var stopped = false
        override fun nowMillis() = now
        override fun after(delayMillis: Long, action: () -> Unit): LiveTask {
            check(!stopped)
            val job = Entry(now + delayMillis, ++sequence, action)
            pending += job
            return LiveTask { job.cancelled = true }
        }
        fun advance(millis: Long) {
            val end = now + millis
            while (pending.peek()?.due?.let { it <= end } == true) {
                val next = pending.remove()
                now = next.due
                if (!next.cancelled) next.run()
            }
            now = end
        }
        override fun shutdown() { stopped = true; pending.clear() }
    }

    private class FakeTransport : LiveSocketTransport {
        val opens = mutableListOf<FakeSocket>()
        private var active: FakeSocket? = null
        private var idleAction: (() -> Unit)? = null
        val current: FakeSocket get() = checkNotNull(active)
        override val idle: Boolean get() = active == null
        override fun onIdle(action: (() -> Unit)?) { idleAction = action }
        override fun open(request: Request, listener: WebSocketListener): WebSocket {
            check(idle) { "two socket attempts overlap" }
            return FakeSocket(request, listener) { socket ->
                check(active === socket)
                active = null
                idleAction?.invoke()
            }.also { opens += it; active = it }
        }
    }

    private class FakeSocket(
        private val original: Request,
        private val listener: WebSocketListener,
        private val onDrained: (FakeSocket) -> Unit
    ) : WebSocket {
        val sent = mutableListOf<String>()
        var cancelled = false
            private set
        override fun request() = original
        override fun queueSize() = 0L
        override fun send(text: String): Boolean = !cancelled && sent.add(text)
        override fun send(bytes: ByteString) = !cancelled
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
        fun open() = listener.onOpen(this,
            Response.Builder().request(original).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        fun message(json: String) = listener.onMessage(this, json)
        fun openAndSetup() { open(); message("""{"setupComplete":{}}""") }
        fun fail(reason: Throwable, drain: Boolean = true) {
            listener.onFailure(this, reason, null)
            if (drain) this.drain()
        }
        fun reject1008() {
            val reason = "BidiGenerateContent session is already connected to an existing client"
            listener.onClosing(this, 1008, reason)
            listener.onClosed(this, 1008, reason)
            drain()
        }
        fun drain() = onDrained(this)
    }

    private class FakeNetwork : NetworkMonitor {
        private var change: ((NetworkSnapshot) -> Unit)? = null
        private var lost: ((NetworkSnapshot) -> Unit)? = null
        private var state = NetworkSnapshot(true, "mobile", internet = true, validated = true)
        private var id = 7L
        override fun snapshot() = state
        override fun networkIdentity() = id
        override fun onAvailable(listener: (NetworkSnapshot) -> Unit) { change = listener }
        override fun onLost(listener: (NetworkSnapshot) -> Unit) { lost = listener }
        override fun removeListeners(available: (NetworkSnapshot) -> Unit, lost: (NetworkSnapshot) -> Unit) {
            change = null; this.lost = null
        }
        fun signalAvailable(newDefault: Boolean = false) {
            if (newDefault) id++
            change?.invoke(state)
        }
        fun loseDefault() {
            state = NetworkSnapshot(false, NetworkSnapshot.TYPE_NONE, internet = false, validated = false)
            lost?.invoke(state)
        }
        fun validate() {
            state = NetworkSnapshot(true, "mobile", internet = true, validated = true)
            signalAvailable(newDefault = true)
        }
    }

    private class FakeMic : LiveMicrophone {
        private val stopped = CountDownLatch(1)
        @Volatile override var recording = false
            private set
        var starts = 0
        var stops = 0
        override fun start() { starts++; recording = true }
        override fun read(buffer: ByteArray): Int { stopped.await(); return 0 }
        override fun stopAndRelease() { stops++; recording = false; stopped.countDown() }
    }

    private class FakeWakeLock : LiveWakeLock {
        override var held = false
            private set
        var releases = 0
        val acquiredFor = mutableListOf<Long>()
        override fun acquire(timeoutMillis: Long) { acquiredFor += timeoutMillis; held = true }
        override fun release() { if (held) { releases++; held = false } }
    }

    companion object {
        private fun dns() = UnknownHostException("resolver failed: EAI_NODATA")
    }
}
