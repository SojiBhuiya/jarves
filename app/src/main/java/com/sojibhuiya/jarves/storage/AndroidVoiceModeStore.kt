package com.sojibhuiya.jarves.storage

import android.content.Context
import com.sojibhuiya.jarves.intelligence.VoiceModeStore

/**
 * Stores the chosen "Voice and search mode" in its own SharedPreferences file.
 *
 * Nothing secret is stored — just the mode name. The app's DataStore schema is untouched.
 */
class AndroidVoiceModeStore(context: Context) : VoiceModeStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_MODE, null)

    override fun write(value: String) {
        prefs.edit().putString(KEY_MODE, value).apply()
    }

    private companion object {
        const val FILE = "voice_search_mode"
        const val KEY_MODE = "mode"
    }
}
