package com.sojibhuiya.jarves.intelligence

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.19: the re-verify cache rule.
 *
 * The 0.2.18 phone report asked the dollar rate twice and got the same "১২৪.৩১ টাকা" both
 * times — the second answer came from the 120 s cache, so a "শিওর?" re-check would have
 * repeated a stale number and called it fresh. A re-verification must run fresh; an ordinary
 * repeat still uses the cache.
 */
class CachedSearchTest {

    /** Counts how many real searches happened; each one returns a different answer. */
    private class CountingBackend : SearchBackend {
        var searches = 0
        override fun describe(): String = "counting"
        override suspend fun search(query: String): GroundedAnswer {
            searches++
            return GroundedAnswer(query = query, text = "answer $searches")
        }
    }

    @Test
    fun aRepeatedQuestionIsAnsweredFromTheCache() = runBlocking {
        val inner = CountingBackend()
        val cached = CachedSearchBackend(inner)

        val first = cached.search("দাম কত?")
        val second = cached.search("দাম কত?")

        assertEquals("only one real search", 1, inner.searches)
        assertEquals(first.text, second.text)
        assertTrue("the cached copy says so", second.detail.contains("cached"))
    }

    @Test
    fun aReVerificationRunsFreshEvenWithinTheCacheWindow() = runBlocking {
        val inner = CountingBackend()
        val cached = CachedSearchBackend(inner)

        val first = cached.search("দাম কত?")
        val fresh = cached.searchFresh("দাম কত?")

        assertEquals("the re-check opened a second real search", 2, inner.searches)
        assertEquals("answer 1", first.text)
        assertEquals("answer 2", fresh.text)
        assertFalse("a fresh answer is never marked cached", fresh.detail.contains("cached"))
    }

    @Test
    fun aFreshAnswerUpdatesTheCacheForTheNextOrdinarySearch() = runBlocking {
        val inner = CountingBackend()
        val cached = CachedSearchBackend(inner)

        cached.search("দাম কত?")
        cached.searchFresh("দাম কত?")
        val third = cached.search("দাম কত?")

        assertEquals("no third real search", 2, inner.searches)
        assertEquals("the ordinary search now sees the fresh answer", "answer 2", third.text)
    }

    @Test
    fun aFailedSearchIsNeverCachedSoTheNextTryIsReal() = runBlocking {
        val failing = object : SearchBackend {
            var searches = 0
            override fun describe(): String = "failing"
            override suspend fun search(query: String): GroundedAnswer {
                searches++
                return GroundedAnswer(query = query, text = "", failure = GroundedSearchFailure.TIMEOUT)
            }
        }
        val cached = CachedSearchBackend(failing)
        cached.search("দাম কত?")
        cached.search("দাম কত?")
        assertEquals("failures are never cached", 2, failing.searches)
    }
}
