package com.sojibhuiya.jarves.youtube

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/**
 * NewPipe's `Downloader` over the OkHttp client the app already uses.
 *
 * The extractor only ever calls [execute]; the `get(...)` helpers on the base class
 * funnel into it. Runs on the extractor's own IO threads, so the call is blocking by
 * design — callers wrap it in Dispatchers.IO (see [NewPipeStreamResolver]).
 *
 * 0.2.25: when a signed-in YouTube session exists, [authCookiesProvider] supplies its
 * cookies and [execute] attaches the signed-in headers ([YouTubeAuth.authHeaders]) to
 * every request — Cookie + SAPISIDHASH + Origin + X-Origin. A bare Cookie alone is
 * ignored by InnerTube, so the hash is what makes the session count. Requests made
 * without a session are unchanged.
 */
class OkHttpNewPipeDownloader(
    private val client: OkHttpClient = defaultClient(),
    private val authCookiesProvider: () -> String? = { null }
) : Downloader() {

    companion object {
        /**
         * 0.2.23: the resolver allows 60 s, so the client must too. The old default
         * OkHttpClient cut every fetch off after 10 s, long before the resolver's cap.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
        var existingCookie: String? = null
        request.headers().forEach { (name, values) ->
            values.forEach { builder.addHeader(name, it) }
            if (name.equals("Cookie", ignoreCase = true)) {
                existingCookie = values.firstOrNull()
            }
        }
        // 0.2.25: attach the signed-in session, if any. A Cookie the extractor already
        // set (e.g. the WEB consent cookie) is kept and the session cookies are
        // appended to it, never replaced.
        val authCookies = runCatching { authCookiesProvider() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (authCookies != null) {
            val headers = YouTubeAuth.authHeaders(authCookies)
            val mergedCookie = if (existingCookie.isNullOrBlank()) {
                headers["Cookie"]
            } else {
                "$existingCookie; ${headers["Cookie"]}"
            }
            mergedCookie?.let { builder.header("Cookie", it) }
            headers.forEach { (name, value) ->
                if (!name.equals("Cookie", ignoreCase = true)) builder.header(name, value)
            }
        }
        val method = request.httpMethod()
        val data = request.dataToSend()
        // 0.2.23: the body's media type MUST be the Content-Type the extractor asked for
        // in the request headers. The extractor sends JSON with "Content-Type:
        // application/json" — but OkHttp's BridgeInterceptor overwrites that header with
        // the body's media type, and the old hardcoded "application/x-www-form-urlencoded"
        // made YouTube answer every player request with HTTP 400 ("Precondition check
        // failed"), so no video could ever load. Verified live: the same player request
        // returns 200 with application/json and 400 with form-urlencoded.
        val contentType = request.headers().entries
            .firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()
        val body = if (method == "GET" || method == "HEAD" || data == null) {
            null
        } else {
            data.toRequestBody(
                contentType?.toMediaTypeOrNull()
                    ?: "application/x-www-form-urlencoded".toMediaTypeOrNull()
            )
        }
        builder.method(method, body)
        client.newCall(builder.build()).execute().use { response ->
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString()
            )
        }
    }
}
