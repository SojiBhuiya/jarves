package com.sojibhuiya.jarves.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Talks to the public GitHub Releases API and downloads the two assets.
 *
 * No token is used anywhere: the repository is public, and the spec forbids a secret in the APK.
 * The anonymous API is rate limited to 60 requests/hour per IP, which is far more than a
 * "check for updates" button needs.
 *
 * Docs: https://docs.github.com/en/rest/releases/releases#list-releases
 *       https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api
 */
open class GitHubUpdateClient(
    private val client: OkHttpClient = defaultClient(),
    private val releasesUrl: String = UpdateConfig.RELEASES_URL,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /** Fetches the release list. Returns the parsed releases, or an [UpdateException] on error. */
    open suspend fun fetchReleases(): Result<List<ReleaseInfo>> = withContext(dispatcher) {
        val request = Request.Builder()
            .url(releasesUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> Result.failure(UpdateException(UpdateFailure.NO_RELEASE, "HTTP 404"))
                    !response.isSuccessful ->
                        Result.failure(UpdateException(UpdateFailure.NETWORK, "HTTP ${response.code}"))
                    else -> {
                        val body = response.body?.string().orEmpty()
                        val releases = ReleaseJson.parse(body)
                        if (releases.isEmpty() && !body.trimStart().startsWith("[")) {
                            Result.failure(UpdateException(UpdateFailure.BAD_JSON, "unreadable body"))
                        } else {
                            Result.success(releases)
                        }
                    }
                }
            }
        } catch (e: InterruptedIOException) {
            Result.failure(UpdateException(UpdateFailure.NETWORK, "timeout"))
        } catch (e: IOException) {
            Result.failure(UpdateException(UpdateFailure.NO_INTERNET, e.javaClass.simpleName))
        }
    }

    /** Downloads one asset into [destination], reporting 0..1 progress. */
    open suspend fun download(
        url: String,
        destination: File,
        onProgress: (Float) -> Unit = {}
    ): Result<Long> = withContext(dispatcher) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        UpdateException(UpdateFailure.DOWNLOAD_FAILED, "HTTP ${response.code}")
                    )
                }
                val body = response.body
                    ?: return@withContext Result.failure(
                        UpdateException(UpdateFailure.DOWNLOAD_FAILED, "empty body")
                    )
                val total = body.contentLength()
                destination.parentFile?.mkdirs()
                var written = 0L
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                onProgress(1f)
                Result.success(written)
            }
        } catch (e: IOException) {
            runCatching { destination.delete() }
            Result.failure(UpdateException(UpdateFailure.DOWNLOAD_FAILED, e.javaClass.simpleName))
        }
    }

    /** Downloads a small text asset (the checksum file) and returns its content. */
    open suspend fun downloadText(url: String): Result<String> = withContext(dispatcher) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        UpdateException(UpdateFailure.DOWNLOAD_FAILED, "HTTP ${response.code}")
                    )
                }
                Result.success(response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            Result.failure(UpdateException(UpdateFailure.NO_INTERNET, e.javaClass.simpleName))
        }
    }

    companion object {
        const val USER_AGENT = "Jarves-Updater"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** A failure with a message that is safe to show to the user. */
class UpdateException(val failure: UpdateFailure, val detail: String = "") :
    Exception("${failure.name}: $detail")
