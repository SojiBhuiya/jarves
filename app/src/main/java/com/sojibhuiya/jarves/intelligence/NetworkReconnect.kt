package com.sojibhuiya.jarves.intelligence

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
    val unknownReason: String = ""
) {
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

    /** Called when a default network becomes available (including a switch wifi <-> mobile). */
    fun onAvailable(listener: (NetworkSnapshot) -> Unit)

    /** Called when the default network is lost. */
    fun onLost(listener: (NetworkSnapshot) -> Unit)

    companion object {
        /** Used by plain-JVM tests and as the safe default. */
        val NONE: NetworkMonitor = object : NetworkMonitor {
            override fun snapshot(): NetworkSnapshot = NetworkSnapshot.UNKNOWN
            override fun onAvailable(listener: (NetworkSnapshot) -> Unit) = Unit
            override fun onLost(listener: (NetworkSnapshot) -> Unit) = Unit
        }
    }
}

/** What the reconnect machinery should do next. */
sealed interface ReconnectAction {
    /**
     * There is no usable network: spend **no** attempt, wait for `onAvailable` until [deadline].
     */
    data class WaitForNetwork(val deadlineMillis: Long) : ReconnectAction

    /** Open a new socket after [delayMillis], using the stored resume handle. */
    data class Attempt(val attempt: Int, val delayMillis: Long) : ReconnectAction

    /** Nothing to do (the user stopped, or the session is not running). */
    data object None : ReconnectAction

    /** Stop trying. [message] is the Bengali line the user sees. */
    data class GiveUp(val reason: GiveUpReason, val message: String) : ReconnectAction
}

enum class GiveUpReason {
    /** 3 attempts on a network that was working. */
    ATTEMPTS_EXHAUSTED,

    /** The network never came back within the deadline. */
    NO_NETWORK_TIMEOUT
}

/**
 * The network-aware reconnect state machine.
 *
 * Rules (each one is a unit test):
 *  * while the network is down, **no attempt is spent** — the app waits for `onAvailable`;
 *  * when the network comes back, the first attempt runs after ~1 s, then 1 s / 2 s / 4 s of
 *    backoff on repeated failures, at most [maxAttempts] attempts on a working network;
 *  * give up after [networkWaitMillis] (2 min) without network, or after [maxAttempts] failed
 *    attempts on a working network;
 *  * a new default network during a healthy session triggers one proactive reconnect.
 *
 * Pure Kotlin: no Android, no clock of its own (every call takes `now`), so the whole behaviour —
 * including "airplane mode for 10 s" — is provable in a unit test.
 */
class ReconnectCoordinator(
    private val maxAttempts: Int = 3,
    private val networkWaitMillis: Long = 120_000L,
    private val firstAttemptDelayMillis: Long = 1_000L,
    private val backoffMillis: List<Long> = listOf(1_000L, 2_000L, 4_000L),
    /** A proactive reconnect is not repeated within this window (avoids churn on flapping links). */
    private val proactiveCooldownMillis: Long = 10_000L
) {

    private val events = mutableListOf<String>()

    var attemptsUsed: Int = 0
        private set

    var waitingForNetwork: Boolean = false
        private set

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

    /** Resets everything for a brand-new session (the user started talking again). */
    fun reset() {
        attemptsUsed = 0
        waitingForNetwork = false
        gaveUp = false
        giveUpReason = null
        lastAttemptAtMillis = 0L
        events.clear()
    }

    /**
     * An unexpected socket failure (or an audio-send failure, which is the same thing one step
     * earlier). Decides between waiting for the network, an immediate attempt and giving up.
     */
    fun onUnexpectedFailure(now: Long, network: NetworkSnapshot, reason: String): ReconnectAction {
        events += "${event(now)} failure (${reason.take(40)})"
        if (!network.available) {
            if (!waitingForNetwork) {
                waitingForNetwork = true
                events += "${event(now)} waiting for network (no attempt spent)"
            }
            // No attempt is spent while the network is down; the caller re-checks the deadline with
            // checkNetworkDeadline(...) so a phone that stays offline ends cleanly after 2 minutes.
            return ReconnectAction.WaitForNetwork(now + networkWaitMillis)
        }
        return attempt(now, network)
    }

    /**
     * Called on a timer while waiting, so a phone that stays offline for two minutes ends cleanly
     * instead of waiting forever.
     */
    fun checkNetworkDeadline(now: Long, waitingSince: Long): ReconnectAction =
        if (waitingForNetwork && now - waitingSince >= networkWaitMillis) {
            giveUp(now, GiveUpReason.NO_NETWORK_TIMEOUT)
        } else {
            ReconnectAction.None
        }

    /** A default network appeared. If we were waiting, this is the first attempt (~1 s later). */
    fun onNetworkAvailable(now: Long, network: NetworkSnapshot): ReconnectAction {
        if (gaveUp) return ReconnectAction.None
        if (!waitingForNetwork && attemptsUsed == 0 && lastAttemptAtMillis == 0L) {
            // A brand-new network during a healthy session: proactive reconnect.
            return proactive(now, network)
        }
        waitingForNetwork = false
        events += "${event(now)} network available (${network.type})"
        // The network returning is not a failed attempt: the budget starts counting now.
        attemptsUsed = 0
        events += "${event(now)} waiting ${firstAttemptDelayMillis}ms before first attempt"
        return ReconnectAction.Attempt(attempt = 1, delayMillis = firstAttemptDelayMillis)
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
        waitingForNetwork = false
        events += "${event(now)} reconnected"
    }

    /** The socket that the reconnect opened failed too. */
    fun onAttemptFailed(now: Long, network: NetworkSnapshot): ReconnectAction {
        events += "${event(now)} attempt failed"
        if (!network.available) {
            waitingForNetwork = true
            events += "${event(now)} waiting for network"
            return ReconnectAction.WaitForNetwork(now + networkWaitMillis)
        }
        return attempt(now, network)
    }

    fun onNetworkLost(now: Long) {
        waitingForNetwork = true
        events += "${event(now)} network lost"
    }

    private fun attempt(now: Long, network: NetworkSnapshot): ReconnectAction {
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
        return ReconnectAction.GiveUp(reason, messageFor(reason))
    }

    private fun event(now: Long): String = "${now / 1000.0}s"

    companion object {

        /** How long the app waits for the network before giving up (task: "about 2 minutes"). */
        const val NETWORK_WAIT_SECONDS = 120L

        /** Shown as "/3" in the status line. */
        const val MAX_ATTEMPTS_DISPLAY = 3

        const val STATUS_WAITING_FOR_NETWORK = "ইন্টারনেটের অপেক্ষা করছি…"
        const val STATUS_RECONNECTED = "পুনরায় সংযুক্ত"
        const val STATUS_CONTEXT_MAY_BE_LOST =
            "আগের কথোপকথনটা হয়তো মনে রাখতে পারব না — নতুন করে শুরু করছি।"

        fun messageFor(reason: GiveUpReason): String = when (reason) {
            GiveUpReason.NO_NETWORK_TIMEOUT ->
                "প্রায় ২ মিনিট ধরে ইন্টারনেট নেই, তাই সংযোগ বন্ধ করছি। ইন্টারনেট চালু করে আবার শুরু করুন।"
            GiveUpReason.ATTEMPTS_EXHAUSTED ->
                "ইন্টারনেট ঠিক আছে, কিন্তু ৩ বার চেষ্টা করেও সংযোগ হয়নি। একটু পরে আবার শুরু করুন।"
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
            return head + change + lost
        }
    }
}
