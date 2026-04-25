// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.data.repository.ControllerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A single live or potential WiFi session to one Satellite server.
 *
 * The [id] is stable across app restarts (derived from server IP+port) so the
 * hub can persist a list of remembered connections and reclaim the same slot
 * binding after a relaunch. [handle] is the native session handle returned by
 * [ControllerRepository.openSocket] and is only valid while [state] is
 * [WifiState.CONNECTED]; once the session is torn down it is reset to -1 and
 * a fresh handle is allocated on reconnect.
 */
class WifiConnection(
    val id: String,
    server: DiscoveredServer,
    private val scope: CoroutineScope,
    private val controllerRepo: ControllerRepository,
) {
    private val _server = MutableStateFlow(server)
    val server: StateFlow<DiscoveredServer> = _server.asStateFlow()

    private val _state = MutableStateFlow(WifiState.IDLE)
    val state: StateFlow<WifiState> = _state.asStateFlow()

    /**
     * Immutable tuple of the live-session handle + connection id. Held in a
     * single `@Volatile` reference so [sendReport] (report thread) always
     * sees the pair atomically — readers can never observe a fresh handle
     * with a stale connection id (or vice versa) mid-reconnect.
     */
    private data class Live(
        val handle: Int,
        val connectionId: String,
    )

    @Volatile private var live: Live? = null

    /** Server-issued connection id for the live session, or null when idle. */
    val connectionId: String? get() = live?.connectionId

    /** Native socket handle for the live session, or -1 when idle. */
    val handle: Int get() = live?.handle ?: -1

    /** Which slot id (if any) currently routes its input to this connection. */
    private val _boundSlotId = MutableStateFlow<String?>(null)
    val boundSlotId: StateFlow<String?> = _boundSlotId.asStateFlow()

    private var ackJob: Job? = null
    private var aliveJob: Job? = null
    private var controllerAdded = false
    private var pendingControllerType: Int = DEFAULT_CONTROLLER_TYPE

    fun updateServer(server: DiscoveredServer) {
        _server.value = server
    }

    /**
     * Advance to CONNECTING from IDLE or an already-in-flight CONNECTING.
     * Rejected from CONNECTED so a caller can't accidentally wipe a live
     * session's state back to CONNECTING without an intervening disconnect.
     */
    internal fun markConnecting() {
        if (_state.value == WifiState.CONNECTED) return
        _state.value = WifiState.CONNECTING
    }

    /**
     * Finalize a successful connect: stash the native handle + server-issued
     * connectionId, start the ACK receive loop and heartbeat. Must be called
     * after [ControllerRepository.openSocket] and [setConnectionParams] succeed.
     *
     * Strict guard: only valid from CONNECTING. A second [markConnected] while
     * already CONNECTED would leak the previous native handle, so we reject
     * and let the caller run [markDisconnected] first if it really meant to
     * rebind. Calls from IDLE are likewise rejected — the pair/auth path must
     * go through [markConnecting].
     *
     * If a slot was bound before the session was online (common right after an
     * app relaunch/auto-reconnect) the server-side `addController` handshake
     * is run now so subsequent [sendReport] packets are accepted.
     */
    internal fun markConnected(
        handle: Int,
        connectionId: String,
        onDead: () -> Unit,
    ) {
        if (_state.value != WifiState.CONNECTING) return
        // Publish the (handle, connectionId) pair before flipping state so
        // any concurrent sendReport that observes CONNECTED cannot see a
        // null/-1 tuple.
        live = Live(handle, connectionId)
        _state.value = WifiState.CONNECTED
        controllerRepo.resetControllerAck(handle)
        ackJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    controllerRepo.receiveAck(handle)
                }
            }
        controllerRepo.startHeartbeat(handle)
        aliveJob =
            scope.launch {
                while (isActive) {
                    delay(ALIVE_POLL_MS)
                    if (!controllerRepo.isConnectionAlive(handle)) {
                        onDead()
                        break
                    }
                }
            }
        if (_boundSlotId.value != null && !controllerAdded) {
            scope.launch { registerController(pendingControllerType) }
        }
    }

    /**
     * Tear the session down. Idempotent: a second call from IDLE is a no-op
     * so callers (alive-poll, manager.disconnect, forget, foreground kicks)
     * can invoke it freely without needing to check state first.
     */
    internal fun markDisconnected() {
        val snap = live
        if (_state.value == WifiState.IDLE && snap == null) return
        aliveJob?.cancel()
        aliveJob = null
        ackJob?.cancel()
        ackJob = null
        // Null the tuple before native teardown so any concurrent sendReport
        // sees a null snapshot and bails, rather than racing against a
        // half-closed handle.
        live = null
        if (snap != null) {
            controllerRepo.stopHeartbeat(snap.handle)
            controllerRepo.closeSocket(snap.handle)
        }
        controllerAdded = false
        _state.value = WifiState.IDLE
    }

    /**
     * Record this slot as the owner of this connection. If the native session
     * is already live the server is notified immediately; otherwise the
     * registration is deferred to [markConnected].
     */
    suspend fun attachSlot(
        slotId: String,
        controllerType: Int,
    ) = withContext(Dispatchers.IO) {
        _boundSlotId.value = slotId
        pendingControllerType = controllerType
        if (_state.value == WifiState.CONNECTED && handle >= 0 && !controllerAdded) {
            registerController(controllerType)
        }
    }

    private suspend fun registerController(controllerType: Int) =
        withContext(Dispatchers.IO) {
            if (handle < 0) return@withContext
            controllerRepo.resetControllerAck(handle)
            controllerRepo.addController(handle, DEFAULT_CTRL_INDEX, DEFAULT_CAPABILITIES)
            var ack = -1
            repeat(ACK_WAIT_ATTEMPTS) {
                ack = controllerRepo.getLastControllerAck(handle)
                if (ack != -1) return@repeat
                delay(ACK_WAIT_INTERVAL_MS)
            }
            if (ack != -1) {
                controllerRepo.sendControllerType(handle, DEFAULT_CTRL_INDEX, controllerType)
                controllerAdded = true
            }
        }

    fun detachSlot() {
        if (_boundSlotId.value == null) return
        _boundSlotId.value = null
        if (handle >= 0 && controllerAdded) {
            controllerRepo.removeController(handle, DEFAULT_CTRL_INDEX)
        }
        controllerAdded = false
    }

    fun sendReport(
        buttons: Int,
        lt: Int,
        rt: Int,
        lx: Int,
        ly: Int,
        rx: Int,
        ry: Int,
    ) {
        val snap = live ?: return
        controllerRepo.sendReport(snap.handle, DEFAULT_CTRL_INDEX, buttons, lt, rt, lx, ly, rx, ry)
    }

    companion object {
        private const val ALIVE_POLL_MS = 1000L
        private const val ACK_WAIT_ATTEMPTS = 20
        private const val ACK_WAIT_INTERVAL_MS = 100L
        private const val DEFAULT_CTRL_INDEX = 0
        private const val DEFAULT_CAPABILITIES = 0x0003
        private const val DEFAULT_CONTROLLER_TYPE = 0

        fun idFor(server: DiscoveredServer): String = "wifi:${server.ip}:${server.udpPort}"
    }
}

enum class WifiState { IDLE, CONNECTING, CONNECTED }
