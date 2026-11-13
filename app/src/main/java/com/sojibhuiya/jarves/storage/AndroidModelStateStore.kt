package com.sojibhuiya.jarves.storage

import android.content.Context
import com.sojibhuiya.jarves.intelligence.ModelStateStore

/**
 * Persists the search-model state (which model worked; which ones ran out of quota today)
 * in its own SharedPreferences file.
 *
 * A separate file on purpose: the app's real preferences live in DataStore, and adding a key there
 * would mean touching that schema and its migration path for a value that is pure cache. Nothing
 * in this file is secret — it holds model names and timestamps only, never the API key.
 */
class AndroidModelStateStore(context: Context) : ModelStateStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_STATE, null)

    override fun write(value: String) {
        prefs.edit().putString(KEY_STATE, value).apply()
    }

    private companion object {
        const val FILE = "search_model_state"
        const val KEY_STATE = "model_state_json"
    }
}
