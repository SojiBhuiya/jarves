package com.sojibhuiya.jarves.intelligence

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The only source of truth for date and time in Jarves.
 *
 * The model has no clock, so every session is told the device's current date/time and the
 * Asia/Dhaka equivalent. Nothing here is hardcoded: values always come from the system clock
 * (injectable for tests) and the device timezone.
 */
object TimeContext {

    /** Bangladesh time, used whenever the user explicitly asks about Bangladesh. */
    val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")

    private val HUMAN = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH)

    /** Words that mean "answer in Bangladesh time". Bangla + English, lowercase. */
    private val BANGLADESH_HINTS = listOf(
        "bangladesh", "dhaka", "bangladeshi", "bd time", "bangladesh time",
        "বাংলাদেশ", "ঢাকা", "বাংলাদেশের", "দেশের সময়", "ঢাকার সময়"
    )

    data class Snapshot(
        val instant: Instant,
        val device: ZonedDateTime,
        val dhaka: ZonedDateTime
    ) {
        val deviceZone: ZoneId get() = device.zone
        /** Stable key for the local calendar day, used to detect a midnight crossing. */
        val dateKey: String get() = device.toLocalDate().toString()
        val dhakaDateKey: String get() = dhaka.toLocalDate().toString()

        fun describeDevice(): String = device.format(HUMAN)
        fun describeDhaka(): String = dhaka.format(HUMAN)
    }

    fun snapshot(
        instant: Instant = Instant.now(),
        deviceZone: ZoneId = ZoneId.systemDefault()
    ): Snapshot = Snapshot(
        instant = instant,
        device = instant.atZone(deviceZone),
        dhaka = instant.atZone(DHAKA)
    )

    /** True when the user asks for Bangladesh's date or time specifically. */
    fun wantsBangladeshTime(text: String): Boolean {
        val normalised = text.lowercase(Locale.ROOT)
        return BANGLADESH_HINTS.any { normalised.contains(it) }
    }

    /** The instruction block injected into the live session's system instruction. */
    fun instructionBlock(snapshot: Snapshot): String = buildString {
        appendLine("Current date and time — this is the ONLY valid source of the date and time:")
        appendLine("• Device timezone (${snapshot.deviceZone.id}): ${snapshot.describeDevice()}")
        appendLine("• Bangladesh / Asia/Dhaka: ${snapshot.describeDhaka()}")
        appendLine(
            "Never guess, infer or state a date or time from memory. When the user asks for the " +
                "current date or time, answer from the values above. When they specifically ask " +
                "about Bangladesh, use the Asia/Dhaka value and say it is Bangladesh time."
        )
        append("If the session has been running for a long time, treat any later time-context update as authoritative.")
    }

    /** Text used to refresh the time context during a long-running session. */
    fun refreshMessage(snapshot: Snapshot): String =
        "[time context update] The current date and time is now: " +
            "${snapshot.describeDevice()} (${snapshot.deviceZone.id}); " +
            "${snapshot.describeDhaka()} in Bangladesh (Asia/Dhaka). " +
            "Use these values, not any earlier ones."
}
