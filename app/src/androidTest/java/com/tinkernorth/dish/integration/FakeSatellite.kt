// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.integration

import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.net.SessionCrypto
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * In-process satellite implementing the protocol-1 contract surface the
 * client exercises (satellite docs/contract.md): PIN pairing, hmacProof
 * validation, the declarative session PUT/GET, self-unpair, catalog and
 * capabilities probes, and the encrypted UDP data plane with heartbeat
 * acks. Each instance mints its own self-signed cert at runtime (no
 * committed keys), so two instances present different certs: pin one,
 * then connect the other on the same identity to exercise TOFU rejection.
 */
class FakeSatellite(
    private val operatorPin: String = "1234",
) : Closeable {
    private val instanceId = INSTANCES.incrementAndGet()
    private val https: SSLServerSocket
    private val udp = DatagramSocket(0)
    private val threads = mutableListOf<Thread>()

    @Volatile private var running = true

    @Volatile var pairingKeyHex: String? = null

    @Volatile var force401Code: String? = null

    @Volatile var ackEpochOverride: Int? = null

    @Volatile private var epoch = 1

    @Volatile private var tokenCounter = 7

    @Volatile private var lastTokenHex: String? = null

    @Volatile private var sessionKey: ByteArray? = null

    @Volatile private var downCounter = 0

    @Volatile private var lastClientAddress: SocketAddress? = null

    @Volatile private var lastControllers = JSONArray()

    val sessionPuts = CopyOnWriteArrayList<JSONObject>()
    val reconcileGets = CopyOnWriteArrayList<String>()
    val unpairCalls = CopyOnWriteArrayList<String>()
    val udpOpcodes = CopyOnWriteArrayList<Int>()

    @Volatile var heartbeatCount = 0
        private set

    @Volatile var catalogRequests = 0
        private set

    @Volatile var lastClientPin: String? = null
        private set

    @Volatile private var stagedApprovalKey: String? = null

    /** Path B: operator approves the pending client PIN; the poll then hands the key back once. */
    fun approveClientPin() {
        stagedApprovalKey = randomHex(32)
    }

    val httpsPort: Int get() = https.localPort
    val udpPort: Int get() = udp.localPort

    init {
        // Fresh self-signed cert per instance: no keys committed to the repo,
        // and two instances differ, which is exactly what the TOFU test needs.
        val held =
            HeldCertificate
                .Builder()
                .addSubjectAlternativeName("127.0.0.1")
                .build()
        val keyStore =
            KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("fake", held.keyPair.private, CharArray(0), arrayOf(held.certificate))
            }
        val kmf =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, CharArray(0))
            }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
        https = ssl.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        thread("fake-sat-https") { acceptLoop() }
        thread("fake-sat-udp") { udpLoop() }
    }

    fun server(
        name: String = "Fake Satellite",
        machineId: String = "fake-sat-$instanceId",
    ): DiscoveredServer =
        DiscoveredServer(
            name = name,
            ip = "127.0.0.1",
            udpPort = udpPort,
            pairPort = httpsPort,
            httpPort = httpsPort,
            machineId = machineId,
        )

    fun awaitUdpOpcode(
        opcode: Int,
        timeoutMs: Long = 8_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (udpOpcodes.contains(opcode)) return true
            Thread.sleep(100)
        }
        return false
    }

    fun sendSessionClose(reason: Int) {
        sendDown(0x000F, byteArrayOf(reason.toByte()))
    }

    override fun close() {
        running = false
        runCatching { https.close() }
        runCatching { udp.close() }
        threads.forEach { runCatching { it.join(2_000) } }
    }

    private fun thread(
        name: String,
        block: () -> Unit,
    ) {
        threads +=
            Thread(block, name).apply {
                isDaemon = true
                start()
            }
    }

    private fun acceptLoop() {
        while (running) {
            val socket =
                try {
                    https.accept() as SSLSocket
                } catch (_: Exception) {
                    return
                }
            thread("fake-sat-conn") { runCatching { handle(socket) }.also { socket.close() } }
        }
    }

    private fun handle(socket: SSLSocket) {
        // HTTP/1.1 keep-alive: the production client pools the TLS socket and
        // reuses it across requests, so serve every request on the connection
        // until the client closes it or goes idle. Closing after one (the old
        // behaviour) left the client reusing a dead socket, forcing a
        // transparent retry per call whose latency accumulated across the run.
        socket.soTimeout = 3_000
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        val out = socket.outputStream
        while (running) {
            val requestLine = reader.readLine() ?: return
            if (requestLine.isEmpty()) continue
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = CharArray(length).also { if (length > 0) reader.read(it, 0, length) }.concatToString()
            val parts = requestLine.split(' ')
            val plainPath = parts.getOrElse(1) { "/" }.substringBefore('?')
            val resp = route(parts[0], parts.getOrElse(1) { "/" }, headers, body)
            val payload = resp.body.toByteArray(Charsets.UTF_8)
            val etagLine = if (plainPath == "/api/catalog") "ETag: $CATALOG_ETAG\r\n" else ""
            out.write(
                (
                    "HTTP/1.1 ${resp.status}\r\n" +
                        "Content-Type: application/json\r\n" +
                        etagLine +
                        "Content-Length: ${payload.size}\r\n\r\n"
                ).toByteArray(Charsets.UTF_8),
            )
            out.write(payload)
            out.flush()
        }
    }

    private data class Resp(
        val status: String,
        val body: String,
    )

    private fun route(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
    ): Resp {
        val plainPath = path.substringBefore('?')
        val (status, responseBody) =
            routePair(method, plainPath, headers, body, path)
        return Resp(status, responseBody)
    }

    private fun routePair(
        method: String,
        plainPath: String,
        headers: Map<String, String>,
        body: String,
        rawPath: String,
    ): Pair<String, String> =
        when {
            method == "GET" && plainPath == "/api/catalog" -> catalog(headers)
            method == "POST" && plainPath == "/api/pair" -> pair(body)
            method == "DELETE" && plainPath == "/api/pair" -> unpair(headers)
            method == "PUT" && plainPath == "/api/connections" -> putSession(headers, body)
            method == "GET" && plainPath.startsWith("/api/connections/") ->
                reconcile(headers, plainPath.removePrefix("/api/connections/"))
            method == "DELETE" && plainPath.startsWith("/api/connections/") -> "200 OK" to """{"ok":true}"""
            method == "PUT" && plainPath.contains("/controllers/") -> putController(headers, body)
            method == "GET" && plainPath == "/api/server/capabilities" -> "200 OK" to CAPABILITIES_JSON
            method == "GET" && rawPath.startsWith("/api/pair/status") -> pairStatus()
            else -> "404 Not Found" to """{"error":"unknown route"}"""
        }

    private fun catalog(headers: Map<String, String>): Pair<String, String> {
        catalogRequests += 1
        // Contract §Caching: If-None-Match hit -> 304, the client keeps its cache.
        if (headers["if-none-match"] == CATALOG_ETAG) return "304 Not Modified" to ""
        return "200 OK" to CATALOG_JSON
    }

    private fun pairStatus(): Pair<String, String> {
        // Path B poll: pending until approveClientPin() stages the key, then it
        // is handed back exactly once (single-use, per contract).
        val key = stagedApprovalKey
        return if (key != null) {
            stagedApprovalKey = null
            pairingKeyHex = key
            "200 OK" to """{"ok":true,"status":"approved","sharedKey":"$key"}"""
        } else {
            "200 OK" to """{"ok":false,"status":"pending"}"""
        }
    }

    private fun pair(body: String): Pair<String, String> {
        val json = JSONObject(body)
        val pin = json.optString("pin")
        val clientPin = json.optString("clientPin")
        return when {
            pin.isNotEmpty() && pin == operatorPin -> {
                pairingKeyHex = randomHex(32)
                "200 OK" to """{"ok":true,"message":"paired","sharedKey":"$pairingKeyHex","protocolVersion":1}"""
            }
            pin.isNotEmpty() -> "200 OK" to """{"ok":false,"error":"invalid pin"}"""
            clientPin.isNotEmpty() -> {
                lastClientPin = clientPin
                "200 OK" to """{"ok":false,"pending":true,"message":"awaiting approval"}"""
            }
            else -> "200 OK" to """{"ok":false,"error":"pairing required"}"""
        }
    }

    private fun authorized(headers: Map<String, String>): Boolean {
        val key = pairingKeyHex ?: return false
        val deviceId = headers["x-device-id"] ?: return false
        val proof = headers["x-hmac-proof"] ?: return false
        return proof == SessionCrypto.hmacProof(hexToBytes(key), deviceId)
    }

    private fun unpair(headers: Map<String, String>): Pair<String, String> {
        if (!authorized(headers)) return UNAUTHORIZED
        unpairCalls += headers["x-device-id"].orEmpty()
        pairingKeyHex = null
        return "200 OK" to """{"ok":true}"""
    }

    private fun putSession(
        headers: Map<String, String>,
        body: String,
    ): Pair<String, String> {
        force401Code?.let { return "401 Unauthorized" to """{"error":"unauthorized","code":"$it"}""" }
        if (!authorized(headers)) return UNAUTHORIZED
        val json = JSONObject(body)
        sessionPuts += json
        lastControllers = json.optJSONArray("controllers") ?: JSONArray()
        tokenCounter += 1
        val tokenHex = "%08x".format(tokenCounter)
        val saltHex = randomHex(8)
        lastTokenHex = tokenHex
        downCounter = 0
        sessionKey =
            SessionCrypto.deriveSessionKey(
                hexToBytes(pairingKeyHex!!),
                hexToBytes(saltHex),
                hexToBytes(tokenHex),
            )
        val applied = JSONArray()
        for (i in 0 until lastControllers.length()) {
            val ctrl = lastControllers.getJSONObject(i)
            applied.put(
                JSONObject()
                    .put("ctrlIdx", ctrl.optInt("ctrlIdx"))
                    .put("result", "ok")
                    .put("appliedType", ctrl.optInt("type"))
                    .put("motion", JSONObject().put("sinkSupportedForType", true).put("backendOk", true)),
            )
        }
        val wantsMouse = json.optJSONObject("hostFeatures")?.optBoolean("mouseControl") ?: false
        val response =
            JSONObject()
                .put("connectionId", "conn_fake01")
                .put("token", tokenHex)
                .put("sessionSalt", saltHex)
                .put("epoch", epoch)
                .put("maxControllers", 16)
                .put("protocolVersion", 1)
                .put("controllers", applied)
                .put("hostFeatures", JSONObject().put("mouseControl", JSONObject().put("granted", wantsMouse)))
        return "200 OK" to response.toString()
    }

    private fun reconcile(
        headers: Map<String, String>,
        connectionId: String,
    ): Pair<String, String> {
        if (!authorized(headers)) return UNAUTHORIZED
        reconcileGets += connectionId
        val controllers = JSONArray()
        for (i in 0 until lastControllers.length()) {
            val ctrl = lastControllers.getJSONObject(i)
            controllers.put(
                JSONObject()
                    .put("ctrlIdx", ctrl.optInt("ctrlIdx"))
                    .put("active", true)
                    .put("appliedType", ctrl.optInt("type"))
                    .put("touchpadMode", ctrl.optString("touchpadMode", "off")),
            )
        }
        val view =
            JSONObject()
                .put("connectionId", connectionId)
                .put("epoch", ackEpochOverride ?: epoch)
                .put("controllers", controllers)
                .put("hostFeatures", JSONObject().put("mouseControl", JSONObject().put("granted", false)))
        return "200 OK" to view.toString()
    }

    private fun putController(
        headers: Map<String, String>,
        body: String,
    ): Pair<String, String> {
        if (!authorized(headers)) return UNAUTHORIZED
        val ctrl = JSONObject(body)
        val response =
            JSONObject()
                .put("epoch", epoch)
                .put(
                    "controller",
                    JSONObject()
                        .put("ctrlIdx", ctrl.optInt("ctrlIdx"))
                        .put("result", "ok")
                        .put("appliedType", ctrl.optInt("type"))
                        .put("motion", JSONObject().put("sinkSupportedForType", true).put("backendOk", true)),
                )
        return "200 OK" to response.toString()
    }

    private fun udpLoop() {
        val buffer = ByteArray(2048)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                udp.receive(packet)
            } catch (_: SocketException) {
                return
            }
            runCatching { onDatagram(packet) }
        }
    }

    private fun onDatagram(packet: DatagramPacket) {
        val key = sessionKey ?: return
        val data = packet.data.copyOf(packet.length)
        if (data.size < 9) return
        val token = data.copyOfRange(0, 4)
        if (bytesToHex(token) != lastTokenHex) return
        val counter = data.copyOfRange(4, 8)
        val nonce = ByteArray(12).also { counter.copyInto(it, 8) }
        val cipher = chaCha()
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(token)
        val inner = cipher.doFinal(data, 8, data.size - 8)
        if (inner.size < 4) return
        val opcode = ((inner[0].toInt() and 0xFF) shl 8) or (inner[1].toInt() and 0xFF)
        udpOpcodes += opcode
        lastClientAddress = packet.socketAddress
        if (opcode == OP_HEARTBEAT) {
            heartbeatCount += 1
            sendHeartbeatAck()
        }
    }

    private fun sendHeartbeatAck() {
        val ackEpoch = ackEpochOverride ?: epoch
        val active = lastControllers.length()
        var bitmap = 0
        for (i in 0 until lastControllers.length()) {
            bitmap = bitmap or (1 shl lastControllers.getJSONObject(i).optInt("ctrlIdx"))
        }
        val payload =
            ByteBuffer
                .allocate(6)
                .put(1)
                .put(active.toByte())
                .putShort(ackEpoch.toShort())
                .putShort(bitmap.toShort())
                .array()
        sendDown(OP_HEARTBEAT_ACK, payload)
    }

    private fun sendDown(
        opcode: Int,
        payload: ByteArray,
    ) {
        val key = sessionKey ?: return
        val tokenHex = lastTokenHex ?: return
        val address = lastClientAddress ?: return
        downCounter += 1
        val token = hexToBytes(tokenHex)
        val inner =
            ByteBuffer
                .allocate(4 + payload.size)
                .putShort(opcode.toShort())
                .putShort(payload.size.toShort())
                .put(payload)
                .array()
        val nonce =
            ByteBuffer
                .allocate(12)
                .put(0x01)
                .put(ByteArray(7))
                .putInt(downCounter)
                .array()
        val cipher = chaCha()
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(token)
        val sealed = cipher.doFinal(inner)
        val datagram =
            ByteBuffer
                .allocate(8 + sealed.size)
                .put(token)
                .putInt(downCounter)
                .put(sealed)
                .array()
        udp.send(DatagramPacket(datagram, datagram.size, address))
    }

    private fun chaCha(): Cipher =
        runCatching { Cipher.getInstance("ChaCha20/Poly1305/NoPadding") }
            .getOrElse { Cipher.getInstance("ChaCha20-Poly1305") }

    private companion object {
        val INSTANCES = AtomicInteger(0)

        const val OP_HEARTBEAT = 0x0002
        const val OP_HEARTBEAT_ACK = 0x0003

        const val CATALOG_ETAG = "\"1.7.0+en\""

        val UNAUTHORIZED = "401 Unauthorized" to """{"error":"unauthorized","code":"BAD_PROOF"}"""

        val CAPABILITIES_JSON =
            """
            {"protocolVersion":1,"serverVersion":"1.6.0","maxControllers":16,
             "backend":{"id":"fake","supported":true,"available":true,"errorCode":null},
             "motion":{"available":true},
             "host":{"catalog":{"supported":true},
                     "mouseControl":{"supported":true,"available":true},
                     "keyboardControl":{"supported":false},
                     "rumble":{"supported":true,"available":true}}}
            """.trimIndent()

        val CATALOG_JSON =
            """
            {"locale":"en","protocolVersion":1,"serverVersion":"1.6.0","catalogVersion":2,
             "controllerTypes":[
               {"id":0,"slug":"xbox360","name":"Xbox 360 Controller","shortName":"Xbox",
                "description":"Best compatibility.",
                "image":{"href":"/api/catalog/images/xbox360","etag":"\"1.6.0\""},
                "features":{"rumble":{"supported":true},"analogTriggers":{"supported":true},
                            "motion":{"supported":false},"lightbar":{"supported":false},
                            "touchpad":{"supported":false}}},
               {"id":1,"slug":"ds4","name":"DualShock 4","shortName":"PlayStation",
                "description":"Motion, touchpad and light bar.",
                "image":{"href":"/api/catalog/images/ds4","etag":"\"1.6.0\""},
                "features":{"rumble":{"supported":true},"analogTriggers":{"supported":true},
                            "motion":{"supported":true},"lightbar":{"supported":true},
                            "touchpad":{"supported":true,"modes":["ds4"]}}},
               {"id":2,"slug":"dualsense","name":"DualSense Wireless Controller","shortName":"DualSense",
                "description":"Motion, touchpad, light bar and haptics.",
                "image":{"href":"/api/catalog/images/dualsense","etag":"\"1.7.0\""},
                "features":{"rumble":{"supported":true},"analogTriggers":{"supported":true},
                            "motion":{"supported":true},"lightbar":{"supported":true},
                            "touchpad":{"supported":true,"modes":["ds4"]}}},
               {"id":3,"slug":"switchpro","name":"Switch Pro Controller","shortName":"Switch Pro",
                "description":"Motion and rumble.",
                "image":{"href":"/api/catalog/images/switchpro","etag":"\"1.7.0\""},
                "features":{"rumble":{"supported":true},"analogTriggers":{"supported":false},
                            "motion":{"supported":true},"lightbar":{"supported":false},
                            "touchpad":{"supported":false}}}],
             "hostFeatures":{"mouseControl":{"supported":true,"modes":["off","ds4","mouse"]},
                             "keyboardControl":{"supported":false},
                             "rumble":{"supported":true}}}
            """.trimIndent()

        fun randomHex(bytes: Int): String {
            val raw = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            return bytesToHex(raw)
        }

        fun bytesToHex(raw: ByteArray): String = raw.joinToString("") { "%02x".format(it) }

        fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
