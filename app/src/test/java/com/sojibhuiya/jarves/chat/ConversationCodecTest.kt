package com.sojibhuiya.jarves.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCodecTest {

    private fun conversation(id: String, messages: List<ChatMessage>) = Conversation(
        id = id,
        title = "Title $id",
        createdAt = 1_000L,
        updatedAt = 2_000L,
        messages = messages
    )

    private fun message(conversationId: String, text: String, fromUser: Boolean, at: Long) =
        ChatMessage(
            id = "m-$at",
            conversationId = conversationId,
            text = text,
            fromUser = fromUser,
            timestamp = at
        )

    @Test
    fun roundTripsConversationWithMessages() {
        val original = listOf(
            conversation(
                "c1",
                listOf(
                    message("c1", "হ্যালো, কেমন আছ?", true, 1),
                    message("c1", "I am doing well, thank you!", false, 2)
                )
            )
        )
        val decoded = ConversationCodec.decode(ConversationCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun preservesBanglaAndSpecialCharacters() {
        val text = "আমি \"বাংলা\" লিখছি\nwith a newline\ttab & \\ backslash"
        val original = listOf(conversation("c1", listOf(message("c1", text, true, 1))))
        val decoded = ConversationCodec.decode(ConversationCodec.encode(original))
        assertEquals(text, decoded.single().messages.single().text)
    }

    @Test
    fun preservesMessageOrder() {
        val messages = (1..25).map { message("c1", "turn $it", it % 2 == 1, it.toLong()) }
        val decoded = ConversationCodec.decode(ConversationCodec.encode(listOf(conversation("c1", messages))))
        assertEquals(messages.map { it.text }, decoded.single().messages.map { it.text })
        assertEquals(messages.map { it.fromUser }, decoded.single().messages.map { it.fromUser })
    }

    @Test
    fun attachesMessagesToTheirOwnConversation() {
        val encoded = ConversationCodec.encode(
            listOf(
                conversation("a", listOf(message("a", "only A", true, 1))),
                conversation("b", listOf(message("b", "only B", false, 2)))
            )
        )
        val decoded = ConversationCodec.decode(encoded)
        assertEquals(listOf("only A"), decoded.first { it.id == "a" }.messages.map { it.text })
        assertEquals(listOf("only B"), decoded.first { it.id == "b" }.messages.map { it.text })
        assertTrue(decoded.all { conversation -> conversation.messages.all { it.conversationId == conversation.id } })
    }

    @Test
    fun missingDocumentYieldsEmptyList() {
        assertEquals(emptyList<Conversation>(), ConversationCodec.decode(null))
        assertEquals(emptyList<Conversation>(), ConversationCodec.decode(""))
        assertEquals(emptyList<Conversation>(), ConversationCodec.decode("   "))
    }

    @Test
    fun malformedDocumentYieldsEmptyListInsteadOfCrashing() {
        assertEquals(emptyList<Conversation>(), ConversationCodec.decode("{not json"))
        assertEquals(emptyList<Conversation>(), ConversationCodec.decode("{\"version\":1}"))
        assertEquals(emptyList<Conversation>(), ConversationCodec.decode("[1,2,3]"))
    }

    @Test
    fun skipsDamagedConversationButKeepsTheRest() {
        val good = ConversationCodec.encode(listOf(conversation("c1", listOf(message("c1", "kept", true, 1))))).let {
            // Inject a conversation without an id between the envelope and the valid one.
            it.replace("\"conversations\":[", "\"conversations\":[{\"title\":\"broken\"},")
        }
        val decoded = ConversationCodec.decode(good)
        assertEquals(1, decoded.size)
        assertEquals("c1", decoded.single().id)
    }

    @Test
    fun blankMessagesAreNotRehydrated() {
        val encoded = ConversationCodec.encode(listOf(conversation("c1", emptyList())))
        assertEquals(emptyList<ChatMessage>(), ConversationCodec.decode(encoded).single().messages)
    }

    @Test
    fun updatedAtFallsBackToCreatedAtForOlderDocuments() {
        val raw = "{\"version\":1,\"conversations\":[{\"id\":\"c1\",\"title\":\"t\",\"createdAt\":42}]}"
        val decoded = ConversationCodec.decode(raw)
        assertEquals(42L, decoded.single().createdAt)
        assertEquals(42L, decoded.single().updatedAt)
    }

    @Test
    fun groundingSourcesArePersistedWithTheAssistantTurn() {
        val sources = listOf(
            ChatSource("The Daily Star", "https://www.thedailystar.net/news/a"),
            ChatSource("Wikipedia", "https://en.wikipedia.org/wiki/b")
        )
        val message = ChatMessage(
            id = "m1",
            conversationId = "c1",
            text = "The current Prime Minister is …",
            fromUser = false,
            timestamp = 5,
            sources = sources
        )
        val decoded = ConversationCodec.decode(ConversationCodec.encode(listOf(conversation("c1", listOf(message)))))
        assertEquals(sources, decoded.single().messages.single().sources)
    }

    @Test
    fun documentsWithoutSourcesStillDecode() {
        val raw = "{\"version\":1,\"conversations\":[{\"id\":\"c1\",\"title\":\"t\",\"createdAt\":1," +
            "\"messages\":[{\"id\":\"m\",\"text\":\"old turn\",\"fromUser\":false,\"timestamp\":2}]}]}"
        val decoded = ConversationCodec.decode(raw)
        assertTrue(decoded.single().messages.single().sources.isEmpty())
    }

    @Test
    fun titleDerivation() {
        assertEquals("New Chat", ConversationTitles.derive(null))
        assertEquals("New Chat", ConversationTitles.derive("   "))
        assertEquals("Book a flight to Dhaka", ConversationTitles.derive("  Book a flight\nto   Dhaka "))
        val long = "আমি আগামীকাল সকালে ঢাকা থেকে টরন্টো যাওয়ার একটি ফ্লাইট বুক করতে চাই"
        val derived = ConversationTitles.derive(long)
        assertTrue(derived.length <= ConversationTitles.MAX_LENGTH + 1)
        assertTrue(derived.endsWith("…"))
    }
}
