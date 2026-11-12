package com.sojibhuiya.jarves.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.sojibhuiya.jarves.apiproviders.SecretKeyProvider
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object KeystoreAliases {
    /** Key for the API provider store. */
    const val API_PROVIDERS = "jarves_api_providers_v1"

    /** Original key the app used for `gemini_key_encrypted`; kept for migration only. */
    const val LEGACY_GEMINI = "agi_gemini_key_v1"
}

/**
 * Supplies a non-exportable AES-256 key from the Android Keystore.
 *
 * The key never leaves the Keystore; only ciphertext is persisted. If the key is missing
 * (e.g. restored data on a new device) decryption fails safely and the UI asks for the key
 * again instead of crashing.
 */
class AndroidKeystoreKeyProvider(private val alias: String) : SecretKeyProvider {

    override fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
