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
        val escapedAccepted = if (accepted) "true" else "false"
        mainHandler.post {
            emitTransferResponse(escapedPeerId, escapedAccepted, attempt = 0)
        }
    }

    private fun emitTransferResponse(escapedPeerId: String, escapedAccepted: String, attempt: Int) {
        val currentWebView = webView ?: return
        currentWebView.evaluateJavascript(
            """
                (function () {
                    if (!window.Events || !window.pairDrop || !window.pairDrop.peers || !window.pairDrop.peers.peers) return false;
                    var peerId = $escapedPeerId;
                    var peer = window.pairDrop.peers.peers[peerId];
                    if (!peer || !peer._requestPending) return false;
                    window.Events.fire('respond-to-files-transfer-request', {
                        to: peerId,
                        accepted: $escapedAccepted
                    });
                    return true;
                })();
            """.trimIndent()
        ) { result ->
            val sent = result == "true"
            if (sent) return@evaluateJavascript
            if (attempt >= 20) return@evaluateJavascript
            mainHandler.postDelayed(
                { emitTransferResponse(escapedPeerId, escapedAccepted, attempt + 1) },
                150
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
                    autoAcceptIncoming = false,
                    incomingTransferHandler = incomingTransferHandler
                ),
                "PairDropAndroid"
            )
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                }
            }
        }
    }
}
