package com.sojibhuiya.jarves

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the microphone session alive while the app is not in front, using the **microphone**
 * foreground-service type.
 *
 * Why this exists (docs, not guesswork):
 *  * the Live session is a long-lived WebSocket plus continuous `AudioRecord` capture; when the
 *    activity is destroyed or the app is cached, Android is free to freeze or kill the process, and
 *    the socket then dies mid-conversation — exactly the 0.2.8 phone report;
 *  * a microphone foreground service is the documented way to "continue microphone capture from the
 *    background, such as voice recorders or communication apps" —
 *    https://developer.android.com/develop/background-work/services/fgs/service-types#microphone
 *
 * Required pieces, straight from that page:
 *  * manifest `android:foregroundServiceType="microphone"`;
 *  * permission `FOREGROUND_SERVICE_MICROPHONE` **plus** `FOREGROUND_SERVICE` (Android 14+:
 *    "you must declare the service type in your app manifest, and also request the appropriate
 *    foreground service permission for that type (in addition to requesting the FOREGROUND_SERVICE
 *    permission)" — https://developer.android.com/develop/background-work/services/fgs/service-types);
 *  * runtime `RECORD_AUDIO` already granted (the app asks before starting a session);
 *  * `startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)` from API 29.
 *
 * It must be **started while the app is visible**: "you cannot create a microphone foreground
 * service while your app is in the background ... RECORD_AUDIO ... is subject to while-in-use
 * restrictions" (https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#while-in-use-permission).
 * Jarves only calls [start] from the moment the user taps the mic button, i.e. with the activity in
 * front, and [stop] as soon as the session ends.
 *
 * The service owns no audio. It permits background capture, not uninterrupted CPU/network:
 * https://developer.android.com/training/monitoring-device-state/doze-standby
 */
class LiveSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 0.2.10: ActivityManager#getRunningServices is deprecated for third-party apps, so the
        // diagnostics answer "is the service running?" from this flag instead of asking Android.
        active.set(true)
    }

    override fun onDestroy() {
        active.set(false)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Not sticky: if the system kills us, the session is already gone; a silent restart of a
        // notification with no audio would only confuse.
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Jarves ভয়েস সেশন চলাকালীন দেখানো হয়"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Jarves শুনছে")
            .setContentText("ভয়েস সেশন চলছে — বন্ধ করতে অ্যাপে ফিরে বাটনে চাপুন")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "jarves_live_session"

        /** True while the foreground service exists. Read by the diagnostics block. */
        private val active = java.util.concurrent.atomic.AtomicBoolean(false)

        fun isRunning(): Boolean = active.get()
        private const val NOTIFICATION_ID = 4101

        /**
         * Starts (or refreshes) the foreground service. Must be called from a visible activity.
         * Never throws: if the platform refuses, the session still runs in the foreground.
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, LiveSessionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        /** Stops the service and removes the notification. Safe to call when it is not running. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LiveSessionService::class.java)) }
            active.set(false)
        }
    }
}
