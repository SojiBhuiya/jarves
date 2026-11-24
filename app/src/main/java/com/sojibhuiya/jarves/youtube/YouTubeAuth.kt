package com.sojibhuiya.jarves.youtube

import java.security.MessageDigest

/**
 * 0.2.25: the signed-in YouTube session, used to beat the "Sign in to confirm you're
 * not a bot" block on the phone's mobile connection.
 *
 * Why this exists: the player requests go out with no identity, and YouTube refuses
 * anonymous requests from the phone's shared mobile line. A signed-in session proves
 * the request comes from a real person. Verified (2026-11-24, from yt-dlp, NewPipe
 * tooling and the InnerTube reference): a bare `Cookie` header is IGNORED — InnerTube
 * treats the request as anonymous unless it also carries `Authorization: SAPISIDHASH`,
 * a SHA-1 of the timestamp, the `SAPISID` cookie and the origin. So the downloader
 * sends the whole set: Cookie + SAPISIDHASH + Origin + X-Origin.
 *
 * The cookies are captured from an in-app WebView sign-in (see YouTubeSignInOverlay)
 * and stored encrypted (see [YouTubeCookieStore]) — they never leave the phone except
 * in YouTube requests, and they are never logged.
 */
object YouTubeAuth {

    /** The origin the SAPISIDHASH is computed for, and the Origin header value. */
    const val ORIGIN = "https://www.youtube.com"

    /**
     * `SAPISIDHASH <timestamp>_<sha1("<timestamp> <sapisid> <origin>")>`.
     * Pure, so it is unit-tested against a vector computed with Python's hashlib.
     */
    fun sapisidHash(
        sapisid: String,
        origin: String = ORIGIN,
        timestampSeconds: Long = System.currentTimeMillis() / 1000
    ): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val bytes = digest.digest("$timestampSeconds $sapisid $origin".toByteArray(Charsets.UTF_8))
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestampSeconds}_$hex"
    }

    /** The `SAPISID` value inside a Cookie header string, or null when absent. */
    fun extractSapisid(cookies: String): String? =
        cookies.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("SAPISID=") }
            ?.removePrefix("SAPISID=")
            ?.takeIf { it.isNotBlank() }

    /** Signed in means the cookies carry a SAPISID — the one the hash needs. */
    fun isSignedIn(cookies: String?): Boolean =
        !cookies.isNullOrBlank() && extractSapisid(cookies) != null

    /**
     * The headers a signed-in YouTube request carries. Returns only `Cookie` when the
     * cookies have no SAPISID (no hash can be computed); an empty map when blank.
     */
    fun authHeaders(cookies: String, origin: String = ORIGIN): Map<String, String> {
        if (cookies.isBlank()) return emptyMap()
        val headers = linkedMapOf("Cookie" to cookies.trim())
        val sapisid = extractSapisid(cookies)
        if (sapisid != null) {
            headers["Authorization"] = sapisidHash(sapisid, origin)
            headers["Origin"] = origin
            headers["X-Origin"] = origin
            headers["X-Goog-AuthUser"] = "0"
        }
        return headers
    }
}

/** The signed-in session, held in memory so the downloader can read it per request. */
class YouTubeAuthHolder {
    @Volatile
    var cookies: String? = null
}
