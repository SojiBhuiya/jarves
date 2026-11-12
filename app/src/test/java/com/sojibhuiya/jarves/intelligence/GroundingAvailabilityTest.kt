package com.sojibhuiya.jarves.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the remember/recheck behaviour (F3 + F6): a refused credential must not pay the same
 * failed handshake on every session, but a changed credential or a passed cooldown must be
 * re-tested automatically. Nothing here may retain or expose the raw key.
 */
class GroundingAvailabilityTest {

    private var now = 1_000_000L
    private val clock = { now }

    private fun memo(cooldown: Long = GroundingAvailability.DEFAULT_COOLDOWN_MILLIS) =
        GroundingAvailability(clock = clock, cooldownMillis = cooldown)

    private val keyA = "AIzaSyFAKE-key-for-unit-tests-A"
    private val keyB = "AIzaSyFAKE-key-for-unit-tests-B"

    @Test
    fun unknownCredentialIsAvailable() {
        assertFalse(memo().isUnavailable(keyA))
    }

    @Test
    fun refusalIsRememberedForThatCredential() {
        val m = memo()
        m.markUnavailable(keyA)
        assertTrue(m.isUnavailable(keyA))
        assertTrue(m.hasRefusal())
    }

    @Test
    fun refusalDoesNotLeakToADifferentCredential() {
        val m = memo()
        m.markUnavailable(keyA)
        // A different key is exactly the "user changed credentials" case: try grounding again.
        assertFalse(m.isUnavailable(keyB))
    }

    @Test
    fun recheckForgetsTheRefusal() {
        val m = memo()
        m.markUnavailable(keyA)
        m.recheck()
        assertFalse(m.isUnavailable(keyA))
        assertFalse(m.hasRefusal())
    }

    @Test
    fun refusalExpiresAfterTheCooldown() {
        val m = memo(cooldown = 1_000L)
        m.markUnavailable(keyA)
        assertTrue(m.isUnavailable(keyA))
        now += 999L
        assertTrue(m.isUnavailable(keyA))
        now += 2L // crossed the cooldown: billing may have changed, so try grounding again
        assertFalse(m.isUnavailable(keyA))
        assertFalse(m.hasRefusal())
    }

    @Test
    fun markingTwiceExtendsFromTheLatestRefusal() {
        val m = memo(cooldown = 1_000L)
        m.markUnavailable(keyA)
        now += 900L
        m.markUnavailable(keyA)
        now += 900L // 1800 ms after the first, 900 ms after the second
        assertTrue(m.isUnavailable(keyA))
    }

    @Test
    fun blankKeyIsNeverRecordedAndNeverBlocks() {
        val m = memo()
        m.markUnavailable("")
        assertFalse(m.hasRefusal())
        assertFalse(m.isUnavailable(""))
    }

    @Test
    fun defaultCooldownIsSixHours() {
        // Long enough not to hammer Google, short enough to pick up a billing change the same day.
        assertTrue(GroundingAvailability.DEFAULT_COOLDOWN_MILLIS >= 60L * 60L * 1000L)
        assertTrue(GroundingAvailability.DEFAULT_COOLDOWN_MILLIS <= 24L * 60L * 60L * 1000L)
    }
}
