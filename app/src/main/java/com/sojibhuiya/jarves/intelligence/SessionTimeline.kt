package com.sojibhuiya.jarves.intelligence

/**
 * 0.2.16 (task C / task 1) — the session timeline: every network change, stall, cancellation and
 * reconnect attempt with `t+seconds`, so a phone log explains itself without a packet capture.
 *
 * Why the rule changed (0.2.15 phone evidence): the diagnostics said `network=mobile (CHANGED)`
 * while the user was standing still on a working connection. `onCapabilitiesChanged` fires whenever
 * the radio's capabilities move (signal strength, bandwidth estimate, …), and 0.2.14/0.2.15 counted
 * that as a network change. The Android docs are clear that these callbacks mean different things:
 *
 *  * `onAvailable(Network)` — "Called when the framework connects and has declared a new network
 *    ready for use. … For callbacks registered with … `registerDefaultNetworkCallback(NetworkCallback)`,
 *    this means the network passed as an argument is the new best network for this request …"
 *  * `onLost(Network)` — "Called when a network disconnects or otherwise no longer satisfies this
 *    request or callback."
 *  * `onCapabilitiesChanged(Network, NetworkCapabilities)` — "Called when the network corresponding
 *    to this request changes capabilities but still satisfies the requested criteria."
 *  * `onLinkPropertiesChanged(Network, LinkProperties)` — "Called when the network corresponding to
 *    this request changes `LinkProperties`."
 *    (all four: https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)
 *
 * So only **a different default network object**, **a different transport type** (wifi ⇄ mobile),
 * or **`onLost` for the default network** is a change. Capability and link-property updates are
 * counted separately as `capability updates: N` and never trigger a reconnect.
 *
 * Pure Kotlin (fake clock in the tests). The Android side only reports what it saw.
 */
class SessionTimeline(private val capacity: Int = 120) {

    private val events = ArrayDeque<String>()

    /** The reference for every `t+…` value; set when the session starts. */
    var sessionStartMillis: Long = 0L
        private set

    var networkChanges: Int = 0
        private set

    var capabilityUpdates: Int = 0
        private set

    /** Losses of the default network (the only event that may start the wait-for-network path). */
    var networkLosses: Int = 0
        private set

    var lastChangeAtMillis: Long = 0L
        private set

    fun start(now: Long) {
        // 0.0 means "the caller started the session at an unknown instant"; a real start instant is
        // never 0 in production, and treating it honestly keeps the t+ stamps meaningful in tests.
        sessionStartMillis = if (now <= 0L) 0L else now
        events.clear()
        networkChanges = 0
        capabilityUpdates = 0
        networkLosses = 0
        lastChangeAtMillis = 0L
    }

    fun mark(now: Long, line: String) {
        events.addLast("${stamp(now)} $line")
        while (events.size > capacity) events.removeFirst()
    }

    /**
     * One `NetworkCallback` event, classified.
     *
     * @param kind `available` / `lost` / `capabilities` / `linkProperties` (the callback that fired).
     * @param newNetworkId the identity of the default network object, `-1` when not known.
     * @param previousNetworkId the identity stored for the previous default network.
     * @param newType `wifi` / `mobile` / … ; [NetworkSnapshot.TYPE_UNKNOWN] when unknown.
     * @param previousType the previously stored transport type.
     * @return true when this event is a real change (worth a timeline line and possibly a reconnect).
     */
    fun onNetworkEvent(
        now: Long,
        kind: String,
        newNetworkId: Long,
        previousNetworkId: Long,
        newType: String,
        previousType: String
    ): Boolean {
        val networkReplaced = newNetworkId >= 0L && previousNetworkId >= 0L && newNetworkId != previousNetworkId
        val transportChanged = newType != NetworkSnapshot.TYPE_UNKNOWN &&
            previousType != NetworkSnapshot.TYPE_UNKNOWN &&
            newType != previousType
        val lost = kind == "lost"
        if (!lost && !networkReplaced && !transportChanged) {
            // Signal strength, bandwidth estimates, link properties: counted, never a change.
            capabilityUpdates += 1
            return false
        }
        networkChanges += 1
        if (lost) networkLosses += 1
        lastChangeAtMillis = now
        val why = when {
            lost -> "onLost"
            networkReplaced -> "new default network"
            else -> "transport $previousType -> $newType"
        }
        mark(now, "network change ($why, $newType)")
        return true
    }

    fun lines(): List<String> = events.toList()

    /** `N` and the last few lines, for the compact diagnostics block. */
    fun summary(keep: Int = 8): String {
        // Task C: the counts are ALWAYS printed — "capability updates: N" is the line that explains
        // the 0.2.15 "CHANGED" that was not a change. Only the tail of the timeline is omitted.
        val counts = "changes=$networkChanges | capability updates: $capabilityUpdates | " +
            "losses=$networkLosses"
        if (events.isEmpty()) return "$counts | (no timeline entries yet)"
        return counts + " | " + events.toList().takeLast(keep).joinToString(" | ")
    }

    fun text(): String = if (events.isEmpty()) "none" else events.joinToString(" -> ")

    private fun stamp(now: Long): String =
        if (sessionStartMillis <= 0L) "t+0.0s" else formatRelativeMillis(now, sessionStartMillis)
}
