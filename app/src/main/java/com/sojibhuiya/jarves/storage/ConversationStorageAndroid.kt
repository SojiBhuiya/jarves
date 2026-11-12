package com.sojibhuiya.jarves.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.sojibhuiya.jarves.chat.ConversationRepository
import com.sojibhuiya.jarves.chat.ConversationStorage
import kotlinx.coroutines.flow.first

/**
 * Conversation storage on top of the app's existing DataStore file.
 *
 * Conversations use their own preference key, so provider records, ciphertext and the assistant
 * name are neither read nor rewritten by the chat feature.
 */
class DataStoreConversationStorage(private val context: Context) : ConversationStorage {

    override suspend fun read(): String? =
        context.jarvesDataStore.data.first()[PrefKeys.CONVERSATIONS_JSON]

    override suspend fun write(value: String) {
        context.jarvesDataStore.edit { it[PrefKeys.CONVERSATIONS_JSON] = value }
    }
}

object ConversationModule {
    fun repository(context: Context): ConversationRepository =
        ConversationRepository(DataStoreConversationStorage(context))
}
