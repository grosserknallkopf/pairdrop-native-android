package com.pairdrop.android.bridge

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import com.pairdrop.android.service.PairDropService
import com.pairdrop.android.share.PendingShareStore

class AndroidBridge(
    context: Context,
    private val autoAcceptIncoming: Boolean = true
) {
    private val appContext = context.applicationContext

    @JavascriptInterface
    fun consumePendingShares(): String = PendingShareStore.consumeAsJson()

    @JavascriptInterface
    fun handlesDownloads(): Boolean = true

    @JavascriptInterface
    fun autoAcceptIncoming(): Boolean = autoAcceptIncoming

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
