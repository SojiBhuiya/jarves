package com.sojibhuiya.jarves.apiproviders

/**
 * JSON (de)serialization for the persisted provider list.
 *
 * Stored shape (DataStore string):
 * ```
 * {"version":1,"providers":[{...},{...}]}
 * ```
 * Only ciphertext and non-secret metadata are written. A malformed document is reported as
 * [StorageFormatException] instead of silently dropping the user's records.
 */
class StorageFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

object ApiProviderCodec {

    const val FORMAT_VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_PROVIDERS = "providers"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_CATEGORY = "category"
    private const val KEY_ENCRYPTED = "encryptedKey"
    private const val KEY_HINT = "keyHint"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROVIDER_ID = "providerId"
    private const val KEY_METADATA = "metadata"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_UPDATED_AT = "updatedAt"

    fun encode(records: List<ApiProviderRecord>): String {
        val providers = records.map { record ->
            linkedMapOf<String, Any?>(
                KEY_ID to record.id,
                KEY_NAME to record.name,
                KEY_CATEGORY to record.category.storageId,
                KEY_ENCRYPTED to record.encryptedApiKey,
                KEY_HINT to record.keyHint,
                KEY_ENABLED to record.enabled,
                KEY_PROVIDER_ID to record.providerId,
                KEY_METADATA to LinkedHashMap(record.metadata),
                KEY_CREATED_AT to record.createdAt,
                KEY_UPDATED_AT to record.updatedAt
            )
        }
        return MiniJson.encode(linkedMapOf(KEY_VERSION to FORMAT_VERSION, KEY_PROVIDERS to providers))
    }

    fun decode(text: String?): List<ApiProviderRecord> {
        if (text.isNullOrBlank()) return emptyList()
        val root = try {
            MiniJson.parse(text)
        } catch (e: Exception) {
            throw StorageFormatException("Saved API providers could not be read", e)
        }
        val rootMap = root as? Map<*, *>
            ?: throw StorageFormatException("Saved API providers have an unexpected format")
        val version = (rootMap[KEY_VERSION] as? Number)?.toInt() ?: FORMAT_VERSION
        if (version > FORMAT_VERSION) {
            throw StorageFormatException("Saved API providers use a newer format ($version)")
        }
        val rawProviders = rootMap[KEY_PROVIDERS] ?: return emptyList()
        val list = rawProviders as? List<*>
            ?: throw StorageFormatException("Saved API provider list has an unexpected format")
        return list.mapNotNull { decodeRecord(it) }
    }

    private fun decodeRecord(raw: Any?): ApiProviderRecord? {
        val map = raw as? Map<*, *> ?: return null
        val id = map[KEY_ID].asStringOrNull() ?: return null
        val name = map[KEY_NAME].asStringOrNull() ?: return null
        val category = ApiCategory.fromStorageId(map[KEY_CATEGORY].asStringOrNull()) ?: return null
        val encrypted = map[KEY_ENCRYPTED].asStringOrNull().orEmpty()
        val createdAt = (map[KEY_CREATED_AT] as? Number)?.toLong() ?: 0L
        val updatedAt = (map[KEY_UPDATED_AT] as? Number)?.toLong() ?: createdAt
        val metadata = (map[KEY_METADATA] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { entry ->
                val key = entry.key.asStringOrNull() ?: return@mapNotNull null
                val value = entry.value.asStringOrNull() ?: return@mapNotNull null
                key to value
            }
            ?.toMap()
            .orEmpty()
        return ApiProviderRecord(
            id = id,
            name = name,
            category = category,
            encryptedApiKey = encrypted,
            keyHint = map[KEY_HINT].asStringOrNull().orEmpty(),
            enabled = map[KEY_ENABLED] as? Boolean ?: true,
            providerId = map[KEY_PROVIDER_ID].asStringOrNull(),
            metadata = metadata,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun Any?.asStringOrNull(): String? = (this as? String)?.takeIf { it.isNotBlank() }
}
