// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.source.sensor.BatteryValidator
import com.tinkernorth.dish.source.sensor.MotionRateLimiter
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import kotlinx.coroutines.CoroutineDispatcher
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

class SatelliteConnection(
    val id: String,
    server: DiscoveredServer,
    private val scope: CoroutineScope,
    private val controllerRepo: ControllerRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val motionCapsBitsFor: (slotId: String) -> Int = { CAP_MOTION_BIT_LEGACY },
    private val motionBackendStatusStore: SatelliteMotionBackendStatusStore? = null,
) {
    private val _server = MutableStateFlow(server)
    val server: StateFlow<DiscoveredServer> = _server.asStateFlow()

    private val _state = MutableStateFlow(SatelliteSessionState.Idle)
    val state: StateFlow<SatelliteSessionState> = _state.asStateFlow()

    // Held atomically so sendReport never observes a torn (handle, connectionId) pair mid-reconnect.
    private data class LiveHandle(
        val handle: Int,
        val connectionId: String,
    )

    @Volatile private var live: LiveHandle? = null

    val connectionId: String? get() = live?.connectionId

    val handle: Int get() = live?.handle ?: -1

    data class SlotBinding(
        val controllerIndex: Int,
        val controllerType: Int,
        val registered: Boolean,
        val lastAdvertisedCaps: Int? = null,
    )

    private val _slots = MutableStateFlow<Map<String, SlotBinding>>(emptyMap())
    val slots: StateFlow<Map<String, SlotBinding>> = _slots.asStateFlow()

    private var ackJob: Job? = null
    private var aliveJob: Job? = null

    // Native ACK channel is per-handle, not per-index — concurrent registers would steal each other's ACKs.
    private val registrationMutex = Mutex()
    private var onRegistrationFailed: ((reason: String) -> Unit)? = null

    fun updateServer(server: DiscoveredServer) {
        _server.value = server
    }

    internal fun markConnecting() {
        if (_state.value == SatelliteSessionState.Live) return
        _state.value = SatelliteSessionState.Linking
    }

    internal fun markConnected(
        handle: Int,
        connectionId: String,
        onRegistrationFailed: ((reason: String) -> Unit)? = null,
        onDead: () -> Unit,
    ) {
        if (_state.value != SatelliteSessionState.Linking) return
        // Publish tuple before state flip so concurrent sendReport never sees Live with null/-1.
        live = LiveHandle(handle, connectionId)
        _state.value = SatelliteSessionState.Live
        this.onRegistrationFailed = onRegistrationFailed
        controllerRepo.resetControllerAck(handle)
        ackJob =
            scope.launch(ioDispatcher) {
                while (isActive) {
                    controllerRepo.receiveAck(handle)
                }
            }
        controllerRepo.startHeartbeat(handle)
        aliveJob =
            scope.launch {
                var consecutiveMisses = 0
                while (isActive) {
                    delay(ALIVE_POLL_MS)
                    val alive = controllerRepo.isConnectionAlive(handle)
                    if (alive) {
                        if (consecutiveMisses > 0 && _state.value == SatelliteSessionState.Faltering) {
                            _state.value = SatelliteSessionState.Live
                        }
                        consecutiveMisses = 0
                        continue
                    }
                    consecutiveMisses += 1
                    when {
                        consecutiveMisses >= FALTER_TO_DEAD -> {
                            onDead()
                            break
                        }
                        consecutiveMisses >= FALTER_THRESHOLD ->
                            if (_state.value == SatelliteSessionState.Live) _state.value = SatelliteSessionState.Faltering
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

    internal fun markDisconnected() {
        val snap = live
        if (_state.value == SatelliteSessionState.Idle && snap == null) return
        aliveJob?.cancel()
        aliveJob = null
        ackJob?.cancel()
        ackJob = null
        // Null tuple before native teardown so concurrent sendReport bails instead of racing a half-closed handle.
        live = null
        if (snap != null) {
            controllerRepo.stopHeartbeat(snap.handle)
            controllerRepo.closeSocket(snap.handle)
        }
        _slots.update { map -> map.mapValues { (_, v) -> v.copy(registered = false) } }
        // Avoid pill reading previous session's flags during Connected→Idle→Connected re-register window.
        motionBackendStatusStore?.clearConnection(id)
        onRegistrationFailed = null
        _state.value = SatelliteSessionState.Idle
    }

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
        val info = updated[slotId] ?: return
        if (info.registered) return
        if (_state.value == SatelliteSessionState.Live && handle >= 0) {
            registerController(slotId)
        }
    }

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
            withContext(ioDispatcher) {
                controllerRepo.sendControllerType(handle, snap.controllerIndex, controllerType)
            }
        }
    }

    private suspend fun registerController(slotId: String) =
        registrationMutex.withLock {
            withContext(ioDispatcher) {
                val info = _slots.value[slotId] ?: return@withContext
                if (info.registered) return@withContext
                if (handle < 0) return@withContext
                controllerRepo.resetControllerAck(handle)
                val caps = BASE_CAPABILITIES or motionCapsBitsFor(slotId)
                controllerRepo.addController(handle, info.controllerIndex, caps)
                var ack = -1
                repeat(ACK_WAIT_ATTEMPTS) {
                    ack = controllerRepo.getLastControllerAck(handle)
                    if (ack != -1) return@repeat
                    delay(ACK_WAIT_INTERVAL_MS)
                }
                val result = if (ack == -1) null else ack and ACK_RESULT_MASK
                if (result == ACK_OK) {
                    // Pre-extension satellite returns -1 — leave store absent so composer uses its heuristic.
                    val motionFlags = controllerRepo.getLastControllerMotionFlags(handle)
                    if (motionFlags >= 0) {
                        motionBackendStatusStore?.setStatus(
                            id,
                            slotId,
                            SatelliteMotionBackendStatus.fromFlags(motionFlags),
                        )
                    } else {
                        motionBackendStatusStore?.clear(id, slotId)
                    }
                    controllerRepo.sendControllerType(handle, info.controllerIndex, info.controllerType)
                    // Close composer-emission-during-ACK race before flipping registered=true.
                    val currentCaps = BASE_CAPABILITIES or motionCapsBitsFor(slotId)
                    if (currentCaps != caps) {
                        controllerRepo.sendControllerCapsUpdate(
                            handle,
                            info.controllerIndex,
                            currentCaps,
                        )
                    }
                    _slots.update { map ->
                        val cur = map[slotId] ?: return@update map
                        map + (slotId to cur.copy(registered = true, lastAdvertisedCaps = currentCaps))
                    }
                } else {
                    onRegistrationFailed?.invoke(registrationFailureReason(result))
                }
            }
        }

    private fun registrationFailureReason(result: Int?): String =
        when (result) {
            null -> "it didn't respond"
            ACK_ERR_BACKEND_UNAVAIL -> "it has no virtual-gamepad backend (e.g. a macOS satellite)"
            ACK_ERR_NO_SLOTS -> "it has no free controller slots"
            ACK_ERR_ALREADY_EXISTS -> "that controller slot is already in use"
            ACK_ERR_PLUGIN_FAIL -> "it couldn't create the virtual controller"
            else -> "it rejected the controller"
        }

    internal suspend fun refreshCapsIfChanged() {
        val snap = live ?: return
        val updates = mutableListOf<Pair<String, Int>>()
        // Single _slots snapshot so parallel mutations can't shear the diff.
        for ((slotId, slotInfo) in _slots.value) {
            if (!slotInfo.registered) continue
            val newCaps = BASE_CAPABILITIES or motionCapsBitsFor(slotId)
            if (slotInfo.lastAdvertisedCaps == newCaps) continue
            updates += slotId to newCaps
        }
        if (updates.isEmpty()) return
        withContext(ioDispatcher) {
            for ((slotId, newCaps) in updates) {
                val info = _slots.value[slotId] ?: continue
                // Re-check: a parallel detach may have unset registered between scan and send.
                if (!info.registered) continue
                controllerRepo.sendControllerCapsUpdate(snap.handle, info.controllerIndex, newCaps)
                _slots.update { map ->
                    val cur = map[slotId] ?: return@update map
                    map + (slotId to cur.copy(lastAdvertisedCaps = newCaps))
                }
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
        motionBackendStatusStore?.clear(id, slotId)
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
        // Gate: reports during registerController() ACK window would be dropped server-side as unknown.
        if (!info.registered) return
        controllerRepo.sendReport(snap.handle, info.controllerIndex, buttons, lt, rt, lx, ly, rx, ry)
    }

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

    @Suppress("LongParameterList")
    fun sendTouchpad(
        slotId: String,
        finger0Active: Boolean,
        finger1Active: Boolean,
        buttonPressed: Boolean,
        finger0TrackingId: Int,
        finger0X: Short,
        finger0Y: Short,
        finger1TrackingId: Int,
        finger1X: Short,
        finger1Y: Short,
        eventTimeMs: Long,
    ) {
        val snap = live ?: return
        val info = _slots.value[slotId] ?: return
        if (!info.registered) return
        controllerRepo.sendTouchpad(
            snap.handle,
            info.controllerIndex,
            finger0Active,
            finger1Active,
            buttonPressed,
            finger0TrackingId,
            finger0X,
            finger0Y,
            finger1TrackingId,
            finger1X,
            finger1Y,
            eventTimeMs,
        )
    }

    companion object {
        private const val ALIVE_POLL_MS = 1000L
        private const val ACK_WAIT_ATTEMPTS = 20
        private const val ACK_WAIT_INTERVAL_MS = 100L

        private const val FALTER_THRESHOLD = 2
        private const val FALTER_TO_DEAD = 5

        // CAP_LIGHTBAR (0x0008) intentionally unset: Android has no controller-LED API.
        private const val CAP_ANALOG_TRIGGERS = 0x0001
        private const val CAP_RUMBLE = 0x0002

        private const val BASE_CAPABILITIES = CAP_ANALOG_TRIGGERS or CAP_RUMBLE

        internal const val CAP_MOTION_BIT_LEGACY = 0x0004

        // getLastControllerAck returns packed word (reqType<<16)|(ctrlIdx<<8)|result; low byte is result.
        private const val ACK_RESULT_MASK = 0xFF
        private const val ACK_OK = 0x00
        private const val ACK_ERR_BACKEND_UNAVAIL = 0x01
        private const val ACK_ERR_NO_SLOTS = 0x02
        private const val ACK_ERR_ALREADY_EXISTS = 0x03
        private const val ACK_ERR_PLUGIN_FAIL = 0x05

        fun idFor(server: DiscoveredServer): String = "satellite:${server.ip}:${server.udpPort}"

        private fun lowestFreeIndex(taken: List<Int>): Int {
            val set = taken.toHashSet()
            var i = 0
            while (i in set) i++
            return i
        }
    }
}

enum class SatelliteSessionState { Idle, Linking, Live, Faltering }
