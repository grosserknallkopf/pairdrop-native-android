package com.pairdrop.android.util

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object DownloadSaver {
    private const val BUFFER_SIZE = 64 * 1024

    suspend fun saveToDownloads(
        context: Context,
        name: String,
        mime: String,
        input: ByteReadChannel,
        onBytesWritten: (Long) -> Unit
    ): Uri {
        val safeName = sanitizeFileName(name.ifBlank { "PairDrop file" })
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, safeName, mime, input, onBytesWritten)
        } else {
            saveLegacy(context, safeName, input, onBytesWritten)
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private suspend fun saveWithMediaStore(
        context: Context,
        name: String,
        mime: String,
        input: ByteReadChannel,
        onBytesWritten: (Long) -> Unit
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/PairDrop"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create MediaStore entry")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                copy(input, output, onBytesWritten)
            } ?: error("Could not open MediaStore output stream")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (throwable: Throwable) {
            resolver.delete(uri, null, null)
            throw throwable
        }
    }

    private suspend fun saveLegacy(
        context: Context,
        name: String,
        input: ByteReadChannel,
        onBytesWritten: (Long) -> Unit
    ): Uri {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PairDrop"
        )
        if (!directory.exists()) directory.mkdirs()

        val file = uniqueFile(directory, name)
        FileOutputStream(file).use { output ->
            copy(input, output, onBytesWritten)
        }

        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        return Uri.fromFile(file)
    }

    private suspend fun copy(
        input: ByteReadChannel,
        output: OutputStream,
        onBytesWritten: (Long) -> Unit
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            output.write(buffer, 0, read)
            total += read
            onBytesWritten(total)
        }
        output.flush()
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

    fun sanitizeFileName(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .ifBlank { "PairDrop file" }
    }
}
