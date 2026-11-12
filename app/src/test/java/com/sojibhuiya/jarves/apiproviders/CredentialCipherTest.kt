package com.sojibhuiya.jarves.apiproviders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** API key encryption / decryption and hostile-input handling. */
class CredentialCipherTest {

    private val cipher = testCipher()

    @Test
    fun encryptsAndDecryptsAsciiKey() {
        val key = "fake-test-credential-not-a-real-key"
        assertEquals(key, cipher.decrypt(cipher.encrypt(key)))
    }

    @Test
    fun encryptsAndDecryptsUnicodeAndSymbols() {
        val key = "sk-秘密-ключ-🔐/+=&?"
        assertEquals(key, cipher.decrypt(cipher.encrypt(key)))
    }

    @Test
    fun ciphertextDoesNotContainPlaintext() {
        val key = "sk-plaintext-should-never-appear"
        val encrypted = cipher.encrypt(key)
        assertTrue("ciphertext leaked the plaintext", !encrypted.contains("plaintext"))
        assertNotEquals(key, encrypted)
    }

    @Test
    fun sameCiphertextIsNeverProducedTwice() {
        val key = "same-key"
        assertNotEquals(cipher.encrypt(key), cipher.encrypt(key))
    }

    @Test
    fun rejectsEmptyPlaintext() {
        try {
            cipher.encrypt("")
            fail("Expected CredentialSecurityException")
        } catch (e: CredentialSecurityException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun rejectsBlankCiphertext() {
        try {
            cipher.decrypt("   ")
            fail("Expected CredentialSecurityException")
        } catch (e: CredentialSecurityException) {
            Unit
        }
    }

    @Test
    fun rejectsNonBase64Ciphertext() {
        try {
            cipher.decrypt("not base64 ***")
            fail("Expected CredentialSecurityException")
        } catch (e: CredentialSecurityException) {
            assertTrue(e.message!!.contains("Base64"))
        }
    }

    @Test
    fun rejectsTruncatedCiphertext() {
        val encrypted = cipher.encrypt("short")
        val truncated = encrypted.substring(0, 8)
        try {
            cipher.decrypt(truncated)
            fail("Expected CredentialSecurityException")
        } catch (e: CredentialSecurityException) {
            Unit
        }
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val encrypted = cipher.encrypt("tamper-me")
        val bytes = java.util.Base64.getDecoder().decode(encrypted)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(bytes)
        try {
            cipher.decrypt(tampered)
            fail("Expected CredentialSecurityException")
        } catch (e: CredentialSecurityException) {
            Unit
        }
    }

    @Test
    fun rejectsCiphertextEncryptedWithAnotherKey() {
        val other = testCipher(seed = 99)
        val encrypted = other.encrypt("another-vault")
        try {
            cipher.decrypt(encrypted)
            fail("Expected CredentialSecurityException")
        } catch (e: CredentialSecurityException) {
            Unit
        }
    }

    @Test
    fun unavailableKeystoreIsReportedSafely() {
        val broken = AesGcmCredentialCipher(SecretKeyProvider { throw IllegalStateException("keystore unavailable") })
        try {
            broken.encrypt("x")
            fail("Expected CredentialSecurityException")
        } catch (e: CredentialSecurityException) {
            assertTrue(e.message!!.contains("key", ignoreCase = true))
        }
        try {
            broken.decrypt("AAAA")
            fail("Expected CredentialSecurityException")
        } catch (e: CredentialSecurityException) {
            Unit
        }
    }
}
