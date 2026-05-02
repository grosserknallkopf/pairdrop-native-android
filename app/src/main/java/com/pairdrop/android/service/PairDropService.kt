package com.pairdrop.android.service

import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import com.pairdrop.android.discovery.PairDropNsdManager
import com.pairdrop.android.quicksettings.PairDropTileService
import com.pairdrop.android.server.LocalPairDropServer
import com.pairdrop.android.util.Constants
import com.pairdrop.android.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class PairDropService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var nodeId: String
    private var server: LocalPairDropServer? = null
    private var nsdManager: PairDropNsdManager? = null
    private var remoteSyncJob: Job? = null
    private var shutdownJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var headlessClient: HeadlessPairDropClient? = null
    private val pendingTransferRequests = mutableMapOf<String, String>()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.serviceNotification(this)
        )
        running.set(true)
        PairDropTileService.requestTileUpdate(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TILE -> {
                setTileEnabled(true)
                ensureStarted()
                startHeadlessClient()
            }
            ACTION_START_UI -> {
                ensureStarted()
                stopHeadlessClient()
            }
            ACTION_RELEASE_UI -> {
                if (isTileEnabled()) {
                    startHeadlessClient()
                } else {
                    stopSelf()
                }
            }
            ACTION_ACCEPT_TRANSFER -> {
                respondToPendingTransfer(
                    peerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty(),
                    accepted = true
                )
            }
            ACTION_REJECT_TRANSFER -> {
                respondToPendingTransfer(
                    peerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty(),
                    accepted = false
                )
            }
            ACTION_STOP -> {
                setTileEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROGRESS -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: getString(com.pairdrop.android.R.string.notification_service_text)
                val progress = intent.getIntExtra(EXTRA_PROGRESS, -1).takeIf { it >= 0 }
                updateNotification(text, progress)
                scheduleShutdown()
            }
            ACTION_KEEP_ALIVE -> scheduleShutdown()
            else -> {
                ensureStarted()
                if (isTileEnabled()) startHeadlessClient()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        remoteSyncJob?.cancel()
        shutdownJob?.cancel()
        stopHeadlessClient()
        server?.stop()
        nsdManager?.stop()
        multicastLock?.let { if (it.isHeld) it.release() }
        setTileEnabled(false)
        running.set(false)
        PairDropTileService.requestTileUpdate(this)
        super.onDestroy()
    }

    private fun ensureStarted() {
        if (server != null) {
            scheduleShutdown()
            return
        }

        nodeId = loadNodeId()
        acquireMulticastLock()

        val localServer = LocalPairDropServer(
            context = applicationContext,
            scope = scope,
            nodeId = nodeId,
            onTransferStatus = ::updateNotification,
            onFileSaved = ::showSavedFileNotification
        )
        localServer.start()
        server = localServer

        nsdManager = PairDropNsdManager(applicationContext, nodeId).also {
            it.start(Constants.LOCAL_HTTP_PORT)
        }

        remoteSyncJob = scope.launch {
            while (isActive) {
                val endpoints = nsdManager?.snapshot().orEmpty()
                endpoints.forEach { endpoint ->
                    localServer.refreshRemoteEndpoint(endpoint)
                }
                delay(3_000)
            }
        }

        updateNotification(getString(com.pairdrop.android.R.string.notification_service_text), null)
        scheduleShutdown()
    }

    private fun updateNotification(text: String, progress: Int?) {
        val notification = NotificationHelper.serviceNotification(this, text, progress)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NotificationHelper.NOTIFICATION_ID, notification)
    }

    private fun showSavedFileNotification(fileName: String, mime: String, uri: Uri) {
        val notification = NotificationHelper.savedFileNotification(
            context = this,
            fileName = fileName,
            uri = uri,
            mime = mime
        )
        getSystemService(NotificationManager::class.java)
            .notify(NotificationHelper.SAVED_NOTIFICATION_BASE_ID + (fileName.hashCode() and 0x0FFF), notification)
    }

    private fun scheduleShutdown() {
        shutdownJob?.cancel()
        val minutes = getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
            .getInt(Constants.PREF_AUTO_SHUTDOWN_MINUTES, Constants.DEFAULT_AUTO_SHUTDOWN_MINUTES)
            .coerceAtLeast(1)
        shutdownJob = scope.launch {
            delay(minutes * 60_000L)
            stopSelf()
        }
    }

    private fun acquireMulticastLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifiManager.createMulticastLock("PairDropNsd").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun loadNodeId(): String {
        val preferences = getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
        preferences.getString("node_id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        preferences.edit().putString("node_id", id).apply()
        return id
    }

    private fun startHeadlessClient() {
        headlessClient ?: HeadlessPairDropClient(
            context = applicationContext,
            incomingTransferHandler = ::onHeadlessTransferRequest
        ).also {
            headlessClient = it
            it.start()
        }
    }

    private fun stopHeadlessClient() {
        headlessClient?.stop()
        headlessClient = null
    }

    private fun onHeadlessTransferRequest(peerId: String, requestJson: String): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        pendingTransferRequests[peerId] = requestJson
        val notification = NotificationHelper.incomingRequestNotification(
            context = this,
            peerId = peerId,
            title = getString(com.pairdrop.android.R.string.notification_request_title),
            body = describeTransferRequest(requestJson)
        )
        getSystemService(NotificationManager::class.java)
            .notify(requestNotificationId(peerId), notification)
        scheduleShutdown()
        return true
    }

    private fun respondToPendingTransfer(peerId: String, accepted: Boolean) {
        if (peerId.isBlank()) return
        pendingTransferRequests.remove(peerId)
        headlessClient?.respondToTransfer(peerId, accepted)
        getSystemService(NotificationManager::class.java).cancel(requestNotificationId(peerId))
        if (accepted) {
            updateNotification("Receiving PairDrop transfer", 0)
        } else {
            updateNotification(getString(com.pairdrop.android.R.string.notification_service_text), null)
        }
        scheduleShutdown()
    }

    private fun describeTransferRequest(requestJson: String): String {
        val request = runCatching { JSONObject(requestJson) }.getOrNull()
            ?: return "A nearby device wants to send files."
        val header = request.optJSONArray("header")
        if (header == null || header.length() == 0) return "A nearby device wants to send files."

        val firstName = header.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
            ?: "File"
        val count = header.length()
        val totalSize = request.optLong("totalSize", -1)
        val size = if (totalSize > 0) " (${formatBytes(totalSize)})" else ""
        return if (count == 1) {
            "$firstName$size"
        } else {
            "$firstName and ${count - 1} more file(s)$size"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit += 1
        }
        return if (unit == 0) "${bytes} ${units[unit]}" else "%.1f %s".format(value, units[unit])
    }

    private fun requestNotificationId(peerId: String): Int {
        return NotificationHelper.REQUEST_NOTIFICATION_BASE_ID + (peerId.hashCode() and 0x0FFF)
    }

    private fun isTileEnabled(): Boolean {
        return getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
            .getBoolean(Constants.PREF_TILE_ENABLED, false)
    }

    private fun setTileEnabled(enabled: Boolean) {
        getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_TILE_ENABLED, enabled)
            .apply()
    }

    companion object {
        const val ACTION_START_UI = "com.pairdrop.android.START_UI"
        const val ACTION_START_TILE = "com.pairdrop.android.START_TILE"
        const val ACTION_RELEASE_UI = "com.pairdrop.android.RELEASE_UI"
        const val ACTION_STOP = "com.pairdrop.android.STOP"
        const val ACTION_PROGRESS = "com.pairdrop.android.PROGRESS"
        const val ACTION_KEEP_ALIVE = "com.pairdrop.android.KEEP_ALIVE"
        const val ACTION_ACCEPT_TRANSFER = "com.pairdrop.android.ACCEPT_TRANSFER"
        const val ACTION_REJECT_TRANSFER = "com.pairdrop.android.REJECT_TRANSFER"
        const val EXTRA_PEER_ID = "peer_id"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PROGRESS = "progress"

        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        fun isTileEnabled(context: Context): Boolean {
            return context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Constants.PREF_TILE_ENABLED, false)
        }

        fun startForUi(context: Context) {
            start(context, ACTION_START_UI)
        }

        fun startFromTile(context: Context) {
            start(context, ACTION_START_TILE)
        }

        fun releaseUi(context: Context) {
            if (!isRunning()) return
            context.startService(Intent(context, PairDropService::class.java).setAction(ACTION_RELEASE_UI))
        }

        private fun start(context: Context, action: String) {
            val intent = Intent(context, PairDropService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PairDropService::class.java).setAction(ACTION_STOP))
        }

        fun keepAlive(context: Context) {
            if (!isRunning()) return
            context.startService(Intent(context, PairDropService::class.java).setAction(ACTION_KEEP_ALIVE))
        }

        fun updateProgress(context: Context, text: String, progress: Int?) {
            if (!isRunning()) return
            val intent = Intent(context, PairDropService::class.java)
                .setAction(ACTION_PROGRESS)
                .putExtra(EXTRA_TEXT, text)
            if (progress != null) intent.putExtra(EXTRA_PROGRESS, progress)
            context.startService(intent)
        }
    }
}
