package com.sojibhuiya.jarves.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * The four "is something outside the app restricting us?" readings for the diagnostics block.
 *
 * Every value is `null` (printed as "unknown") only when Android genuinely cannot answer, and the
 * reason is then reported next to it.
 *
 * Docs:
 *  * `ActivityManager.getRunningServices` is **deprecated for third-party apps** on Android 8+, so
 *    "is LiveSessionService running?" is answered from our own flag, not by asking the system —
 *    https://developer.android.com/reference/android/app/ActivityManager#getRunningServices(int)
 *  * `PowerManager.isIgnoringBatteryOptimizations` —
 *    https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String)
 *  * `ConnectivityManager.getRestrictBackgroundStatus` (Data Saver) —
 *    https://developer.android.com/reference/android/net/ConnectivityManager#getRestrictBackgroundStatus()
 *  * `POST_NOTIFICATIONS` runtime permission (Android 13+) —
 *    https://developer.android.com/develop/ui/views/notifications/notification-permission
 */
object SystemChecks {

    /** Data Saver answer mapped to words. */
    fun dataSaverWord(status: Int?): String = when (status) {
        null -> "unknown"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "off (unrestricted)"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "on (app allowlisted)"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "ON (background data restricted)"
        else -> "unknown (status $status)"
    }

    fun isNotificationPermissionGranted(context: Context): Boolean? = runCatching {
        if (Build.VERSION.SDK_INT < 33) {
            // Before Android 13 notifications need no runtime permission at all.
            true
        } else {
            ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") ==
                PackageManager.PERMISSION_GRANTED
        }
    }.getOrNull()

    fun isIgnoringBatteryOptimizations(context: Context): Boolean? = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        power?.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrNull()

    fun dataSaverStatus(context: Context): Int? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) cm?.restrictBackgroundStatus else null
    }.getOrNull()

    /** True only when the app's own bookkeeping says the foreground service is up. */
    fun serviceRunningWord(running: Boolean): String = if (running) "running" else "not running"

    /**
     * The whole line, with the Service/notification/battery/DataSaver answers side by side.
     * [serviceRunning] comes from [com.sojibhuiya.jarves.LiveSessionService.isRunning]; the rest is
     * asked of Android here.
     */
    fun line(context: Context, serviceRunning: Boolean): String {
        val notification = isNotificationPermissionGranted(context)
        val battery = isIgnoringBatteryOptimizations(context)
        val dataSaver = dataSaverWord(dataSaverStatus(context))
        return buildString {
            append("Service: ").append(serviceRunningWord(serviceRunning))
            append(" | Notifications: ").append(
                when (notification) {
                    true -> "granted"
                    false -> "DENIED (foreground-service notification hidden)"
                    null -> "unknown (check failed)"
                }
            )
            append(" | Battery optimization exempt: ").append(
                when (battery) {
                    true -> "yes"
                    false -> "no (system may freeze background work)"
                    null -> "unknown (check failed)"
                }
            )
            append(" | Data Saver: ").append(dataSaver)
        }
    }
}
