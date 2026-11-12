package com.sojibhuiya.jarves.apiproviders

import javax.crypto.spec.SecretKeySpec

/** In-memory stand-in for the DataStore-backed storage. */
class FakeApiProviderStorage(var value: String? = null) : ApiProviderStorage {

    var writes: Int = 0
        private set

    /** Simulates a storage backend that accepts writes but silently loses them. */
    var dropWrites: Boolean = false

    override suspend fun read(): String? = value

    override suspend fun write(value: String) {
        writes++
        if (!dropWrites) this.value = value
    }
}

object TestKeys {
    fun key(seed: Int = 7): SecretKeySpec =
        SecretKeySpec(ByteArray(32) { index -> (index * 31 + seed).toByte() }, "AES")
}

/** Real AES-GCM cipher, exercising the production crypto path on the JVM. */
fun testCipher(seed: Int = 7): AesGcmCredentialCipher =
    AesGcmCredentialCipher(SecretKeyProvider { TestKeys.key(seed) })

fun testRepository(
    storage: FakeApiProviderStorage = FakeApiProviderStorage(),
    cipher: CredentialCipher = testCipher()
): ApiProviderRepository = ApiProviderRepository(storage = storage, cipher = cipher)
