package com.sojibhuiya.jarves.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory stand-in for the DataStore-backed storage. */
private class FakeConversationStorage(var value: String? = null) : ConversationStorage {
    override suspend fun read(): String? = value

    override suspend fun write(value: String) {
        this.value = value
    }
}

class ConversationRepositoryTest {

    private var now = 1_000L
    private var ids = 0

    private fun repository(storage: ConversationStorage) = ConversationRepository(
        storage = storage,
        clock = { now += 1_000; now },
        idGenerator = { "id-${++ids}" }
    )

    @Test
    fun newConversationIsEmptyAndHasUniqueId() = runBlocking {
        val storage = FakeConversationStorage()
        val repository = repository(storage)
        val afterFirst = repository.newConversation()
        val afterSecond = repository.newConversation()

        assertEquals(2, afterSecond.size)
        assertEquals(2, afterSecond.map { it.id }.distinct().size)
        assertNotEquals(afterFirst.single().id, afterSecond.first().id)
        assertTrue(afterSecond.all { it.isEmpty })
        assertEquals(ConversationTitles.FALLBACK_TITLE, afterSecond.first().title)
    }

    @Test
    fun messagesAreSavedUnderTheActiveConversationOnly() = runBlocking {
        val repository = repository(FakeConversationStorage())
        val first = repository.newConversation().first { it.isEmpty }.id
        val second = repository.newConversation().first { it.id != first }.id

        repository.appendMessage(first, "question in chat one", fromUser = true)
        repository.appendMessage(first, "answer in chat one", fromUser = false)
        repository.appendMessage(second, "question in chat two", fromUser = true)

        val all = repository.load()
        val chatOne = all.first { it.id == first }
        val chatTwo = all.first { it.id == second }

        assertEquals(listOf("question in chat one", "answer in chat one"), chatOne.messages.map { it.text })
        assertEquals(listOf("question in chat two"), chatTwo.messages.map { it.text })
        assertTrue(chatTwo.messages.all { it.conversationId == second })
    }

    @Test
    fun switchingConversationsNeverMixesMessages() = runBlocking {
        val repository = repository(FakeConversationStorage())
        val a = repository.newConversation().first().id
        repository.appendMessage(a, "A1", fromUser = true)
        val b = repository.newConversation().first { it.id != a }.id
        repository.appendMessage(b, "B1", fromUser = true)
        repository.appendMessage(a, "A2", fromUser = false)

        assertEquals(listOf("A1", "A2"), repository.find(a)!!.messages.map { it.text })
        assertEquals(listOf("B1"), repository.find(b)!!.messages.map { it.text })
    }

    @Test
    fun newChatStartsEmptyAndDoesNotTouchExistingChats() = runBlocking {
        val repository = repository(FakeConversationStorage())
        val existing = repository.newConversation().first().id
        repository.appendMessage(existing, "keep me", fromUser = true)

        val created = repository.newConversation().first { it.id != existing }
        assertTrue(created.isEmpty)
        assertEquals(listOf("keep me"), repository.find(existing)!!.messages.map { it.text })
    }

    @Test
    fun conversationsAreOrderedByMostRecentActivity() = runBlocking {
        val repository = repository(FakeConversationStorage())
        val first = repository.newConversation().first().id
        val second = repository.newConversation().first { it.id != first }.id
        repository.appendMessage(first, "bump the older chat", fromUser = true)

        assertEquals(first, repository.load().first().id)
        assertEquals(second, repository.load().last().id)
    }

    @Test
    fun conversationSurvivesRestart() = runBlocking {
        val storage = FakeConversationStorage()
        val firstRun = repository(storage)
        val id = firstRun.newConversation().first().id
        firstRun.appendMessage(id, "persisted turn", fromUser = true)

        // New process: fresh repository over the same persisted value.
        val secondRun = ConversationRepository(storage, clock = { 9_999L }, idGenerator = { "new-id" })
        val restored = secondRun.load()
        assertEquals(1, restored.size)
        assertEquals(id, restored.single().id)
        assertEquals(listOf("persisted turn"), restored.single().messages.map { it.text })
    }

    @Test
    fun firstUserMessageBecomesTitle() = runBlocking {
        val repository = repository(FakeConversationStorage())
        val id = repository.newConversation().first().id
        repository.appendMessage(id, "Plan a trip to Sylhet", fromUser = true)
        repository.appendMessage(id, "Sure, when would you like to go?", fromUser = false)

        assertEquals("Plan a trip to Sylhet", repository.find(id)!!.title)
    }

    @Test
    fun assistantMessageFirstDoesNotBecomeTitle() = runBlocking {
        val repository = repository(FakeConversationStorage())
        val id = repository.newConversation().first().id
        repository.appendMessage(id, "Hello, how can I help?", fromUser = false)
        assertEquals(ConversationTitles.FALLBACK_TITLE, repository.find(id)!!.title)

        repository.appendMessage(id, "আমার একটা প্রশ্ন আছে", fromUser = true)
        assertEquals("আমার একটা প্রশ্ন আছে", repository.find(id)!!.title)
    }

    @Test
    fun blankMessagesAreIgnored() = runBlocking {
        val repository = repository(FakeConversationStorage())
        val id = repository.newConversation().first().id
        val result = repository.appendMessage(id, "   ", fromUser = true)
        assertNull(result)
        assertTrue(repository.find(id)!!.messages.isEmpty())
    }

    @Test
    fun appendingToUnknownConversationIsRejected() = runBlocking {
        val repository = repository(FakeConversationStorage())
        repository.newConversation()
        assertNull(repository.appendMessage("does-not-exist", "hello", fromUser = true))
    }

    @Test
    fun conversationIdIsStableAndFindable() = runBlocking {
        val storage = FakeConversationStorage()
        val repository = repository(storage)
        val id = repository.newConversation().first().id
        repository.appendMessage(id, "turn", fromUser = true)
        assertNotNull(repository.find(id))
        assertNull(repository.find("other-id"))
        assertTrue(storage.value!!.contains(id))
    }
}
