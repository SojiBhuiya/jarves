package com.sojibhuiya.jarves.youtube

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
    private val client: OkHttpClient = OkHttpClient()
) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
        request.headers().forEach { (name, values) ->
            values.forEach { builder.addHeader(name, it) }
        }
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = if (method == "GET" || method == "HEAD" || data == null) {
            null
        } else {
            data.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
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
