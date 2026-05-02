package com.pairdrop.android.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
            ACTION_STOP -> {
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
            else -> ensureStarted()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        remoteSyncJob?.cancel()
        shutdownJob?.cancel()
        server?.stop()
        nsdManager?.stop()
        multicastLock?.let { if (it.isHeld) it.release() }
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
            onTransferStatus = ::updateNotification
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

    companion object {
        const val ACTION_START = "com.pairdrop.android.START"
        const val ACTION_STOP = "com.pairdrop.android.STOP"
        const val ACTION_PROGRESS = "com.pairdrop.android.PROGRESS"
        const val ACTION_KEEP_ALIVE = "com.pairdrop.android.KEEP_ALIVE"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PROGRESS = "progress"

        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        fun start(context: Context) {
            val intent = Intent(context, PairDropService::class.java).setAction(ACTION_START)
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
