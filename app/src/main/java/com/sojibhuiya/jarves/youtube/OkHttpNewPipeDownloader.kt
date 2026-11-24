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
 */
class OkHttpNewPipeDownloader(
    private val client: OkHttpClient = defaultClient()
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
        request.headers().forEach { (name, values) ->
            values.forEach { builder.addHeader(name, it) }
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
