package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Scenario 2 (today's date) and scenario 8 (a long-running session crossing midnight), plus
 * the Asia/Dhaka requirement.
 */
class TimeContextTest {

    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun usesTheProvidedInstantAndNeverAHardcodedDate() {
        val snapshot = TimeContext.snapshot(Instant.parse("2026-11-09T10:15:00Z"), paris)
        val text = TimeContext.instructionBlock(snapshot)
        assertTrue(text.contains("2026"))
        assertTrue(text.contains("9 November 2026"))
        assertTrue(text.contains("Paris"))
    }

    @Test
    fun describesBothDeviceAndBangladeshTime() {
        val snapshot = TimeContext.snapshot(Instant.parse("2026-11-09T10:15:00Z"), paris)
        // 10:15 UTC = 16:15 Asia/Dhaka (UTC+6).
        assertEquals("Monday, 9 November 2026, 16:15", snapshot.describeDhaka())
        assertEquals("Monday, 9 November 2026, 11:15", snapshot.describeDevice())
    }

    @Test
    fun bangladeshTimeIsCorrectAcrossDayBoundary() {
        // 19:30 UTC is already the next day in Dhaka (01:30).
        val snapshot = TimeContext.snapshot(Instant.parse("2026-11-09T19:30:00Z"), paris)
        assertEquals("Tuesday, 10 November 2026, 01:30", snapshot.describeDhaka())
        assertEquals("Monday, 9 November 2026, 20:30", snapshot.describeDevice())
        assertNotEquals(snapshot.dateKey, snapshot.dhakaDateKey)
    }

    @Test
    fun detectsBangladeshRequestsInEnglishAndBangla() {
        assertTrue(TimeContext.wantsBangladeshTime("What is the date in Bangladesh?"))
        assertTrue(TimeContext.wantsBangladeshTime("bangladesh time now?"))
        assertTrue(TimeContext.wantsBangladeshTime("ঢাকায় এখন কয়টা বাজে?"))
        assertTrue(TimeContext.wantsBangladeshTime("বাংলাদেশের আজকের তারিখ কী?"))
        assertFalse(TimeContext.wantsBangladeshTime("What is today's date?"))
    }

    @Test
    fun midnightCrossingChangesTheDateKeyAndRefreshedMessage() {
        val before = TimeContext.snapshot(Instant.parse("2026-11-09T23:59:00Z"), ZoneId.of("UTC"))
        val after = TimeContext.snapshot(Instant.parse("2026-11-10T00:01:00Z"), ZoneId.of("UTC"))

        assertEquals("2026-11-09", before.dateKey)
        assertEquals("2026-11-10", after.dateKey)
        assertNotEquals(before.dateKey, after.dateKey)

        val refresh = TimeContext.refreshMessage(after)
        assertTrue(refresh.contains("10 November 2026"))
        assertFalse(refresh.contains("9 November 2026"))
    }

    @Test
    fun instructionForbidsGuessingDates() {
        val text = TimeContext.instructionBlock(
            TimeContext.snapshot(Instant.parse("2026-11-09T10:15:00Z"), paris)
        )
        assertTrue(text.contains("Never guess", ignoreCase = true))
        assertTrue(text.contains("Asia/Dhaka"))
    }

    @Test
    fun dhakaZoneIsTheRealBangladeshZone() {
        assertEquals("Asia/Dhaka", TimeContext.DHAKA.id)
        assertEquals(6 * 60 * 60, TimeContext.DHAKA.rules.getOffset(Instant.parse("2026-11-09T00:00:00Z")).totalSeconds)
    }
}
