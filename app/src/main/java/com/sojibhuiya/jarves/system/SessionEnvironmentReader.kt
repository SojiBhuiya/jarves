package com.sojibhuiya.jarves.system

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.app.KeyguardManager
import com.sojibhuiya.jarves.intelligence.SessionEnvironment
import com.sojibhuiya.jarves.intelligence.SessionEnvironmentProvider
import java.util.concurrent.atomic.AtomicInteger

/**
 * Answers "what was the phone doing when the socket died?" with real readings instead of guesses.
 *
 * Android APIs used (all documented):
 *  * `PowerManager.isInteractive` — https://developer.android.com/reference/android/os/PowerManager#isInteractive()
 *  * `KeyguardManager.isKeyguardLocked` — https://developer.android.com/reference/android/app/KeyguardManager#isKeyguardLocked()
 *  * `PowerManager.isPowerSaveMode` — https://developer.android.com/reference/android/os/PowerManager#isPowerSaveMode()
 *  * `ConnectivityManager.registerDefaultNetworkCallback` — https://developer.android.com/reference/android/net/ConnectivityManager#registerDefaultNetworkCallback(android.net.ConnectivityManager.NetworkCallback)
 *  * `Application.ActivityLifecycleCallbacks` — https://developer.android.com/reference/android/app/Application.ActivityLifecycleCallbacks
 *
 * Every reading is wrapped: a missing service or a revoked permission must never break a session,
 * it only makes the field "unknown".
 */
class SessionEnvironmentReader(context: Context) : SessionEnvironmentProvider {

    private val app = context.applicationContext
    private val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val startedActivities = AtomicInteger(0)

    @Volatile private var lastNetworkChangeAt: Long? = null
    @Volatile private var networkType: String = "unknown"
    @Volatile private var watching = false

    init {
        runCatching {
            (app as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivities.incrementAndGet()
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities.decrementAndGet()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }
        startNetworkWatch()
    }

    /**
     * Records when the default network last changed and what it is now. `registerDefaultNetworkCallback`
     * needs no permission on any API level the app supports (minSdk 26).
     */
    private fun startNetworkWatch() {
        val manager = connectivity ?: return
        runCatching {
            readCurrentNetwork(manager)
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    markChange(manager)
                }

                override fun onLost(network: Network) {
                    markChange(manager)
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    // Transport changes (wifi <-> mobile) arrive here without onAvailable/onLost.
                    val type = typeOf(capabilities)
                    if (type != networkType) {
                        networkType = type
                        lastNetworkChangeAt = System.currentTimeMillis()
                    }
                }
            })
            watching = true
        }
    }

    private fun markChange(manager: ConnectivityManager) {
        lastNetworkChangeAt = System.currentTimeMillis()
        readCurrentNetwork(manager)
    }

    private fun readCurrentNetwork(manager: ConnectivityManager) {
        runCatching {
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            networkType = typeOf(caps)
        }
    }

    private fun typeOf(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "none"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        else -> "other"
    }

    override fun snapshot(): SessionEnvironment {
        val changedAgo = lastNetworkChangeAt?.let { System.currentTimeMillis() - it }
        return SessionEnvironment(
            appVisible = startedActivities.get() > 0,
            screenInteractive = runCatching { power?.isInteractive }.getOrNull(),
            keyguardLocked = runCatching { keyguard?.isKeyguardLocked }.getOrNull(),
            networkType = networkType,
            networkChangedAgoMillis = changedAgo,
            powerSaveMode = runCatching { power?.isPowerSaveMode }.getOrNull()
        )
    }

    /** Diagnostics: was the connectivity callback actually registered? */
    fun isWatchingNetwork(): Boolean = watching

    companion object {
        /** API level guard kept explicit for readers: the callback exists since API 24. */
        val REQUIRES_API: Int = Build.VERSION_CODES.N
    }
}
