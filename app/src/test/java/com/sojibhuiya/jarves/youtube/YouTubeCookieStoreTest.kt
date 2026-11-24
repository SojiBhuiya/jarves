package com.sojibhuiya.jarves.youtube

import com.sojibhuiya.jarves.apiproviders.testCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.2.25: the signed-in session cookie store — same shape as the API key store. */
class YouTubeCookieStoreTest {

    private fun store(): Pair<YouTubeCookieStore, () -> String?> {
        var stored: String? = null
        val store = YouTubeCookieStore(
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
        store.save("  SID=aaa; SAPISID=AbC123xYz  ")
        assertEquals("SID=aaa; SAPISID=AbC123xYz", store.load())
        assertTrue(store.hasCookies())
    }

    @Test
    fun storedValueIsCiphertextNeverThePlainCookies() = runBlocking {
        val (store, readStored) = store()
        store.save("SID=aaa; SAPISID=AbC123xYz")
        val stored = readStored()
        assertTrue(stored != null && !stored.contains("SAPISID=AbC123xYz"))
    }

    @Test
    fun corruptStoredValueLoadsAsNull() = runBlocking {
        var stored: String? = "not-valid-base64!!!"
        val store = YouTubeCookieStore(
            readEncrypted = { stored },
            writeEncrypted = { stored = it },
            cipher = testCipher()
        )
        assertNull(store.load())
        assertFalse(store.hasCookies())
    }

    @Test
    fun clearRemovesTheSession() = runBlocking {
        val (store, _) = store()
        store.save("SID=aaa; SAPISID=AbC123xYz")
        store.clear()
        assertNull(store.load())
    }
}
