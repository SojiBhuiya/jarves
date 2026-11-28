package com.sojibhuiya.jarves.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.25: the signed-in session headers. The SAPISIDHASH vector was computed with
 * Python's hashlib (sha1("1700000000 AbC123xYz https://www.youtube.com")).
 */
class YouTubeAuthTest {

    @Test
    fun `sapisidHash matches the verified vector`() {
        assertEquals(
            "SAPISIDHASH 1700000000_af647c6748871978498852d0a9899cad9db1f759",
            YouTubeAuth.sapisidHash(
                sapisid = "AbC123xYz",
                timestampSeconds = 1_700_000_000L
            )
        )
    }

    @Test
    fun `sapisidHash matches the verified GAPIS vector`() {
        // 0.2.29: the hash for a GAPIS request is computed for the GAPIS origin.
        // Vector computed with Python's hashlib
        // (sha1("1700000000 AbC123xYz https://youtubei.googleapis.com")).
        assertEquals(
            "SAPISIDHASH 1700000000_d30169db32e3b40549789d3879ee1f84cf5fb9dc",
            YouTubeAuth.sapisidHash(
                sapisid = "AbC123xYz",
                origin = "https://youtubei.googleapis.com",
                timestampSeconds = 1_700_000_000L
            )
        )
    }

    @Test
    fun `authHeaders with GAPIS origin carry GAPIS origin and hash`() {
        // 0.2.29: a signed-in GAPIS request must carry the GAPIS origin, and the
        // hash must be the one computed for that origin — a www-origin hash
        // would not match, and YouTube would answer HTTP 400.
        val headers = YouTubeAuth.authHeaders(
            "SID=aaa; SAPISID=AbC123xYz; LOGIN_INFO=xyz",
            origin = "https://youtubei.googleapis.com"
        )
        assertEquals("https://youtubei.googleapis.com", headers["Origin"])
        assertEquals("https://youtubei.googleapis.com", headers["X-Origin"])
        val authorization = headers["Authorization"]!!
        val timestamp = authorization
            .removePrefix("SAPISIDHASH ").substringBefore("_").toLong()
        assertEquals(
            YouTubeAuth.sapisidHash(
                sapisid = "AbC123xYz",
                origin = "https://youtubei.googleapis.com",
                timestampSeconds = timestamp
            ),
            authorization
        )
    }

    @Test
    fun `authHeaders carry cookie sapisidhash origin`() {
        val headers = YouTubeAuth.authHeaders(
            "SID=aaa; SAPISID=AbC123xYz; LOGIN_INFO=xyz"
        )
        assertEquals("SID=aaa; SAPISID=AbC123xYz; LOGIN_INFO=xyz", headers["Cookie"])
        assertTrue(headers["Authorization"]!!.startsWith("SAPISIDHASH "))
        assertEquals("https://www.youtube.com", headers["Origin"])
        assertEquals("https://www.youtube.com", headers["X-Origin"])
        assertEquals("0", headers["X-Goog-AuthUser"])
    }

    @Test
    fun `authHeaders without sapisid carry only the cookie`() {
        val headers = YouTubeAuth.authHeaders("SID=aaa; LOGIN_INFO=xyz")
        assertEquals("SID=aaa; LOGIN_INFO=xyz", headers["Cookie"])
        assertNull(headers["Authorization"])
        assertNull(headers["Origin"])
    }

    @Test
    fun `authHeaders blank cookies are empty`() {
        assertTrue(YouTubeAuth.authHeaders("").isEmpty())
        assertTrue(YouTubeAuth.authHeaders("   ").isEmpty())
    }

    @Test
    fun `extractSapisid finds the value`() {
        assertEquals(
            "AbC123xYz",
            YouTubeAuth.extractSapisid("SID=aaa; SAPISID=AbC123xYz; LOGIN_INFO=xyz")
        )
        assertNull(YouTubeAuth.extractSapisid("SID=aaa; LOGIN_INFO=xyz"))
        assertNull(YouTubeAuth.extractSapisid(""))
    }

    @Test
    fun `isSignedIn needs a sapisid`() {
        assertTrue(YouTubeAuth.isSignedIn("SID=aaa; SAPISID=AbC123xYz"))
        assertFalse(YouTubeAuth.isSignedIn("SID=aaa; LOGIN_INFO=xyz"))
        assertFalse(YouTubeAuth.isSignedIn(null))
        assertFalse(YouTubeAuth.isSignedIn(""))
    }
}
