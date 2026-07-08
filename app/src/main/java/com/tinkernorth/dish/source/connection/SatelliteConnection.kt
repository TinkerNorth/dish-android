// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.connection

import com.tinkernorth.dish.core.jni.ControllerRepository
import com.tinkernorth.dish.core.model.ControllerApplyDto
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.SessionViewDto
import com.tinkernorth.dish.core.model.stableKey
import com.tinkernorth.dish.core.net.ControllerDescriptor
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One satellite session. Slots are DECLARATIVE: this class holds the desired
 * descriptor per slot plus the applied state the satellite last confirmed;
 * every mutation funnels into the manager's REST sync (session PUT on connect,
 * per-controller PUT/DELETE while live). UDP carries streams only. It can
 * never mutate topology (satellite docs/contract.md).
 */
@Suppress("LongParameterList")
class SatelliteConnection(
    val id: String,
    server: DiscoveredServer,
    private val scope: CoroutineScope,
    private val controllerRepo: ControllerRepository,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val motionCapsBitsFor: (slotId: String) -> Int = { CAP_MOTION_BIT_LEGACY },
    // Pulled at descriptor-build time like motionCapsBitsFor, so the mode a slot declares can
    // never drift from the store-derived routing the rest of the app displays.
    private val touchpadModeFor: (slotId: String) -> String = { ControllerDescriptor.TOUCHPAD_MODE_OFF },
    private val motionBackendStatusStore: SatelliteMotionBackendStatusStore? = null,
    // Manager-provided REST sync hooks. Slot-level changes ride the
    // per-controller routes so the session (and its UDP keys) never churns.
    private val onSlotChanged: (slotId: String) -> Unit = {},
    private val onSlotRemoved: (ctrlIdx: Int) -> Unit = {},
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

    // The epoch the satellite stamped on our last PUT/GET: the reference the
    // heartbeat-ack epoch is compared against.
    @Volatile var lastAppliedEpoch: Int = -1
        private set

    /**
     * Session-level mouseControl grant from the last session PUT. The server
     * computes it ONLY there (contract §hostFeatures), so a mid-session mode
     * toggle leaves wants ≠ granted until the session is re-PUT.
     */
    @Volatile var mouseControlGranted: Boolean = false
        private set

    data class SlotBinding(
        val controllerIndex: Int,
        val controllerType: Int,
        // True once the satellite confirmed this descriptor applied (the slot's
        // virtual pad exists). Streams are gated on it.
        val registered: Boolean,
        val lastAdvertisedCaps: Int? = null,
        val lastAdvertisedTouchpadMode: String? = null,
    )

    private val _slots = MutableStateFlow<Map<String, SlotBinding>>(emptyMap())
    val slots: StateFlow<Map<String, SlotBinding>> = _slots.asStateFlow()

    private var aliveJob: Job? = null
    private var ackJob: Job? = null

    fun updateServer(server: DiscoveredServer) {
        _server.value = server
    }

    internal fun markConnecting() {
        if (_state.value == SatelliteSessionState.Live) return
        _state.value = SatelliteSessionState.Linking
    }

    /**
     * Session PUT succeeded: adopt the UDP tuple + the applied state from the
     * response. [onDead] fires on heartbeat death; [onClosedByServer] on an
     * authenticated close-notify (immediate, no death-timeout wait);
     * [onReconcileNeeded] when the heartbeat-ack epoch/bitmap stops matching
     * what we believe is applied.
     */
    internal fun markConnected(
        handle: Int,
        connectionId: String,
        epoch: Int,
        applied: List<ControllerApplyDto>,
        mouseControlGranted: Boolean = false,
        onDead: () -> Unit,
        onClosedByServer: (reason: Int) -> Unit = { onDead() },
        onReconcileNeeded: () -> Unit = {},
        onApplyFailures: (failures: List<ControllerApplyDto>) -> Unit = {},
    ) {
        if (_state.value != SatelliteSessionState.Linking) return
        // Publish tuple before state flip so concurrent sendReport never sees Live with null/-1.
        live = LiveHandle(handle, connectionId)
        lastAppliedEpoch = epoch
        this.mouseControlGranted = mouseControlGranted
        applyResults(applied, onApplyFailures)
        _state.value = SatelliteSessionState.Live
        // Drain the downstream socket (acks, rumble, close-notify); receiveAck
        // blocks ≤500 ms. Negative status = socket gone and every further call
        // returns instantly: exit, don't busy-spin; aliveJob owns death detection.
        ackJob =
            scope.launch(ioDispatcher) {
                var status = 0
                while (isActive && status >= 0) {
                    status = controllerRepo.receiveAck(handle)
                }
            }
        controllerRepo.startHeartbeat(handle)
        aliveJob =
            scope.launch {
                var consecutiveMisses = 0
                while (isActive) {
                    delay(ALIVE_POLL_MS)
                    // An authenticated close-notify is terminal NOW: the
                    // session is already gone server-side, so waiting out the
                    // heartbeat death window would just be dead air.
                    val closeReason = controllerRepo.getSessionCloseReason(handle)
                    if (closeReason >= 0) {
                        onClosedByServer(closeReason)
                        break
                    }
                    val alive = controllerRepo.isConnectionAlive(handle)
                    if (alive) {
                        if (consecutiveMisses > 0 && _state.value == SatelliteSessionState.Faltering) {
                            _state.value = SatelliteSessionState.Live
                        }
                        consecutiveMisses = 0
                        checkReconcile(onReconcileNeeded)
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
    }

    // Heartbeat acks carry the server's (epoch, active-bitmap). A mismatch with
    // our applied view means the server lost or mutated topology involuntarily
    // (failed replug, reap, sibling close). Self-heal via the reconcile
    // endpoint instead of streaming into a dead slot.
    private fun checkReconcile(onReconcileNeeded: () -> Unit) {
        val snap = live ?: return
        val serverEpoch = controllerRepo.getServerEpoch(snap.handle)
        if (serverEpoch < 0) return // no enriched ack seen yet
        val serverBitmap = controllerRepo.getActiveBitmap(snap.handle)
        val expectedBitmap = registeredBitmap()
        if (serverEpoch != lastAppliedEpoch || (serverBitmap >= 0 && serverBitmap != expectedBitmap)) {
            onReconcileNeeded()
        }
    }

    private fun registeredBitmap(): Int {
        var bitmap = 0
        for (binding in _slots.value.values) {
            if (binding.registered && binding.controllerIndex in 0..15) {
                bitmap = bitmap or (1 shl binding.controllerIndex)
            }
        }
        return bitmap
    }

    internal fun adoptEpoch(epoch: Int) {
        lastAppliedEpoch = epoch
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
        lastAppliedEpoch = -1
        mouseControlGranted = false
        if (snap != null) {
            controllerRepo.stopHeartbeat(snap.handle)
            controllerRepo.closeSocket(snap.handle)
        }
        _slots.update { map -> map.mapValues { (_, v) -> v.copy(registered = false) } }
        // Avoid pill reading previous session's flags during Connected→Idle→Connected re-register window.
        motionBackendStatusStore?.clearConnection(id)
        _state.value = SatelliteSessionState.Idle
    }

    /**
     * Converge this connection's desired slot set to [desired] (slotId -> type),
     * derived from the binding/type stores by SlotTopologyComposer. The ONLY
     * production write path into the slot map; everything below it is plumbing.
     * A single removed+added pair of the same type is the same physical
     * controller re-keyed (a USB claim/release swaps the device id): renaming
     * preserves the controller index so the server-side pad survives without an
     * unplug/replug.
     */
    fun applyDesired(desired: Map<String, Int>) {
        val current = _slots.value
        val removed = current.keys - desired.keys
        val added = desired.keys - current.keys
        if (removed.size == 1 && added.size == 1) {
            val from = removed.first()
            val to = added.first()
            if (current.getValue(from).controllerType == desired.getValue(to)) {
                renameSlot(from, to)
            }
        }
        for (slotId in _slots.value.keys.toList()) {
            if (slotId !in desired) detachSlot(slotId)
        }
        for ((slotId, type) in desired) declareSlot(slotId, type)
    }

    /**
     * Declare a slot with its FINAL descriptor: the type travels with the
     * attach and the touchpad mode is pulled from [touchpadModeFor] when the
     * descriptor is built, so there is never a default-then-correct phase
     * anywhere in the pipeline. While live, the manager converges it via a
     * controller PUT; while idle, it rides the next session PUT.
     */
    internal fun attachSlot(
        slotId: String,
        controllerType: Int,
    ) {
        var added = false
        _slots.update { map ->
            if (map.containsKey(slotId)) return@update map
            added = true
            val index = lowestFreeIndex(map.values.map { it.controllerIndex })
            map + (slotId to SlotBinding(index, controllerType, registered = false))
        }
        if (added && _state.value == SatelliteSessionState.Live) onSlotChanged(slotId)
    }

    /** Attach-or-update: the WHOLE descriptor in one declaration, one sync. */
    internal fun declareSlot(
        slotId: String,
        controllerType: Int,
    ) {
        if (!_slots.value.containsKey(slotId)) {
            attachSlot(slotId, controllerType)
            return
        }
        mutateSlot(slotId) { it.copy(controllerType = controllerType) }
    }

    internal fun setControllerType(
        slotId: String,
        controllerType: Int,
    ) = mutateSlot(slotId) { it.copy(controllerType = controllerType) }

    private fun mutateSlot(
        slotId: String,
        transform: (SlotBinding) -> SlotBinding,
    ) {
        var changed = false
        _slots.update { map ->
            val cur = map[slotId] ?: return@update map
            val next = transform(cur)
            if (next == cur) return@update map
            changed = true
            map + (slotId to next)
        }
        if (changed && _state.value == SatelliteSessionState.Live) onSlotChanged(slotId)
    }

    // Caps or touchpad routing changed (motion toggled, mode picked, type gate flipped): the
    // descriptor is re-sent whole via the per-controller route; nothing special-cased on the
    // wire. This is the convergence point for EVERY bound slot, so a per-satellite mode pick
    // reaches sibling slots too, not just the one a screen happened to re-declare.
    internal fun refreshCapsIfChanged() {
        if (_state.value != SatelliteSessionState.Live) return
        for ((slotId, binding) in _slots.value) {
            if (!binding.registered) continue
            val newCaps = BASE_CAPABILITIES or motionCapsBitsFor(slotId)
            if (binding.lastAdvertisedCaps == newCaps &&
                binding.lastAdvertisedTouchpadMode == touchpadModeFor(slotId)
            ) {
                continue
            }
            onSlotChanged(slotId)
        }
    }

    internal fun detachSlot(slotId: String) {
        var removed: SlotBinding? = null
        _slots.update { map ->
            val cur = map[slotId] ?: return@update map
            removed = cur
            map - slotId
        }
        val info = removed ?: return
        motionBackendStatusStore?.clear(id, slotId)
        if (_state.value == SatelliteSessionState.Live && info.registered) {
            onSlotRemoved(info.controllerIndex)
        }
    }

    internal fun renameSlot(
        fromSlotId: String,
        toSlotId: String,
    ): Boolean {
        if (fromSlotId == toSlotId) return _slots.value.containsKey(toSlotId)
        var renamed = false
        _slots.update { map ->
            val cur = map[fromSlotId] ?: return@update map
            if (map.containsKey(toSlotId)) return@update map
            renamed = true
            (map - fromSlotId) + (toSlotId to cur)
        }
        if (renamed) {
            motionBackendStatusStore?.let { store ->
                store.statusFor(id, fromSlotId)?.let { status ->
                    store.setStatus(id, toSlotId, status)
                    store.clear(id, fromSlotId)
                }
            }
        }
        return _slots.value.containsKey(toSlotId)
    }

    internal fun descriptorFor(slotId: String): ControllerDescriptor? {
        val binding = _slots.value[slotId] ?: return null
        return ControllerDescriptor(
            ctrlIdx = binding.controllerIndex,
            type = binding.controllerType,
            caps = BASE_CAPABILITIES or motionCapsBitsFor(slotId),
            touchpadMode = touchpadModeFor(slotId),
        )
    }

    internal fun desiredDescriptors(): List<ControllerDescriptor> = _slots.value.keys.mapNotNull { descriptorFor(it) }

    internal fun slotIdForIndex(ctrlIdx: Int): String? =
        _slots.value.entries
            .firstOrNull { it.value.controllerIndex == ctrlIdx }
            ?.key

    internal fun wantsMouseControl(): Boolean = _slots.value.keys.any { touchpadModeFor(it) == ControllerDescriptor.TOUCHPAD_MODE_MOUSE }

    /**
     * Failures surface through [onApplyFailures], never swallowed: input
     * would silently drop otherwise.
     */
    internal fun applyResults(
        results: List<ControllerApplyDto>,
        onApplyFailures: (failures: List<ControllerApplyDto>) -> Unit = {},
    ) {
        if (results.isEmpty()) return
        val byIdx = results.associateBy { it.ctrlIdx }
        _slots.update { map ->
            map.mapValues { (slotId, binding) ->
                val result = byIdx[binding.controllerIndex] ?: return@mapValues binding
                if (result.slotIsLive) {
                    // A failed replug keeps the PREVIOUS pad alive (appliedType
                    // reports it). Streams keep flowing rather than killing a
                    // working pad over a type the driver couldn't switch.
                    motionBackendStatusStore?.setStatus(
                        id,
                        slotId,
                        SatelliteMotionBackendStatus(
                            sinkSupportedForType = result.motion.sinkSupportedForType,
                            backendOk = result.motion.backendOk,
                        ),
                    )
                    binding.copy(
                        registered = true,
                        lastAdvertisedCaps = BASE_CAPABILITIES or motionCapsBitsFor(slotId),
                        lastAdvertisedTouchpadMode = touchpadModeFor(slotId),
                    )
                } else {
                    motionBackendStatusStore?.clear(id, slotId)
                    binding.copy(registered = false)
                }
            }
        }
        val failures = results.filter { !it.ok }
        if (failures.isNotEmpty()) onApplyFailures(failures)
    }

    internal fun matchesAppliedView(view: SessionViewDto): Boolean {
        val desired =
            _slots.value.values.associate { it.controllerIndex to it.controllerType }
        val applied = view.controllers.filter { it.active }.associate { it.ctrlIdx to it.appliedType }
        // The host-feature grant is applied state too: a slot toggled to mouse
        // mid-session leaves wants ≠ granted (the grant is only computed at
        // session PUT), and the converge re-PUT is what heals it.
        return desired == applied &&
            view.hostFeatures.mouseControl.granted == wantsMouseControl()
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
        // Gate: reports for an unapplied descriptor would be dropped server-side as unknown.
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

        private const val FALTER_THRESHOLD = 2
        private const val FALTER_TO_DEAD = 5

        // CAP_LIGHTBAR intentionally unset: Android has no controller-LED API.
        private const val BASE_CAPABILITIES =
            ControllerDescriptor.CAP_ANALOG_TRIGGERS or ControllerDescriptor.CAP_RUMBLE

        internal const val CAP_MOTION_BIT_LEGACY = ControllerDescriptor.CAP_MOTION

        // CLOSE_REASON_* wire values (contract §UDP messages).
        const val CLOSE_REASON_SHUTDOWN = 0
        const val CLOSE_REASON_KICKED = 1
        const val CLOSE_REASON_REPLACED = 2
        const val CLOSE_REASON_UNPAIRED = 3

        const val ID_PREFIX = "satellite:"

        // Keyed on the stable machineId so a receiver that changes IP keeps the
        // same identity; ip:udpPort only for a beacon that carries no id at all.
        fun idFor(server: DiscoveredServer): String = "$ID_PREFIX${server.stableKey}"

        private fun lowestFreeIndex(taken: List<Int>): Int {
            val set = taken.toHashSet()
            var i = 0
            while (i in set) i++
            return i
        }
    }
}

enum class SatelliteSessionState { Idle, Linking, Live, Faltering }
