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
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.core.jni.PhysicalInputNative
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
    ) {
        private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // Single source of truth for USB controller path state, keyed by vpKey (vid<<16|pid).
        private val _controllers = MutableStateFlow<Map<Int, UsbController>>(emptyMap())
        val controllers: StateFlow<Map<Int, UsbController>> = _controllers.asStateFlow()

        // Main-thread-confined bookkeeping the effects need.
        private val usbDevices = HashMap<Int, UsbDevice>()
        private val claimedConns = HashMap<Int, ClaimedConn>()
        private val lastFrameworkId = HashMap<Int, Int?>()
        private val timeouts = HashMap<Int, Job>()

        @Volatile private var installed = false

        private data class ClaimedConn(
            val connection: UsbDeviceConnection,
            val intf: UsbInterface,
            val syntheticId: Int,
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
            for (device in usbManager.deviceList.values) onUsbPresent(device)
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
                usbManager.deviceList.values.firstOrNull { it.vendorId == vendorId && it.productId == productId }
            if (device != null) onUsbPresent(device)
            applyEvent(vpk(vendorId, productId), UsbEvent.Choose(choice, userInitiated = true))
        }

        private val receiver =
            object : BroadcastReceiver() {
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
                                applyEvent(vpk(device.vendorId, device.productId), UsbEvent.PermissionGranted)
                            } else {
                                applyEvent(vpk(device.vendorId, device.productId), UsbEvent.PermissionDenied)
                            }
                    }
                }
            }

        // ── Signal sources → events ──────────────────────────────────────────

        private fun onUsbPresent(device: UsbDevice) {
            if (!isGamepadShaped(device)) return
            val vid = device.vendorId
            val pid = device.productId
            val key = vpk(vid, pid)
            usbDevices[key] = device
            val existing = _controllers.value[key]
            if (existing == null) {
                val fwId = liveFrameworkFor(registry.devices.value, vid, pid)
                _controllers.update {
                    it +
                        (
                            key to
                                UsbController(
                                    vendorId = vid,
                                    productId = pid,
                                    name = friendlyName(device),
                                    phase = UsbPhase.Routed,
                                    usbPresent = true,
                                    frameworkId = fwId,
                                    hasPermission = usbManager.hasPermission(device),
                                    desired = resolvePath(vid, pid),
                                ).withCapturedBinding(fwId)
                        )
                }
                lastFrameworkId[key] = fwId
                // Drive toward the resolved path automatically (not user-initiated).
                applyEvent(key, UsbEvent.Choose(resolvePath(vid, pid), userInitiated = false))
            } else if (usbManager.hasPermission(device) && !existing.hasPermission) {
                applyEvent(key, UsbEvent.PermissionGranted)
            }
        }

        private fun onUsbGone(device: UsbDevice) {
            val key = vpk(device.vendorId, device.productId)
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
                if (vpk(dev.vendorId, dev.productId) in _controllers.value) continue
                usbManager.deviceList.values
                    .firstOrNull { it.vendorId == dev.vendorId && it.productId == dev.productId }
                    ?.let { onUsbPresent(it) }
            }
        }

        // ── The reducer driver (main thread) ─────────────────────────────────

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
        @Suppress("CyclomaticComplexMethod")
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
                is UsbEffect.RemoveSynthetic -> {
                    claimedConns.remove(key)?.let { runCatching { it.connection.close() } }
                    registry.removeUsbSynthetic(fx.syntheticId)
                }
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

        // ── Effectors ────────────────────────────────────────────────────────

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

        // Open + claim + native attach + register synthetic + initial bind. Reports the cause on failure
        // and whether the framework interface was stolen (so the FSM knows if it must wait for re-enum).
        @Suppress("ReturnCount")
        private fun doClaim(device: UsbDevice): ClaimOutcome {
            val key = vpk(device.vendorId, device.productId)
            val (intf, epIn, epOut) =
                findInterruptInPair(device)
                    ?: return ClaimOutcome.Fail(DirectClaimFailure.InitFailed, frameworkStolen = false)
            val routedFrameworkId =
                registry.devices.value.values
                    .firstOrNull {
                        !it.isUsbSynthetic && it.vendorId == device.vendorId && it.productId == device.productId
                    }?.id
            val conn =
                try {
                    usbManager.openDevice(device)
                } catch (e: SecurityException) {
                    Log.w(TAG, "openDevice denied for ${device.vendorId.toHex4()}:${device.productId.toHex4()}", e)
                    return ClaimOutcome.Fail(DirectClaimFailure.PermissionDenied, frameworkStolen = false)
                } ?: return ClaimOutcome.Fail(DirectClaimFailure.Busy, frameworkStolen = false)
            if (!conn.claimInterface(intf, true)) {
                conn.close()
                return ClaimOutcome.Fail(DirectClaimFailure.Busy, frameworkStolen = false)
            }
            // The interface is ours now: the kernel HID driver has been detached (framework stolen).
            val synthetic =
                native.attachUsbDevice(
                    fd = conn.fileDescriptor,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    interfaceNumber = intf.id,
                    endpointIn = epIn.address,
                    endpointInMaxPacket = epIn.maxPacketSize,
                    endpointOut = epOut?.address ?: 0,
                    interfaceClass = intf.interfaceClass,
                    interfaceSubclass = intf.interfaceSubclass,
                    interfaceProtocol = intf.interfaceProtocol,
                )
            if (synthetic == 0) {
                runCatching {
                    conn.releaseInterface(intf)
                    conn.close()
                }
                return ClaimOutcome.Fail(DirectClaimFailure.InitFailed, frameworkStolen = true)
            }
            claimedConns[key] = ClaimedConn(conn, intf, synthetic)
            registry.addUsbSynthetic(
                deviceId = synthetic,
                name = friendlyName(device),
                hasGyro = native.modelHasImu(device.vendorId, device.productId),
                pollRateHz = computeUsbPollRateHz(epIn.interval, epIn.maxPacketSize),
                vendorId = device.vendorId,
                productId = device.productId,
            )
            connectionHubProvider.get().bindClaimedSynthetic(routedFrameworkId?.toString(), synthetic.toString())
            // Drop the framework we just stole now, rather than leaving it in the 5s disconnect grace where
            // it would collide with the framework that re-enumerates on a switch back to Standard and show a
            // second card for one controller.
            routedFrameworkId?.let { registry.forgetSupersededFramework(it) }
            Log.i(TAG, "claimed ${device.vendorId.toHex4()}:${device.productId.toHex4()} → dev=$synthetic")
            return ClaimOutcome.Ok(synthetic)
        }

        // Hand the interface back to the framework but keep the synthetic entry as a held loader
        // placeholder so the slot stays visible while the framework device re-enumerates.
        private fun releaseToFramework(key: Int) {
            val claimed = claimedConns.remove(key) ?: return
            native.detachUsbDevice(claimed.syntheticId)
            runCatching {
                claimed.connection.releaseInterface(claimed.intf)
                claimed.connection.close()
            }
            // Capture the live binding so a return-to-Standard (or a rollback) preserves it.
            val hub = connectionHubProvider.get()
            val synthSlot = claimed.syntheticId.toString()
            val connId = hub.bindings.value[synthSlot]
            val type = connId?.let { hub.satTypes.value[it to synthSlot] }
            _controllers.update { map ->
                val c = map[key] ?: return@update map
                map + (key to c.copy(connId = connId, type = type))
            }
            registry.setUsbSyntheticTransitioning(claimed.syntheticId, true)
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
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            val pending = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
            scope.launch(Dispatchers.Main) { usbManager.requestPermission(device, pending) }
        }

        // ── Pure-ish helpers ─────────────────────────────────────────────────

        private fun vpk(
            vendorId: Int,
            productId: Int,
        ): Int = (vendorId shl 16) or (productId and 0xFFFF)

        private fun liveFrameworkFor(
            devices: Map<Int, PhysicalGamepadRegistry.Device>,
            vendorId: Int,
            productId: Int,
        ): Int? =
            devices.values
                .firstOrNull {
                    !it.isUsbSynthetic &&
                        !it.transitioning &&
                        !it.needsReplug &&
                        !it.isDisconnecting &&
                        it.vendorId == vendorId &&
                        it.productId == productId
                }?.id

        // Capture the framework device's current binding so it survives a Standard→Direct→Standard trip.
        private fun UsbController.withCapturedBinding(frameworkId: Int?): UsbController {
            frameworkId ?: return this
            val hub = connectionHubProvider.get()
            val slot = frameworkId.toString()
            val connId = hub.bindings.value[slot] ?: return this
            return copy(connId = connId, type = hub.satTypes.value[connId to slot])
        }

        private fun friendlyName(device: UsbDevice): String {
            val known = native.lookupKnownModelName(device.vendorId, device.productId)
            if (known.isNotEmpty()) return known
            val product = device.productName
            return product?.takeIf { it.isNotBlank() } ?: device.deviceName
        }

        private fun isGamepadShaped(device: UsbDevice): Boolean = findInterruptInPair(device) != null

        private fun gameInterfaceRank(intf: UsbInterface): Int {
            val cls = intf.interfaceClass
            if (cls == UsbConstants.USB_CLASS_HID) return RANK_HID
            if (cls != UsbConstants.USB_CLASS_VENDOR_SPEC) return RANK_NONE
            val sub = intf.interfaceSubclass
            val proto = intf.interfaceProtocol
            return when {
                sub == XINPUT_SUBCLASS && proto == XINPUT_PROTOCOL -> RANK_XINPUT
                sub == GIP_SUBCLASS && proto == GIP_PROTOCOL -> RANK_GIP
                sub == XINPUT_SUBCLASS && (proto == XINPUT_AUX_PROTOCOL || proto == XINPUT_AUDIO_PROTOCOL) -> RANK_NONE
                sub == XINPUT_SECURITY_SUBCLASS -> RANK_NONE
                else -> RANK_VENDOR_FALLBACK
            }
        }

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

        // Ranked, not first-match: a composite 360 pad's audio/security interfaces also carry an interrupt-IN.
        private fun findInterruptInPair(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? {
            var best: Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? = null
            var bestRank = RANK_NONE
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                val rank = gameInterfaceRank(intf)
                if (rank <= bestRank) continue
                val pair = interruptInOutOf(intf) ?: continue
                best = Triple(intf, pair.first, pair.second)
                bestRank = rank
            }
            return best
        }

        private fun deviceFromIntent(intent: Intent): UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

        private fun Int.toHex4(): String = "%04x".format(this and 0xFFFF)

        private companion object {
            const val TAG = "UsbGamepadManager"
            const val ACTION_USB_PERMISSION = "com.tinkernorth.dish.USB_PERMISSION"
            const val TRANSITION_TIMEOUT_MS = 4000L

            const val XINPUT_SUBCLASS = 0x5D
            const val XINPUT_PROTOCOL = 0x01
            const val XINPUT_AUX_PROTOCOL = 0x02
            const val XINPUT_AUDIO_PROTOCOL = 0x03
            const val XINPUT_SECURITY_SUBCLASS = 0xFD
            const val GIP_SUBCLASS = 0x47
            const val GIP_PROTOCOL = 0xD0

            const val RANK_NONE = 0
            const val RANK_VENDOR_FALLBACK = 1
            const val RANK_HID = 2
            const val RANK_GIP = 3
            const val RANK_XINPUT = 4
        }
    }

// An explicit stored pick always wins; absent one, auto-Direct only a verified fast-lane model
// that has not just failed to claim.
internal fun resolvePathChoice(
    stored: PathChoice?,
    isFastLaneModel: Boolean,
    priorFailure: DirectClaimFailure?,
): PathChoice = stored ?: if (isFastLaneModel && priorFailure == null) PathChoice.Direct else PathChoice.Standard
