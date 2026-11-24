package com.sojibhuiya.jarves.youtube

import com.sojibhuiya.jarves.apiproviders.CredentialCipher

/**
 * The signed-in YouTube session cookies, stored encrypted.
 *
 * Same shape as [YouTubeApiKeyStore]: storage is two lambdas so the class stays
 * unit-testable on the JVM (production wires them to the `youtube_cookies_encrypted`
 * DataStore preference, tests wire them to an in-memory variable). The cookies are
 * account access — they are never logged and never appear in any result or error text.
 */
class YouTubeCookieStore(
    private val readEncrypted: suspend () -> String?,
    private val writeEncrypted: suspend (String?) -> Unit,
    private val cipher: CredentialCipher
) {

    /** Saves a cookie header string (whitespace trimmed). Overwrites any saved session. */
    suspend fun save(cookies: String) {
        val trimmed = cookies.trim()
        require(trimmed.isNotEmpty()) { "Cookies must not be empty" }
        writeEncrypted(cipher.encrypt(trimmed))
    }

    /** Returns the saved cookies, or null when none are saved or the value is corrupt. */
    suspend fun load(): String? {
        val encrypted = readEncrypted() ?: return null
        return runCatching { cipher.decrypt(encrypted) }.getOrNull()
    }

    /** Removes the saved session. */
    suspend fun clear() {
        writeEncrypted(null)
    }

    suspend fun hasCookies(): Boolean = load() != null
}
