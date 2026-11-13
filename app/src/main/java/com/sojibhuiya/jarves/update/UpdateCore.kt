package com.sojibhuiya.jarves.update

import com.sojibhuiya.jarves.apiproviders.MiniJson
import java.security.MessageDigest

/**
 * Everything in this file is platform-free: no `android.*` imports, so the whole update decision
 * (version comparison, checksum, certificate fingerprint, GitHub JSON) is covered by real JVM unit
 * tests. The Android-specific install step lives in [UpdateInstaller].
 */

/** Where releases come from. The repository is public (checked with the unauthenticated GitHub API). */
object UpdateConfig {
    const val OWNER = "SojiBhuiya"
    const val REPO = "jarves"

    /** Unauthenticated GitHub REST endpoint: no token, no secret in the APK. */
    const val RELEASES_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=10"

    /** Asset name pattern published by the workflow: `jarves-<versionName>.apk`. */
    const val APK_PREFIX = "jarves-"
    const val APK_SUFFIX = ".apk"
    const val SHA_SUFFIX = ".sha256"

    /** The deep link shown to a user who wants the release page instead. */
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"
}

/** A version as the app can see it: the name from the release tag, the code from the APK. */
data class AppVersion(val name: String, val code: Long) {
    override fun toString(): String = "$name ($code)"
}

/** One published release with the two assets the updater needs. */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val checksumUrl: String,
    val apkSize: Long,
    val publishedAt: String
)

/** Why an update could not be offered. Every message is Bengali and safe to show as-is. */
enum class UpdateFailure(val message: String) {
    NO_INTERNET("ইন্টারনেট সংযোগ নেই। ওয়াই-ফাই বা মোবাইল ডেটা চালু করে আবার চেষ্টা করুন।"),
    NETWORK("GitHub-এ পৌঁছানো যায়নি। একটু পরে আবার চেষ্টা করুন।"),
    NO_RELEASE("এখনও কোনো প্রকাশিত রিলিজ নেই।"),
    NO_APK("সর্বশেষ রিলিজে ইনস্টলযোগ্য APK পাওয়া যায়নি।"),
    BAD_JSON("GitHub-এর উত্তর পড়া যায়নি।"),
    CHECKSUM_MISMATCH("ডাউনলোড করা ফাইলের SHA-256 মেলেনি — ইনস্টল করা হয়নি। ফাইলটি আবার ডাউনলোড করুন।"),
    NO_CHECKSUM("রিলিজে SHA-256 ফাইল নেই, তাই যাচাই করা যায়নি — ইনস্টল করা হয়নি।"),
    CERT_MISMATCH("নতুন APK-এর সাইনিং সার্টিফিকেট এই অ্যাপের সার্টিফিকেটের সাথে মেলে না — " +
        "ইনস্টল করা হয়নি। ভুল ফাইল ইনস্টল করলে চ্যাট ও Key হারাত।"),
    VERSION_NOT_NEWER("ডাউনলোড করা APK-এর versionCode বর্তমান অ্যাপের চেয়ে বড় নয় — ইনস্টল করা হয়নি।"),
    BAD_APK("ডাউনলোড করা ফাইলটি বৈধ APK নয়।"),
    PERMISSION_DENIED("অ্যাপ ইনস্টল করার অনুমতি নেই। সেটিংসে \"Install unknown apps\" চালু করে আবার চেষ্টা করুন।"),
    CANCELLED("ইনস্টল বাতিল হয়েছে। অ্যাপ আগের মতোই আছে।"),
    DOWNLOAD_FAILED("ডাউনলোড সম্পূর্ণ হয়নি।"),
    INTERNAL("অ্যাপের ভেতরের সমস্যা। আবার চেষ্টা করুন।")
}

/** Result of "আপডেট আছে কি দেখুন". */
sealed interface UpdateCheck {
    data class UpToDate(val current: AppVersion, val latestName: String) : UpdateCheck

    data class Available(
        val current: AppVersion,
        val release: ReleaseInfo
    ) : UpdateCheck {
        val sizeLabel: String get() = formatSize(release.apkSize)
    }

    data class Failed(val failure: UpdateFailure, val detail: String = "") : UpdateCheck
}

/** Result of the download-and-verify step. */
sealed interface UpdateDownload {
    data class Ready(val release: ReleaseInfo, val path: String, val bytes: Long) : UpdateDownload

    data class Failed(val failure: UpdateFailure, val detail: String = "") : UpdateDownload
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "আকার অজানা"
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else -> "${bytes / 1024} KB"
}

/** Numeric, segment-wise version comparison: "0.2.10" is newer than "0.2.9". */
object VersionCompare {

    fun compare(left: String, right: String): Int {
        val a = segments(left)
        val b = segments(right)
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    private fun segments(version: String): List<Long> =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.', '-', '+', '_')
            .map { part -> part.takeWhile(Char::isDigit).toLongOrNull() ?: 0L }

    /** A release is an update when its name is newer, or equal in name but a higher versionCode. */
    fun isNewer(remote: AppVersion, local: AppVersion): Boolean {
        val byName = compare(remote.name, local.name)
        if (byName != 0) return byName > 0
        return remote.code > local.code
    }
}

/** SHA-256 helpers. The expected value comes from the `*.apk.sha256` asset, never from the app. */
object Sha256 {

    fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun ofFile(path: java.io.File): String =
        MessageDigest.getInstance("SHA-256").run {
            path.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    update(buffer, 0, read)
                }
            }
            digest().toHex()
        }

    /**
     * Reads the expected digest out of a `sha256sum`-style file: either the bare 64 hex characters
     * or the `<digest>  <filename>` form that `sha256sum` writes.
     */
    fun parseChecksumFile(text: String): String? {
        if (text.isBlank()) return null
        val candidate = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.substringBefore(' ')
            ?.substringBefore('\t')
            ?.lowercase()
            ?: return null
        return candidate.takeIf { it.length == 64 && it.all { ch -> ch in "0123456789abcdef" } }
    }

    fun matches(expected: String?, actual: String): Boolean {
        val normalised = expected?.trim()?.lowercase()?.replace(":", "") ?: return false
        if (normalised.length != 64) return false
        return normalised == actual.trim().lowercase().replace(":", "")
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * Certificate fingerprints (SHA-256 of the DER certificate, `AA:BB:…` form) — the only thing that
 * decides whether Android will accept an update over the installed app.
 */
object CertFingerprint {

    fun of(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString(":") { byte -> "%02X".format(byte) }

    fun same(left: String?, right: String?): Boolean {
        val a = normalise(left) ?: return false
        val b = normalise(right) ?: return false
        return a == b
    }

    private fun normalise(value: String?): String? =
        value?.uppercase()?.replace(":", "")?.replace(" ", "")?.takeIf { it.length == 64 }
}

/**
 * Parses the JSON that GitHub returns for `/repos/{owner}/{repo}/releases`.
 *
 * Only releases that carry both `jarves-<version>.apk` and `jarves-<version>.apk.sha256` are
 * usable; drafts and pre-releases are ignored. The version name is taken from the APK name
 * (`jarves-0.2.7.apk` → `0.2.7`) or from the tag when the tag is a plain version.
 */
object ReleaseJson {

    fun parse(body: String): List<ReleaseInfo> {
        val root = runCatching { MiniJson.parse(body) }.getOrNull() ?: return emptyList()
        val array = root as? List<*> ?: return emptyList()
        return array.mapNotNull { item -> (item as? Map<*, *>)?.let(::fromRelease) }
    }

    private fun fromRelease(map: Map<*, *>): ReleaseInfo? {
        if (map["draft"] == true) return null
        if (map["prerelease"] == true) return null

        val assets = (map["assets"] as? List<*>).orEmpty().mapNotNull { raw ->
            (raw as? Map<*, *>)?.let { asset ->
                val name = asset["name"] as? String ?: return@let null
                val url = asset["browser_download_url"] as? String ?: return@let null
                ReleaseAsset(name, url, (asset["size"] as? Number)?.toLong() ?: 0L)
            }
        }
        val apk = assets.firstOrNull {
            it.name.startsWith(UpdateConfig.APK_PREFIX) && it.name.endsWith(UpdateConfig.APK_SUFFIX)
        } ?: return null
        val checksum = assets.firstOrNull { it.name == apk.name + UpdateConfig.SHA_SUFFIX }
            ?: assets.firstOrNull { it.name.endsWith(UpdateConfig.SHA_SUFFIX) }
            ?: return null

        val tag = (map["tag_name"] as? String).orEmpty()
        val version = versionFromName(apk.name)
            ?: tag.removePrefix("v").removePrefix("V").takeIf { it.isNotBlank() }
            ?: return null

        return ReleaseInfo(
            tagName = tag,
            versionName = version,
            notes = (map["body"] as? String).orEmpty().trim(),
            apkUrl = apk.url,
            apkName = apk.name,
            checksumUrl = checksum.url,
            apkSize = apk.size,
            publishedAt = (map["published_at"] as? String).orEmpty()
        )
    }

    /** `jarves-0.2.7.apk` → `0.2.7`. Anything that is not a Jarves APK name → null. */
    fun versionFromName(apkName: String): String? {
        if (!apkName.startsWith(UpdateConfig.APK_PREFIX)) return null
        if (!apkName.endsWith(UpdateConfig.APK_SUFFIX)) return null
        return apkName.removePrefix(UpdateConfig.APK_PREFIX)
            .removeSuffix(UpdateConfig.APK_SUFFIX)
            .takeIf { it.isNotBlank() }
    }

    private data class ReleaseAsset(val name: String, val url: String, val size: Long)

    /** The newest usable release by version name. */
    fun latest(releases: List<ReleaseInfo>): ReleaseInfo? =
        releases.maxWithOrNull { a, b -> VersionCompare.compare(a.versionName, b.versionName) }
}
