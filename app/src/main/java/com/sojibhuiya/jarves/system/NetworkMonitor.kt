package com.sojibhuiya.jarves.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.sojibhuiya.jarves.intelligence.NetworkMonitor
import com.sojibhuiya.jarves.intelligence.NetworkSnapshot
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Watch the app's default network, not every network the device can see. Android recommends
 * consuming the capabilities delivered by the callback rather than synchronously querying inside
 * the callback (the query can be stale). VALIDATED describes the system probe, not Gemini DNS.
 * https://developer.android.com/develop/connectivity/network-ops/reading-network-state
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val availableListeners = CopyOnWriteArrayList<(NetworkSnapshot) -> Unit>()
    private val lostListeners = CopyOnWriteArrayList<(NetworkSnapshot) -> Unit>()
    @Volatile private var current = NetworkSnapshot.UNKNOWN
    @Volatile private var identity = -1L
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val id = network.networkHandle
            if (identity != id) {
                identity = id
                current = NetworkSnapshot(
                    available = true,
                    lastLostAtMillis = current.lastLostAtMillis,
                    changedAtMillis = System.currentTimeMillis()
                )
            }
            availableListeners.forEach { it(current) }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (identity != network.networkHandle) return
            val previous = current
            val type = typeOf(capabilities)
            val switched = previous.type != NetworkSnapshot.TYPE_UNKNOWN && previous.type != type
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            current = previous.copy(
                available = true,
                type = type,
                internet = internet,
                validated = validated,
                changedAtMillis = if (switched) System.currentTimeMillis() else previous.changedAtMillis
            )
            if (switched || validated && previous.validated != true ||
                internet && previous.internet != true || previous.type == NetworkSnapshot.TYPE_UNKNOWN) {
                availableListeners.forEach { it(current) }
            }
        }

        override fun onLost(network: Network) {
            if (identity != network.networkHandle) return
            identity = -1L
            val now = System.currentTimeMillis()
            current = NetworkSnapshot(false, NetworkSnapshot.TYPE_NONE, now, now, internet = false, validated = false)
            lostListeners.forEach { it(current) }
        }
    }

    init {
        try {
            if (manager != null) {
                // Seed once outside NetworkCallback; subsequent state comes from the callback.
                val active = manager.activeNetwork
                val caps = active?.let { manager.getNetworkCapabilities(it) }
                identity = active?.networkHandle ?: -1L
                current = NetworkSnapshot(
                    available = active != null,
                    type = if (active == null) NetworkSnapshot.TYPE_NONE else caps?.let(::typeOf) ?: NetworkSnapshot.TYPE_UNKNOWN,
                    internet = if (active == null) false else caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    validated = if (active == null) false else caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                )
                manager.registerDefaultNetworkCallback(callback)
                registered = true
            }
        } catch (e: Exception) {
            current = current.copy(unknownReason = e.javaClass.simpleName)
        }
    }

    override fun snapshot(): NetworkSnapshot = current
    override fun networkIdentity(): Long = identity
    override fun onAvailable(listener: (NetworkSnapshot) -> Unit) { availableListeners += listener }
    override fun onLost(listener: (NetworkSnapshot) -> Unit) { lostListeners += listener }

    override fun removeListeners(available: (NetworkSnapshot) -> Unit, lost: (NetworkSnapshot) -> Unit) {
        availableListeners -= available
        lostListeners -= lost
    }

    fun close() {
        if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun typeOf(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        else -> "other"
    }
}
