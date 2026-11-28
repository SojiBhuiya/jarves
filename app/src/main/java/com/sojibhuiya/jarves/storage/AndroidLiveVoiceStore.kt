package com.sojibhuiya.jarves.storage

import android.content.Context
import com.sojibhuiya.jarves.intelligence.LiveVoiceStore

/**
 * Stores the chosen Live voice in its own SharedPreferences file.
 *
 * Nothing secret is stored — just the voice name. The app's DataStore schema is untouched.
 */
class AndroidLiveVoiceStore(context: Context) : LiveVoiceStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_VOICE, null)

    override fun write(value: String) {
        prefs.edit().putString(KEY_VOICE, value).apply()
    }

    private companion object {
        const val FILE = "live_voice"
        const val KEY_VOICE = "voice"
    }
}
