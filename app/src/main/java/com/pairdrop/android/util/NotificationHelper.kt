package com.pairdrop.android.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.pairdrop.android.MainActivity
import com.pairdrop.android.R
import com.pairdrop.android.service.PairDropService

object NotificationHelper {
    const val SERVICE_CHANNEL_ID = "pairdrop_service"
    const val TRANSFER_CHANNEL_ID = "pairdrop_transfers"
    const val REQUEST_CHANNEL_ID = "pairdrop_requests"
    const val NOTIFICATION_ID = 42
    const val REQUEST_NOTIFICATION_BASE_ID = 10_000
    const val SAVED_NOTIFICATION_BASE_ID = 20_000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            context.getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW
        )
        serviceChannel.description = context.getString(R.string.notification_service_text)
        manager.createNotificationChannel(serviceChannel)

        val transferChannel = NotificationChannel(
            TRANSFER_CHANNEL_ID,
            context.getString(R.string.notification_channel_transfer),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(transferChannel)

        val requestChannel = NotificationChannel(
            REQUEST_CHANNEL_ID,
            context.getString(R.string.notification_channel_requests),
            NotificationManager.IMPORTANCE_HIGH
        )
        requestChannel.description = context.getString(R.string.notification_channel_transfer)
        requestChannel.enableVibration(true)
        manager.createNotificationChannel(requestChannel)
    }

    fun serviceNotification(
        context: Context,
        text: String = context.getString(R.string.notification_service_text),
        progress: Int? = null
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            pendingIntentFlags()
        )
        val stopIntent = PendingIntent.getService(
            context,
            2,
            Intent(context, PairDropService::class.java).setAction(PairDropService.ACTION_STOP),
            pendingIntentFlags()
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, SERVICE_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_service_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.notification_stop),
                    stopIntent
                ).build()
            )

        if (progress != null) {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    fun incomingRequestNotification(
        context: Context,
        peerId: String,
        title: String,
        body: String
    ): Notification {
        val acceptIntent = PendingIntent.getService(
            context,
            peerId.hashCode() xor 0xA11,
            Intent(context, PairDropService::class.java)
                .setAction(PairDropService.ACTION_ACCEPT_TRANSFER)
                .putExtra(PairDropService.EXTRA_PEER_ID, peerId),
            pendingIntentFlags()
        )
        val rejectIntent = PendingIntent.getService(
            context,
            peerId.hashCode() xor 0xD11,
            Intent(context, PairDropService::class.java)
                .setAction(PairDropService.ACTION_REJECT_TRANSFER)
                .putExtra(PairDropService.EXTRA_PEER_ID, peerId),
            pendingIntentFlags()
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, REQUEST_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)
            .setDefaults(Notification.DEFAULT_ALL)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.notification_reject),
                    rejectIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.notification_accept),
                    acceptIntent
                ).build()
            )
            .build()
    }

    fun savedFileNotification(
        context: Context,
        fileName: String,
        uri: Uri,
        mime: String
    ): Notification {
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            fileName.hashCode(),
            Intent.createChooser(openIntent, "Open with"),
            pendingIntentFlags()
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, TRANSFER_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PairDrop file saved")
            .setContentText(fileName)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.notification_open),
                    openPendingIntent
                ).build()
            )
            .build()
    }

    fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }
}
