package com.sojibhuiya.jarves.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.sojibhuiya.jarves.apiproviders.ApiProviderStorage
import com.sojibhuiya.jarves.apiproviders.LegacyGeminiKeyStore
import kotlinx.coroutines.flow.first

/** Persists the encrypted provider list inside the existing app DataStore. */
class DataStoreApiProviderStorage(private val context: Context) : ApiProviderStorage {

    override suspend fun read(): String? =
        context.jarvesDataStore.data.first()[PrefKeys.API_PROVIDERS_JSON]

    override suspend fun write(value: String) {
        context.jarvesDataStore.edit { prefs -> prefs[PrefKeys.API_PROVIDERS_JSON] = value }
    }
}

/** Read-only access to the pre-provider-store Gemini configuration, plus its one-time cleanup. */
class DataStoreLegacyGeminiStore(private val context: Context) : LegacyGeminiKeyStore {

    override suspend fun legacyEncryptedKey(): String? =
        context.jarvesDataStore.data.first()[PrefKeys.GEMINI_KEY_ENCRYPTED]

    override suspend fun legacyGeminiEnabled(): Boolean =
        context.jarvesDataStore.data.first()[PrefKeys.GEMINI_ENABLED] ?: false

    override suspend fun clearLegacyEncryptedKey() {
        context.jarvesDataStore.edit { prefs -> prefs.remove(PrefKeys.GEMINI_KEY_ENCRYPTED) }
    }
}
