package com.sojibhuiya.jarves.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.sojibhuiya.jarves.intelligence.NetworkMonitor
import com.sojibhuiya.jarves.intelligence.NetworkSnapshot
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real network reader behind [com.sojibhuiya.jarves.intelligence.ReconnectCoordinator].
 *
 * **0.2.9 bug found here:** the app never declared `ACCESS_NETWORK_STATE`, so
 * `ConnectivityManager.registerDefaultNetworkCallback` threw `SecurityException`, the exception was
 * swallowed by `runCatching`, and the diagnostics line said `Network: unknown` forever. The
 * permission is declared in the manifest now (0.2.10), and if the call *still* fails the reason is
 * reported instead of hidden — see [unknownReason].
 *
 * Docs:
 *  * `ACCESS_NETWORK_STATE` — https://developer.android.com/reference/android/Manifest.permission#ACCESS_NETWORK_STATE
 *    ("Allows applications to access information about networks.")
 *  * `registerDefaultNetworkCallback` — https://developer.android.com/reference/android/net/ConnectivityManager#registerDefaultNetworkCallback(android.net.ConnectivityManager.NetworkCallback)
 *    ("Registers to receive notifications about changes in the application's default network.")
 *  * `NetworkCallback.onAvailable` / `onLost` — https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val availableListeners = CopyOnWriteArrayList<(NetworkSnapshot) -> Unit>()
    private val lostListeners = CopyOnWriteArrayList<(NetworkSnapshot) -> Unit>()

    @Volatile private var type: String = NetworkSnapshot.TYPE_UNKNOWN
    @Volatile private var availableState: Boolean = false
    @Volatile private var lastLostAt: Long? = null
    @Volatile private var changedAt: Long? = null
    @Volatile private var changes: Int = 0
    @Volatile private var failure: String = ""
    @Volatile private var registered = false

    // ---- 0.2.16 (task C): only a REAL change is a change -------------------------------------
    /**
     * Identity of the default network object and its transport type, so a change can be told apart
     * from noise. Android's own contract (quoted in [com.sojibhuiya.jarves.intelligence.SessionTimeline]):
     * `onAvailable` = "the framework connects and has declared a new network ready for use",
     * `onLost` = "a network disconnects", `onCapabilitiesChanged` = "changes capabilities but still
     * satisfies the requested criteria", `onLinkPropertiesChanged` = "changes LinkProperties"
     * (https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback).
     * Only the first two may therefore count as a change.
     */
    @Volatile private var lastNetworkId: Long = -1L
    @Volatile private var lastTransport: String = NetworkSnapshot.TYPE_UNKNOWN
    @Volatile private var capabilityUpdates: Int = 0

    /** Real changes counted this process: a new default network, a transport switch, an onLost. */
    fun networkChangeCount(): Int = changes

    /** Capability / link-property callbacks: counted, never treated as a change. */
    fun capabilityUpdateCount(): Int = capabilityUpdates

    private val changeListeners = CopyOnWriteArrayList<(NetworkSnapshot, String) -> Unit>()

    init {
        register()
    }

    private fun register() {
        val cm = manager
        if (cm == null) {
            failure = "ConnectivityManager unavailable"
            return
        }
        try {
            readCurrent(cm)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val snapshot = readCurrent(cm)
                    val id = identityOf(network)
                    val transport = typeOf(cm.getNetworkCapabilities(network))
                    // A new default network, or the same network object on a different transport:
                    // a real change. `onAvailable` always fires once at startup, which is fine — it is
                    // the first value, not a change of a value we already had.
                    if (lastNetworkId == -1L || id != lastNetworkId || transport != lastTransport) {
                        val wasKnown = lastNetworkId != -1L
                        lastNetworkId = id
                        lastTransport = transport
                        changedAt = System.currentTimeMillis()
                        availableState = true
                        if (wasKnown) {
                            changes += 1
                            changeListeners.forEach { runCatching { it(snapshot, "onAvailable($transport)") } }
                        }
                    } else {
                        capabilityUpdates += 1
                    }
                    availableListeners.forEach { runCatching { it(snapshot) } }
                }

                override fun onLost(network: Network) {
                    lastLostAt = System.currentTimeMillis()
                    if (lastNetworkId == -1L || identityOf(network) == lastNetworkId) {
                        changes += 1
                        lastNetworkId = -1L
                        lastTransport = NetworkSnapshot.TYPE_NONE
                        readCurrent(cm)
                        val snapshot = snapshot()
                        changeListeners.forEach { runCatching { it(snapshot, "onLost") } }
                        lostListeners.forEach { runCatching { it(snapshot) } }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    // 0.2.16 (task C): signal strength / bandwidth estimates land here. Count them,
                    // never treat them as a network change (the 0.2.15 log said "CHANGED" while the
                    // user stood still on a working connection).
                    val next = typeOf(capabilities)
                    if (next != type && next != NetworkSnapshot.TYPE_UNKNOWN) {
                        // The transport genuinely changed underneath the same network object.
                        type = next
                        lastTransport = next
                        changedAt = System.currentTimeMillis()
                        changes += 1
                        val snapshot = snapshot()
                        changeListeners.forEach { runCatching { it(snapshot, "transport($next)") } }
                    } else {
                        capabilityUpdates += 1
                    }
                    availableState = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                    // New IPs/DNS/routes: reported, but NOT a change on its own (the same network
                    // object keeps serving the same session).
                    capabilityUpdates += 1
                }
            })
            registered = true
            failure = ""
        } catch (e: SecurityException) {
            // Named, not hidden: the diagnostics say exactly why the network cannot be read.
            failure = "SecurityException (ACCESS_NETWORK_STATE missing?)"
        } catch (e: Exception) {
            failure = e.javaClass.simpleName
        }
    }

    private fun readCurrent(cm: ConnectivityManager): NetworkSnapshot {
        return try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            type = typeOf(caps)
            availableState = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            snapshot()
        } catch (e: SecurityException) {
            failure = "SecurityException (ACCESS_NETWORK_STATE missing?)"
            type = NetworkSnapshot.TYPE_UNKNOWN
            snapshot()
        }
    }

    /** A stable identity for a `Network` object: its hash, which the platform keeps per network. */
    private fun identityOf(network: Network): Long = network.hashCode().toLong()

    /** 0.2.16 (task C): the identity the engine compares against; -1 while unknown. */
    override fun networkIdentity(): Long = lastNetworkId

    /**
     * 0.2.16 (task C): a listener for REAL changes only — a new default network object, a transport
     * switch, or `onLost` for the default network. The second argument says which.
     */
    fun onChange(listener: (NetworkSnapshot, String) -> Unit) {
        changeListeners += listener
    }

    private fun typeOf(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> NetworkSnapshot.TYPE_NONE
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        else -> "other"
    }

    override fun snapshot(): NetworkSnapshot = NetworkSnapshot(
        available = availableState,
        type = type,
        lastLostAtMillis = lastLostAt,
        changedAtMillis = changedAt,
        unknownReason = failure
    )

    override fun onAvailable(listener: (NetworkSnapshot) -> Unit) {
        availableListeners += listener
    }

    override fun onLost(listener: (NetworkSnapshot) -> Unit) {
        lostListeners += listener
    }

    /** Diagnostics: did the platform accept our callback? */
    fun isRegistered(): Boolean = registered
}
