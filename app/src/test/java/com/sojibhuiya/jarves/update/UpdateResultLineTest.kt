package com.sojibhuiya.jarves.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.8 — the Settings → Update card was printing the version twice (once in the status line and
 * once in the button row). [resultLine] is the single short result text that replaced it, so these
 * tests pin down that it never repeats the version and never goes blank for a real state.
 */
class UpdateResultLineTest {

    private fun release(name: String = "0.2.9") = ReleaseInfo(
        tagName = "v$name",
        versionName = name,
        notes = "notes",
        apkUrl = "https://example.invalid/jarves-$name.apk",
        apkName = "jarves-$name.apk",
        checksumUrl = "https://example.invalid/jarves-$name.apk.sha256",
        apkSize = 7_525_117L,
        publishedAt = "2026-11-09T00:00:00Z"
    )

    private fun version() = AppVersion(name = "0.2.8", code = 10)

    @Test
    fun idleShowsNothingAtAll() {
        assertEquals("", UpdateUiState.Idle.resultLine())
    }

    @Test
    fun theResultLineAlreadyCoversTheVersionSoTheLabelIsNeverRepeated() {
        val available = UpdateUiState.Available(version(), release("0.2.9"))
        val line = available.resultLine()

        assertTrue("must name the new version once", line.contains("0.2.9"))
        assertEquals("the version must appear exactly once", 1, Regex("0\\.2\\.9").findAll(line).count())
        assertFalse("no double arrow / no 'current → new' phrasing", line.contains("→"))
        assertTrue("size is useful here", line.contains("MB"))
    }

    @Test
    fun everyNonIdleStateHasAReadableBengaliLine() {
        val states = listOf(
            UpdateUiState.Checking,
            UpdateUiState.UpToDate(version(), "0.2.8"),
            UpdateUiState.Available(version(), release()),
            UpdateUiState.Downloading(release(), 0.42f),
            UpdateUiState.ReadyToInstall(release(), "/tmp/x.apk"),
            UpdateUiState.Failed(UpdateFailure.NETWORK, "boom"),
            UpdateUiState.Message("ইনস্টল বাতিল হয়েছে।")
        )
        states.forEach { state ->
            val line = state.resultLine()
            assertTrue("${state::class.simpleName} needs a line", line.isNotBlank())
            if (state !is UpdateUiState.Message) {
                assertTrue(
                    "${state::class.simpleName} must be readable Bengali",
                    line.any { c -> c.code in 0x0980..0x09FF }
                )
            }
        }
    }

    @Test
    fun progressIsShownAsAPercentage() {
        assertTrue(UpdateUiState.Downloading(release(), 0.42f).resultLine().contains("42%"))
    }

    @Test
    fun theOldStatusLineIsKeptBecauseItStillHasItsOwnTests() {
        // The main screen no longer shows statusText(), but it is still the app's long-form status
        // text and other tests rely on it — 0.2.8 removes only its *placement*, not the function.
        assertTrue(UpdateUiState.Idle.statusText("0.2.8").contains("0.2.8"))
    }
}
