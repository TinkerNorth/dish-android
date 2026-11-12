// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.core.input.vidPidKey
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.jni.UsbInterfaceClaim
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.notification.DishNotifications
import com.tinkernorth.dish.source.store.UsbPathPreferenceStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

// Drives the per-controller UsbPathMachine (the pure, exhaustively-tested FSM in UsbPathMachine.kt).
// World signals (USB attach/detach, framework presence, permission, claim results, timeouts) become
// UsbEvents; `reduce` decides the next state and the effects; this class executes those effects
// against the real subsystems (native claim/detach, the registry, the connection hub). All FSM
// mutation runs on the main thread so events apply in order and never race InputManager callbacks.
@Singleton
class UsbGamepadManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val registry: PhysicalGamepadRegistry,
        private val connectionHubProvider: Provider<ConnectionCoordinator>,
        private val notifications: DishNotifications,
        private val scope: CoroutineScope,
        private val native: PhysicalInputNative,
        private val pathPrefs: UsbPathPreferenceStore,
        private val descriptors: UsbDescriptorStore,
    ) {
        private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

        // Single source of truth for USB controller path state, keyed by vidPidKey.
        private val _controllers = MutableStateFlow<Map<Int, UsbController>>(emptyMap())
        val controllers: StateFlow<Map<Int, UsbController>> = _controllers.asStateFlow()

        // Main-thread-confined bookkeeping the effects need.
        private val usbDevices = HashMap<Int, UsbDevice>()
        private val claimedConns = HashMap<Int, ClaimedConn>()
        private val lastFrameworkId = HashMap<Int, Int?>()
        private val timeouts = HashMap<Int, Job>()

        // Each Direct receiver's slots by model key, read for their controllers while any is held.
        private val receiverSlots = HashMap<Int, List<ReceiverSlot>>()
        private var slotLinks: Job? = null

        @Volatile private var installed = false

        // One interface a claim holds, and the pad native attached it as.
        private data class ClaimedPad(
            val intf: UsbInterface,
            val syntheticId: Int,
            val pollRateHz: Int,
        )

        private data class ClaimedConn(
            val connection: UsbDeviceConnection,
            val pads: List<ClaimedPad>,
        )

        private sealed interface ClaimOutcome {
            data class Ok(
                val syntheticId: Int,
            ) : ClaimOutcome

            data class Fail(
                val reason: DirectClaimFailure,
                val frameworkStolen: Boolean,
            ) : ClaimOutcome
        }

        fun install() {
            if (installed) return
            installed = true
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                    addAction(ACTION_USB_PERMISSION)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registry.devices
                .onEach { devices -> withContext(Dispatchers.Main) { syncFromRegistry(devices) } }
                .launchIn(scope)
            scanExistingUsb()
        }

        // Re-scan on foreground; idempotent (onUsbPresent only creates absent entries).
        fun reconcileForeground() = scanExistingUsb()

        private fun scanExistingUsb() {
            for (device in usbManager?.deviceList?.values.orEmpty()) onUsbPresent(device)
        }

        fun tryDirectMode(
            vendorId: Int,
            productId: Int,
        ) = setPathChoice(vendorId, productId, PathChoice.Direct)

        fun setPathChoice(
            vendorId: Int,
            productId: Int,
            choice: PathChoice,
        ) {
            pathPrefs.setChoice(vendorId, productId, choice)
            val device =
                usbManager?.deviceList?.values?.firstOrNull { it.vendorId == vendorId && it.productId == productId }
            if (device != null) onUsbPresent(device)
            applyEvent(vidPidKey(vendorId, productId), UsbEvent.Choose(choice, userInitiated = true))
        }

        // The streaming notification's Stop action: release every held claim so each pad gets its
        // device-side restore and is handed back before the service exits. Main thread only.
        fun releaseAllDirect() {
            for ((key, c) in _controllers.value) {
                if (c.phase == UsbPhase.Direct) applyEvent(key, UsbEvent.Choose(PathChoice.Standard, userInitiated = true))
            }
        }

        private inner class UsbPermissionReceiver : BroadcastReceiver() {
            override fun onReceive(
                ctx: Context,
                intent: Intent,
            ) {
                val device = deviceFromIntent(intent) ?: return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> onUsbPresent(device)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> onUsbGone(device)
                    ACTION_USB_PERMISSION ->
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            onUsbPresent(device)
                            applyEvent(vidPidKey(device.vendorId, device.productId), UsbEvent.PermissionGranted)
                        } else {
                            applyEvent(vidPidKey(device.vendorId, device.productId), UsbEvent.PermissionDenied)
                        }
                }
            }
        }

        private val receiver = UsbPermissionReceiver()

        private fun onUsbPresent(device: UsbDevice) {
            if (!isGamepadShaped(device)) return
            val key = vidPidKey(device.vendorId, device.productId)
            usbDevices[key] = device
            noteEndpointFacts(device)

            val existing = _controllers.value[key]
            if (existing == null) {
                trackNewController(key, device)
                return
            }
            val permissionJustArrived = usbManager?.hasPermission(device) == true && !existing.hasPermission
            if (permissionJustArrived) applyEvent(key, UsbEvent.PermissionGranted)
        }

        private fun noteEndpointFacts(device: UsbDevice) {
            val pair = findInterruptInPair(device) ?: return
            val (intf, epIn, epOut) = pair
            descriptors.note(
                device.vendorId,
                device.productId,
                UsbEndpointFacts(
                    intervalRaw = epIn.interval,
                    maxPacketSize = epIn.maxPacketSize,
                    pollRateHz = computeUsbPollRateHz(epIn.interval, epIn.maxPacketSize),
                    highSpeed = epIn.maxPacketSize > FULL_SPEED_MAX_PACKET,
                    interfaceClass = intf.interfaceClass,
                    hasOutEndpoint = epOut != null,
                ),
            )
        }

        private fun trackNewController(
            key: Int,
            device: UsbDevice,
        ) {
            val vid = device.vendorId
            val pid = device.productId
            val fwId = liveFrameworkFor(registry.devices.value, vid, pid)
            _controllers.update { it + (key to newUsbController(device, fwId)) }
            lastFrameworkId[key] = fwId
            // Drive toward the resolved path automatically (not user-initiated).
            applyEvent(key, UsbEvent.Choose(resolvePath(vid, pid), userInitiated = false))
        }

        private fun newUsbController(
            device: UsbDevice,
            frameworkId: Int?,
        ): UsbController {
            val vid = device.vendorId
            val pid = device.productId
            return UsbController(
                vendorId = vid,
                productId = pid,
                name = friendlyName(device),
                phase = UsbPhase.Routed,
                usbPresent = true,
                frameworkId = frameworkId,
                hasPermission = usbManager?.hasPermission(device) == true,
                desired = resolvePath(vid, pid),
                frameworkExpected = native.modelExpectsFrameworkGamepad(vid, pid),
                receiver = receiverSlotsOf(vid, pid) != null,
            ).withCapturedBinding(frameworkId)
        }

        private fun onUsbGone(device: UsbDevice) {
            val key = vidPidKey(device.vendorId, device.productId)
            applyEvent(key, UsbEvent.UsbUnplugged)
            usbDevices.remove(key)
            timeouts.remove(key)?.cancel()
            lastFrameworkId.remove(key)
            claimedConns.remove(key)?.let { runCatching { it.connection.close() } }
            // A fresh plug-in of this model should re-evaluate Direct rather than inherit a stale failure.
            registry.clearDirectFailed(device.vendorId, device.productId)
        }

        private fun syncFromRegistry(devices: Map<Int, PhysicalGamepadRegistry.Device>) {
            // Framework presence deltas for tracked controllers.
            for ((key, c) in _controllers.value) {
                val fwId = liveFrameworkFor(devices, c.vendorId, c.productId)
                if (fwId != lastFrameworkId[key]) {
                    lastFrameworkId[key] = fwId
                    applyEvent(key, if (fwId != null) UsbEvent.FrameworkUp(fwId) else UsbEvent.FrameworkDown)
                }
            }
            // A framework gamepad for a USB-present model we aren't tracking yet (framework enumerated
            // before the USB broadcast landed): start tracking it.
            for (dev in devices.values) {
                if (dev.isUsbSynthetic || dev.vendorId == 0 || dev.productId == 0) continue
                if (vidPidKey(dev.vendorId, dev.productId) in _controllers.value) continue
                usbManager
                    ?.deviceList
                    ?.values
                    ?.firstOrNull { it.vendorId == dev.vendorId && it.productId == dev.productId }
                    ?.let { onUsbPresent(it) }
            }
        }

        private fun applyEvent(
            key: Int,
            event: UsbEvent,
        ) {
            val cur = _controllers.value[key] ?: return
            val (next, effects) = reduce(cur, event)
            _controllers.update { if (next == null) it - key else it + (key to next) }
            val ctx = next ?: cur
            for (fx in effects) execute(key, ctx, fx)
            // Once the wait resolves to any other phase, a still-pending timeout is stale. Read the live
            // phase rather than this event's immediate reduction so a nested transition into
            // AwaitingFramework during effect execution (a claim that fails after stealing the interface)
            // keeps the fresh timeout it just started instead of cancelling it.
            if (_controllers.value[key]?.phase != UsbPhase.AwaitingFramework) timeouts.remove(key)?.cancel()
        }

        // A flat effect dispatch: many arms, each trivial. The branch count trips the complexity rule.
        private fun execute(
            key: Int,
            c: UsbController,
            fx: UsbEffect,
        ) {
            when (fx) {
                UsbEffect.Claim -> runClaim(key)
                UsbEffect.Reclaim -> runReclaim(key, c)
                UsbEffect.Release -> releaseToFramework(key)
                UsbEffect.RequestPermission -> usbDevices[key]?.let { requestPermission(it) }
                is UsbEffect.BindFramework -> bindTo(fx.frameworkId, c.connId, c.type)
                is UsbEffect.RemoveSynthetic -> removeSynthetic(key, fx.syntheticId)
                UsbEffect.BeginHold -> registry.beginModelTransition(c.vendorId, c.productId)
                UsbEffect.EndHold -> registry.endModelTransition(c.vendorId, c.productId)
                UsbEffect.MarkNeedsReplug -> registry.markNeedsReplug(c.vendorId, c.productId)
                UsbEffect.MarkRestoreStuck -> registry.markRestoreStuck(c.vendorId, c.productId)
                UsbEffect.ClearRestoreStuck -> registry.clearRestoreStuck(c.vendorId, c.productId)
                UsbEffect.StartTimeout -> startTimeout(key)
                is UsbEffect.Notify -> notify(c, fx.notice)
                is UsbEffect.SetPref -> pathPrefs.setChoice(c.vendorId, c.productId, fx.choice)
                is UsbEffect.MarkFailure -> registry.markDirectFailed(c.vendorId, c.productId, fx.reason)
                UsbEffect.ClearFailure -> registry.clearDirectFailed(c.vendorId, c.productId)
            }
        }

        // Idempotent after a Release already detached; on a physical unplug it is the only path that
        // reaps the native pollers and their fds instead of stranding them.
        private fun removeSynthetic(
            key: Int,
            syntheticId: Int,
        ) {
            native.detachUsbDevice(syntheticId)
            claimedConns.remove(key)?.let { dropClaim(it, syntheticId) }
            registry.removeUsbSynthetic(syntheticId)
            unlistReceiver(key)
        }

        // The claim's other pads go with the one the FSM held: a receiver's other slots.
        private fun dropClaim(
            claimed: ClaimedConn,
            detached: Int,
        ) {
            claimed.pads
                .filter { it.syntheticId != detached }
                .forEach { native.detachUsbDevice(it.syntheticId) }
            runCatching { claimed.connection.close() }
        }

        private fun startTimeout(key: Int) {
            timeouts.remove(key)?.cancel()
            timeouts[key] =
                scope.launch {
                    delay(TRANSITION_TIMEOUT_MS)
                    withContext(Dispatchers.Main) {
                        timeouts.remove(key)
                        applyEvent(key, UsbEvent.Timeout)
                    }
                }
        }

        private fun runClaim(key: Int) {
            when (val outcome = usbDevices[key]?.let { doClaim(it) }) {
                is ClaimOutcome.Ok -> applyEvent(key, UsbEvent.ClaimSucceeded(outcome.syntheticId))
                is ClaimOutcome.Fail -> applyEvent(key, UsbEvent.ClaimFailed(outcome.reason, outcome.frameworkStolen))
                null -> applyEvent(key, UsbEvent.ClaimFailed(DirectClaimFailure.Busy, frameworkStolen = false))
            }
        }

        private fun runReclaim(
            key: Int,
            c: UsbController,
        ) {
            val oldPlaceholder = c.syntheticId
            val outcome = usbDevices[key]?.let { doClaim(it) }
            oldPlaceholder?.let { registry.removeUsbSynthetic(it) }
            when (outcome) {
                is ClaimOutcome.Ok -> {
                    bindTo(outcome.syntheticId, c.connId, c.type)
                    applyEvent(key, UsbEvent.ClaimSucceeded(outcome.syntheticId))
                }
                is ClaimOutcome.Fail -> applyEvent(key, UsbEvent.ClaimFailed(outcome.reason, outcome.frameworkStolen))
                null -> applyEvent(key, UsbEvent.ClaimFailed(DirectClaimFailure.Busy, frameworkStolen = false))
            }
        }

        // The open+claim step's answer: a connection that holds the interface, or why not.
        private sealed interface Opened {
            data class Ready(
                val connection: UsbDeviceConnection,
            ) : Opened

            data class Refused(
                val outcome: ClaimOutcome.Fail,
            ) : Opened
        }

        // The endpoints of an interface a claim targets, as claimTargetsOf picked them.
        private class ClaimTarget(
            val intf: UsbInterface,
            val epIn: UsbEndpoint,
            val epOut: UsbEndpoint?,
        )

        // Open + claim + native attach + register synthetic + initial bind. Reports the cause on failure
        // and whether the framework interface was stolen (so the FSM knows if it must wait for re-enum).
        private fun doClaim(device: UsbDevice): ClaimOutcome {
            val usb = usbManager ?: return ClaimOutcome.Fail(DirectClaimFailure.InitFailed, frameworkStolen = false)
            val targets = claimTargetsOf(device)
            if (targets.isEmpty()) return ClaimOutcome.Fail(DirectClaimFailure.InitFailed, frameworkStolen = false)
            // Read before the claim detaches the kernel driver and the framework device starts going.
            val routedFrameworkId =
                registry.devices.value.values
                    .firstOrNull {
                        !it.isUsbSynthetic && it.vendorId == device.vendorId && it.productId == device.productId
                    }?.id
            return when (val opened = openAndClaim(usb, device, targets.map { it.intf })) {
                is Opened.Refused -> opened.outcome
                is Opened.Ready -> attachClaimed(device, opened.connection, targets, routedFrameworkId)
            }
        }

        private fun openAndClaim(
            usb: UsbManager,
            device: UsbDevice,
            interfaces: List<UsbInterface>,
        ): Opened {
            val conn =
                try {
                    usb.openDevice(device)
                } catch (e: SecurityException) {
                    Log.w(TAG, "openDevice denied for ${device.vendorId.toHex4()}:${device.productId.toHex4()}", e)
                    return Opened.Refused(ClaimOutcome.Fail(DirectClaimFailure.PermissionDenied, frameworkStolen = false))
                } ?: return Opened.Refused(ClaimOutcome.Fail(DirectClaimFailure.Busy, frameworkStolen = false))
            if (!claimAll(conn, interfaces)) {
                conn.close()
                return Opened.Refused(ClaimOutcome.Fail(DirectClaimFailure.Busy, frameworkStolen = false))
            }
            return Opened.Ready(conn)
        }

        // All of them or none: a refused interface hands back the ones claimed before it.
        private fun claimAll(
            conn: UsbDeviceConnection,
            interfaces: List<UsbInterface>,
        ): Boolean {
            val claimed = interfaces.takeWhile { conn.claimInterface(it, true) }
            val allClaimed = claimed.size == interfaces.size
            if (!allClaimed) runCatching { claimed.forEach { conn.releaseInterface(it) } }
            return allClaimed
        }

        // The interfaces are ours now: the kernel HID driver has been detached (framework stolen).
        private fun attachClaimed(
            device: UsbDevice,
            conn: UsbDeviceConnection,
            targets: List<ClaimTarget>,
            routedFrameworkId: Int?,
        ): ClaimOutcome {
            val receiver = receiverSlotsOf(device.vendorId, device.productId) != null
            val pads = attachAll(device, conn, targets, receiver)
            if (pads == null) {
                runCatching {
                    targets.forEach { conn.releaseInterface(it.intf) }
                    conn.close()
                }
                return ClaimOutcome.Fail(DirectClaimFailure.InitFailed, frameworkStolen = true)
            }
            val key = vidPidKey(device.vendorId, device.productId)
            claimedConns[key] = ClaimedConn(conn, pads)
            if (receiver) listenOnSlots(key, pads) else listPad(device, pads.single(), routedFrameworkId)
            Log.i(TAG, "claimed ${device.vendorId.toHex4()}:${device.productId.toHex4()} → dev=${pads.map { it.syntheticId }}")
            return ClaimOutcome.Ok(pads.first().syntheticId)
        }

        // Each target under a synthetic id of its own, or none: a refused one detaches those before it.
        private fun attachAll(
            device: UsbDevice,
            conn: UsbDeviceConnection,
            targets: List<ClaimTarget>,
            receiver: Boolean,
        ): List<ClaimedPad>? {
            val pads =
                targets
                    .asSequence()
                    .map { attachTarget(device, conn, it, receiver) }
                    .takeWhile { it != null }
                    .filterNotNull()
                    .toList()
            val allAttached = pads.size == targets.size
            if (allAttached) return pads
            pads.forEach { native.detachUsbDevice(it.syntheticId) }
            return null
        }

        // Null when native refused the interface.
        private fun attachTarget(
            device: UsbDevice,
            conn: UsbDeviceConnection,
            target: ClaimTarget,
            receiverSlot: Boolean,
        ): ClaimedPad? {
            val intf = target.intf
            val epIn = target.epIn
            val synthetic =
                native.attachUsbDevice(
                    fd = conn.fileDescriptor,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    claim =
                        UsbInterfaceClaim(
                            interfaceNumber = intf.id,
                            endpointIn = epIn.address,
                            endpointInMaxPacket = epIn.maxPacketSize,
                            endpointOut = target.epOut?.address ?: 0,
                            interfaceClass = intf.interfaceClass,
                            interfaceSubclass = intf.interfaceSubclass,
                            interfaceProtocol = intf.interfaceProtocol,
                            receiverSlot = receiverSlot,
                        ),
                )
            val refused = synthetic == 0
            if (refused) return null
            return ClaimedPad(intf, synthetic, computeUsbPollRateHz(epIn.interval, epIn.maxPacketSize))
        }

        // A pad of its own is on the list from its claim, bound where its framework twin was.
        private fun listPad(
            device: UsbDevice,
            pad: ClaimedPad,
            routedFrameworkId: Int?,
        ) {
            registry.addUsbSynthetic(
                deviceId = pad.syntheticId,
                name = friendlyName(device),
                descriptor = routedFrameworkId?.let { registry.devices.value[it]?.descriptor }.orEmpty(),
                hasGyro = native.modelHasImu(device.vendorId, device.productId),
                pollRateHz = pad.pollRateHz,
                vendorId = device.vendorId,
                productId = device.productId,
            )
            connectionHubProvider.get().bindClaimedSynthetic(routedFrameworkId?.toString(), pad.syntheticId.toString())
            routedFrameworkId?.let { registry.forgetSupersededFramework(it) }
        }

        // A receiver's slots are claimed to listen: each is a pad only while its controller is connected.
        private fun listenOnSlots(
            key: Int,
            pads: List<ClaimedPad>,
        ) {
            receiverSlots[key] = pads.map { ReceiverSlot(it.intf.id, it.syntheticId, it.pollRateHz) }
            followSlotLinksOf(key)
            watchSlotLinks()
        }

        private fun watchSlotLinks() {
            if (slotLinks != null) return
            slotLinks =
                scope.launch {
                    while (true) {
                        delay(RECEIVER_LINK_POLL_MS)
                        withContext(Dispatchers.Main) { receiverSlots.keys.toList().forEach(::followSlotLinksOf) }
                    }
                }
        }

        private fun followSlotLinksOf(key: Int) {
            val slots = receiverSlots[key] ?: return
            val device = usbDevices[key] ?: return
            val linked = slots.map { it.syntheticId }.filter(native::isUsbPadLinked).toSet()
            val follow = followSlotLinks(slots, linked, heldBindingsOf(slots))
            if (follow.effects.isEmpty()) return
            receiverSlots[key] = follow.slots
            follow.effects.forEach { applySlotEffect(device, it) }
            publishSlotPads(key, follow.slots)
        }

        private fun heldBindingsOf(slots: List<ReceiverSlot>): Map<Int, HeldBinding> {
            val hub = connectionHubProvider.get()
            val bound = hub.bindings.value
            val types = hub.satTypes.value
            return slots
                .filter { it.listed }
                .mapNotNull { slot -> heldBindingOf(slot.syntheticId, bound, types) }
                .toMap()
        }

        private fun heldBindingOf(
            syntheticId: Int,
            bound: Map<String, String>,
            types: Map<Pair<String, String>, Int>,
        ): Pair<Int, HeldBinding>? {
            val slotId = syntheticId.toString()
            val connId = bound[slotId] ?: return null
            return syntheticId to HeldBinding(connId, types[connId to slotId])
        }

        private fun applySlotEffect(
            device: UsbDevice,
            effect: SlotEffect,
        ) {
            when (effect) {
                is SlotEffect.ListPad -> listSlotPad(device, effect.slot)
                is SlotEffect.UnlistPad -> unlistSlotPad(device, effect.slot)
            }
        }

        private fun listSlotPad(
            device: UsbDevice,
            slot: ReceiverSlot,
        ) {
            Log.i(TAG, "${modelOf(device)} slot ${slot.interfaceNumber} connected → dev=${slot.syntheticId}")
            registry.addUsbSynthetic(
                deviceId = slot.syntheticId,
                name = friendlyName(device),
                hasGyro = native.modelHasImu(device.vendorId, device.productId),
                pollRateHz = slot.pollRateHz,
                vendorId = device.vendorId,
                productId = device.productId,
            )
            bindSlotPad(slot)
        }

        // Bound as it left, or the way a new claim is.
        private fun bindSlotPad(slot: ReceiverSlot) {
            val held = slot.heldBinding
            if (held == null) {
                connectionHubProvider.get().bindClaimedSynthetic(null, slot.syntheticId.toString())
            } else {
                bindTo(slot.syntheticId, held.connectionId, held.type)
            }
        }

        private fun unlistSlotPad(
            device: UsbDevice,
            slot: ReceiverSlot,
        ) {
            Log.i(TAG, "${modelOf(device)} slot ${slot.interfaceNumber} left → dev=${slot.syntheticId}")
            registry.removeUsbSynthetic(slot.syntheticId)
        }

        private fun publishSlotPads(
            key: Int,
            slots: List<ReceiverSlot>,
        ) {
            val pads = slots.filter { it.listed }.map { it.syntheticId }
            _controllers.update { map -> map[key]?.let { map + (key to it.copy(slotPads = pads)) } ?: map }
        }

        // The receiver's claim is over: every slot's pad comes off the list.
        private fun unlistReceiver(key: Int) {
            val slots = receiverSlots.remove(key) ?: return
            slots.filter { it.listed }.forEach { registry.removeUsbSynthetic(it.syntheticId) }
            publishSlotPads(key, emptyList())
            if (receiverSlots.isNotEmpty()) return
            slotLinks?.cancel()
            slotLinks = null
        }

        // Hand the interfaces back to the framework. A pad of its own keeps its synthetic entry as a
        // held loader placeholder, so the slot stays visible while the framework device re-enumerates;
        // a receiver's slots, which re-enumerate as nothing, come off the list.
        private fun releaseToFramework(key: Int) {
            val claimed = claimedConns.remove(key) ?: return
            claimed.pads.forEach { native.detachUsbDevice(it.syntheticId) }
            runCatching {
                claimed.pads.forEach { claimed.connection.releaseInterface(it.intf) }
                claimed.connection.close()
            }
            val receiver = receiverSlots.containsKey(key)
            if (receiver) unlistReceiver(key) else holdReleasedPad(key, claimed.pads.single().syntheticId)
        }

        private fun holdReleasedPad(
            key: Int,
            syntheticId: Int,
        ) {
            // Capture the live binding so a return-to-Standard (or a rollback) preserves it.
            val hub = connectionHubProvider.get()
            val synthSlot = syntheticId.toString()
            val connId = hub.bindings.value[synthSlot]
            val type = connId?.let { hub.satTypes.value[it to synthSlot] }
            _controllers.update { map ->
                val c = map[key] ?: return@update map
                map + (key to c.copy(connId = connId, type = type))
            }
            registry.setUsbSyntheticTransitioning(syntheticId, true)
        }

        private fun bindTo(
            deviceId: Int,
            connId: String?,
            type: Int?,
        ) {
            connId ?: return
            // A restored binding re-registers as its remembered type; Xbox only when there was never a choice.
            connectionHubProvider.get().bind(deviceId.toString(), connId, type ?: CONTROLLER_TYPE_XBOX)
        }

        private fun notify(
            c: UsbController,
            notice: UsbNotice,
        ) {
            val name = c.name
            val titleRes =
                when (notice) {
                    UsbNotice.SwitchToDirectFailed -> R.string.direct_failed
                    UsbNotice.NeedsReplug -> R.string.direct_restore_failed
                    UsbNotice.RolledBackToDirect -> R.string.direct_revert_to_direct
                    UsbNotice.RestoreFailed -> R.string.direct_restore_failed
                }
            // The card carries the persistent reason; the banner echoes it so the cause is visible at a glance.
            val body = if (notice == UsbNotice.SwitchToDirectFailed) c.failure?.let { directFailureText(it) } else null
            notifications.warn(
                glyph = R.drawable.ic_gamepad,
                title = context.getString(titleRes, name),
                body = body,
                key = "direct-result:${c.vendorId.toHex4()}:${c.productId.toHex4()}",
            )
        }

        private fun directFailureText(reason: DirectClaimFailure): String =
            context.getString(
                when (reason) {
                    DirectClaimFailure.Busy -> R.string.path_reason_busy
                    DirectClaimFailure.InitFailed -> R.string.path_reason_init_failed
                    DirectClaimFailure.PermissionDenied -> R.string.path_reason_permission_denied
                    DirectClaimFailure.Dropped -> R.string.path_needs_replug
                },
            )

        private fun resolvePath(
            vendorId: Int,
            productId: Int,
        ): PathChoice =
            resolvePathChoice(
                stored = pathPrefs.choiceFor(vendorId, productId),
                isFastLaneModel = native.isKnownFastLaneModel(vendorId, productId),
                priorFailure = registry.directFailureFor(vendorId, productId),
            )

        private fun requestPermission(device: UsbDevice) {
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val flags = usbPermissionIntentFlags(Build.VERSION.SDK_INT)
            val pending = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
            scope.launch(Dispatchers.Main) { usbManager?.requestPermission(device, pending) }
        }

        // Capture the framework device's current binding so it survives a Standard→Direct→Standard trip.
        private fun UsbController.withCapturedBinding(frameworkId: Int?): UsbController {
            frameworkId ?: return this
            val hub = connectionHubProvider.get()
            val slot = frameworkId.toString()
            val connId = hub.bindings.value[slot] ?: return this
            return copy(connId = connId, type = hub.satTypes.value[connId to slot])
        }

        private fun friendlyName(device: UsbDevice): String =
            friendlyUsbName(
                knownModelName = native.lookupKnownModelName(device.vendorId, device.productId),
                deviceName = device::getDeviceName,
                productName = device::getProductName,
            )

        private fun isGamepadShaped(device: UsbDevice): Boolean = findInterruptInPair(device) != null

        private fun UsbInterface.facts(): UsbInterfaceFacts = UsbInterfaceFacts(interfaceClass, interfaceSubclass, interfaceProtocol)

        private fun interruptInOutOf(intf: UsbInterface): Pair<UsbEndpoint, UsbEndpoint?>? {
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
            }
            return epIn?.let { it to epOut }
        }

        // Every slot of a receiver; the one findInterruptInPair picks for any other model.
        private fun claimTargetsOf(device: UsbDevice): List<ClaimTarget> {
            val withEndpoints = interruptInPairs(device)
            val candidates = withEndpoints.map { UsbInterfaceCandidate(it.first.id, it.first.facts()) }
            return interfacesToClaim(device.vendorId, device.productId, candidates)
                .map { withEndpoints[it] }
                .map { (intf, epIn, epOut) -> ClaimTarget(intf, epIn, epOut) }
        }

        // Ranked, not first-match: a composite 360 pad's audio/security interfaces also carry an interrupt-IN.
        private fun findInterruptInPair(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? {
            val withEndpoints = interruptInPairs(device)
            val candidates = withEndpoints.map { UsbInterfaceCandidate(it.first.id, it.first.facts()) }
            val picked = pickGameInterface(device.vendorId, device.productId, candidates) ?: return null
            return withEndpoints[picked]
        }

        private fun interruptInPairs(device: UsbDevice): List<Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>> =
            (0 until device.interfaceCount)
                .map(device::getInterface)
                .mapNotNull { intf -> interruptInOutOf(intf)?.let { Triple(intf, it.first, it.second) } }

        private fun deviceFromIntent(intent: Intent): UsbDevice? =
            IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

        private fun Int.toHex4(): String = "%04x".format(this and 0xFFFF)

        private fun modelOf(device: UsbDevice): String = "${device.vendorId.toHex4()}:${device.productId.toHex4()}"

        private companion object {
            const val TAG = "UsbGamepadManager"
            const val ACTION_USB_PERMISSION = "com.tinkernorth.dish.USB_PERMISSION"
            const val TRANSITION_TIMEOUT_MS = 4000L
        }
    }

// An explicit stored pick always wins; absent one, auto-Direct only a verified fast-lane model
// that has not just failed to claim.
internal fun resolvePathChoice(
    stored: PathChoice?,
    isFastLaneModel: Boolean,
    priorFailure: DirectClaimFailure?,
): PathChoice = stored ?: if (isFastLaneModel && priorFailure == null) PathChoice.Direct else PathChoice.Standard

private const val FULL_SPEED_MAX_PACKET = 64
