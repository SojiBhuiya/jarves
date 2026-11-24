package com.sojibhuiya.jarves.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePlayerQualityTest {

    private val options = listOf(
        StreamCandidate(url = "v1080", height = 1080, bitrate = 2_000, hasAudio = true),
        StreamCandidate(url = "v720", height = 720, bitrate = 1_000, hasAudio = true),
        StreamCandidate(url = "v480", height = 480, bitrate = 600, hasAudio = true),
        StreamCandidate(url = "v360", height = 360, bitrate = 400, hasAudio = true)
    )

    @Test
    fun stepsDownOneLevelAtATime() {
        assertEquals("v720", nextLowerOption(options, 1080)?.url)
        assertEquals("v480", nextLowerOption(options, 720)?.url)
        assertEquals("v360", nextLowerOption(options, 480)?.url)
    }

    @Test
    fun lowestQualityHasNowhereToStepDownTo() {
        assertNull(nextLowerOption(options, 360))
        assertNull(nextLowerOption(options, 144))
    }

    @Test
    fun emptyOptionsStepNowhere() {
        assertNull(nextLowerOption(emptyList(), 1080))
    }
}
