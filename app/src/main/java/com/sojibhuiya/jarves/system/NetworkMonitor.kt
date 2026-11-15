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
    @Volatile private var failure: String = ""
    @Volatile private var registered = false

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
                    changedAt = System.currentTimeMillis()
                    availableState = true
                    availableListeners.forEach { runCatching { it(snapshot) } }
                }

                override fun onLost(network: Network) {
                    lastLostAt = System.currentTimeMillis()
                    readCurrent(cm)
                    val snapshot = snapshot()
                    lostListeners.forEach { runCatching { it(snapshot) } }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val next = typeOf(capabilities)
                    if (next != type) {
                        type = next
                        changedAt = System.currentTimeMillis()
                    }
                    availableState = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
