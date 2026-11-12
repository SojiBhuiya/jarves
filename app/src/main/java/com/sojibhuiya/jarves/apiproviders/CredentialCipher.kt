package com.sojibhuiya.jarves.apiproviders

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Raised for every credential encrypt/decrypt problem so callers can handle it uniformly. */
class CredentialSecurityException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Supplies the AES key used for credential encryption (Android Keystore in production). */
fun interface SecretKeyProvider {
    fun secretKey(): SecretKey
}

/** Encrypts and decrypts API credentials before/after persistence. */
interface CredentialCipher {
    fun encrypt(plaintext: String): String

    /**
     * @throws CredentialSecurityException when the payload is malformed, was encrypted with a
     * different key, or has been tampered with.
     */
    fun decrypt(encrypted: String): String
}

/** Base64 strategy; replaceable so tests do not depend on `android.util.Base64`. */
interface Base64Codec {
    fun encode(bytes: ByteArray): String

    fun decode(text: String): ByteArray

    companion object {
        /** JVM / pure-Kotlin implementation, also used by JVM unit tests. */
        val DEFAULT: Base64Codec = object : Base64Codec {
            override fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
            override fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
        }
    }
}

/**
 * AES-GCM credential cipher.
 *
 * Payload layout: `Base64( IV (12 bytes) || ciphertext+tag (16 bytes) )` — byte-for-byte the
 * same layout the app already used for the Gemini key, so previously stored values stay
 * readable while the Keystore alias is supplied by [keyProvider].
 *
 * The class is pure `javax.crypto`, which keeps the security-critical code unit-testable
 * on the JVM while production uses the non-exportable Android Keystore key.
 */
class AesGcmCredentialCipher(
    private val keyProvider: SecretKeyProvider,
    private val base64: Base64Codec = Base64Codec.DEFAULT
) : CredentialCipher {

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        private const val MIN_PAYLOAD_BYTES = IV_LENGTH_BYTES + (TAG_LENGTH_BITS / 8)
    }

    override fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) throw CredentialSecurityException("Cannot encrypt an empty credential")
        val key = resolveKey()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_LENGTH_BYTES) {
                throw CredentialSecurityException("Unexpected encryption IV length")
            }
            val payload = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            base64.encode(iv + payload)
        } catch (e: CredentialSecurityException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw CredentialSecurityException("Could not encrypt the credential", e)
        }
    }

    override fun decrypt(encrypted: String): String {
        if (encrypted.isBlank()) throw CredentialSecurityException("No stored credential to decrypt")
        val key = resolveKey()
        val packed = try {
            base64.decode(encrypted)
        } catch (e: IllegalArgumentException) {
            throw CredentialSecurityException("Stored credential is not valid Base64", e)
        }
        if (packed.size <= MIN_PAYLOAD_BYTES) {
            throw CredentialSecurityException("Stored credential is truncated or corrupt")
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_LENGTH_BITS, packed.copyOfRange(0, IV_LENGTH_BYTES))
            )
            val plain = cipher.doFinal(packed.copyOfRange(IV_LENGTH_BYTES, packed.size))
            String(plain, Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw CredentialSecurityException("Could not decrypt the stored credential", e)
        }
    }

    private fun resolveKey(): SecretKey = try {
        keyProvider.secretKey()
    } catch (e: Exception) {
        throw CredentialSecurityException("Encryption key is unavailable", e)
    }
}
