package com.sojibhuiya.jarves.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/**
 * Same DataStore file (`assistant_settings`) the app already shipped, so existing installs keep
 * their assistant name, Gemini switch and Gemini ciphertext.
 *
 * Note: the Gemini API key is only ever stored here as AES-GCM ciphertext (legacy
 * `gemini_key_encrypted`), and the provider list holds ciphertext only.
 */
val Context.jarvesDataStore by preferencesDataStore(name = "assistant_settings")

object PrefKeys {
    val ASSISTANT_NAME = stringPreferencesKey("assistant_name")
    val GEMINI_ENABLED = booleanPreferencesKey("gemini_enabled")

    /** Legacy Gemini ciphertext (Android Keystore alias `agi_gemini_key_v1`). */
    val GEMINI_KEY_ENCRYPTED = stringPreferencesKey("gemini_key_encrypted")

    /** Serialized, encrypted API provider records. */
    val API_PROVIDERS_JSON = stringPreferencesKey("api_providers_json")

    /** Serialized chat conversations (own key — no existing preference is migrated or rewritten). */
    val CONVERSATIONS_JSON = stringPreferencesKey("conversations_json")
}
