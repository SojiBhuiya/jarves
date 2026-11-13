package com.sojibhuiya.jarves.update

/**
 * What the "Update" section of Settings is showing right now. One value, so the UI can never show
 * "checking" and "up to date" at the same time, and never announces success before the system
 * installer has said so.
 */
sealed interface UpdateUiState {

    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    data class UpToDate(val current: AppVersion, val latest: String) : UpdateUiState

    data class Available(val current: AppVersion, val release: ReleaseInfo) : UpdateUiState

    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateUiState

    /** Downloaded and verified; the installer has not been asked yet. */
    data class ReadyToInstall(val release: ReleaseInfo, val path: String) : UpdateUiState

    data class Failed(val failure: UpdateFailure, val detail: String = "") : UpdateUiState

    /** A plain message, used for install outcomes reported by the system. */
    data class Message(val text: String) : UpdateUiState

    /** True while a check or a download is running, so the buttons can be disabled. */
    val isBusy: Boolean get() = this is Checking || this is Downloading
}

/** The one-line Bengali status shown under the update button. */
fun UpdateUiState.statusText(currentVersionName: String): String = when (this) {
    is UpdateUiState.Idle -> "বর্তমান ভার্সন: $currentVersionName"
    is UpdateUiState.Checking -> "বর্তমান ভার্সন: $currentVersionName — GitHub-এ খোঁজা হচ্ছে…"
    is UpdateUiState.UpToDate -> "বর্তমান ভার্সন: $currentVersionName — নতুন ভার্সন নেই। অ্যাপ আপডেট আছে।"
    is UpdateUiState.Available ->
        "বর্তমান ভার্সন: $currentVersionName → নতুন ভার্সন: ${release.versionName} (${formatSize(release.apkSize)})"
    is UpdateUiState.Downloading ->
        "ডাউনলোড হচ্ছে: ${release.versionName} — ${(progress * 100).toInt()}%"
    is UpdateUiState.ReadyToInstall ->
        "${release.versionName} ডাউনলোড ও যাচাই সম্পন্ন (SHA-256 + সার্টিফিকেট)। এখন ইনস্টল করুন।"
    is UpdateUiState.Failed -> failure.message + if (detail.isBlank()) "" else "\n($detail)"
    is UpdateUiState.Message -> text
}
