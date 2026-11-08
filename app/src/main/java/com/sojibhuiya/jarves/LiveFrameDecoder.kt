package com.sojibhuiya.jarves

/**
 * Converts a Gemini Live binary WebSocket frame into its UTF-8 JSON text.
 * Gemini Live control/content messages are JSON; this keeps frame decoding
 * separate and independently testable.
 */
internal object LiveFrameDecoder {
    fun decode(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)
}
