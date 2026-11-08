package com.sojibhuiya.jarves

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveFrameDecoderTest {
    @Test
    fun decodesUtf8JsonFrame() {
        val json = """{"serverContent":{"inputTranscription":{"text":"হ্যালো"}}}"""
        assertEquals(json, LiveFrameDecoder.decode(json.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun decodesEmptyFrame() {
        assertEquals("", LiveFrameDecoder.decode(byteArrayOf()))
    }
}
