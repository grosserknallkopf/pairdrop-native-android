package com.pairdrop.android.bridge

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import com.pairdrop.android.service.PairDropService
import com.pairdrop.android.share.PendingShareStore
import com.pairdrop.android.util.NativeReceiveStore

class AndroidBridge(
    context: Context,
    private val autoAcceptIncoming: Boolean = true,
    private val incomingTransferHandler: ((String, String) -> Boolean)? = null
) {
    private val appContext = context.applicationContext

    @JavascriptInterface
    fun consumePendingShares(): String = PendingShareStore.consumeAsJson()

    @JavascriptInterface
    fun hasPendingShares(): Boolean = PendingShareStore.hasPendingShares()

    @JavascriptInterface
    fun handlesDownloads(): Boolean = true

    @JavascriptInterface
    fun beginReceiveFile(name: String?, mime: String?, size: Double): String {
        return NativeReceiveStore.begin(
            context = appContext,
            name = name.orEmpty(),
            mime = mime.orEmpty()
        )
    }

    @JavascriptInterface
    fun appendReceiveFile(token: String?, base64: String?): Boolean {
        return NativeReceiveStore.append(token.orEmpty(), base64.orEmpty())
    }

    @JavascriptInterface
    fun finishReceiveFile(token: String?): String {
        return NativeReceiveStore.finish(appContext, token.orEmpty())
    }

    @JavascriptInterface
    fun abortReceiveFile(token: String?) {
        NativeReceiveStore.abort(token.orEmpty())
    }

    @JavascriptInterface
    fun autoAcceptIncoming(): Boolean = autoAcceptIncoming

    @JavascriptInterface
    fun onIncomingTransferRequest(peerId: String?, requestJson: String?): Boolean {
        val id = peerId.orEmpty()
        val request = requestJson.orEmpty()
        if (id.isBlank() || request.isBlank()) return false
        return incomingTransferHandler?.invoke(id, request) ?: false
    }

    @JavascriptInterface
    fun keepAlive() {
        PairDropService.keepAlive(appContext)
    }

    @JavascriptInterface
    fun onTransferProgress(peerId: String?, progress: Double, status: String?) {
        val percent = (progress.coerceIn(0.0, 1.0) * 100).toInt()
        val label = when (status) {
            "transfer" -> "Transfer in progress"
            "process" -> "Processing received files"
            "prepare" -> "Preparing transfer"
            "wait" -> "Waiting for peer"
            else -> "PairDrop transfer"
        }
        PairDropService.updateProgress(appContext, label, percent)
    }

    @JavascriptInterface
    fun log(message: String?) {
        Log.d("PairDropWeb", message.orEmpty())
    }
}
