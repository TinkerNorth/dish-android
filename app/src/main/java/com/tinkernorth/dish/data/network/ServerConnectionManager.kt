package com.tinkernorth.dish.data.network

import android.content.Context
import com.tinkernorth.dish.data.model.ConnectResponse
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.data.model.PairResponse
import com.tinkernorth.dish.data.repository.ControllerRepository
import com.tinkernorth.dish.data.repository.DiscoveryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

sealed class ConnectionEvent {
    data class PairingRequired(val server: DiscoveredServer) : ConnectionEvent()
    data class Error(val message: String) : ConnectionEvent()
}

/**
 * Manages the lifecycle of a server connection: discovery → pairing → connect → disconnect.
 */
@Singleton
class ServerConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope,
    private val discoveryRepo: DiscoveryRepository,
    private val controllerRepo: ControllerRepository,
    private val json: Json
) {
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectedServer = MutableStateFlow<DiscoveredServer?>(null)
    val connectedServer: StateFlow<DiscoveredServer?> = _connectedServer.asStateFlow()

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _events = MutableSharedFlow<ConnectionEvent>()
    val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()

    var connectionId: String? = null ; private set

    private val deviceId by lazy { getOrCreateDeviceId() }
    private val deviceName by lazy { android.os.Build.MODEL }

    private var ackReceiverJob: Job? = null
    private var aliveMonitorJob: Job? = null

    // ── Discovery ─────────────────────────────────────────────────────────

    fun startDiscovery() {
        if (_isConnected.value) return
        _isScanning.value = true
        scope.launch {
            val servers = discoveryRepo.discoverServers(DISC_PORT, DISC_TIMEOUT_MS)
            _discoveredServers.value = servers
            _isScanning.value = false
            if (servers.isEmpty()) {
                _events.emit(ConnectionEvent.Error("No servers found — check your network"))
            }
        }
    }

    // ── Server selection & pairing ────────────────────────────────────────

    fun selectServer(server: DiscoveredServer) {
        _isScanning.value = true
        scope.launch {
            val result = discoveryRepo.pair(server.ip, server.pairPort, deviceId, deviceName, "")
            val pairResponse = try {
                json.decodeFromString<PairResponse>(result)
            } catch (e: Exception) {
                PairResponse(error = "Malformed pair response")
            }

            if (pairResponse.ok && pairResponse.sharedKey != null) {
                savePairKey(pairResponse.sharedKey)
                connectToServer(server)
            } else {
                _isScanning.value = false
                _events.emit(ConnectionEvent.PairingRequired(server))
            }
        }
    }

    fun pairWithPin(server: DiscoveredServer, pin: String) {
        scope.launch {
            val result = discoveryRepo.pair(server.ip, server.pairPort, deviceId, deviceName, pin)
            val pairResponse = try {
                json.decodeFromString<PairResponse>(result)
            } catch (e: Exception) {
                PairResponse(error = "Malformed pair response")
            }

            if (pairResponse.ok && pairResponse.sharedKey != null) {
                savePairKey(pairResponse.sharedKey)
                connectToServer(server)
            } else {
                _events.emit(ConnectionEvent.Error(pairResponse.error ?: "Pairing failed"))
            }
        }
    }

    // ── Connect / Disconnect ──────────────────────────────────────────────

    private fun connectToServer(server: DiscoveredServer) {
        scope.launch {
            val sharedKeyHex = getStoredPairKey()
            if (sharedKeyHex.length != 64) {
                _events.emit(ConnectionEvent.Error("No shared key — re-pair needed"))
                handleDisconnect()
                return@launch
            }
            val key = hexToBytes(sharedKeyHex)
            val response = discoveryRepo.connect(server.ip, server.httpPort, deviceId)

            val connResponse = try {
                json.decodeFromString<ConnectResponse>(response)
            } catch (e: Exception) {
                ConnectResponse(error = "Malformed connect response")
            }

            val connId = connResponse.connectionId
            val tokenHex = connResponse.token

            if (connId == null || tokenHex == null) {
                _events.emit(ConnectionEvent.Error("Error: ${connResponse.error ?: "connection failed"}"))
                handleDisconnect()
                return@launch
            }
            val token = hexToBytes(tokenHex)
            if (token.size != 4 || key.size != 32) {
                handleDisconnect()
                return@launch
            }
            if (!controllerRepo.openSocket(server.ip, server.udpPort)) {
                handleDisconnect()
                return@launch
            }

            controllerRepo.setConnectionParams(token, key)
            connectionId = connId
            _connectedServer.value = server
            _isConnected.value = true
            _isScanning.value = false

            controllerRepo.resetControllerAck()
            ackReceiverJob = scope.launch(Dispatchers.IO) {
                while (isActive) { controllerRepo.receiveAck() }
            }
            controllerRepo.startHeartbeat()

            aliveMonitorJob = scope.launch {
                while (isActive) {
                    delay(1000)
                    if (!controllerRepo.isConnectionAlive()) { disconnect(); break }
                }
            }
        }
    }

    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            val server = _connectedServer.value
            val connId = connectionId
            if (server != null && connId != null) {
                discoveryRepo.disconnect(server.ip, server.httpPort, connId, deviceId)
            }
            withContext(Dispatchers.Main) {
                handleDisconnect()
            }
        }
    }

    private fun handleDisconnect() {
        aliveMonitorJob?.cancel(); aliveMonitorJob = null
        ackReceiverJob?.cancel(); ackReceiverJob = null
        controllerRepo.stopHeartbeat()
        controllerRepo.closeSocket()
        connectionId = null
        _connectedServer.value = null
        _isConnected.value = false
        _isScanning.value = false
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun getOrCreateDeviceId(): String {
        val p = context.getSharedPreferences("satellite", Context.MODE_PRIVATE)
        return p.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString()
            .replace("-", "").also { p.edit().putString("deviceId", it).apply() }
    }

    private fun savePairKey(key: String) {
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
