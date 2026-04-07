package com.tinkernorth.dish

import android.os.CountDownTimer
import android.view.InputDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages the set of physical controllers: detection, virtual-controller
 * lifecycle on the server, and disconnect-countdown logic.
 *
 * No UI code — notifies via [onControllersChanged].
 */
class ControllerManager(
    private val server: ServerConnectionManager,
    private val scope: CoroutineScope,
) {
    private val controllers = mutableMapOf<Int, ControllerEntry>()
    private var nextControllerIndex = 0

    val entries: Collection<ControllerEntry> get() = controllers.values
    val hasActiveControllers: Boolean
        get() = controllers.values.any { it.cardState == ControllerCardState.ACTIVE }

    /** Fired after any add / remove / state change. */
    var onControllersChanged: (() -> Unit)? = null

    /** Fired when all controllers are gone and we should disconnect. */
    var onAllControllersGone: (() -> Unit)? = null

    // ── InputDeviceListener delegation ────────────────────────────────────

    fun onDeviceAdded(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId) ?: return
        if ((dev.sources and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) return

        val existing = controllers[deviceId]
        if (existing != null && existing.cardState == ControllerCardState.DISCONNECTING) {
            existing.countdownTimer?.cancel()
            existing.countdownTimer = null
            existing.cardState = ControllerCardState.ACTIVE
            onControllersChanged?.invoke()
            return
        }
        addController(deviceId, dev.name)
    }

    fun onDeviceRemoved(deviceId: Int) {
        val entry = controllers[deviceId] ?: return
        if (entry.cardState == ControllerCardState.ACTIVE && entry.vigemActive) {
            startDisconnectCountdown(entry)
        } else {
            removeController(deviceId)
        }
    }

    fun onDeviceChanged(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId)
        val stillGamepad = dev != null &&
            (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        val entry = controllers[deviceId] ?: return
        if (!stillGamepad) {
            if (entry.cardState == ControllerCardState.ACTIVE && entry.vigemActive) {
                startDisconnectCountdown(entry)
            } else {
                removeController(deviceId)
            }
        }
    }

    fun scanExistingGamepads() {
        InputDevice.getDeviceIds().forEach { id ->
            val dev = InputDevice.getDevice(id) ?: return@forEach
            if ((dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
                addController(id, dev.name)
            }
        }
    }

    // ── Controller lifecycle ──────────────────────────────────────────────

    fun addController(androidDeviceId: Int, name: String) {
        if (controllers.containsKey(androidDeviceId)) return
        if (controllers.size >= MAX_CONTROLLERS) return
        val index = nextControllerIndex++
        val entry = ControllerEntry(
            androidDeviceId = androidDeviceId, name = name, controllerIndex = index,
        )
        if (server.isConnected) entry.cardState = ControllerCardState.ADDING
        controllers[androidDeviceId] = entry
        onControllersChanged?.invoke()
        if (server.isConnected) {
            sendControllerAdd(entry)
        } else if (entry.cardState == ControllerCardState.NEED_SERVER) {
            server.startDiscovery()
        }
    }

    fun removeController(androidDeviceId: Int) {
        val entry = controllers.remove(androidDeviceId) ?: return
        entry.countdownTimer?.cancel()
        if (entry.vigemActive) {
            SatelliteNative.controllerRemove(entry.controllerIndex)
            entry.vigemActive = false
        }
        onControllersChanged?.invoke()
    }

    /** Called by ServerConnectionManager when connection is established. */
    fun onServerConnected() {
        controllers.values.forEach { entry ->
            entry.cardState = ControllerCardState.ADDING
            sendControllerAdd(entry)
        }
        onControllersChanged?.invoke()
    }

    /** Called when the server connection drops. */
    fun onServerDisconnected() {
        controllers.values.forEach { entry ->
            entry.countdownTimer?.cancel(); entry.countdownTimer = null
            if (entry.vigemActive) {
                SatelliteNative.controllerRemove(entry.controllerIndex)
                entry.vigemActive = false
            }
            entry.cardState = ControllerCardState.NEED_SERVER
        }
        onControllersChanged?.invoke()
    }

    /** Remove all virtual controllers (for disconnect-all). */
    fun destroyAll() {
        controllers.values.forEach { entry ->
            entry.countdownTimer?.cancel()
            if (entry.vigemActive) {
                SatelliteNative.controllerRemove(entry.controllerIndex)
                entry.vigemActive = false
            }
        }
    }

    /** Return the first active controller index, or -1. */
    fun activeControllerIndex(): Int =
        controllers.values.firstOrNull {
            it.cardState == ControllerCardState.ACTIVE && it.vigemActive
        }?.controllerIndex ?: -1

    // ── Internal ──────────────────────────────────────────────────────────

    private fun sendControllerAdd(entry: ControllerEntry) {
        SatelliteNative.resetControllerAck()
        SatelliteNative.controllerAdd(entry.controllerIndex, 0x0003)
        scope.launch {
            val ackResult = withContext(Dispatchers.IO) {
                var ack = -1
                for (i in 0 until 30) {
                    ack = SatelliteNative.getLastControllerAck()
                    if (ack != -1) break
                    Thread.sleep(100)
                }
                ack
            }
            if (ackResult != -1 && (ackResult and 0xFF) == 0x00) {
                entry.vigemActive = true
                entry.cardState = ControllerCardState.ACTIVE
                SatelliteNative.sendControllerType(
                    entry.controllerIndex, entry.controllerType.wireValue,
                )
            } else {
                entry.cardState = ControllerCardState.ACTIVE
            }
            onControllersChanged?.invoke()
        }
    }

    private fun startDisconnectCountdown(entry: ControllerEntry) {
        entry.cardState = ControllerCardState.DISCONNECTING
        entry.countdownSeconds = COUNTDOWN_SECONDS
        onControllersChanged?.invoke()
        entry.countdownTimer = object : CountDownTimer(
            (COUNTDOWN_SECONDS * 1000).toLong(), 1000,
        ) {
            override fun onTick(millisUntilFinished: Long) {
                entry.countdownSeconds = (millisUntilFinished / 1000).toInt() + 1
                onControllersChanged?.invoke()
            }
            override fun onFinish() { finalizeDisconnect(entry) }
        }.start()
    }

    fun finalizeDisconnect(entry: ControllerEntry) {
        entry.countdownTimer?.cancel(); entry.countdownTimer = null
        if (entry.vigemActive) {
            SatelliteNative.controllerRemove(entry.controllerIndex)
            entry.vigemActive = false
        }
        controllers.remove(entry.androidDeviceId)
        onControllersChanged?.invoke()
        if (controllers.isEmpty() && server.isConnected) {
            onAllControllersGone?.invoke()
        }
    }

    fun setControllerType(entry: ControllerEntry, type: ControllerType) {
        entry.controllerType = type
        if (entry.vigemActive) {
            SatelliteNative.sendControllerType(entry.controllerIndex, type.wireValue)
        }
    }

    companion object {
        private const val MAX_CONTROLLERS = 16
        private const val COUNTDOWN_SECONDS = 10
    }
}
