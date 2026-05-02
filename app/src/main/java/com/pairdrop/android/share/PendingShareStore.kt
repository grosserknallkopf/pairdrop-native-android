package com.pairdrop.android.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.pairdrop.android.util.Constants
import com.pairdrop.android.util.DownloadSaver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object PendingShareStore {
    private data class SharedFile(
        val id: String,
        val name: String,
        val mime: String,
        val size: Long,
        val file: File
    )

    private val lock = Any()
    private val pendingFiles = mutableListOf<SharedFile>()
    private val availableFiles = linkedMapOf<String, SharedFile>()
    private var pendingText: String? = null

    fun addFromIntent(context: Context, intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> addSingle(context, intent)
            Intent.ACTION_SEND_MULTIPLE -> addMultiple(context, intent)
        }
    }

    fun consumeAsJson(): String {
        val files: List<SharedFile>
        val text: String?
        synchronized(lock) {
            files = pendingFiles.toList()
            pendingFiles.clear()
            files.forEach { availableFiles[it.id] = it }
            text = pendingText
            pendingText = null
        }

        val root = JSONObject()
        root.put("text", text ?: "")
        root.put("files", JSONArray().also { array ->
            files.forEach { file ->
                array.put(
                    JSONObject()
                        .put("id", file.id)
                        .put("name", file.name)
                        .put("mime", file.mime)
                        .put("size", file.size)
                        .put(
                            "url",
                            "http://127.0.0.1:${Constants.LOCAL_HTTP_PORT}/native/share/${file.id}"
                        )
                )
            }
        })
        return root.toString()
    }

    fun fileForId(id: String): File? = synchronized(lock) {
        availableFiles[id]?.file
    }

    fun metadataForId(id: String): Pair<String, String>? = synchronized(lock) {
        availableFiles[id]?.let { it.name to it.mime }
    }

    private fun addSingle(context: Context, intent: Intent) {
        val stream = intent.streamUri()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (stream != null) {
            addUri(context, stream, intent.type)
        } else if (!text.isNullOrBlank()) {
            synchronized(lock) { pendingText = text }
        }
    }

    private fun addMultiple(context: Context, intent: Intent) {
        intent.streamUris().forEach { uri ->
            addUri(context, uri, intent.type)
        }
    }

    private fun addUri(context: Context, uri: Uri, fallbackMime: String?) {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri)
            ?: uri.lastPathSegment
            ?: "shared-file"
        val mime = resolver.getType(uri)
            ?: fallbackMime
            ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        val safeName = DownloadSaver.sanitizeFileName(displayName)
        val directory = File(context.cacheDir, "pending-shares").apply { mkdirs() }
        val target = File(directory, "$id-$safeName")

        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        } ?: return

        val sharedFile = SharedFile(
            id = id,
            name = safeName,
            mime = mime,
            size = target.length(),
            file = target
        )
        synchronized(lock) {
            pendingFiles += sharedFile
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }

    private fun Intent.streamUri(): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    private fun Intent.streamUris(): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList().orEmpty()
        }
    }
}
