package com.sojibhuiya.jarves.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 0.2.24: the visionOS retry + Android fallback in [NewPipeStreamResolver]. The
 * visionOS attempt and the Android client are faked, so no test touches the network;
 * the retry pause is 0 here, so these run fast.
 */
class YouTubeStreamResolverTest {

    private fun botFailure(videoId: String = "abc123") = YouTubeStreams(
        videoId = videoId,
        failure = YouTubeFailure.INTERNAL,
        detail = "SignInConfirmNotBotException"
    )

    private fun okStreams(videoId: String = "abc123", source: String = "visionOS") =
        YouTubeStreams(
            videoId = videoId, title = "Song",
            videoUrl = "https://example.test/v.mp4",
            audioUrl = "https://example.test/a.m4a",
            source = source
        )

    private class ScriptedResolver(
        private val script: ArrayDeque<YouTubeStreams>,
        androidClient: AndroidPlayerClient? = null
    ) : NewPipeStreamResolver(
        OkHttpNewPipeDownloader(),
        androidClient = androidClient,
        retryDelayMillis = 0
    ) {
        var calls = 0
        override fun tryVisionOs(videoId: String): YouTubeStreams {
            calls++
            return script.removeFirstOrNull()
                ?: YouTubeStreams(videoId, failure = YouTubeFailure.EMPTY)
        }
    }

    private class FakeAndroid(private val result: YouTubeStreams) :
        AndroidPlayerClient(OkHttpNewPipeDownloader()) {
        var calls = 0
        override fun fetch(videoId: String): YouTubeStreams {
            calls++
            return result
        }
    }

    @Test
    fun `bot check then retry succeeds`() = runBlocking {
        val resolver = ScriptedResolver(
            ArrayDeque(listOf(botFailure(), okStreams()))
        )
        val streams = resolver.resolve("abc123")
        assertNull(streams.failure)
        assertEquals("https://example.test/v.mp4", streams.videoUrl)
        assertEquals("visionOS-retry", streams.source)
        assertEquals(2, resolver.calls)
    }

    @Test
    fun `bot check twice then android fallback succeeds`() = runBlocking {
        val android = FakeAndroid(okStreams(source = "android"))
        val resolver = ScriptedResolver(
            ArrayDeque(listOf(botFailure(), botFailure())),
            androidClient = android
        )
        val streams = resolver.resolve("abc123")
        assertNull(streams.failure)
        assertEquals("https://example.test/v.mp4", streams.videoUrl)
        assertEquals("android", streams.source)
        assertEquals(2, resolver.calls)
        assertEquals(1, android.calls)
    }

    @Test
    fun `bot check twice and android fails returns combined failure`() = runBlocking {
        // 0.2.25: the combined detail names what the Android fallback hit too, or the
        // next phone test cannot tell the two failures apart.
        val android = FakeAndroid(botFailure())
        val resolver = ScriptedResolver(
            ArrayDeque(listOf(botFailure(), botFailure())),
            androidClient = android
        )
        val streams = resolver.resolve("abc123")
        assertEquals(YouTubeFailure.INTERNAL, streams.failure)
        assertEquals(
            "SignInConfirmNotBotException; android INTERNAL (SignInConfirmNotBotException)",
            streams.detail
        )
        assertEquals(2, resolver.calls)
        assertEquals(1, android.calls)
    }

    @Test
    fun `non-bot failure tries the android fallback`() = runBlocking {
        // 0.2.26: the old rule (only a bot-check ever reached Android) hid the fix —
        // the phone's signed-in 0.2.25 test failed visionOS with a plain
        // ExtractionException and Android was never tried.
        val android = FakeAndroid(okStreams(source = "android"))
        val resolver = ScriptedResolver(
            ArrayDeque(
                listOf(
                    YouTubeStreams(
                        videoId = "abc123",
                        failure = YouTubeFailure.INTERNAL,
                        detail = "ExtractionException"
                    )
                )
            ),
            androidClient = android
        )
        val streams = resolver.resolve("abc123")
        assertNull(streams.failure)
        assertEquals("https://example.test/v.mp4", streams.videoUrl)
        assertEquals("android", streams.source)
        assertEquals(1, resolver.calls)
        assertEquals(1, android.calls)
    }

    @Test
    fun `non-bot failure and android fails returns combined failure`() = runBlocking {
        val android = FakeAndroid(botFailure())
        val resolver = ScriptedResolver(
            ArrayDeque(
                listOf(
                    YouTubeStreams(
                        videoId = "abc123",
                        failure = YouTubeFailure.INTERNAL,
                        detail = "ExtractionException"
                    )
                )
            ),
            androidClient = android
        )
        val streams = resolver.resolve("abc123")
        assertEquals(YouTubeFailure.INTERNAL, streams.failure)
        assertEquals(
            "ExtractionException; android INTERNAL (SignInConfirmNotBotException)",
            streams.detail
        )
        assertEquals(1, resolver.calls)
        assertEquals(1, android.calls)
    }

    @Test
    fun `failure detail includes the exception message`() {
        assertEquals(
            "ExtractionException: could not parse player response",
            describeFailure(
                org.schabi.newpipe.extractor.exceptions.ExtractionException(
                    "could not parse player response"
                )
            )
        )
        assertEquals(
            "IllegalStateException",
            describeFailure(IllegalStateException())
        )
        assertEquals(
            "IllegalStateException: first line second line",
            describeFailure(IllegalStateException("first line\nsecond line"))
        )
        assertEquals(
            "RuntimeException: " + "x".repeat(200),
            describeFailure(RuntimeException("x".repeat(500)))
        )
    }

    @Test
    fun `visionOS success first time does not retry`() = runBlocking {
        val android = FakeAndroid(okStreams(source = "android"))
        val resolver = ScriptedResolver(
            ArrayDeque(listOf(okStreams())),
            androidClient = android
        )
        val streams = resolver.resolve("abc123")
        assertNull(streams.failure)
        assertEquals("visionOS", streams.source)
        assertEquals(1, resolver.calls)
        assertEquals(0, android.calls)
    }
}
