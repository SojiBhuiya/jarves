package com.sojibhuiya.jarves.system

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.sojibhuiya.jarves.intelligence.LiveScheduler
import com.sojibhuiya.jarves.intelligence.LiveSocketFlight
import com.sojibhuiya.jarves.intelligence.LiveTask
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class AndroidLiveScheduler : LiveScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "jarves-live-timer").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }

    // Includes deep sleep, unlike uptimeMillis(): https://developer.android.com/reference/android/os/SystemClock
    override fun nowMillis(): Long = SystemClock.elapsedRealtime()

    override fun after(delayMillis: Long, action: () -> Unit): LiveTask {
        val task = executor.schedule({ action() }, delayMillis, TimeUnit.MILLISECONDS)
        return LiveTask { task.cancel(false) }
    }

    override fun shutdown() { executor.shutdown() }
}

internal interface LiveSocketTransport {
    val idle: Boolean
    fun onIdle(action: (() -> Unit)?)
    fun open(request: Request, listener: WebSocketListener): WebSocket
}

internal class OkHttpVoiceTransport(pingIntervalSeconds: Int) : LiveSocketTransport {
    private companion object {
        // Shared across Stop → Start as cancelled DNS can outlive the previous engine. Auto's
        // separate search session has its own transport. OkHttp keeps cancelled calls counted
        // until AsyncCall finishes:
        // https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Dispatcher.kt
        val dispatcher = Dispatcher()
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectTimeout(LiveSocketFlight.OPEN_MILLIS, TimeUnit.MILLISECONDS)
        .callTimeout(LiveSocketFlight.OPEN_MILLIS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .apply { if (pingIntervalSeconds > 0) pingInterval(pingIntervalSeconds.toLong(), TimeUnit.SECONDS) }
        .build()

    override val idle: Boolean get() = dispatcher.runningCallsCount() == 0 && dispatcher.queuedCallsCount() == 0

    override fun onIdle(action: (() -> Unit)?) {
        dispatcher.idleCallback = action?.let { Runnable { it() } }
    }

    override fun open(request: Request, listener: WebSocketListener): WebSocket =
        client.newWebSocket(request, listener)
}

internal interface LiveWakeLock {
    val held: Boolean
    fun acquire(timeoutMillis: Long)
    fun release()

    companion object {
        val NONE = object : LiveWakeLock {
            override val held = false
            override fun acquire(timeoutMillis: Long) = Unit
            override fun release() = Unit
        }
    }
}

/** https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/set */
internal class AndroidLiveWakeLock(context: Context) : LiveWakeLock {
    private val lock by lazy {
        context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarves:LiveVoice")
            ?.apply { setReferenceCounted(false) }
    }
    override val held: Boolean get() = lock?.isHeld == true
    override fun acquire(timeoutMillis: Long) { lock?.acquire(timeoutMillis) }
    override fun release() { lock?.let { if (it.isHeld) it.release() } }
}

internal interface LiveMicrophone {
    val recording: Boolean
    fun start()
    fun read(buffer: ByteArray): Int
    fun stopAndRelease()
}
