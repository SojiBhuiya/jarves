package com.sojibhuiya.jarves.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Persistent storage for the serialized conversation list (DataStore in production). */
interface ConversationStorage {
    suspend fun read(): String?

    suspend fun write(value: String)
}

/**
 * Single source of truth for chats.
 *
 * Every conversation owns its messages, so a new conversation starts empty and switching between
 * conversations can never leak turns from another one. Writes are serialized with a mutex and read
 * back through [load], which returns the store's current state; a conversation id is only ever a
 * lookup key, never a global transcript.
 */
class ConversationRepository(
    private val storage: ConversationStorage,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {

    private val mutex = Mutex()

    /** All conversations, newest activity first. */
    suspend fun load(): List<Conversation> = mutex.withLock { readAll() }

    /** Creates and persists an empty conversation, then returns the full, ordered list. */
    suspend fun newConversation(): List<Conversation> = mutex.withLock {
        val now = clock()
        val conversation = Conversation(
            id = idGenerator(),
            title = ConversationTitles.FALLBACK_TITLE,
            createdAt = now,
            updatedAt = now
        )
        val all = readAll() + conversation
        write(all)
        ordered(all)
    }

    /**
     * Appends a turn to one conversation. Returns the ordered list, or `null` when the
     * conversation no longer exists (e.g. it was removed while a voice session was running).
     */
    suspend fun appendMessage(
        conversationId: String,
        text: String,
        fromUser: Boolean,
        sources: List<ChatSource> = emptyList()
    ): List<Conversation>? = mutex.withLock {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withLock null
        val all = readAll()
        val index = all.indexOfFirst { it.id == conversationId }
        if (index < 0) return@withLock null

        val now = clock()
        val conversation = all[index]
        val message = ChatMessage(
            id = idGenerator(),
            conversationId = conversationId,
            text = trimmed,
            fromUser = fromUser,
            timestamp = now,
            sources = if (fromUser) emptyList() else sources
        )
        val messages = conversation.messages + message
        val title = if (conversation.title == ConversationTitles.FALLBACK_TITLE && fromUser) {
            ConversationTitles.derive(trimmed)
        } else {
            conversation.title
        }
        val updated = conversation.copy(title = title, updatedAt = now, messages = messages)
        val next = all.toMutableList().also { it[index] = updated }
        write(next)
        ordered(next)
    }

    suspend fun find(conversationId: String): Conversation? = mutex.withLock {
        readAll().firstOrNull { it.id == conversationId }
    }

    private suspend fun readAll(): List<Conversation> {
        val raw = runCatching { storage.read() }.getOrNull()
        return ConversationCodec.decode(raw)
    }

    private suspend fun write(conversations: List<Conversation>) {
        runCatching { storage.write(ConversationCodec.encode(conversations)) }
    }

    /** Most recent activity first; ties resolved by creation time then id for stable ordering. */
    private fun ordered(conversations: List<Conversation>): List<Conversation> =
        conversations.sortedWith(
            compareByDescending<Conversation> { it.updatedAt }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.id }
        )
}
