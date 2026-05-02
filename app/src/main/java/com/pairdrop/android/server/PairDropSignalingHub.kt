package com.pairdrop.android.server

import android.os.Build
import android.os.SystemClock
import io.ktor.http.Parameters
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class ServiceEndpoint(
    val host: String,
    val port: Int,
    val nodeId: String? = null
) {
    val key: String = "$host:$port"
    private val urlHost: String = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
    val baseUrl: String = "http://$urlHost:$port"
}

class PairDropSignalingHub(
    private val scope: CoroutineScope,
    private val nodeId: String
) {
    private val lock = Any()
    private val rooms = mutableMapOf<String, MutableMap<String, SignalingPeer>>()
    private val peers = mutableMapOf<String, SignalingPeer>()
    private val roomSecrets = mutableMapOf<String, PairKeyEntry>()
    private val keepAliveJobs = mutableMapOf<String, Job>()
    private val remotePeers = mutableMapOf<String, RemotePeer>()

    fun localPeersJson(): String {
        val peerArray = JSONArray()
        synchronized(lock) {
            peers.values.forEach { peerArray.put(it.infoJson()) }
        }
        return JSONObject()
            .put("nodeId", nodeId)
            .put("peers", peerArray)
            .toString()
    }

    suspend fun handleConnection(
        session: DefaultWebSocketServerSession,
        query: Parameters,
        userAgent: String?
    ) {
        val peer = createPeer(session, query, userAgent)
        synchronized(lock) {
            peers[peer.id]?.let { disconnectLocked(it, true) }
            peers[peer.id] = peer
        }

        send(peer, wsConfigJson())
        send(peer, displayNameJson(peer))
        startKeepAlive(peer)

        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                handleClientMessage(peer, frame.readText())
            }
        } finally {
            synchronized(lock) {
                if (peers[peer.id] === peer) {
                    disconnectLocked(peer, true)
                }
            }
        }
    }

    fun updateRemotePeers(endpoint: ServiceEndpoint, peerInfos: List<JSONObject>) {
        val seenPeerIds = peerInfos.mapNotNull { it.optString("id").takeIf { id -> id.isNotBlank() } }.toSet()
        val joinedMessages = mutableListOf<Pair<SignalingPeer, JSONObject>>()
        val leftMessages = mutableListOf<Pair<SignalingPeer, JSONObject>>()

        synchronized(lock) {
            peerInfos.forEach { info ->
                val peerId = info.optString("id")
                if (peerId.isBlank() || peers.containsKey(peerId)) return@forEach
                val existing = remotePeers[peerId]
                val remotePeer = RemotePeer(peerId, info, endpoint, SystemClock.elapsedRealtime())
                remotePeers[peerId] = remotePeer
                if (existing == null || existing.endpoint.key != endpoint.key) {
                    localIpRoomPeersLocked().forEach { local ->
                        joinedMessages += local to JSONObject()
                            .put("type", "peer-joined")
                            .put("peer", info)
                            .put("roomType", "ip")
                            .put("roomId", local.ip)
                    }
                }
            }

            val currentEndpointRemoteIds = remotePeers.values
                .filter { it.endpoint.key == endpoint.key }
                .map { it.id }
                .toSet()
            val gone = currentEndpointRemoteIds - seenPeerIds
            gone.forEach { peerId ->
                remotePeers.remove(peerId)
                localIpRoomPeersLocked().forEach { local ->
                    leftMessages += local to JSONObject()
                        .put("type", "peer-left")
                        .put("peerId", peerId)
                        .put("roomType", "ip")
                        .put("roomId", local.ip)
                        .put("disconnect", true)
                }
            }
        }

        joinedMessages.forEach { (peer, message) -> send(peer, message) }
        leftMessages.forEach { (peer, message) -> send(peer, message) }
    }

    fun pruneRemoteEndpoint(endpoint: ServiceEndpoint) {
        val leftMessages = mutableListOf<Pair<SignalingPeer, JSONObject>>()
        synchronized(lock) {
            val gone = remotePeers.values.filter { it.endpoint.key == endpoint.key }.map { it.id }
            gone.forEach { peerId ->
                remotePeers.remove(peerId)
                localIpRoomPeersLocked().forEach { local ->
                    leftMessages += local to JSONObject()
                        .put("type", "peer-left")
                        .put("peerId", peerId)
                        .put("roomType", "ip")
                        .put("roomId", local.ip)
                        .put("disconnect", true)
                }
            }
        }
        leftMessages.forEach { (peer, message) -> send(peer, message) }
    }

    fun receiveRemoteRelay(message: JSONObject) {
        val to = message.optString("to")
        val recipient = synchronized(lock) { peers[to] } ?: return
        message.remove("to")
        send(recipient, message)
    }

    private fun handleClientMessage(sender: SignalingPeer, rawMessage: String) {
        val message = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return
        when (message.optString("type")) {
            "disconnect" -> synchronized(lock) { disconnectLocked(sender, true) }
            "pong" -> sender.lastBeat = SystemClock.elapsedRealtime()
            "join-ip-room" -> joinIpRoom(sender)
            "room-secrets" -> onRoomSecrets(sender, message)
            "room-secrets-deleted" -> onRoomSecretsDeleted(message)
            "pair-device-initiate" -> onPairDeviceInitiate(sender)
            "pair-device-join" -> onPairDeviceJoin(sender, message)
            "pair-device-cancel" -> onPairDeviceCancel(sender)
            "regenerate-room-secret" -> onRegenerateRoomSecret(sender, message)
            "create-public-room" -> onCreatePublicRoom(sender)
            "join-public-room" -> onJoinPublicRoom(sender, message)
            "leave-public-room" -> onLeavePublicRoom(sender)
            "signal",
            "request",
            "header",
            "partition",
            "partition-received",
            "progress",
            "files-transfer-response",
            "file-transfer-complete",
            "message-transfer-complete",
            "text",
            "display-name-changed",
            "ws-chunk" -> signalAndRelay(sender, message)
        }
    }

    private fun joinIpRoom(peer: SignalingPeer) = joinRoom(peer, "ip", peer.ip)

    private fun onRoomSecrets(sender: SignalingPeer, message: JSONObject) {
        val secrets = message.optJSONArray("roomSecrets") ?: return
        for (index in 0 until secrets.length()) {
            val secret = secrets.optString(index)
            if (secret.length in 64..256 && secret.all { it.code in 0..127 }) {
                joinSecretRoom(sender, secret)
            }
        }
    }

    private fun onRoomSecretsDeleted(message: JSONObject) {
        val secrets = message.optJSONArray("roomSecrets") ?: return
        for (index in 0 until secrets.length()) {
            deleteSecretRoom(secrets.optString(index))
        }
    }

    private fun deleteSecretRoom(roomSecret: String) {
        val messages = mutableListOf<Pair<SignalingPeer, JSONObject>>()
        synchronized(lock) {
            val room = rooms[roomSecret] ?: return
            room.values.toList().forEach { peer ->
                leaveRoomLocked(peer, "secret", roomSecret, true)
                peer.roomSecrets.remove(roomSecret)
                messages += peer to JSONObject()
                    .put("type", "secret-room-deleted")
                    .put("roomSecret", roomSecret)
            }
        }
        messages.forEach { (peer, message) -> send(peer, message) }
    }

    private fun onPairDeviceInitiate(sender: SignalingPeer) {
        val roomSecret = randomString(256)
        val pairKey = createPairKey(sender, roomSecret)
        sender.pairKey?.let { removePairKey(it) }
        sender.pairKey = pairKey
        send(
            sender,
            JSONObject()
                .put("type", "pair-device-initiated")
                .put("roomSecret", roomSecret)
                .put("pairKey", pairKey)
        )
        joinSecretRoom(sender, roomSecret)
    }

    private fun onPairDeviceJoin(sender: SignalingPeer, message: JSONObject) {
        if (sender.rateLimitReached()) {
            send(sender, JSONObject().put("type", "join-key-rate-limit"))
            return
        }

        val pairKey = message.optString("pairKey")
        val entry = synchronized(lock) { roomSecrets[pairKey] }
        if (entry == null || entry.creator.id == sender.id) {
            send(sender, JSONObject().put("type", "pair-device-join-key-invalid"))
            return
        }

        removePairKey(pairKey)
        send(
            sender,
            JSONObject()
                .put("type", "pair-device-joined")
                .put("roomSecret", entry.roomSecret)
                .put("peerId", entry.creator.id)
        )
        send(
            entry.creator,
            JSONObject()
                .put("type", "pair-device-joined")
                .put("roomSecret", entry.roomSecret)
                .put("peerId", sender.id)
        )
        joinSecretRoom(sender, entry.roomSecret)
        sender.pairKey?.let { removePairKey(it) }
    }

    private fun onPairDeviceCancel(sender: SignalingPeer) {
        val pairKey = sender.pairKey ?: return
        removePairKey(pairKey)
        send(sender, JSONObject().put("type", "pair-device-canceled").put("pairKey", pairKey))
    }

    private fun onRegenerateRoomSecret(sender: SignalingPeer, message: JSONObject) {
        val oldSecret = message.optString("roomSecret")
        val newSecret = randomString(256)
        val peersToNotify = synchronized(lock) {
            rooms.remove(oldSecret)?.values.orEmpty().onEach { it.roomSecrets.remove(oldSecret) }.toList()
        }
        peersToNotify.forEach { peer ->
            send(
                peer,
                JSONObject()
                    .put("type", "room-secret-regenerated")
                    .put("oldRoomSecret", oldSecret)
                    .put("newRoomSecret", newSecret)
            )
        }
        joinSecretRoom(sender, newSecret)
    }

    private fun onCreatePublicRoom(sender: SignalingPeer) {
        val roomId = randomString(5, lettersOnly = true).lowercase()
        send(sender, JSONObject().put("type", "public-room-created").put("roomId", roomId))
        joinPublicRoom(sender, roomId)
    }

    private fun onJoinPublicRoom(sender: SignalingPeer, message: JSONObject) {
        if (sender.rateLimitReached()) {
            send(sender, JSONObject().put("type", "join-key-rate-limit"))
            return
        }

        val roomId = message.optString("publicRoomId")
        val createIfInvalid = message.optBoolean("createIfInvalid")
        synchronized(lock) {
            if (!rooms.containsKey(roomId) && !createIfInvalid) {
                send(
                    sender,
                    JSONObject().put("type", "public-room-id-invalid").put("publicRoomId", roomId)
                )
                return
            }
        }
        joinPublicRoom(sender, roomId)
    }

    private fun onLeavePublicRoom(sender: SignalingPeer) {
        synchronized(lock) {
            sender.publicRoomId?.let { leaveRoomLocked(sender, "public-id", it, true) }
            sender.publicRoomId = null
        }
        send(sender, JSONObject().put("type", "public-room-left"))
    }

    private fun joinSecretRoom(peer: SignalingPeer, roomSecret: String) {
        joinRoom(peer, "secret", roomSecret)
        peer.roomSecrets.add(roomSecret)
    }

    private fun joinPublicRoom(peer: SignalingPeer, roomId: String) {
        synchronized(lock) {
            peer.publicRoomId?.let { leaveRoomLocked(peer, "public-id", it, false) }
            peer.publicRoomId = null
        }
        joinRoom(peer, "public-id", roomId)
        peer.publicRoomId = roomId
    }

    private fun joinRoom(peer: SignalingPeer, roomType: String, roomId: String) {
        val messages = mutableListOf<Pair<SignalingPeer, JSONObject>>()
        synchronized(lock) {
            if (rooms[roomId]?.containsKey(peer.id) == true) {
                leaveRoomLocked(peer, roomType, roomId, false)
            }
            val room = rooms.getOrPut(roomId) { linkedMapOf() }

            room.values.filter { it.id != peer.id }.forEach { other ->
                messages += other to JSONObject()
                    .put("type", "peer-joined")
                    .put("peer", peer.infoJson())
                    .put("roomType", roomType)
                    .put("roomId", roomId)
            }

            val peersJson = JSONArray()
            room.values.filter { it.id != peer.id }.forEach { peersJson.put(it.infoJson()) }
            if (roomType == "ip") {
                remotePeers.values.forEach { peersJson.put(it.info) }
            }

            messages += peer to JSONObject()
                .put("type", "peers")
                .put("peers", peersJson)
                .put("roomType", roomType)
                .put("roomId", roomId)

            room[peer.id] = peer
        }
        messages.forEach { (recipient, message) -> send(recipient, message) }
    }

    private fun signalAndRelay(sender: SignalingPeer, originalMessage: JSONObject) {
        val message = JSONObject(originalMessage.toString())
        val to = message.optString("to")
        if (to.isBlank()) return
        val roomId = if (message.optString("roomType") == "ip") sender.ip else message.optString("roomId")

        val localRecipient: SignalingPeer?
        val remoteRecipient: RemotePeer?
        synchronized(lock) {
            localRecipient = rooms[roomId]?.get(to) ?: peers[to]
            remoteRecipient = remotePeers[to]
        }

        message.remove("to")
        message.put("sender", sender.senderJson())

        if (localRecipient != null) {
            send(localRecipient, message)
        } else if (remoteRecipient != null) {
            message.put("to", to)
            relayToRemote(remoteRecipient.endpoint, message)
        }
    }

    private fun leaveRoomLocked(
        peer: SignalingPeer,
        roomType: String,
        roomId: String,
        disconnect: Boolean
    ) {
        val room = rooms[roomId] ?: return
        if (room.remove(peer.id) == null) return
        if (room.isEmpty()) {
            rooms.remove(roomId)
            return
        }
        room.values.forEach { other ->
            send(
                other,
                JSONObject()
                    .put("type", "peer-left")
                    .put("peerId", peer.id)
                    .put("roomType", roomType)
                    .put("roomId", roomId)
                    .put("disconnect", disconnect)
            )
        }
    }

    private fun disconnectLocked(peer: SignalingPeer, disconnect: Boolean) {
        removePairKeyLocked(peer.pairKey)
        peer.pairKey = null
        keepAliveJobs.remove(peer.id)?.cancel()
        peers.remove(peer.id)
        leaveRoomLocked(peer, "ip", peer.ip, disconnect)
        peer.roomSecrets.toList().forEach { leaveRoomLocked(peer, "secret", it, disconnect) }
        peer.roomSecrets.clear()
        peer.publicRoomId?.let { leaveRoomLocked(peer, "public-id", it, disconnect) }
        peer.publicRoomId = null
        scope.launch { runCatching { peer.session.close(CloseReason(CloseReason.Codes.NORMAL, "disconnect")) } }
    }

    private fun localIpRoomPeersLocked(): List<SignalingPeer> {
        return rooms["127.0.0.1"]?.values?.toList().orEmpty()
    }

    private fun createPairKey(creator: SignalingPeer, roomSecret: String): String {
        synchronized(lock) {
            while (true) {
                val key = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
                if (!roomSecrets.containsKey(key)) {
                    roomSecrets[key] = PairKeyEntry(roomSecret, creator)
                    return key
                }
            }
        }
    }

    private fun removePairKey(pairKey: String?) {
        synchronized(lock) { removePairKeyLocked(pairKey) }
    }

    private fun removePairKeyLocked(pairKey: String?) {
        if (pairKey == null) return
        roomSecrets.remove(pairKey)?.creator?.pairKey = null
    }

    private fun startKeepAlive(peer: SignalingPeer) {
        keepAliveJobs[peer.id]?.cancel()
        keepAliveJobs[peer.id] = scope.launch {
            while (true) {
                delay(5_000)
                if (SystemClock.elapsedRealtime() - peer.lastBeat > 10_000) {
                    synchronized(lock) { disconnectLocked(peer, true) }
                    break
                }
                send(peer, JSONObject().put("type", "ping"))
            }
        }
    }

    private fun relayToRemote(endpoint: ServiceEndpoint, message: JSONObject) {
        scope.launch {
            runCatching {
                val connection = (URL("${endpoint.baseUrl}/native/relay").openConnection() as HttpURLConnection)
                connection.requestMethod = "POST"
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { output ->
                    output.write(message.toString().toByteArray(Charsets.UTF_8))
                }
                connection.inputStream.close()
                connection.disconnect()
            }
        }
    }

    private fun send(peer: SignalingPeer, message: JSONObject) {
        val text = message.toString()
        scope.launch {
            runCatching { peer.session.send(Frame.Text(text)) }
        }
    }

    private fun createPeer(
        session: DefaultWebSocketServerSession,
        query: Parameters,
        userAgent: String?
    ): SignalingPeer {
        val requestedPeerId = query["peer_id"]
        val requestedHash = query["peer_id_hash"]
        val peerId = if (
            requestedPeerId != null &&
            requestedHash != null &&
            isValidUuid(requestedPeerId) &&
            PeerHasher.isValid(requestedPeerId, requestedHash)
        ) {
            requestedPeerId
        } else {
            UUID.randomUUID().toString()
        }

        return SignalingPeer(
            id = peerId,
            session = session,
            ip = "127.0.0.1",
            rtcSupported = query["webrtc_supported"] == "true",
            name = createPeerName(peerId, userAgent)
        )
    }

    private fun createPeerName(peerId: String, userAgent: String?): PeerName {
        val browser = when {
            userAgent?.contains("Chrome", ignoreCase = true) == true -> "Chrome"
            userAgent?.contains("Firefox", ignoreCase = true) == true -> "Firefox"
            userAgent?.contains("Safari", ignoreCase = true) == true -> "Safari"
            else -> "WebView"
        }
        return PeerName(
            model = Build.MODEL,
            os = "Android ${Build.VERSION.RELEASE}",
            browser = browser,
            type = "mobile",
            deviceName = "Android ${Build.MODEL}",
            displayName = displayNameFor(peerId)
        )
    }

    private fun displayNameFor(seed: String): String {
        val colors = listOf("Blue", "Green", "Silver", "Golden", "Crimson", "Bright", "Quiet")
        val names = listOf("Falcon", "Maple", "Comet", "River", "Beacon", "Quartz", "Harbor")
        val hash = abs(seed.hashCode())
        return "${colors[hash % colors.size]} ${names[(hash / colors.size) % names.size]}"
    }

    private fun wsConfigJson(): JSONObject {
        return JSONObject()
            .put("type", "ws-config")
            .put(
                "wsConfig",
                JSONObject()
                    .put(
                        "rtcConfig",
                        JSONObject()
                            .put("sdpSemantics", "unified-plan")
                            .put(
                                "iceServers",
                                JSONArray().put(
                                    JSONObject().put("urls", "stun:stun.l.google.com:19302")
                                )
                            )
                    )
                    .put("wsFallback", true)
            )
    }

    private fun displayNameJson(peer: SignalingPeer): JSONObject {
        return JSONObject()
            .put("type", "display-name")
            .put("displayName", peer.name.displayName)
            .put("deviceName", peer.name.deviceName)
            .put("peerId", peer.id)
            .put("peerIdHash", PeerHasher.hash(peer.id))
    }

    private fun randomString(length: Int, lettersOnly: Boolean = false): String {
        val allowed = if (lettersOnly) {
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        } else {
            "-/0123456789@ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        }
        val random = SecureRandom()
        return buildString(length) {
            repeat(length) {
                append(allowed[random.nextInt(allowed.length)])
            }
        }
    }

    private fun isValidUuid(uuid: String): Boolean {
        return Regex("^([0-9a-f]){8}-(([0-9a-f]){4}-){3}([0-9a-f]){12}$").matches(uuid)
    }

    private data class PairKeyEntry(
        val roomSecret: String,
        val creator: SignalingPeer
    )

    private data class RemotePeer(
        val id: String,
        val info: JSONObject,
        val endpoint: ServiceEndpoint,
        val lastSeen: Long
    )
}

private data class SignalingPeer(
    val id: String,
    val session: DefaultWebSocketServerSession,
    val ip: String,
    val rtcSupported: Boolean,
    val name: PeerName,
    val roomSecrets: MutableSet<String> = linkedSetOf(),
    var pairKey: String? = null,
    var publicRoomId: String? = null,
    var lastBeat: Long = SystemClock.elapsedRealtime()
) {
    private val requestTimestamps = ArrayDeque<Long>()

    fun rateLimitReached(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (requestTimestamps.firstOrNull()?.let { now - it > 10_000 } == true) {
            requestTimestamps.removeFirst()
        }
        if (requestTimestamps.size >= 10) return true
        requestTimestamps.addLast(now)
        return false
    }

    fun infoJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name.toJson())
            .put("rtcSupported", rtcSupported)
    }

    fun senderJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("rtcSupported", rtcSupported)
    }
}

private data class PeerName(
    val model: String?,
    val os: String?,
    val browser: String?,
    val type: String?,
    val deviceName: String,
    val displayName: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("model", model ?: JSONObject.NULL)
            .put("os", os ?: JSONObject.NULL)
            .put("browser", browser ?: JSONObject.NULL)
            .put("type", type ?: JSONObject.NULL)
            .put("deviceName", deviceName)
            .put("displayName", displayName)
    }
}

private object PeerHasher {
    private val password = SecureRandom().generateSeed(64).joinToString("") { "%02x".format(it) }

    fun hash(peerId: String): String {
        return sha512(password + sha512(peerId))
    }

    fun isValid(peerId: String, expectedHash: String): Boolean = hash(peerId) == expectedHash

    private fun sha512(value: String): String {
        val digest = MessageDigest.getInstance("SHA-512").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
