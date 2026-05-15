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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A single live or potential session to one Satellite server (the host PC the
 * device routes input to over LAN).
 *
 * Multi-slot: a satellite session can host more than one controller. Each
 * attached slot is allocated a server-visible [SlotBinding.controllerIndex]
 * (0..N) so per-slot reports, type changes and removals can be addressed
 * independently. Bluetooth's single-host constraint is enforced one layer up
 * in [ConnectionHub.bind].
 *
 * The [id] is stable across app restarts (derived from server IP+port) so the
 * hub can persist a list of remembered connections and reclaim slot bindings
 * after a relaunch. [handle] is the native session handle returned by
 * [ControllerRepository.openSocket] and is only valid while [state] is
 * [SatelliteState.CONNECTED]; once the session is torn down it is reset to -1
 * and a fresh handle is allocated on reconnect.
 */
class SatelliteConnection(
    val id: String,
    server: DiscoveredServer,
    private val scope: CoroutineScope,
    private val controllerRepo: ControllerRepository,
) {
    private val _server = MutableStateFlow(server)
    val server: StateFlow<DiscoveredServer> = _server.asStateFlow()

    private val _state = MutableStateFlow(SatelliteState.IDLE)
    val state: StateFlow<SatelliteState> = _state.asStateFlow()

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

    /**
     * State of a single slot bound to this connection. [controllerIndex] is the
     * server-visible 0..N index used in `addController`/`sendReport` etc.
     * [registered] flips true once `addController` has been ACKed and the type
     * has been pushed; reports for this slot are dropped until then so we don't
     * silently feed packets the server will reject as "unknown controller".
     */
    data class SlotBinding(
        val controllerIndex: Int,
        val controllerType: Int,
        val registered: Boolean,
    )

    private val _slots = MutableStateFlow<Map<String, SlotBinding>>(emptyMap())
    val slots: StateFlow<Map<String, SlotBinding>> = _slots.asStateFlow()

    private var ackJob: Job? = null
    private var aliveJob: Job? = null

    /**
     * Serializes [registerController] runs. The native ACK channel
     * (`getLastControllerAck`) is per-handle, not per-controller-index, so two
     * concurrent registrations would race on `resetControllerAck` and steal
     * each other's ACKs.
     */
    private val registrationMutex = Mutex()
    private var onRegistrationFailed: (() -> Unit)? = null

    fun updateServer(server: DiscoveredServer) {
        _server.value = server
    }

    /**
     * Advance to CONNECTING from IDLE or an already-in-flight CONNECTING.
     * Rejected from CONNECTED so a caller can't accidentally wipe a live
     * session's state back to CONNECTING without an intervening disconnect.
     */
    internal fun markConnecting() {
        if (_state.value == SatelliteState.CONNECTED) return
        _state.value = SatelliteState.CONNECTING
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
     * Any slots attached before the session was online (common right after an
     * app relaunch/auto-reconnect) get their `addController` handshake run now,
     * sequentially, so subsequent [sendReport] packets are accepted.
     */
    internal fun markConnected(
        handle: Int,
        connectionId: String,
        onRegistrationFailed: (() -> Unit)? = null,
        onDead: () -> Unit,
    ) {
        if (_state.value != SatelliteState.CONNECTING) return
        // Publish the (handle, connectionId) pair before flipping state so
        // any concurrent sendReport that observes CONNECTED cannot see a
        // null/-1 tuple.
        live = Live(handle, connectionId)
        _state.value = SatelliteState.CONNECTED
        this.onRegistrationFailed = onRegistrationFailed
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
        val pending =
            _slots.value
                .filterValues { !it.registered }
                .keys
                .toList()
        if (pending.isNotEmpty()) {
            scope.launch {
                for (slotId in pending) registerController(slotId)
            }
        }
    }

    /**
     * Tear the session down. Idempotent: a second call from IDLE is a no-op
     * so callers (alive-poll, manager.disconnect, forget, foreground kicks)
     * can invoke it freely without needing to check state first.
     *
     * Slot attachments are preserved (only the `registered` flag is cleared)
     * so the next [markConnected] re-registers them automatically.
     */
    internal fun markDisconnected() {
        val snap = live
        if (_state.value == SatelliteState.IDLE && snap == null) return
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
        _slots.update { map -> map.mapValues { (_, v) -> v.copy(registered = false) } }
        onRegistrationFailed = null
        _state.value = SatelliteState.IDLE
    }

    /**
     * Record [slotId] as bound to this connection at [controllerType]. If the
     * slot is already attached this is a no-op (use [setControllerType] to
     * change the type of an attached slot). When the native session is live
     * the server is notified immediately; otherwise the registration is
     * deferred to [markConnected].
     */
    suspend fun attachSlot(
        slotId: String,
        controllerType: Int,
    ) {
        val updated =
            _slots.updateAndGet { map ->
                if (map.containsKey(slotId)) return@updateAndGet map
                val index = lowestFreeIndex(map.values.map { it.controllerIndex })
                map + (slotId to SlotBinding(index, controllerType, registered = false))
            }
        // No-op if this slot was already present.
        val info = updated[slotId] ?: return
        if (info.registered) return
        if (_state.value == SatelliteState.CONNECTED && handle >= 0) {
            registerController(slotId)
        }
    }

    /**
     * Update the controller type for an already-attached [slotId] (Xbox/PS
     * etc.). If the slot is registered with the server, the change is pushed
     * immediately; otherwise it sticks in [SlotBinding.controllerType] and is
     * sent at registration time.
     */
    suspend fun setControllerType(
        slotId: String,
        controllerType: Int,
    ) {
        var info: SlotBinding? = null
        _slots.update { map ->
            val cur = map[slotId] ?: return@update map
            if (cur.controllerType == controllerType) {
                info = null
                return@update map
            }
            val next = cur.copy(controllerType = controllerType)
            info = next
            map + (slotId to next)
        }
        val snap = info ?: return
        if (snap.registered && handle >= 0) {
            withContext(Dispatchers.IO) {
                controllerRepo.sendControllerType(handle, snap.controllerIndex, controllerType)
            }
        }
    }

    private suspend fun registerController(slotId: String) =
        registrationMutex.withLock {
            withContext(Dispatchers.IO) {
                val info = _slots.value[slotId] ?: return@withContext
                if (info.registered) return@withContext
                if (handle < 0) return@withContext
                controllerRepo.resetControllerAck(handle)
                controllerRepo.addController(handle, info.controllerIndex, DEFAULT_CAPABILITIES)
                var ack = -1
                repeat(ACK_WAIT_ATTEMPTS) {
                    ack = controllerRepo.getLastControllerAck(handle)
                    if (ack != -1) return@repeat
                    delay(ACK_WAIT_INTERVAL_MS)
                }
                if (ack != -1) {
                    controllerRepo.sendControllerType(handle, info.controllerIndex, info.controllerType)
                    _slots.update { map ->
                        val cur = map[slotId] ?: return@update map
                        map + (slotId to cur.copy(registered = true))
                    }
                } else {
                    // Server never ACKed addController; don't silently feed
                    // reports into a connection it will reject. Surface so the
                    // user sees "couldn't register controller" instead of an
                    // inert UI.
                    onRegistrationFailed?.invoke()
                }
            }
        }

    fun detachSlot(slotId: String) {
        var removed: SlotBinding? = null
        _slots.update { map ->
            val cur = map[slotId] ?: return@update map
            removed = cur
            map - slotId
        }
        val info = removed ?: return
        if (handle >= 0 && info.registered) {
            controllerRepo.removeController(handle, info.controllerIndex)
        }
    }

    fun sendReport(
        slotId: String,
        buttons: Int,
        lt: Int,
        rt: Int,
        lx: Int,
        ly: Int,
        rx: Int,
        ry: Int,
    ) {
        val snap = live ?: return
        val info = _slots.value[slotId] ?: return
        // Gate on registered so reports during the registerController() ACK
        // window (up to 2s after markConnected) aren't silently dropped
        // server-side as "unknown controller". Slot-bind → connect ordering
        // makes this window unavoidable on auto-reconnect.
        if (!info.registered) return
        controllerRepo.sendReport(snap.handle, info.controllerIndex, buttons, lt, rt, lx, ly, rx, ry)
    }

    /**
     * Forward an IMU sample for [slotId]. Axes are pre-scaled to the wire
     * int16 form by the caller; rate-limiting is the caller's job too (see
     * [com.tinkernorth.dish.data.network.MotionRateLimiter]). Same
     * registered-gate + atomic [live] snapshot discipline as [sendReport].
     */
    @Suppress("LongParameterList")
    fun sendMotion(
        slotId: String,
        gyroX: Short,
        gyroY: Short,
        gyroZ: Short,
        accelX: Short,
        accelY: Short,
        accelZ: Short,
        timestampDeltaUs: Int,
    ) {
        val snap = live ?: return
        val info = _slots.value[slotId] ?: return
        if (!info.registered) return
        controllerRepo.sendMotion(
            snap.handle,
            info.controllerIndex,
            gyroX,
            gyroY,
            gyroZ,
            accelX,
            accelY,
            accelZ,
            timestampDeltaUs,
        )
    }

    /**
     * Forward a battery snapshot for [slotId]. Deduping is the caller's job
     * ([com.tinkernorth.dish.data.network.BatteryCoalescer]).
     */
    fun sendBattery(
        slotId: String,
        level: Int,
        status: Int,
    ) {
        val snap = live ?: return
        val info = _slots.value[slotId] ?: return
        if (!info.registered) return
        controllerRepo.sendBattery(snap.handle, info.controllerIndex, level, status)
    }

    companion object {
        private const val ALIVE_POLL_MS = 1000L
        private const val ACK_WAIT_ATTEMPTS = 20
        private const val ACK_WAIT_INTERVAL_MS = 100L
        private const val DEFAULT_CAPABILITIES = 0x0003

        fun idFor(server: DiscoveredServer): String = "satellite:${server.ip}:${server.udpPort}"

        private fun lowestFreeIndex(taken: List<Int>): Int {
            val set = taken.toHashSet()
            var i = 0
            while (i in set) i++
            return i
        }
    }
}

enum class SatelliteState { IDLE, CONNECTING, CONNECTED }
