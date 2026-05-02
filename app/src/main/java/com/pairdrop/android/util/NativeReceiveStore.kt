package com.pairdrop.android.util

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object NativeReceiveStore {
    private data class Session(
        val name: String,
        val mime: String,
        val uri: Uri,
        val output: OutputStream,
        val legacyFile: File?
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    fun begin(context: Context, name: String, mime: String): String {
        val safeName = DownloadSaver.sanitizeFileName(name.ifBlank { "PairDrop file" })
        val safeMime = mime.ifBlank { "application/octet-stream" }
        val token = UUID.randomUUID().toString()
        val session = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            beginMediaStore(context, safeName, safeMime)
        } else {
            beginLegacy(context, safeName)
        }
        sessions[token] = session
        return token
    }

    fun append(token: String, base64: String): Boolean {
        val session = sessions[token] ?: return false
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        session.output.write(bytes)
        return true
    }

    fun finish(context: Context, token: String): String {
        val session = sessions.remove(token) ?: return ""
        session.output.flush()
        session.output.close()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(session.uri, values, null, null)
        } else {
            session.legacyFile?.let { file ->
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            }
        }

        NotificationHelper.ensureChannels(context)
        val notification = NotificationHelper.savedFileNotification(
            context = context,
            fileName = session.name,
            uri = session.uri,
            mime = session.mime
        )
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.notify(
            NotificationHelper.SAVED_NOTIFICATION_BASE_ID + (session.name.hashCode() and 0x0FFF),
            notification
        )
        return session.uri.toString()
    }

    fun abort(token: String) {
        val session = sessions.remove(token) ?: return
        runCatching { session.output.close() }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun beginMediaStore(context: Context, name: String, mime: String): Session {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PairDrop")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create MediaStore entry")
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("Could not open MediaStore output stream")
        return Session(name = name, mime = mime, uri = uri, output = output, legacyFile = null)
    }

    private fun beginLegacy(context: Context, name: String): Session {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PairDrop"
        ).apply { mkdirs() }
        val file = uniqueFile(directory, name)
        return Session(
            name = name,
            mime = "application/octet-stream",
            uri = Uri.fromFile(file),
            output = FileOutputStream(file),
            legacyFile = file
        )
    }

    private fun uniqueFile(directory: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var candidate = File(directory, name)
        var index = 1
        while (candidate.exists()) {
            val suffix = if (extension.isBlank()) " ($index)" else " ($index).$extension"
            candidate = File(directory, "$base$suffix")
            index += 1
        }
        return candidate
    }
}
