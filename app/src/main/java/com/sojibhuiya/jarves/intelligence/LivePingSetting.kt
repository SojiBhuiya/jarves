package com.sojibhuiya.jarves.intelligence

/**
 * 0.2.13 (task 3): the **experiment** switch for WebSocket ping frames.
 *
 * Why it exists: the phone log shows `AUDIO_SEND_FAILED` on a socket the server still considered
 * connected — the classic half-open connection. OkHttp can notice that by itself:
 *
 *  * `pingInterval` sends a WebSocket ping every N seconds and fails the socket when no pong comes
 *    back (https://square.github.io/okhttp/ — `OkHttpClient.Builder.pingInterval`);
 *  * ping/pong is part of the protocol: "Upon receipt of a Ping frame, an endpoint **MUST** send a
 *    Pong frame in response" and "An endpoint MAY send a Ping frame any time after the connection is
 *    established" (RFC 6455 §5.5.2, https://www.rfc-editor.org/rfc/rfc6455#section-5.5.2);
 *  * the Gemini Live docs never mention ping frames, so nothing there forbids them — and nothing
 *    there promises they are welcome either.
 *
 * Because the docs are silent, this is **off by default** and reachable only from
 * Settings → Advanced. The real behaviour against the Live endpoint is NOT-RUN until it is measured
 * on the phone with this switch on.
 */
object LivePingSetting {

    /** The only values this experiment accepts: off, 15 s, 20 s. */
    val OPTIONS: List<Int> = listOf(0, 15, 20)

    const val DEFAULT_SECONDS = 0

    /** A stored/typed value that is not one of [OPTIONS] falls back to off — never a guess. */
    fun fromStored(stored: String?): Int {
        val value = stored?.trim()?.toIntOrNull() ?: return DEFAULT_SECONDS
        return if (value in OPTIONS) value else DEFAULT_SECONDS
    }

    fun toStored(seconds: Int): String = (if (seconds in OPTIONS) seconds else DEFAULT_SECONDS).toString()

    /** Bengali label for the Settings row. */
    fun label(seconds: Int): String = when (seconds) {
        0 -> "বন্ধ (ডিফল্ট)"
        15 -> "প্রতি ১৫ সেকেন্ডে ping (পরীক্ষামূলক)"
        20 -> "প্রতি ২০ সেকেন্ডে ping (পরীক্ষামূলক)"
        else -> "বন্ধ (ডিফল্ট)"
    }

    /** One diagnostics line. */
    fun describe(seconds: Int): String =
        if (seconds > 0) "${seconds}s ping (EXPERIMENT, RFC 6455 §5.5.2)" else "off"
}

/** Persistence seam, faked in tests exactly like the mic-sensitivity store. */
interface LivePingStore {
    fun read(): String?
    fun write(value: String)
}

/** Default for plain-JVM use and for tests that do not care about persistence. */
class InMemoryLivePingStore(initial: String? = null) : LivePingStore {
    private var value: String? = initial

    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }
}

/** The typed wrapper the UI and the engine both use. */
class LivePingSettings(private val store: LivePingStore = InMemoryLivePingStore()) {

    fun current(): Int = LivePingSetting.fromStored(store.read())

    fun save(seconds: Int) {
        store.write(LivePingSetting.toStored(seconds))
    }
}
