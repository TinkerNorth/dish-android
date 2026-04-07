package com.tinkernorth.dish

import android.content.Context
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages the lifecycle of a server connection: discovery → pairing → connect → disconnect.
 *
 * Owns the connection state, ACK receiver job, heartbeat, and alive monitor.
 * All callbacks fire on the main thread.
 */
@Suppress("TooManyFunctions")
class ServerConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var connectedServer: DiscoveredServer? = null; private set
    var connectionId: String? = null; private set
    var isConnected = false; private set

    /** Called after connection established (all controllers should be added). */
    var onConnected: ((DiscoveredServer) -> Unit)? = null

    /** Called when the connection drops or is torn down. */
    var onDisconnected: (() -> Unit)? = null

    /** Called with the list of discovered servers after a scan. */
    var onServersDiscovered: ((List<DiscoveredServer>) -> Unit)? = null

    /** Called when scan starts. */
    var onScanStarted: (() -> Unit)? = null

    /** Called when scan finds nothing. */
    var onScanEmpty: (() -> Unit)? = null

    private val deviceId by lazy { getOrCreateDeviceId() }
    private val deviceName by lazy { android.os.Build.MODEL }

    private var ackReceiverJob: Job? = null
    private var aliveMonitorJob: Job? = null

    // ── Discovery ─────────────────────────────────────────────────────────

    fun startDiscovery() {
        if (isConnected) return
        onScanStarted?.invoke()
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                SatelliteNative.discoverServers(DISC_PORT, DISC_TIMEOUT_MS)
            }
            val servers = parseServers(json)
            if (servers.isEmpty()) {
                onScanEmpty?.invoke()
                Toast.makeText(context, "No servers found — check your network", Toast.LENGTH_SHORT).show()
            } else {
                onServersDiscovered?.invoke(servers)
            }
        }
    }

    // ── Server selection & pairing ────────────────────────────────────────

    fun selectServer(server: DiscoveredServer) {
        onScanStarted?.invoke() // show scanning state while pairing
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                SatelliteNative.pair(server.ip, server.pairPort, deviceId, deviceName, "")
            }
            if (result.contains("\"ok\":true")) {
                savePairKey(result)
                connectToServer(server)
            } else {
                showPinDialog(server)
            }
        }
    }

    @Suppress("LongMethod")
    private fun showPinDialog(server: DiscoveredServer) {
        val dp = context.resources.displayMetrics.density
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "PIN"
            textSize = 24f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.2f
            filters = arrayOf(InputFilter.LengthFilter(8))
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Pair with ${server.name}")
            .setMessage("Enter the PIN shown in the server web UI:")
            .setView(input)
            .setPositiveButton("PAIR", null)
            .setNegativeButton("CANCEL", null)
            .setCancelable(true)
            .create()

        dialog.setOnShowListener {
            input.requestFocus()
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString().trim()
                if (pin.isEmpty()) { input.error = "Enter the PIN shown on the server"; return@setOnClickListener }
                input.error = null; input.isEnabled = false
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        SatelliteNative.pair(server.ip, server.pairPort, deviceId, deviceName, pin)
                    }
                    if (result.contains("\"ok\":true")) {
                        savePairKey(result); dialog.dismiss(); connectToServer(server)
                    } else {
                        input.isEnabled = true
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        input.error = jsonGet(result, "error") ?: "Pairing failed"
                    }
                }
            }
        }
        dialog.show()
    }

    // ── Connect / Disconnect ──────────────────────────────────────────────

    @Suppress("LongMethod")
    private fun connectToServer(server: DiscoveredServer) {
        scope.launch {
            val sharedKeyHex = getStoredPairKey()
            if (sharedKeyHex.length != 64) {
                Toast.makeText(context, "No shared key — re-pair needed", Toast.LENGTH_SHORT).show()
                onDisconnected?.invoke(); return@launch
            }
            val key = hexToBytes(sharedKeyHex)
            val response = withContext(Dispatchers.IO) {
                SatelliteNative.httpConnect(server.ip, server.httpPort, deviceId)
            }
            val connId = jsonGet(response, "connectionId")
            val tokenHex = jsonGet(response, "token")
            if (connId == null || tokenHex == null) {
                val error = jsonGet(response, "error") ?: "connection failed"
                Toast.makeText(context, "Error: $error", Toast.LENGTH_SHORT).show()
                onDisconnected?.invoke(); return@launch
            }
            val token = hexToBytes(tokenHex)
            if (token.size != 4 || key.size != 32) { onDisconnected?.invoke(); return@launch }
            if (!SatelliteNative.openSocket(server.ip, server.udpPort)) {
                onDisconnected?.invoke(); return@launch
            }

            SatelliteNative.setConnectionParams(token, key)
            connectionId = connId; connectedServer = server; isConnected = true
            SatelliteNative.resetControllerAck()
            ackReceiverJob = scope.launch(Dispatchers.IO) {
                while (isActive) { SatelliteNative.receiveAck() }
            }
            SatelliteNative.startHeartbeat()
            onConnected?.invoke(server)
            aliveMonitorJob = scope.launch {
                while (isActive) {
                    delay(1000)
                    if (!SatelliteNative.isConnectionAlive()) { disconnect(); break }
                }
            }
        }
    }

    fun disconnect() {
        aliveMonitorJob?.cancel(); aliveMonitorJob = null
        ackReceiverJob?.cancel(); ackReceiverJob = null
        SatelliteNative.stopHeartbeat()
        SatelliteNative.closeSocket()
        val server = connectedServer; val connId = connectionId
        if (server != null && connId != null) {
            scope.launch(Dispatchers.IO) {
                SatelliteNative.httpDisconnect(server.ip, server.httpPort, connId, deviceId)
            }
        }
        connectionId = null; connectedServer = null; isConnected = false
        onDisconnected?.invoke()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun getOrCreateDeviceId(): String {
        val p = context.getSharedPreferences("satellite", Context.MODE_PRIVATE)
        return p.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString()
            .replace("-", "").also { p.edit().putString("deviceId", it).apply() }
    }

    private fun savePairKey(pairResult: String) {
        val key = jsonGet(pairResult, "sharedKey") ?: return
        context.getSharedPreferences("satellite", Context.MODE_PRIVATE)
            .edit().putString("sharedKey", key).apply()
    }

    private fun getStoredPairKey(): String =
        context.getSharedPreferences("satellite", Context.MODE_PRIVATE)
            .getString("sharedKey", "") ?: ""

    companion object {
        private const val DISC_PORT = 9879
        private const val DISC_TIMEOUT_MS = 4000
    }
}

// ── Pure parsing helpers (top-level, easily testable) ────────────────────

fun jsonGet(json: String, key: String): String? {
    val needle = "\"$key\":"
    val idx = json.indexOf(needle)
    if (idx < 0) return null
    var pos = idx + needle.length
    while (pos < json.length && json[pos] == ' ') pos++
    if (pos >= json.length) return null
    return if (json[pos] == '"') {
        val end = json.indexOf('"', pos + 1)
        if (end < 0) null else json.substring(pos + 1, end)
    } else {
        val end = json.substring(pos).indexOfFirst { it == ',' || it == '}' }
        if (end < 0) json.substring(pos) else json.substring(pos, pos + end)
    }
}

fun hexToBytes(hex: String): ByteArray {
    val data = ByteArray(hex.length / 2)
    var i = 0
    while (i < hex.length) {
        data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        i += 2
    }
    return data
}

fun parseServers(json: String): List<DiscoveredServer> {
    val stripped = json.trim().removePrefix("[").removeSuffix("]").trim()
    if (stripped.isEmpty()) return emptyList()
    val result = mutableListOf<DiscoveredServer>()
    var depth = 0; var start = 0
    for (i in stripped.indices) {
        when (stripped[i]) {
            '{' -> { if (depth == 0) start = i; depth++ }
            '}' -> {
                depth--
                if (depth == 0) {
                    val obj = stripped.substring(start, i + 1)
                    val name = jsonGet(obj, "name") ?: continue
                    val ip = jsonGet(obj, "ip") ?: continue
                    val udpPort = jsonGet(obj, "udpPort")?.toIntOrNull() ?: 9876
                    val pairPort = jsonGet(obj, "pairPort")?.toIntOrNull() ?: 9878
                    val httpPort = jsonGet(obj, "httpPort")?.toIntOrNull() ?: 9877
                    result.add(DiscoveredServer(name, ip, udpPort, pairPort, httpPort))
                }
            }
        }
    }
    return result
}
