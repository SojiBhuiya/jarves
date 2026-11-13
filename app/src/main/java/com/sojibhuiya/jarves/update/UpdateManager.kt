package com.sojibhuiya.jarves.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The thin seam between the update logic and Android's PackageManager, so the decision itself can
 * be unit-tested with fakes. The real implementation is [AndroidInstalledAppReader].
 */
interface InstalledAppReader {
    /** The app that is running right now. */
    fun installedVersion(): AppVersion

    /** SHA-256 of the running app's signing certificate in `AA:BB:…` form, or null if unknown. */
    fun installedCertSha256(): String?

    /** versionName/versionCode read out of a downloaded APK file, or null if it is not an APK. */
    fun apkVersion(apk: File): AppVersion?

    /** SHA-256 of a downloaded APK's signing certificate in `AA:BB:…` form, or null. */
    fun apkCertSha256(apk: File): String?
}

/**
 * "আপডেট আছে কি দেখুন" — the whole decision path, in one place.
 *
 * Check: GitHub Releases (public, no token) → newest usable release → compare with the installed
 * version. Download: HTTPS into the app's own cache → verify SHA-256 against the published
 * `.sha256` asset → verify the APK's versionCode is higher than the installed one → verify the
 * signing certificate is identical to the installed app's certificate. Only then is the installer
 * offered. A failure at any step returns a Bengali message and nothing is installed.
 */
class UpdateManager(
    private val client: GitHubUpdateClient = GitHubUpdateClient(),
    private val reader: InstalledAppReader,
    private val cacheDir: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun check(): UpdateCheck = withContext(dispatcher) {
        val current = reader.installedVersion()
        client.fetchReleases().fold(
            onSuccess = { releases ->
                val latest = ReleaseJson.latest(releases)
                    ?: return@withContext UpdateCheck.Failed(UpdateFailure.NO_RELEASE)
                val remote = AppVersion(latest.versionName, 0L)
                if (VersionCompare.isNewer(remote, current)) {
                    UpdateCheck.Available(current, latest)
                } else {
                    UpdateCheck.UpToDate(current, latest.versionName)
                }
            },
            onFailure = { error ->
                val failure = (error as? UpdateException)?.failure ?: UpdateFailure.NETWORK
                UpdateCheck.Failed(failure, error.message.orEmpty())
            }
        )
    }

    suspend fun downloadAndVerify(
        release: ReleaseInfo,
        onProgress: (Float) -> Unit = {}
    ): UpdateDownload = withContext(dispatcher) {
        val target = File(cacheDir, "${CACHE_FOLDER}/${release.apkName}")
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        val downloaded = client.download(release.apkUrl, target, onProgress).getOrElse { error ->
            return@withContext UpdateDownload.Failed(
                (error as? UpdateException)?.failure ?: UpdateFailure.DOWNLOAD_FAILED,
                error.message.orEmpty()
            )
        }
        if (downloaded <= 0L) {
            return@withContext UpdateDownload.Failed(UpdateFailure.DOWNLOAD_FAILED, "0 bytes")
        }

        verify(release, target).fold(
            onSuccess = { UpdateDownload.Ready(release, target.absolutePath, downloaded) },
            onFailure = { error ->
                runCatching { target.delete() }
                UpdateDownload.Failed(
                    (error as? UpdateException)?.failure ?: UpdateFailure.INTERNAL,
                    error.message.orEmpty()
                )
            }
        )
    }

    /** The three gates, in the order the spec requires. Exposed for tests. */
    suspend fun verify(release: ReleaseInfo, apk: File): Result<Unit> {
        val current = reader.installedVersion()

        // 1) Checksum of exactly the bytes that were downloaded.
        // Without the published digest the download cannot be verified, so it is not used at all.
        val checksumText = client.downloadText(release.checksumUrl).getOrElse { error ->
            return Result.failure(UpdateException(UpdateFailure.NO_CHECKSUM, error.message.orEmpty()))
        }
        val expected = Sha256.parseChecksumFile(checksumText)
            ?: return Result.failure(UpdateException(UpdateFailure.NO_CHECKSUM, "no 64-hex digest"))
        val actual = Sha256.ofFile(apk)
        if (!Sha256.matches(expected, actual)) {
            return Result.failure(
                UpdateException(UpdateFailure.CHECKSUM_MISMATCH, "expected=$expected actual=$actual")
            )
        }

        // 2) The APK must really be a newer build of this app.
        val remote = reader.apkVersion(apk)
            ?: return Result.failure(UpdateException(UpdateFailure.BAD_APK, "no package info"))
        if (!VersionCompare.isNewer(remote, current)) {
            return Result.failure(
                UpdateException(UpdateFailure.VERSION_NOT_NEWER, "apk=$remote installed=$current")
            )
        }

        // 3) Same signing certificate, or Android would refuse it (and the user would lose data).
        val expectedCert = reader.installedCertSha256()
        val actualCert = reader.apkCertSha256(apk)
        if (expectedCert == null || actualCert == null) {
            return Result.failure(UpdateException(UpdateFailure.CERT_MISMATCH, "certificate unavailable"))
        }
        if (!CertFingerprint.same(expectedCert, actualCert)) {
            return Result.failure(
                UpdateException(UpdateFailure.CERT_MISMATCH, "installed=$expectedCert apk=$actualCert")
            )
        }
        return Result.success(Unit)
    }

    companion object {
        /** Name of the folder inside the app cache that holds downloaded updates. */
        const val CACHE_FOLDER = "updates"

        /** Housekeeping: drop downloads older than a day so the cache cannot grow. */
        fun pruneCache(cacheDir: File, olderThanMillis: Long = TimeUnit.DAYS.toMillis(1)) {
            val folder = File(cacheDir, CACHE_FOLDER)
            val files = folder.listFiles() ?: return
            val cutoff = System.currentTimeMillis() - olderThanMillis
            files.filter { it.isFile && it.lastModified() < cutoff }.forEach { runCatching { it.delete() } }
        }
    }
}
