package com.sojibhuiya.jarves.chat

import com.sojibhuiya.jarves.apiproviders.MiniJson

/**
 * Persistence format for the conversation store (one DataStore string value).
 *
 * ```
 * {"version":1,"conversations":[
 *   {"id":"…","title":"…","createdAt":0,"updatedAt":0,
 *    "messages":[{"id":"…","text":"…","fromUser":true,"timestamp":0}]}
 * ]}
 * ```
 *
 * Messages live inside their own conversation, so a conversation can only ever read back its own
 * turns. The key is new, so an existing install simply starts with no conversations — nothing else
 * in DataStore is read, rewritten or removed.
 *
 * Decoding is deliberately forgiving: a missing or unreadable document yields an empty list rather
 * than crashing the app, and one damaged conversation does not discard the others.
 */
object ConversationCodec {

    const val FORMAT_VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_CONVERSATIONS = "conversations"
    private const val KEY_ID = "id"
    private const val KEY_TITLE = "title"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_UPDATED_AT = "updatedAt"
    private const val KEY_MESSAGES = "messages"
    private const val KEY_TEXT = "text"
    private const val KEY_FROM_USER = "fromUser"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_SOURCES = "sources"
    private const val KEY_URL = "url"
    private const val KEY_SOURCE_TITLE = "title"

    fun encode(conversations: List<Conversation>): String {
        val encoded = conversations.map { conversation ->
            linkedMapOf<String, Any?>(
                KEY_ID to conversation.id,
                KEY_TITLE to conversation.title,
                KEY_CREATED_AT to conversation.createdAt,
                KEY_UPDATED_AT to conversation.updatedAt,
                KEY_MESSAGES to conversation.messages.map { message ->
                    linkedMapOf<String, Any?>(
                        KEY_ID to message.id,
                        KEY_TEXT to message.text,
                        KEY_FROM_USER to message.fromUser,
                        KEY_TIMESTAMP to message.timestamp,
                        KEY_SOURCES to message.sources.map { source ->
                            linkedMapOf<String, Any?>(
                                KEY_SOURCE_TITLE to source.title,
                                KEY_URL to source.url
                            )
                        }
                    )
                }
            )
        }
        return MiniJson.encode(linkedMapOf(KEY_VERSION to FORMAT_VERSION, KEY_CONVERSATIONS to encoded))
    }

    fun decode(text: String?): List<Conversation> {
        if (text.isNullOrBlank()) return emptyList()
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val rawConversations = root[KEY_CONVERSATIONS] as? List<*> ?: return emptyList()
        return rawConversations.mapNotNull { decodeConversation(it as? Map<*, *> ?: return@mapNotNull null) }
    }

    private fun decodeConversation(raw: Map<*, *>): Conversation? {
        val id = (raw[KEY_ID] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val createdAt = raw.number(KEY_CREATED_AT)
        val updatedAt = raw.number(KEY_UPDATED_AT)
        val messages = (raw[KEY_MESSAGES] as? List<*>)
            ?.mapNotNull { decodeMessage(it as? Map<*, *> ?: return@mapNotNull null, id) }
            .orEmpty()
        return Conversation(
            id = id,
            title = (raw[KEY_TITLE] as? String)?.takeIf { it.isNotBlank() }
                ?: ConversationTitles.FALLBACK_TITLE,
            createdAt = createdAt,
            // Survive documents written before `updatedAt` existed.
            updatedAt = if (updatedAt > 0L) updatedAt else createdAt,
            messages = messages
        )
    }

    private fun decodeMessage(raw: Map<*, *>, conversationId: String): ChatMessage? {
        val text = raw[KEY_TEXT] as? String ?: return null
        if (text.isBlank()) return null
        return ChatMessage(
            id = (raw[KEY_ID] as? String)?.takeIf { it.isNotBlank() } ?: ConversationTitles.newId(),
            conversationId = conversationId,
            text = text,
            fromUser = raw[KEY_FROM_USER] as? Boolean ?: false,
            timestamp = raw.number(KEY_TIMESTAMP),
            // Older documents have no sources key — they simply decode to an empty list.
            sources = (raw[KEY_SOURCES] as? List<*>).orEmpty().mapNotNull { entry ->
                val source = entry as? Map<*, *> ?: return@mapNotNull null
                val url = (source[KEY_URL] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ChatSource(
                    title = (source[KEY_SOURCE_TITLE] as? String)?.takeIf { it.isNotBlank() } ?: url,
                    url = url
                )
            }
        )
    }

    private fun Map<*, *>.number(key: String): Long = when (val value = this[key]) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        is Number -> value.toLong()
        else -> 0L
    }
}
