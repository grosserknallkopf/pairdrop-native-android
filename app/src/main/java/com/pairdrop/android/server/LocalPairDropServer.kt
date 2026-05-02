package com.pairdrop.android.server

import android.content.Context
import android.net.Uri
import com.pairdrop.android.share.PendingShareStore
import com.pairdrop.android.util.Constants
import com.pairdrop.android.util.DownloadSaver
import com.pairdrop.android.util.NetworkState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration

class LocalPairDropServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val nodeId: String,
    private val onTransferStatus: (String, Int?) -> Unit
) {
    val hub = PairDropSignalingHub(scope, nodeId)
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, host = "0.0.0.0", port = Constants.LOCAL_HTTP_PORT, module = {
            install(WebSockets) {
                pingPeriodMillis = Duration.ofSeconds(15).toMillis()
                timeoutMillis = Duration.ofSeconds(30).toMillis()
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            pairDropRoutes()
        }).start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1_500)
        engine = null
    }

    fun refreshRemoteEndpoint(endpoint: ServiceEndpoint): Boolean {
        if (endpoint.port == Constants.LOCAL_HTTP_PORT && endpoint.host == "127.0.0.1") return true
        return runCatching {
            val connection = (URL("${endpoint.baseUrl}/native/peers").openConnection() as HttpURLConnection)
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.setRequestProperty("Accept", "application/json")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val root = JSONObject(text)
            val remoteNodeId = root.optString("nodeId")
            if (remoteNodeId == nodeId) return true
            val peers = root.optJSONArray("peers") ?: JSONArray()
            val peerObjects = (0 until peers.length()).map { index -> peers.getJSONObject(index) }
            hub.updateRemotePeers(endpoint.copy(nodeId = remoteNodeId), peerObjects)
            true
        }.getOrElse {
            hub.pruneRemoteEndpoint(endpoint)
            false
        }
    }

    private fun Application.pairDropRoutes() {
        routing {
            get("/config") {
                val signalingServer: Any = if (NetworkState.hasValidatedInternet(context)) {
                    Constants.CLOUD_SIGNALING_SERVER
                } else {
                    false
                }

                call.respondText(
                    JSONObject()
                        .put("signalingServer", signalingServer)
                        .put("buttons", JSONObject())
                        .put(
                            "native",
                            JSONObject()
                                .put("offlineLan", true)
                                .put("nodeId", nodeId)
                        )
                        .toString(),
                    ContentType.Application.Json
                )
            }

            webSocket("/server") {
                hub.handleConnection(
                    session = this,
                    query = call.request.queryParameters,
                    userAgent = call.request.header("User-Agent")
                )
            }

            get("/native/peers") {
                call.respondText(hub.localPeersJson(), ContentType.Application.Json)
            }

            post("/native/relay") {
                hub.receiveRemoteRelay(JSONObject(call.receiveText()))
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            }

            get("/native/share/{id}") {
                val id = call.parameters["id"].orEmpty()
                val file = PendingShareStore.fileForId(id)
                if (file == null || !file.exists()) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val (name, mime) = PendingShareStore.metadataForId(id) ?: (file.name to "application/octet-stream")
                call.response.headers.append("Content-Type", mime)
                call.response.headers.append(
                    "Content-Disposition",
                    "inline; filename=\"${name.replace("\"", "")}\""
                )
                call.respondFile(file)
            }

            post("/native/received-file") {
                val fileName = call.request.queryParameters["name"] ?: "PairDrop file"
                val mime = call.request.queryParameters["mime"] ?: "application/octet-stream"
                val totalBytes = call.request.header("Content-Length")?.toLongOrNull()
                var lastPercent = -1

                val uri: Uri = DownloadSaver.saveToDownloads(
                    context = context,
                    name = fileName,
                    mime = mime,
                    input = call.receiveChannel()
                ) { written ->
                    val percent = if (totalBytes != null && totalBytes > 0) {
                        ((written * 100) / totalBytes).toInt().coerceIn(0, 100)
                    } else {
                        null
                    }
                    if (percent == null || percent != lastPercent) {
                        lastPercent = percent ?: -1
                        onTransferStatus("Saving $fileName", percent)
                    }
                }

                onTransferStatus("Saved $fileName", 100)
                call.respondText(
                    JSONObject()
                        .put("ok", true)
                        .put("uri", uri.toString())
                        .toString(),
                    ContentType.Application.Json
                )
            }

            get("/") {
                call.respondAsset("index.html")
            }

            get("/{path...}") {
                val path = call.parameters.getAll("path")?.joinToString("/") ?: "index.html"
                if (path.startsWith("native/") || path.contains("..")) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                if (!call.respondAsset(path)) {
                    call.respondRedirect("/")
                }
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondAsset(path: String): Boolean {
        val assetPath = "${Constants.PAIRDROP_ASSET_ROOT}/${path.ifBlank { "index.html" }}"
        return try {
            respondOutputStream(contentTypeFor(path)) {
                context.assets.open(assetPath).use { input ->
                    input.copyTo(this)
                }
            }
            true
        } catch (_: FileNotFoundException) {
            false
        }
    }

    private fun contentTypeFor(path: String): ContentType {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "html" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
            "js" -> ContentType.parse("application/javascript").withCharset(Charsets.UTF_8)
            "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
            "json", "webmanifest" -> ContentType.Application.Json.withCharset(Charsets.UTF_8)
            "svg" -> ContentType.parse("image/svg+xml")
            "png" -> ContentType.Image.PNG
            "jpg", "jpeg" -> ContentType.Image.JPEG
            "gif" -> ContentType.Image.GIF
            "ttf" -> ContentType.parse("font/ttf")
            "mp3" -> ContentType.parse("audio/mpeg")
            "ogg" -> ContentType.parse("audio/ogg")
            else -> ContentType.Application.OctetStream
        }
    }
}
