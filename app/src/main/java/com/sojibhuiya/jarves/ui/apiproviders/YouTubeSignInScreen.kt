package com.sojibhuiya.jarves.ui.apiproviders

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sojibhuiya.jarves.ui.components.JarvesBackRow
import com.sojibhuiya.jarves.ui.theme.JarvesColors
import com.sojibhuiya.jarves.youtube.YouTubeAuth

/**
 * 0.2.25: sign in to YouTube inside the app, so the player requests carry the user's
 * session and YouTube stops treating them as a robot.
 *
 * How it works: a WebView opens youtube.com and the user signs in with Google there,
 * exactly as in a browser. As soon as the youtube.com cookies carry a SAPISID, the
 * session is captured ([onSignedIn]) and this dialog closes itself — the user never
 * pastes anything. The WebView runs with a Chrome user agent (not the default
 * WebView one) so Google's sign-in does not refuse it as an embedded browser.
 *
 * The captured cookies are the account's session: the caller stores them encrypted
 * (see [com.sojibhuiya.jarves.youtube.YouTubeCookieStore]) and they are used only
 * for YouTube video requests. Signing out clears them.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSignInDialog(
    onSignedIn: (String) -> Unit,
    onClose: () -> Unit
) {
    var done by remember { mutableStateOf(false) }

    fun checkCookies(webView: WebView) {
        if (done) return
        val cookies = CookieManager.getInstance()
            .getCookie("https://www.youtube.com").orEmpty()
        if (YouTubeAuth.isSignedIn(cookies)) {
            done = true
            onSignedIn(cookies)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JarvesColors.Background)
                .padding(12.dp)
        ) {
            JarvesBackRow(onBack = onClose)
            Spacer(Modifier.height(8.dp))
            Text(
                "YouTube-এ সাইন ইন করুন",
                color = Color.White,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "তোমার Google অ্যাকাউন্ট দিয়ে সাইন ইন করো। সাইন ইন হলেই এই পর্দা নিজে বন্ধ হবে।",
                color = JarvesColors.TextMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(10.dp))
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // A real Chrome user agent, so Google's sign-in does not
                        // refuse the page as an embedded browser.
                        settings.userAgentString = CHROME_USER_AGENT
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                checkCookies(view)
                            }
                        }
                        loadUrl("https://www.youtube.com")
                    }
                }
            )
        }
    }
}

/** A current Chrome on Android user agent string. */
private const val CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
