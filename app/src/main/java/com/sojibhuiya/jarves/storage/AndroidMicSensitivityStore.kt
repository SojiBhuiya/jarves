package com.sojibhuiya.jarves.storage

import android.content.Context
import com.sojibhuiya.jarves.intelligence.MicSensitivityStore

/**
 * Stores the chosen mic sensitivity in its own SharedPreferences file. Nothing secret is stored —
 * just the option name — and the app's DataStore schema stays untouched.
 */
class AndroidMicSensitivityStore(context: Context) : MicSensitivityStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(value: String) {
        prefs.edit().putString(KEY, value).apply()
    }

    private companion object {
        const val FILE = "voice_mic_sensitivity"
        const val KEY = "sensitivity"
    }
}
