package com.sojibhuiya.jarves.intelligence

/**
 * A snapshot of the phone at the moment a session ended. Every field is nullable because a reading
 * can genuinely be unavailable (and a guess is worse than "unknown" in a diagnostic block).
 */
data class SessionEnvironment(
    /** True when at least one activity is started (foreground). */
    val appVisible: Boolean? = null,
    /** `PowerManager.isInteractive` — the screen is on. */
    val screenInteractive: Boolean? = null,
    /** `KeyguardManager.isKeyguardLocked` — the lock screen is up. */
    val keyguardLocked: Boolean? = null,
    /** `wifi` / `mobile` / `ethernet` / `none` / `unknown`. */
    val networkType: String = "unknown",
    /** Milliseconds since the default network last changed, or null when it never changed. */
    val networkChangedAgoMillis: Long? = null,
    /** `PowerManager.isPowerSaveMode`. */
    val powerSaveMode: Boolean? = null
) {
    companion object {
        val UNKNOWN = SessionEnvironment(networkType = "unknown")
    }
}

/** Reads the environment at the moment it is asked. Implemented on Android; faked in tests. */
fun interface SessionEnvironmentProvider {
    fun snapshot(): SessionEnvironment
}

/**
 * The diagnostic line for "the session ended". Printed in the chat bubble's technical block and in
 * Settings → Advanced → Diagnostics, and it is designed to answer the 0.2.8 phone report directly:
 * *who* closed the socket, how long the session had been up, and what the phone was doing.
 *
 * Nothing here is secret: no key, no query, no handle (only its length).
 */
object SessionDiagnostics {

    const val RECENT_NETWORK_CHANGE_MILLIS = 10_000L

    fun line(
        initiator: CloseInitiator,
        environment: SessionEnvironment,
        sessionDurationMillis: Long,
        closeCode: Int,
        closeReason: String,
        reconnectsUsed: Int,
        resumption: SessionResumptionState,
        goAwaySeen: Boolean,
        reconnectCause: ReconnectCause
    ): String = buildString {
        append("Closed by: ").append(initiator.label)
        append(" | Session duration: ").append(seconds(sessionDurationMillis))
        if (closeCode != 0 || closeReason.isNotBlank()) {
            append(" | Close: code=").append(if (closeCode > 0) closeCode.toString() else "0")
                .append(" reason=").append(closeReason.ifBlank { "none" })
        }
        append(" | GoAway: ").append(if (goAwaySeen) "yes" else "no")
        append(" | Reconnects: ").append(reconnectsUsed).append('/').append(ReconnectPolicy.MAX_ATTEMPTS)
        if (reconnectCause != ReconnectCause.NONE) append(" (").append(reconnectCause.name).append(')')
        append('\n').append("App: ").append(visibleWord(environment.appVisible))
        append(" | Screen: ").append(interactiveWord(environment.screenInteractive))
        append(" | Locked: ").append(lockedWord(environment.keyguardLocked))
        append('\n').append("Network: ").append(environment.networkType)
        append(networkChangeWord(environment.networkChangedAgoMillis))
        append(" | Battery saver: ").append(batteryWord(environment.powerSaveMode))
        append('\n').append(resumption.describe())
    }

    private fun seconds(millis: Long): String = when {
        millis <= 0L -> "0s"
        millis < 60_000L -> "${millis / 1000}s"
        else -> "${millis / 60_000}m${(millis % 60_000) / 1000}s"
    }

    private fun visibleWord(value: Boolean?): String = when (value) {
        true -> "foreground"
        false -> "background"
        null -> "unknown"
    }

    private fun interactiveWord(value: Boolean?): String = when (value) {
        true -> "on"
        false -> "off"
        null -> "unknown"
    }

    private fun lockedWord(value: Boolean?): String = when (value) {
        true -> "yes"
        false -> "no"
        null -> "unknown"
    }

    private fun batteryWord(value: Boolean?): String = when (value) {
        true -> "on"
        false -> "off"
        null -> "unknown"
    }

    /**
     * "changed 3.2s ago" when the default network changed within the last
     * [RECENT_NETWORK_CHANGE_MILLIS] — the most common cause of a socket dying mid-sentence.
     */
    private fun networkChangeWord(agoMillis: Long?): String = when {
        agoMillis == null -> " (unchanged this session)"
        agoMillis < 0L -> " (change time unknown)"
        agoMillis <= RECENT_NETWORK_CHANGE_MILLIS -> " (CHANGED ${"%.1f".format(agoMillis / 1000.0)}s before the end)"
        else -> " (last changed ${agoMillis / 1000}s ago)"
    }
}
