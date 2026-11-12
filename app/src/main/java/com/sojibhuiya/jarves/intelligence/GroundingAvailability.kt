package com.sojibhuiya.jarves.intelligence

import java.security.MessageDigest

/**
 * Remembers that search grounding was refused for a particular credential, so later sessions do
 * not pay the same failed handshake every time.
 *
 * Design constraints:
 *  * **Memory only.** Nothing is written to disk, so no new preference, storage schema or
 *    migration is introduced, and no credential material is persisted.
 *  * **Keyed by a one-way fingerprint** of the API key. The raw key is never retained — only a
 *    SHA-256 digest the instance cannot reverse — and a *different* key is automatically treated
 *    as "unknown, try again", which is exactly the "user changed credentials" recheck.
 *  * **Expires.** Billing configuration can change without the key changing, so a refusal is
 *    forgotten after [cooldownMillis]; the next session tries grounding again on its own.
 *  * [recheck] clears it immediately (used when a credential record is re-saved).
 */
class GroundingAvailability(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS
) {

    private var refusedFingerprint: String? = null
    private var refusedAtMillis: Long = 0L

    /** True when grounding is known to be refused for this exact credential and still cooling down. */
    @Synchronized
    fun isUnavailable(apiKey: String): Boolean {
        val fingerprint = refusedFingerprint ?: return false
        if (fingerprint != fingerprintOf(apiKey)) return false
        return clock() - refusedAtMillis < cooldownMillis
    }

    /** Records a tool refusal for this credential. Safe to call more than once. */
    @Synchronized
    fun markUnavailable(apiKey: String) {
        if (apiKey.isBlank()) return
        refusedFingerprint = fingerprintOf(apiKey)
        refusedAtMillis = clock()
    }

    /**
     * Forgets any recorded refusal, so the next session asks for Google Search again. Called when
     * the saved credential changes (new key, re-saved key) or the user may have changed billing.
     */
    @Synchronized
    fun recheck() {
        refusedFingerprint = null
        refusedAtMillis = 0L
    }

    /** True when a refusal is currently remembered (diagnostics only — never exposes the key). */
    @Synchronized
    fun hasRefusal(): Boolean = refusedFingerprint != null &&
        clock() - refusedAtMillis < cooldownMillis

    private fun fingerprintOf(apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        /**
         * How long a refusal is trusted before grounding is offered again. Billing upgrades or
         * quota resets are picked up automatically at the next session after this window.
         */
        const val DEFAULT_COOLDOWN_MILLIS: Long = 6L * 60L * 60L * 1000L

        /** Process-wide instance used by the live engine and the UI layer. */
        val shared = GroundingAvailability()
    }
}
