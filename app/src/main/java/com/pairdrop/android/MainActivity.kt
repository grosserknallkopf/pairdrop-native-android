package com.pairdrop.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedDispatcher
import com.pairdrop.android.bridge.AndroidBridge
import com.pairdrop.android.intro.IntroView
import com.pairdrop.android.service.PairDropService
import com.pairdrop.android.share.PendingShareStore
import com.pairdrop.android.util.Constants

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var loadAttempts = 0
    private var pendingShareInjectionAttempts = 0
    private var introView: IntroView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (shouldShowIntro()) {
            showIntro()
            return
        }
        startPairDropUi(intent)
    }

    private fun startPairDropUi(startIntent: Intent?) {
        requestNotificationPermissionIfNeeded()
        PairDropService.startForUi(this)
        PendingShareStore.addFromIntent(this, startIntent)
        setupWebView()
        registerBackHandler()
        loadPairDrop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PairDropService.startForUi(this)
        PendingShareStore.addFromIntent(this, intent)
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "window.PairDropNative && window.PairDropNative.consumePendingShares && window.PairDropNative.consumePendingShares();",
                null
            )
        }
    }

    override fun onResume() {
        super.onResume()
        introView?.refresh() ?: PairDropService.keepAlive(this)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("PairDropAndroid")
            webView.destroy()
        }
        if (!isChangingConfigurations) {
            PairDropService.releaseUi(this)
        }
        super.onDestroy()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && handleBackNavigation()) return true
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val result = if (resultCode == RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else {
                null
            }
            filePathCallback?.onReceiveValue(result)
            filePathCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        )
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            addJavascriptInterface(AndroidBridge(this@MainActivity, autoAcceptIncoming = false), "PairDropAndroid")
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame && loadAttempts < 20) {
                        loadAttempts += 1
                        view.postDelayed({ loadPairDrop() }, 300)
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    loadAttempts = 0
                    PairDropService.keepAlive(this@MainActivity)
                    schedulePendingShareInjection()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    android.util.Log.d(
                        "PairDropWeb",
                        "${consoleMessage.message()} @${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                    )
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    return runCatching {
                        startActivityForResult(fileChooserParams.createIntent(), FILE_CHOOSER_REQUEST)
                        true
                    }.getOrElse {
                        this@MainActivity.filePathCallback = null
                        false
                    }
                }
            }
        }
        setContentView(webView)
    }

    private fun loadPairDrop() {
        webView.loadUrl("http://127.0.0.1:${Constants.LOCAL_HTTP_PORT}/")
    }

    private fun schedulePendingShareInjection() {
        pendingShareInjectionAttempts = 0
        tryInjectPendingShare()
    }

    private fun tryInjectPendingShare() {
        if (!::webView.isInitialized || pendingShareInjectionAttempts >= 40) return
        pendingShareInjectionAttempts += 1
        webView.evaluateJavascript(
            """
                (function () {
                    if (window.PairDropNative && window.PairDropNative.ready) {
                        window.PairDropNative.consumePendingShares();
                        return true;
                    }
                    return false;
                })();
            """.trimIndent()
        ) { result ->
            if (result != "true") {
                mainHandler.postDelayed({ tryInjectPendingShare() }, 250)
            }
        }
    }

    private fun registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (!handleBackNavigation()) finish()
            }
        }
    }

    private fun handleBackNavigation(): Boolean {
        return if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            true
        } else {
            false
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
    }

    private fun shouldShowIntro(): Boolean {
        return !getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
            .getBoolean(Constants.PREF_INTRO_DONE, false)
    }

    private fun showIntro() {
        introView = IntroView(
            context = this,
            callbacks = object : IntroView.Callbacks {
                override fun requestNotifications() = requestNotificationPermissionIfNeeded()
                override fun openBatterySettings() = openBatteryOptimizationSettings()
                override fun openAppSettings() = this@MainActivity.openAppSettings()
                override fun finishIntro() {
                    getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
                        .edit()
                        .putBoolean(Constants.PREF_INTRO_DONE, true)
                        .apply()
                    introView = null
                    startPairDropUi(intent)
                }
            }
        )
        setContentView(introView!!.create())
    }

    private fun openBatteryOptimizationSettings() {
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
        val packageUri = Uri.parse("package:$packageName")
        val intent = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            powerManager?.isIgnoringBatteryOptimizations(packageName) != true
        ) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    companion object {
        private const val FILE_CHOOSER_REQUEST = 4100
        private const val NOTIFICATION_PERMISSION_REQUEST = 4101
    }
}
