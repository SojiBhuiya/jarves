package com.sojibhuiya.jarves.chat

import java.util.UUID

/** A web source cited by Google Search grounding for one assistant turn. */
data class ChatSource(val title: String, val url: String)

/** One turn stored in a conversation. Messages are always addressed to a conversation id. */
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val text: String,
    val fromUser: Boolean,
    val timestamp: Long,
    /** Grounding sources behind an assistant answer; empty for user turns. */
    val sources: List<ChatSource> = emptyList()
)

/** A single, independently addressable conversation. */
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList()
) {
    val messageCount: Int get() = messages.size

    /** True while nothing has been said yet (a freshly created New Chat). */
    val isEmpty: Boolean get() = messages.isEmpty()
}

/**
 * Conversation titles: the first thing the user said, collapsed to one readable line.
 * Falls back to [FALLBACK_TITLE] for an empty conversation.
 */
object ConversationTitles {

    const val FALLBACK_TITLE = "New Chat"
    const val MAX_LENGTH = 48

    fun derive(firstUserText: String?): String {
        val collapsed = firstUserText
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        if (collapsed.isEmpty()) return FALLBACK_TITLE
        if (collapsed.length <= MAX_LENGTH) return collapsed
        val cut = collapsed.substring(0, MAX_LENGTH).trimEnd()
        // Prefer breaking on a word boundary when one is close to the limit.
        val boundary = cut.lastIndexOf(' ')
        val head = if (boundary >= MAX_LENGTH / 2) cut.substring(0, boundary) else cut
        return head.trimEnd().trimEnd(',', '.', '।', '?', '!') + "…"
    }

    fun newId(): String = UUID.randomUUID().toString()
}
