package com.sojibhuiya.jarves.youtube

import com.sojibhuiya.jarves.apiproviders.testCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeApiKeyStoreTest {

    private fun store(): Pair<YouTubeApiKeyStore, () -> String?> {
        var stored: String? = null
        val store = YouTubeApiKeyStore(
            readEncrypted = { stored },
            writeEncrypted = { stored = it },
            cipher = testCipher()
        )
        return store to { stored }
    }

    @Test
    fun saveAndLoadRoundTrip() = runBlocking {
        val (store, _) = store()
        assertNull(store.load())
        store.save("  yt-key-123  ")
        assertEquals("yt-key-123", store.load())
        assertTrue(store.hasKey())
    }

    @Test
    fun storedValueIsCiphertextNeverThePlainKey() = runBlocking {
        val (store, readStored) = store()
        store.save("yt-key-123")
        val stored = readStored()
        assertTrue(stored != null && !stored.contains("yt-key-123"))
    }

    @Test
    fun corruptStoredValueLoadsAsNull() = runBlocking {
        var stored: String? = "not-valid-base64!!!"
        val store = YouTubeApiKeyStore(
            readEncrypted = { stored },
            writeEncrypted = { stored = it },
            cipher = testCipher()
        )
        assertNull(store.load())
        assertFalse(store.hasKey())
    }

    @Test
    fun clearRemovesTheKey() = runBlocking {
        val (store, _) = store()
        store.save("yt-key-123")
        store.clear()
        assertNull(store.load())
    }
}
