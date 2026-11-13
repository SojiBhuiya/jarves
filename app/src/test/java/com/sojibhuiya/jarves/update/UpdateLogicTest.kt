package com.sojibhuiya.jarves.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 0.2.7 in-app updater, everything that can be checked without a phone:
 * version comparison, GitHub JSON, SHA-256, certificate fingerprints, the three verification gates
 * and every Bengali failure message. The Android install step itself is NOT covered here (it needs
 * a device) — it is reported as READ-FROM-DOCS-ONLY.
 */
class UpdateLogicTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ---- version comparison ---------------------------------------------------------

    @Test
    fun versionsAreComparedSegmentBySegmentNotAlphabetically() {
        assertTrue(VersionCompare.compare("0.2.10", "0.2.9") > 0)
        assertTrue(VersionCompare.compare("0.2.9", "0.2.10") < 0)
        assertEquals(0, VersionCompare.compare("0.2.7", "v0.2.7"))
        assertTrue(VersionCompare.compare("0.3", "0.2.99") > 0)
        assertTrue(VersionCompare.compare("1.0", "0.99.99") > 0)
    }

    @Test
    fun anEqualNameWithAHigherVersionCodeIsStillAnUpdate() {
        assertTrue(VersionCompare.isNewer(AppVersion("0.2.7", 9), AppVersion("0.2.7", 8)))
        assertFalse(VersionCompare.isNewer(AppVersion("0.2.7", 8), AppVersion("0.2.7", 9)))
        assertFalse(VersionCompare.isNewer(AppVersion("0.2.6", 99), AppVersion("0.2.7", 9)))
        assertTrue(VersionCompare.isNewer(AppVersion("0.2.7", 0), AppVersion("0.2.6", 8)))
    }

    // ---- SHA-256 --------------------------------------------------------------------

    @Test
    fun theChecksumFileIsReadInBothPublishedForms() {
        val digest = "a".repeat(64)
        assertEquals(digest, Sha256.parseChecksumFile("$digest  jarves-0.2.7.apk\n"))
        assertEquals(digest, Sha256.parseChecksumFile("$digest\n"))
        assertEquals(digest, Sha256.parseChecksumFile("\n\n " + digest + "\r\n"))
        assertNull(Sha256.parseChecksumFile(""))
        assertNull("a short digest is rejected", Sha256.parseChecksumFile("abc123"))
        assertNull("a non-hex digest is rejected", Sha256.parseChecksumFile("z".repeat(64)))
    }

    @Test
    fun sha256IsComputedOverTheRealBytesAndComparedCaseInsensitively() {
        val file = File.createTempFile("jarves", ".apk")
        try {
            file.writeBytes("hello jarves".toByteArray())
            val digest = Sha256.ofFile(file)
            assertEquals("SHA-256 is 64 hex characters", 64, digest.length)
            assertEquals(digest, Sha256.of("hello jarves".toByteArray()))
            assertTrue(Sha256.matches(digest.uppercase(), digest))
            assertTrue(Sha256.matches(digest, digest.uppercase()))
            assertFalse(Sha256.matches(digest, Sha256.of("other".toByteArray())))
            assertFalse("a null expectation never matches", Sha256.matches(null, digest))
        } finally {
            file.delete()
        }
    }

    // ---- certificate fingerprints ---------------------------------------------------

    @Test
    fun certificateFingerprintsCompareOnlyWhenTheyAreTheSameCertificate() {
        val der = ByteArray(64) { it.toByte() }
        val fingerprint = CertFingerprint.of(der)
        assertEquals(95, fingerprint.length)      // 32 bytes as AA:BB:… = 64 hex + 31 colons
        assertTrue(CertFingerprint.same(fingerprint, fingerprint.lowercase()))
        assertTrue(CertFingerprint.same(fingerprint, fingerprint.replace(":", "")))
        assertFalse(CertFingerprint.same(fingerprint, CertFingerprint.of(ByteArray(64) { (it + 1).toByte() })))
        assertFalse("null never matches", CertFingerprint.same(null, fingerprint))
        assertFalse("a malformed fingerprint never matches", CertFingerprint.same("ABCD", fingerprint))
    }

    // ---- GitHub JSON ---------------------------------------------------------------

    private fun releaseJson(
        tag: String = "v0.2.7",
        apkName: String = "jarves-0.2.7.apk",
        withChecksum: Boolean = true,
        draft: Boolean = false,
        prerelease: Boolean = false
    ): String {
        val checksum = if (withChecksum) {
            """,{"name":"$apkName.sha256","browser_download_url":"https://example/$apkName.sha256","size":65}"""
        } else {
            ""
        }
        return """{
          "tag_name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "published_at": "2026-11-09T10:00:00Z",
          "body": "Auto mode and in-app update",
          "assets": [
            {"name":"$apkName","browser_download_url":"https://example/$apkName","size":7040103}$checksum
          ]
        }"""
    }

    @Test
    fun theGitHubPayloadYieldsExactlyTheTwoAssetsTheUpdaterNeeds() {
        val releases = ReleaseJson.parse("[${releaseJson()}]")
        assertEquals(1, releases.size)
        val release = releases[0]
        assertEquals("0.2.7", release.versionName)
        assertEquals("jarves-0.2.7.apk", release.apkName)
        assertEquals("https://example/jarves-0.2.7.apk", release.apkUrl)
        assertEquals("https://example/jarves-0.2.7.apk.sha256", release.checksumUrl)
        assertEquals(7040103L, release.apkSize)
        assertTrue(release.notes.contains("Auto mode"))
    }

    @Test
    fun draftsPrereleasesAndReleasesWithoutChecksumsAreIgnored() {
        assertTrue(ReleaseJson.parse("[${releaseJson(draft = true)}]").isEmpty())
        assertTrue(ReleaseJson.parse("[${releaseJson(prerelease = true)}]").isEmpty())
        assertTrue(
            "a release without the .sha256 asset cannot be verified, so it is not offered",
            ReleaseJson.parse("[${releaseJson(withChecksum = false)}]").isEmpty()
        )
        assertTrue(ReleaseJson.parse("[]").isEmpty())
        assertTrue("garbage must not crash the app", ReleaseJson.parse("not json").isEmpty())
        assertTrue(ReleaseJson.parse("""{"message":"Not Found"}""").isEmpty())
    }

    @Test
    fun theNewestUsableReleaseWins() {
        val list = ReleaseJson.parse(
            "[${releaseJson(tag = "v0.2.6", apkName = "jarves-0.2.6.apk")}," +
                "${releaseJson(tag = "v0.2.10", apkName = "jarves-0.2.10.apk")}," +
                "${releaseJson(tag = "v0.2.9", apkName = "jarves-0.2.9.apk")}]"
        )
        assertEquals(3, list.size)
        assertEquals("0.2.10", ReleaseJson.latest(list)?.versionName)
    }

    @Test
    fun theVersionNameComesOutOfTheAssetName() {
        assertEquals("0.2.7", ReleaseJson.versionFromName("jarves-0.2.7.apk"))
        assertNull(ReleaseJson.versionFromName("other.apk"))
    }

    // ---- the real HTTP client against a mock server --------------------------------

    @Test
    fun theClientParsesAGitHubResponseOverRealHttp() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[${releaseJson()}]")
        )
        server.start()
        val client = GitHubUpdateClient(releasesUrl = server.url("/releases").toString())

        val releases = client.fetchReleases().getOrThrow()
        assertEquals(1, releases.size)
        assertEquals("0.2.7", releases[0].versionName)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("Jarves-Updater", recorded.getHeader("User-Agent"))
        assertEquals("application/vnd.github+json", recorded.getHeader("Accept"))
        assertNull("NO token may be sent", recorded.getHeader("Authorization"))
    }

    @Test
    fun aMissingRepositoryOrAnOfflineDeviceGivesADistinctMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        val notFound = GitHubUpdateClient(releasesUrl = server.url("/releases").toString())
            .fetchReleases()
        assertEquals(UpdateFailure.NO_RELEASE, (notFound.exceptionOrNull() as UpdateException).failure)

        // A port nobody listens on = no internet.
        val offline = GitHubUpdateClient(releasesUrl = "http://127.0.0.1:1/releases")
            .fetchReleases()
        assertEquals(UpdateFailure.NO_INTERNET, (offline.exceptionOrNull() as UpdateException).failure)
    }

    // ---- the three verification gates ----------------------------------------------

    /** A realistic 64-hex fingerprint pair: one installed, one downloaded. */
    private val certA =
        "39:E9:BD:28:C0:3D:47:87:0C:24:7E:B6:05:6A:D8:2D:F5:B1:A6:42:B1:0F:E2:68:B4:52:43:EF:38:1D:0F:6C"
    private val certB =
        "30:40:7D:63:D8:8D:47:86:5F:82:27:89:7D:9D:DB:F8:FE:97:F1:08:EE:55:77:43:7B:C0:6F:77:D0:7E:E9:18"

    private class FakeReader(
        var current: AppVersion,
        var installedCert: String?,
        var apkCert: String?,
        var apkInfo: AppVersion?
    ) : InstalledAppReader {
        override fun installedVersion(): AppVersion = current
        override fun installedCertSha256(): String? = installedCert
        override fun apkVersion(apk: File): AppVersion? = apkInfo
        override fun apkCertSha256(apk: File): String? = apkCert
    }

    private class FakeClient(
        var checksum: String?,
        var releases: List<ReleaseInfo> = emptyList()
    ) : GitHubUpdateClient(releasesUrl = "https://example.invalid") {
        override suspend fun fetchReleases(): Result<List<ReleaseInfo>> = Result.success(releases)

        override suspend fun downloadText(url: String): Result<String> =
            checksum?.let { Result.success(it) }
                ?: Result.failure(UpdateException(UpdateFailure.DOWNLOAD_FAILED, "404"))

        override suspend fun download(
            url: String,
            destination: File,
            onProgress: (Float) -> Unit
        ): Result<Long> {
            destination.parentFile?.mkdirs()
            destination.writeBytes(APK_BYTES)
            onProgress(1f)
            return Result.success(APK_BYTES.size.toLong())
        }
    }

    private fun tempCache(): File = Files.createTempDirectory("jarves-update-test").toFile()

    private fun release(): ReleaseInfo = ReleaseInfo(
        tagName = "v0.2.7",
        versionName = "0.2.7",
        notes = "notes",
        apkUrl = "https://example/jarves-0.2.7.apk",
        apkName = "jarves-0.2.7.apk",
        checksumUrl = "https://example/jarves-0.2.7.apk.sha256",
        apkSize = APK_BYTES.size.toLong(),
        publishedAt = "2026-11-09T10:00:00Z"
    )

    @Test
    fun aVerifiedDownloadBecomesReadyAndNothingElseIsInstalledFromTheApp() = runBlocking {
        val digest = Sha256.of(APK_BYTES)
        val client = FakeClient(checksum = "$digest  jarves-0.2.7.apk\n")
        val reader = FakeReader(AppVersion("0.2.6", 8), certA, certA.lowercase(), AppVersion("0.2.7", 9))
        val manager = UpdateManager(client = client, reader = reader, cacheDir = tempCache())

        val result = manager.downloadAndVerify(release())
        assertTrue(result is UpdateDownload.Ready)
        result as UpdateDownload.Ready
        assertTrue(File(result.path).exists())
        assertEquals(APK_BYTES.size.toLong(), result.bytes)
    }

    @Test
    fun aChecksumMismatchStopsTheUpdateAndDeletesTheFile() = runBlocking {
        val client = FakeClient(checksum = "${"0".repeat(64)}  jarves-0.2.7.apk\n")
        val reader = FakeReader(AppVersion("0.2.6", 8), certA, certA, AppVersion("0.2.7", 9))
        val cache = tempCache()
        val manager = UpdateManager(client = client, reader = reader, cacheDir = cache)

        val result = manager.downloadAndVerify(release())
        assertTrue(result is UpdateDownload.Failed)
        assertEquals(UpdateFailure.CHECKSUM_MISMATCH, (result as UpdateDownload.Failed).failure)
        assertFalse("the bad file must not be left behind", File(cache, "updates/jarves-0.2.7.apk").exists())
    }

    @Test
    fun aMissingChecksumAssetStopsTheUpdate() = runBlocking {
        val client = FakeClient(checksum = null)
        val reader = FakeReader(AppVersion("0.2.6", 8), certA, certA, AppVersion("0.2.7", 9))
        val result = UpdateManager(client, reader, tempCache()).downloadAndVerify(release())
        assertEquals(UpdateFailure.NO_CHECKSUM, (result as UpdateDownload.Failed).failure)
    }

    @Test
    fun aDifferentSigningCertificateStopsTheUpdateBeforeTheInstallerIsEverOpened() = runBlocking {
        val digest = Sha256.of(APK_BYTES)
        val client = FakeClient(checksum = digest)
        val reader = FakeReader(AppVersion("0.2.6", 8), certA, certB, AppVersion("0.2.7", 9))
        val result = UpdateManager(client, reader, tempCache()).downloadAndVerify(release())

        assertEquals(UpdateFailure.CERT_MISMATCH, (result as UpdateDownload.Failed).failure)
        assertTrue("the message must say why it matters", result.failure.message.contains("চ্যাট"))
    }

    @Test
    fun anApkThatIsNotNewerStopsTheUpdate() = runBlocking {
        val digest = Sha256.of(APK_BYTES)
        val client = FakeClient(checksum = digest)
        val reader = FakeReader(AppVersion("0.2.7", 9), certA, certA, AppVersion("0.2.7", 9))
        val result = UpdateManager(client, reader, tempCache()).downloadAndVerify(release())
        assertEquals(UpdateFailure.VERSION_NOT_NEWER, (result as UpdateDownload.Failed).failure)
    }

    @Test
    fun aDownloadThatIsNotAnApkIsRejected() = runBlocking {
        val digest = Sha256.of(APK_BYTES)
        val client = FakeClient(checksum = digest)
        val reader = FakeReader(AppVersion("0.2.6", 8), certA, certA, apkInfo = null)
        val result = UpdateManager(client, reader, tempCache()).downloadAndVerify(release())
        assertEquals(UpdateFailure.BAD_APK, (result as UpdateDownload.Failed).failure)
    }

    // ---- the check itself ----------------------------------------------------------

    @Test
    fun anOlderInstalledAppIsToldThereIsAnUpdateAndACurrentOneIsNot() = runBlocking {
        val client = FakeClient(checksum = "x", releases = listOf(release()))

        val behind = UpdateManager(
            client, FakeReader(AppVersion("0.2.6", 8), certA, certA, null), tempCache()
        ).check()
        assertTrue(behind is UpdateCheck.Available)
        assertEquals("0.2.7", (behind as UpdateCheck.Available).release.versionName)
        assertEquals("0.2.6 (8)", behind.current.toString())

        val current = UpdateManager(
            client, FakeReader(AppVersion("0.2.7", 9), certA, certA, null), tempCache()
        ).check()
        assertTrue(current is UpdateCheck.UpToDate)
        assertEquals("0.2.7", (current as UpdateCheck.UpToDate).latestName)
    }

    @Test
    fun noReleaseAtAllIsItsOwnMessage() = runBlocking {
        val result = UpdateManager(
            FakeClient(checksum = "x", releases = emptyList()),
            FakeReader(AppVersion("0.2.7", 9), certA, certA, null),
            tempCache()
        ).check()
        assertEquals(UpdateFailure.NO_RELEASE, (result as UpdateCheck.Failed).failure)
    }

    // ---- messages ------------------------------------------------------------------

    @Test
    fun everyFailureMessageIsBengaliAndNeverMentionsAKey() {
        UpdateFailure.entries.forEach { failure ->
            assertTrue("${failure.name} needs a Bengali message", failure.message.any { it.code in 0x0980..0x09FF })
            assertFalse(failure.message.contains("AIza"))
            assertFalse(failure.message.contains("token", ignoreCase = true))
        }
        assertNotNull(UpdateFailure.NO_INTERNET.message)
        assertTrue(UpdateFailure.PERMISSION_DENIED.message.contains("Install unknown apps"))
        assertTrue(UpdateFailure.CANCELLED.message.contains("বাতিল"))
        assertTrue(UpdateFailure.CHECKSUM_MISMATCH.message.contains("SHA-256"))
    }

    @Test
    fun theSettingsLineAlwaysShowsTheInstalledVersion() {
        val states = listOf(
            UpdateUiState.Idle,
            UpdateUiState.Checking,
            UpdateUiState.UpToDate(AppVersion("0.2.7", 9), "0.2.7"),
            UpdateUiState.Available(AppVersion("0.2.6", 8), release()),
            UpdateUiState.Downloading(release(), 0.42f),
            UpdateUiState.ReadyToInstall(release(), "/tmp/x.apk"),
            UpdateUiState.Failed(UpdateFailure.NETWORK),
            UpdateUiState.Message("ইনস্টল বাতিল হয়েছে।")
        )
        states.forEach { state ->
            val text = state.statusText("0.2.6")
            assertTrue("$state must produce text", text.isNotBlank())
            assertFalse(text.contains("AIza"))
        }
        assertTrue(
            UpdateUiState.UpToDate(AppVersion("0.2.7", 9), "0.2.7")
                .statusText("0.2.7").contains("নতুন ভার্সন নেই")
        )
        assertTrue(UpdateUiState.Downloading(release(), 0.42f).statusText("0.2.6").contains("42%"))
        assertTrue(
            UpdateUiState.Available(AppVersion("0.2.6", 8), release())
                .statusText("0.2.6").contains("0.2.6 → নতুন ভার্সন: 0.2.7")
        )
    }

    private companion object {
        val APK_BYTES = ByteArray(2048) { (it % 251).toByte() }
    }
}
