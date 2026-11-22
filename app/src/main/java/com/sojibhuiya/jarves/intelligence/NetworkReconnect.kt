package com.sojibhuiya.jarves.intelligence

import java.io.InterruptedIOException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What the phone's default network looks like right now.
 *
 * [type] is never guessed: it is `wifi` / `mobile` / `ethernet` / `vpn` / `bluetooth` / `other`,
 * or [TYPE_NONE] when Android reports no default network, or [TYPE_UNKNOWN] when Android could not
 * be asked at all (see [unknownReason]).
 */
data class NetworkSnapshot(
    val available: Boolean,
    val type: String = TYPE_UNKNOWN,
    /** System-clock time of the last `onLost`, or null when no loss was seen this process. */
    val lastLostAtMillis: Long? = null,
    /** System-clock time of the last transport change (`onAvailable` / capabilities change). */
    val changedAtMillis: Long? = null,
    /** Why [type] is [TYPE_UNKNOWN] — e.g. the permission is missing. Empty when known. */
    val unknownReason: String = "",
    val internet: Boolean? = null,
    val validated: Boolean? = null
) {
    val indicatesNoUsableNetwork: Boolean get() = (!available && unknownReason.isBlank()) ||
        internet == false || validated == false

    fun capabilitiesLine(): String =
        "NET_CAPABILITY_INTERNET=${internet ?: "unknown"}, NET_CAPABILITY_VALIDATED=${validated ?: "unknown"}"

    companion object {
        const val TYPE_NONE = "none"
        const val TYPE_UNKNOWN = "unknown"

        val UNKNOWN = NetworkSnapshot(
            available = false,
            type = TYPE_UNKNOWN,
            unknownReason = "no network reader in this process"
        )
    }
}

/**
 * Reads the network and reports changes. Implemented on Android by
 * [com.sojibhuiya.jarves.system.NetworkMonitor]; a fake is used in the tests.
 */
interface NetworkMonitor {
    fun snapshot(): NetworkSnapshot

    /** Called on a new default network, a transport change, or a transition to validated. */
    fun onAvailable(listener: (NetworkSnapshot) -> Unit)

    /** Called when the default network is lost. */
    fun onLost(listener: (NetworkSnapshot) -> Unit)

    fun removeListeners(available: (NetworkSnapshot) -> Unit, lost: (NetworkSnapshot) -> Unit) = Unit

    /**
     * 0.2.16 (task C): identity of the current default network object, `-1` when unknown. A change is
     * counted only when this value changes, when the transport type changes, or on `onLost` —
     * capability/link-property updates are counted separately by the implementation.
     */
    fun networkIdentity(): Long = -1L

    companion object {
        /** Used by plain-JVM tests and as the safe default. */
        val NONE: NetworkMonitor = object : NetworkMonitor {
            override fun snapshot(): NetworkSnapshot = NetworkSnapshot.UNKNOWN
            override fun onAvailable(listener: (NetworkSnapshot) -> Unit) = Unit
            override fun onLost(listener: (NetworkSnapshot) -> Unit) = Unit
            override fun networkIdentity(): Long = -1L
        }
    }
}

enum class NetworkDown {
    DNS, NO_ROUTE, CONNECT_TIMEOUT, NO_DEFAULT_OR_UNVALIDATED;

    companion object {
        /** Only transport errors qualify; a setup timeout or a server status is not a DNS error. */
        fun from(error: Throwable, opening: Boolean): NetworkDown? {
            val causes = generateSequence(error) { it.cause }.take(12).toList()
            fun has(text: String) = causes.any { it.message?.contains(text, ignoreCase = true) == true }
            return when {
                causes.any { it is UnknownHostException } || has("EAI_NODATA") || has("EAI_NONAME") -> DNS
                causes.any { it is NoRouteToHostException } || has("ENETUNREACH") ||
                    has("EHOSTUNREACH") || has("Network is unreachable") || has("No route to host") -> NO_ROUTE
                has("connect timed out") || has("connect timeout") ||
                    opening && (has("ETIMEDOUT") || has("connection timed out")) ||
                    opening && causes.any { it is SocketTimeoutException ||
                        it is InterruptedIOException && it.message.equals("timeout", ignoreCase = true) } -> CONNECT_TIMEOUT
                else -> null
            }
        }
    }
}

/** What the reconnect machinery should do next. */
sealed interface ReconnectAction {
    /**
     * No usable transport: spend **no** normal attempt, and retry until the fixed episode deadline.
     */
    data class WaitForNetwork(val deadlineMillis: Long, val delayMillis: Long, val first: Boolean) : ReconnectAction

    /** Open a new socket after [delayMillis], using the stored resume handle. */
    data class Attempt(val attempt: Int, val delayMillis: Long) : ReconnectAction

    /**
     * 0.2.13: the server refused the new connection with `1008 "…already connected to an existing
     * client"`. Nothing is wrong with the network, so this has its own schedule and does **not**
     * spend one of the [maxAttempts] normal attempts.
     */
    data class RetryServerBusy(val attempt: Int, val delayMillis: Long) : ReconnectAction

    /** Nothing to do (the user stopped, or the session is not running). */
    data object None : ReconnectAction

    /** Stop trying. [message] is the Bengali line the user sees. */
    data class GiveUp(val reason: GiveUpReason, val message: String) : ReconnectAction
}

enum class GiveUpReason {
    /** 3 attempts on a network that was working. */
    ATTEMPTS_EXHAUSTED,

    /** The network never came back within the deadline. */
    NO_NETWORK_TIMEOUT,

    /**
     * 0.2.13: the server kept answering `1008 "already connected to an existing client"` for the
     * whole server-busy budget (~30 s), so the session is ended cleanly instead of waiting forever.
     */
    SERVER_BUSY_TIMEOUT
}

/** Retry budget based on host failures, not just a default-network callback. Caller supplies time. */
class ReconnectCoordinator(
    private val maxAttempts: Int = 3,
    private val networkWaitMillis: Long = 180_000L,
    private val backoffMillis: List<Long> = listOf(1_000L, 2_000L, 4_000L),
    /** A proactive reconnect is not repeated within this window (avoids churn on flapping links). */
    private val proactiveCooldownMillis: Long = 10_000L
) {

    private val events = mutableListOf<String>()

    /**
     * 0.2.13 (task 4): every timeline entry is printed as `t+12.8s` relative to this moment, never
     * as an epoch value. Set by [startTimeline] when a session starts.
     */
    private var sessionStartMillis: Long = 0L

    /** 0.2.13: the 1008 "already connected" schedule (3 s, 5 s, 8 s, 12 s, ~30 s in total). */
    val serverBusy: ServerBusyRetry = ServerBusyRetry()

    var attemptsUsed: Int = 0
        private set

    private var networkDeadline: Long? = null
    private var networkFailure: NetworkDown? = null
    val waitingForNetwork: Boolean get() = networkDeadline != null

    var gaveUp: Boolean = false
        private set

    var giveUpReason: GiveUpReason? = null
        private set

    var lastAttemptAtMillis: Long = 0L
        private set

    /** The reconnect timeline, newest last, for the diagnostics block. */
    fun timeline(): List<String> = events.toList()

    fun timelineText(): String =
        if (events.isEmpty()) "none" else events.joinToString(" -> ")

    /** 0.2.13 (task 4): timestamps below are relative to this instant. */
    fun startTimeline(now: Long) {
        sessionStartMillis = now
        events.clear()
    }

    /** `t+12.8s` for the current call. */
    private fun stamp(now: Long): String = formatRelativeMillis(now, sessionStartMillis)

    /** Resets everything for a brand-new session (the user started talking again). */
    fun reset() {
        attemptsUsed = 0
        networkDeadline = null
        networkFailure = null
        gaveUp = false
        giveUpReason = null
        lastAttemptAtMillis = 0L
        serverBusy.reset()
        events.clear()
    }

    /** [failedAttempt] is refunded if its failure is evidence of a network outage. */
    fun onUnexpectedFailure(
        now: Long,
        network: NetworkSnapshot,
        reason: String,
        down: NetworkDown? = null,
        failedAttempt: Int = 0
    ): ReconnectAction {
        if (gaveUp) return ReconnectAction.None
        events += "${event(now)} failure (${reason.take(70)}; ${network.capabilitiesLine()})"
        if (down == null && !network.indicatesNoUsableNetwork && !waitingForNetwork) return attempt(now)
        if (failedAttempt > 0 && failedAttempt == attemptsUsed) attemptsUsed--
        val first = !waitingForNetwork
        if (first) {
            networkDeadline = now + networkWaitMillis
            events += "${event(now)} waiting for network (no attempt spent)"
        }
        // Do not lose the DNS explanation if the default network disappears during the same outage.
        if (down == NetworkDown.DNS || networkFailure == null ||
            networkFailure != NetworkDown.DNS && down != null) {
            networkFailure = down ?: NetworkDown.NO_DEFAULT_OR_UNVALIDATED
        }
        val deadline = checkNotNull(networkDeadline)
        return if (now >= deadline) checkNetworkDeadline(now) else
            ReconnectAction.WaitForNetwork(deadline, if (first) 2_000L else 4_000L, first)
    }

    /** A timed-out resolver can still return DNS evidence while its cancellation is draining. */
    fun noteDnsFailure() {
        if (waitingForNetwork) networkFailure = NetworkDown.DNS
    }

    fun checkNetworkDeadline(now: Long): ReconnectAction =
        if (!gaveUp && networkDeadline?.let { now >= it } == true) {
            giveUp(now, GiveUpReason.NO_NETWORK_TIMEOUT)
        } else {
            ReconnectAction.None
        }

    /** Android availability is only a signal to try now, not proof that Gemini can be reached. */
    fun onNetworkAvailable(now: Long, network: NetworkSnapshot): ReconnectAction {
        if (gaveUp || !waitingForNetwork) return ReconnectAction.None
        events += "${event(now)} network available (${network.type}) -> probe now (${network.capabilitiesLine()})"
        return ReconnectAction.Attempt(attempt = 0, delayMillis = 0L)
    }

    /** A successful WebSocket upgrade (or HTTP response) proved the route to the endpoint. */
    fun onNetworkReachable(now: Long) {
        if (waitingForNetwork) events += "${event(now)} endpoint reached"
        networkDeadline = null
        networkFailure = null
    }

    /** A new default network while the session is healthy: reconnect once, without a failure. */
    fun onNewNetworkWhileHealthy(now: Long, network: NetworkSnapshot): ReconnectAction {
        if (gaveUp || waitingForNetwork) return ReconnectAction.None
        return proactive(now, network)
    }

    private fun proactive(now: Long, network: NetworkSnapshot): ReconnectAction {
        if (now - lastAttemptAtMillis < proactiveCooldownMillis) return ReconnectAction.None
        lastAttemptAtMillis = now
        attemptsUsed = 0
        events += "${event(now)} new network (${network.type}) -> proactive reconnect"
        return ReconnectAction.Attempt(attempt = 0, delayMillis = 1_000L)
    }

    /** A new socket was opened for [ReconnectAction.Attempt]; the result is reported separately. */
    fun onAttemptStarted(now: Long, attempt: Int) {
        if (attempt > 0) {
            attemptsUsed = attempt
            lastAttemptAtMillis = now
        }
        events += "${event(now)} attempt #${if (attempt > 0) attempt else "proactive"}"
    }

    /** The reconnect worked: the session continues. */
    fun onAttemptSucceeded(now: Long) {
        attemptsUsed = 0
        onNetworkReachable(now)
        // The session runs again: a later 1008 episode starts its schedule from 3 s again.
        if (serverBusy.active) events += "${event(now)} server-busy cleared (${serverBusy.describe(now)})"
        serverBusy.reset()
        events += "${event(now)} reconnected"
    }

    /**
     * 0.2.13: the server refused the new socket with `1008 "…already connected to an existing
     * client"`. Returns [ReconnectAction.RetryServerBusy] on its own schedule (3 s, 5 s, 8 s, 12 s)
     * or [ReconnectAction.GiveUp] once the ~30 s budget is spent. [attemptsUsed] is untouched.
     */
    fun onServerBusy(now: Long): ReconnectAction {
        if (gaveUp) return ReconnectAction.None
        val delay = serverBusy.nextDelayMillis(now)
        if (delay == null) {
            events += "${event(now)} server still busy after ${serverBusy.describe(now)}"
            return giveUp(now, GiveUpReason.SERVER_BUSY_TIMEOUT)
        }
        events += "${event(now)} server busy -> retry in ${delay}ms (${serverBusy.describe(now)})"
        return ReconnectAction.RetryServerBusy(attempt = serverBusy.attemptsUsed, delayMillis = delay)
    }

    /** The socket that the reconnect opened failed too (legacy caller/test helper). */
    fun onAttemptFailed(now: Long, network: NetworkSnapshot): ReconnectAction =
        onUnexpectedFailure(now, network, "attempt failed", failedAttempt = attemptsUsed)

    fun onNetworkLost(now: Long) {
        events += "${event(now)} network lost"
    }

    private fun attempt(now: Long): ReconnectAction {
        if (attemptsUsed >= maxAttempts) return giveUp(now, GiveUpReason.ATTEMPTS_EXHAUSTED)
        val next = attemptsUsed + 1
        val delay = backoffMillis.getOrElse(next - 1) { backoffMillis.lastOrNull() ?: 1_000L }
        attemptsUsed = next
        lastAttemptAtMillis = now
        events += "${event(now)} attempt #$next in ${delay}ms"
        return ReconnectAction.Attempt(attempt = next, delayMillis = delay)
    }

    private fun giveUp(now: Long, reason: GiveUpReason): ReconnectAction {
        gaveUp = true
        giveUpReason = reason
        events += "${event(now)} gave up ($reason)"
        val message = if (reason == GiveUpReason.NO_NETWORK_TIMEOUT && networkFailure == NetworkDown.DNS) {
            "ইন্টারনেটে পৌঁছানো যায়নি (নাম সমাধান ব্যর্থ) — আর চেষ্টা করা হবে না।"
        } else messageFor(reason)
        return ReconnectAction.GiveUp(reason, message)
    }

    /** 0.2.13 (task 4): `t+12.8s` instead of an epoch value like `1.7E7s`. */
    private fun event(now: Long): String = stamp(now)

    companion object {

        const val STATUS_WAITING_FOR_NETWORK = "ইন্টারনেট পাওয়া যাচ্ছে না, আবার চেষ্টা করছি…"
        const val STATUS_RECONNECTED = "পুনরায় সংযুক্ত"
        const val STATUS_CONTEXT_MAY_BE_LOST =
            "আগের কথোপকথনটা হয়তো মনে রাখতে পারব না — নতুন করে শুরু করছি।"

        fun messageFor(reason: GiveUpReason): String = when (reason) {
            GiveUpReason.NO_NETWORK_TIMEOUT ->
                "ইন্টারনেটে পৌঁছানো যায়নি — আর চেষ্টা করা হবে না।"
            GiveUpReason.ATTEMPTS_EXHAUSTED ->
                "৩ বার চেষ্টা করেও সংযোগ হয়নি।"
            GiveUpReason.SERVER_BUSY_TIMEOUT ->
                "সার্ভার এখনো পুরোনো সংযোগটা ছাড়েনি, তাই প্রায় ৩০ সেকেন্ড চেষ্টার পর বন্ধ করছি। " +
                    "আবার শুরু করুন — সাধারণত সাথে সাথে যুক্ত হয়ে যায়।"
        }

        /** Diagnostics wording for the network line, including *why* it is unknown. */
        fun describeNetwork(snapshot: NetworkSnapshot, now: Long): String {
            val type = if (snapshot.type.isBlank()) NetworkSnapshot.TYPE_UNKNOWN else snapshot.type
            val head = if (snapshot.type == NetworkSnapshot.TYPE_UNKNOWN && snapshot.unknownReason.isNotBlank()) {
                "unknown (${snapshot.unknownReason})"
            } else {
                type
            }
            val change = when (val changed = snapshot.changedAtMillis) {
                null -> " (unchanged this session)"
                else -> {
                    val ago = now - changed
                    if (ago <= 10_000L) " (CHANGED ${"%.1f".format(ago / 1000.0)}s ago)" else " (last changed ${ago / 1000}s ago)"
                }
            }
            val lost = when (val lostAt = snapshot.lastLostAtMillis) {
                null -> " | last onLost: never"
                else -> " | last onLost: ${((now - lostAt) / 1000.0)}s ago"
            }
            return head + change + lost + " | " + snapshot.capabilitiesLine()
        }
    }
}
