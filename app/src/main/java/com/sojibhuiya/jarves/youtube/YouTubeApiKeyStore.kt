package com.sojibhuiya.jarves.youtube

import com.sojibhuiya.jarves.apiproviders.CredentialCipher

/**
 * The YouTube Data API key, stored encrypted.
 *
 * Storage is two lambdas so the class stays unit-testable on the JVM: production wires
 * them to the `youtube_key_encrypted` DataStore preference (see [com.sojibhuiya.jarves.storage.PrefKeys]),
 * tests wire them to an in-memory variable. The key itself is never logged and never
 * appears in any result or error text.
 */
class YouTubeApiKeyStore(
    private val readEncrypted: suspend () -> String?,
    private val writeEncrypted: suspend (String?) -> Unit,
    private val cipher: CredentialCipher
) {

    /** Saves a key (whitespace trimmed). Overwrites any key already saved. */
    suspend fun save(apiKey: String) {
        val trimmed = apiKey.trim()
        require(trimmed.isNotEmpty()) { "API key must not be empty" }
        writeEncrypted(cipher.encrypt(trimmed))
    }

    /** Returns the saved key, or null when none is saved or the stored value is corrupt. */
    suspend fun load(): String? {
        val encrypted = readEncrypted() ?: return null
        return runCatching { cipher.decrypt(encrypted) }.getOrNull()
    }

    /** Removes the saved key. */
    suspend fun clear() {
        writeEncrypted(null)
    }

    suspend fun hasKey(): Boolean = load() != null
}
