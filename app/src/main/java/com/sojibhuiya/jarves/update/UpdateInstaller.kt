package com.sojibhuiya.jarves.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The Android half of the updater: read the installed app's identity, and hand a verified APK to the
 * system installer.
 *
 * Two documented APIs are used:
 *  * `PackageManager.getPackageInfo(…, GET_SIGNING_CERTIFICATES)` /
 *    `getPackageArchiveInfo(…)` to compare certificate fingerprints —
 *    https://developer.android.com/reference/android/content/pm/PackageManager
 *  * `PackageInstaller` sessions to install — the API that replaced `ACTION_INSTALL_PACKAGE`, which
 *    "was deprecated in API level 29. use PackageInstaller instead"
 *    (https://developer.android.com/reference/android/content/Intent#ACTION_INSTALL_PACKAGE) —
 *    https://developer.android.com/reference/android/content/pm/PackageInstaller
 */

/** Reads the version/certificate of the running app and of a downloaded APK file. */
class AndroidInstalledAppReader(private val context: Context) : InstalledAppReader {

    private val packageName: String get() = context.packageName

    override fun installedVersion(): AppVersion {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return AppVersion(info.versionName.orEmpty().ifBlank { "0" }, code)
    }

    override fun installedCertSha256(): String? = signingCertOf(
        context.packageManager.getPackageInfo(packageName, signingFlags())
    )

    override fun apkVersion(apk: File): AppVersion? {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return AppVersion(info.versionName.orEmpty().ifBlank { "0" }, code)
    }

    override fun apkCertSha256(apk: File): String? = signingCertOf(
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags())
    )

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun signingCertOf(info: android.content.pm.PackageInfo?): String? {
        if (info == null) return null
        val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        } ?: return null
        return CertFingerprint.of(signer.toByteArray())
    }
}

/** What happened when the app asked the system to install the APK. */
sealed interface InstallOutcome {
    /** The system accepted the request and, if it needs to, is showing its own dialog. */
    data object Started : InstallOutcome

    /** "Install unknown apps" is off for this app; the caller should open the settings screen. */
    data object NeedsPermission : InstallOutcome

    /** The install finished and the system reported success. */
    data object Success : InstallOutcome

    /** The user cancelled in the system dialog. */
    data object Cancelled : InstallOutcome

    data class Failed(val message: String) : InstallOutcome
}

/**
 * The result of a committed [PackageInstaller] session, published by [InstallResultReceiver] so the
 * UI can tell the user the truth instead of guessing.
 */
object InstallResultBus {
    private val _outcome = MutableStateFlow<InstallOutcome?>(null)
    val outcome: StateFlow<InstallOutcome?> = _outcome

    fun publish(value: InstallOutcome) {
        _outcome.value = value
    }

    fun clear() {
        _outcome.value = null
    }
}

/**
 * Receives the installer's verdict.
 *
 * The session is committed with a broadcast `PendingIntent` to this receiver; the system sends
 * `EXTRA_STATUS` when the install succeeds, fails or is aborted, and `STATUS_PENDING_USER_ACTION`
 * when it needs the user to confirm — in that case the confirmation activity is in
 * `Intent.EXTRA_INTENT` and must be launched by us.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                    InstallResultBus.publish(InstallOutcome.Started)
                } else {
                    InstallResultBus.publish(InstallOutcome.Failed("installer dialog unavailable"))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> InstallResultBus.publish(InstallOutcome.Success)
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResultBus.publish(InstallOutcome.Cancelled)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                InstallResultBus.publish(
                    InstallOutcome.Failed("status=$status ${message.take(160)}".trim())
                )
            }
        }
    }
}

/** Hands a verified APK to the system installer. Nothing is installed from inside this app. */
class UpdateInstaller(private val context: Context) {

    /** True when the user has allowed this app to install packages. */
    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Opens the system "install unknown apps" screen for this app. */
    fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Starts an install session for [apk]. The bytes are streamed into the session (the documented
     * `PackageInstaller` flow), then committed with a broadcast that reports the outcome.
     */
    fun install(apk: File): InstallOutcome {
        if (!apk.exists()) return InstallOutcome.Failed("apk missing")
        if (!canInstall()) return InstallOutcome.NeedsPermission
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                // 0 = INSTALL_LOCATION_AUTO; the named constant is a hidden framework value.
                setInstallLocation(0)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(APK_ENTRY, 0, apk.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                val statusIntent = Intent(context, InstallResultReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_MUTABLE
                } else {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pending = android.app.PendingIntent.getBroadcast(
                    context,
                    SESSION_REQUEST_CODE,
                    statusIntent,
                    flags
                )
                session.commit(pending.intentSender)
            }
            InstallResultBus.publish(InstallOutcome.Started)
            InstallOutcome.Started
        } catch (e: Exception) {
            InstallOutcome.Failed("${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(200))
        }
    }

    /**
     * A `content://` URI for the downloaded file, kept for the diagnostics block and for a manual
     * fallback: some OEM installers only accept the URI route.
     */
    fun fileUri(apk: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apk
    )

    companion object {
        const val ACTION_INSTALL_STATUS = "com.sojibhuiya.jarves.INSTALL_STATUS"
        const val SESSION_REQUEST_CODE = 4711
        private const val APK_ENTRY = "base.apk"
    }
}
