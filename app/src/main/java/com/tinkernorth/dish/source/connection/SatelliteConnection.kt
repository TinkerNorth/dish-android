// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection

import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.source.sensor.BatteryValidator
import com.tinkernorth.dish.source.sensor.MotionRateLimiter
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
 * [SatelliteSessionState.Live]; once the session is torn down it is reset to -1
 * and a fresh handle is allocated on reconnect.
 */
class SatelliteConnection(
    val id: String,
    server: DiscoveredServer,
    private val scope: CoroutineScope,
    private val controllerRepo: ControllerRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _server = MutableStateFlow(server)
    val server: StateFlow<DiscoveredServer> = _server.asStateFlow()

    private val _state = MutableStateFlow(SatelliteSessionState.Idle)
    val state: StateFlow<SatelliteSessionState> = _state.asStateFlow()

    /**
     * Immutable tuple of the live-session handle + connection id. Held in a
     * single `@Volatile` reference so [sendReport] (report thread) always
     * sees the pair atomically — readers can never observe a fresh handle
     * with a stale connection id (or vice versa) mid-reconnect.
     */
    private data class LiveHandle(
        val handle: Int,
        val connectionId: String,
    )

    @Volatile private var live: LiveHandle? = null

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
    private var onRegistrationFailed: ((reason: String) -> Unit)? = null

    fun updateServer(server: DiscoveredServer) {
        _server.value = server
    }

    /**
     * Advance to Linking from Idle or an already-in-flight Linking.
     * Rejected from Live so a caller can't accidentally wipe a live
     * session's state back to Linking without an intervening disconnect.
     */
    internal fun markConnecting() {
        if (_state.value == SatelliteSessionState.Live) return
        _state.value = SatelliteSessionState.Linking
    }

    /**
     * Finalize a successful connect: stash the native handle + server-issued
     * connectionId, start the ACK receive loop and heartbeat. Must be called
     * after [ControllerRepository.openSocket] and [setConnectionParams] succeed.
     *
     * Strict guard: only valid from Linking. A second [markConnected] while
     * already Live would leak the previous native handle, so we reject
     * and let the caller run [markDisconnected] first if it really meant to
     * rebind. Calls from Idle are likewise rejected — the pair/auth path must
     * go through [markConnecting].
     *
     * Any slots attached before the session was online (common right after an
     * app relaunch/auto-reconnect) get their `addController` handshake run now,
     * sequentially, so subsequent [sendReport] packets are accepted.
     */
    internal fun markConnected(
        handle: Int,
        connectionId: String,
        onRegistrationFailed: ((reason: String) -> Unit)? = null,
        onDead: () -> Unit,
    ) {
        if (_state.value != SatelliteSessionState.Linking) return
        // Publish the (handle, connectionId) pair before flipping state so
        // any concurrent sendReport that observes Live cannot see a
        // null/-1 tuple.
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
                // Synthesized Faltering: keep a short ring buffer of alive-poll
                // results and only declare the session dead after FALTER_TO_DEAD
                // consecutive misses. A single miss flips state to Faltering →
                // chip "Unsteady"; a recovery before the threshold restores Live.
                // This dampens single-tick Wi-Fi blips that the previous
                // binary-alive code rendered as Online → Offline bounces.
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

    /**
     * Tear the session down. Idempotent: a second call from Idle is a no-op
     * so callers (alive-poll, manager.disconnect, forget, foreground kicks)
     * can invoke it freely without needing to check state first.
     *
     * Slot attachments are preserved (only the `registered` flag is cleared)
     * so the next [markConnected] re-registers them automatically.
     */
    internal fun markDisconnected() {
        val snap = live
        if (_state.value == SatelliteSessionState.Idle && snap == null) return
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
        _state.value = SatelliteSessionState.Idle
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
        if (_state.value == SatelliteSessionState.Live && handle >= 0) {
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
                controllerRepo.addController(handle, info.controllerIndex, DEFAULT_CAPABILITIES)
                var ack = -1
                repeat(ACK_WAIT_ATTEMPTS) {
                    ack = controllerRepo.getLastControllerAck(handle)
                    if (ack != -1) return@repeat
                    delay(ACK_WAIT_INTERVAL_MS)
                }
                // ack == -1 → no ACK arrived at all. Otherwise the low byte is
                // the MSG_CONTROLLER_ACK result code, and only ACK_OK means the
                // controller is live server-side. An *error* ACK — e.g. a macOS
                // satellite answering ACK_ERR_BACKEND_UNAVAIL because it has no
                // virtual-gamepad backend — must NOT be mistaken for success,
                // or sendReport() would stream input the server silently drops.
                val result = if (ack == -1) null else ack and ACK_RESULT_MASK
                if (result == ACK_OK) {
                    controllerRepo.sendControllerType(handle, info.controllerIndex, info.controllerType)
                    _slots.update { map ->
                        val cur = map[slotId] ?: return@update map
                        map + (slotId to cur.copy(registered = true))
                    }
                } else {
                    // Leave the slot unregistered so sendReport() stays gated,
                    // and surface why — the user gets a real reason instead of
                    // a UI that looks connected but silently does nothing.
                    onRegistrationFailed?.invoke(registrationFailureReason(result))
                }
            }
        }

    /**
     * A short, human-readable reason a controller registration was rejected,
     * passed to the [onRegistrationFailed] callback for display. [result] is
     * the MSG_CONTROLLER_ACK result byte, or null when the satellite never
     * answered at all. Phrased as a clause — the caller prefixes the server.
     */
    private fun registrationFailureReason(result: Int?): String =
        when (result) {
            null -> "it didn't respond"
            ACK_ERR_BACKEND_UNAVAIL -> "it has no virtual-gamepad backend (e.g. a macOS satellite)"
            ACK_ERR_NO_SLOTS -> "it has no free controller slots"
            ACK_ERR_ALREADY_EXISTS -> "that controller slot is already in use"
            ACK_ERR_PLUGIN_FAIL -> "it couldn't create the virtual controller"
            else -> "it rejected the controller"
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
     * [com.tinkernorth.dish.source.sensor.MotionRateLimiter]). Same
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
     * ([com.tinkernorth.dish.source.sensor.BatteryValidator]).
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

        /**
         * Consecutive missed alive-polls before [SatelliteSessionState.Live] demotes to
         * [SatelliteSessionState.Faltering] (chip "Unsteady"). Recovery within the next
         * poll restores [SatelliteSessionState.Live] without the user ever seeing a
         * disconnect. Tuned for Wi-Fi: a single ~1s blip stays "Online", but
         * a real outage flips to "Unsteady" before the death threshold so the
         * user sees the change coming.
         */
        private const val FALTER_THRESHOLD = 2

        /**
         * Consecutive missed alive-polls before the session is treated as dead
         * and [onDead] fires. Above this, the manager runs its silent
         * RETRY_AFTER_DEATH path; the chip flips Faltering → Connecting → final.
         */
        private const val FALTER_TO_DEAD = 5

        // MSG_CONTROLLER_ADD capability word (2-byte big-endian): bit 0x0001
        // analog triggers, 0x0002 rumble, 0x0004 motion (this client emits the
        // MSG_MOTION IMU stream — see PhoneMotionSource, Task 1.1).
        //
        // CAP_LIGHTBAR (0x0008) is intentionally NOT set: Android exposes no
        // controller-LED API, so dish-android cannot drive a controller's RGB
        // lightbar. Leaving the bit clear tells a capability-aware satellite
        // not to waste packets sending MSG_LIGHTBAR (0x000D) to this client.
        // Any 0x000D that does arrive is decoded, logged, and dropped by the
        // native receive loop (satellite_jni.cpp::receiveAck).
        private const val CAP_ANALOG_TRIGGERS = 0x0001
        private const val CAP_RUMBLE = 0x0002
        private const val CAP_MOTION = 0x0004
        private const val DEFAULT_CAPABILITIES =
            CAP_ANALOG_TRIGGERS or CAP_RUMBLE or CAP_MOTION

        // MSG_CONTROLLER_ACK result codes — wire values mirror the satellite's
        // core/types.h. getLastControllerAck() returns a packed word,
        // (reqType shl 16) or (ctrlIdx shl 8) or result; the result is the low byte.
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

/**
 * Internal wire-level session state for one Satellite connection. This is the
 * "Presence" axis (per the shared nomenclature): how far the live network link
 * has progressed for *this* connection.
 *
 * Distinct from the UI-facing [LinkState] (in [ConnectionHub]), which folds
 * pairing/discovery in on top of this.
 *
 * - [Idle] — no live session (paired or not).
 * - [Linking] — pair+auth handshake / [SatelliteConnection.markConnecting]
 *   is in flight; native socket not yet open. UI chip: "Connecting…".
 * - [Live] — native socket open, heartbeat ACKs flowing. UI chip: "Online".
 * - [Faltering] — Live, but the heartbeat-miss counter is non-zero and below
 *   the death threshold. UI chip: "Unsteady". **Not yet entered** —
 *   reaching it requires the native side to expose the consecutive-missed
 *   count separately from the binary `isConnectionAlive()` boolean. Today
 *   the alive-poll flips Live → Idle directly when misses hit the threshold.
 */
enum class SatelliteSessionState { Idle, Linking, Live, Faltering }
