package com.pairdrop.android.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pairdrop.android.bridge.AndroidBridge
import com.pairdrop.android.util.Constants
import org.json.JSONObject

class HeadlessPairDropClient(
    private val context: Context,
    private val incomingTransferHandler: (String, String) -> Boolean
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    fun start() {
        mainHandler.post {
            if (webView != null) return@post
            webView = createWebView().also { view ->
                view.loadUrl("http://127.0.0.1:${Constants.LOCAL_HTTP_PORT}/")
            }
        }
    }

    fun stop() {
        mainHandler.post {
            webView?.let { view ->
                view.removeJavascriptInterface("PairDropAndroid")
                view.stopLoading()
                view.loadUrl("about:blank")
                view.destroy()
            }
            webView = null
        }
    }

    fun respondToTransfer(peerId: String, accepted: Boolean) {
        val escapedPeerId = JSONObject.quote(peerId)
        mainHandler.post {
            webView?.evaluateJavascript(
                """
                    (function () {
                        if (!window.Events) return false;
                        window.Events.fire('respond-to-files-transfer-request', {
                            to: $escapedPeerId,
                            accepted: $accepted
                        });
                        return true;
                    })();
                """.trimIndent(),
                null
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            addJavascriptInterface(
                AndroidBridge(
                    context = context,
                    autoAcceptIncoming = true,
                    incomingTransferHandler = incomingTransferHandler
                ),
                "PairDropAndroid"
            )
            webViewClient = object : WebViewClient() {}
        }
    }
}
