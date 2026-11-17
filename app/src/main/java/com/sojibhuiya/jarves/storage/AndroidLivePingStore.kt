package com.sojibhuiya.jarves.storage

import android.content.Context
import com.sojibhuiya.jarves.intelligence.LivePingStore

/**
 * 0.2.13: stores the hidden WebSocket-ping experiment switch (`0` = off, `15`, `20`) in its own
 * SharedPreferences file, exactly like the mic-sensitivity setting. Nothing secret is stored, and
 * the app's DataStore schema stays untouched.
 */
class AndroidLivePingStore(context: Context) : LivePingStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(value: String) {
        prefs.edit().putString(KEY, value).apply()
    }

    private companion object {
        const val FILE = "voice_live_ping"
        const val KEY = "seconds"
    }
}
