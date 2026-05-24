// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.jni

import com.tinkernorth.dish.core.net.SatelliteHttpClient
import com.tinkernorth.dish.hotpath.input.BluetoothGamepadBridge
import com.tinkernorth.dish.source.sensor.BatteryValidator
import com.tinkernorth.dish.source.sensor.MotionRateLimiter

/**
 * JNI bridge to satellite_jni.cpp.
 * Controller input is handled via direct JNI calls from Kotlin for lowest latency.
 * The network-blocking discovery function ([discoverServers]) must be called
 * from a background coroutine (Dispatchers.IO).
 *
 * The satellite's client-facing HTTPS API (connection management and PIN
 * pairing) is handled in pure Kotlin by [SatelliteHttpClient] — it speaks TLS,
 * which is far simpler to do with [javax.net.ssl.HttpsURLConnection] than by
 * adding an SSL library to the NDK build. The UDP gamepad wire and the LAN
 * broadcast beacon stay native here.
 *
 * The function count is naturally large because this is the *entire* native
 * surface for the satellite path; splitting it would just spread the JNI
 * coupling across multiple files.
 */
@Suppress("TooManyFunctions")
object SatelliteNative {
    init {
        System.loadLibrary("satellite")
    }

    // ── UDP Session (handle-based) ──────────────────────────────────────────
    // openSocket returns a positive session handle, or -1 on failure. All other
    // session-scoped calls take that handle so multiple satellites can run
    // side-by-side with independent sockets, tokens, counters and heartbeat
    // threads.

    /** Open a UDP socket aimed at ip:port. Returns handle >= 1 on success, -1 on failure. */
    external fun openSocket(
        ip: String,
        port: Int,
    ): Int

    /** Close the session identified by [handle] and stop its heartbeat. */
    external fun closeSocket(handle: Int)

    // ── Connection params ───────────────────────────────────────────────────

    /** Set the 4-byte token and 32-byte encryption key for [handle]. */
    external fun setConnectionParams(
        handle: Int,
        token: ByteArray,
        key: ByteArray,
    )

    // ── Encrypted gamepad data ──────────────────────────────────────────────

    /**
     * Send an encrypted gamepad report (0x0001) on [handle] for the given
     * controller index. Non-blocking sendto, called from main thread for
     * minimum latency.
     */
    external fun sendReport(
        handle: Int,
        controllerIndex: Int,
        wButtons: Int,
        bLeftTrigger: Int,
        bRightTrigger: Int,
        sThumbLX: Int,
        sThumbLY: Int,
        sThumbRX: Int,
        sThumbRY: Int,
    )

    // ── Controller management ───────────────────────────────────────────────

    /** Send 0x0004 Controller Add message on [handle]. */
    external fun controllerAdd(
        handle: Int,
        controllerIndex: Int,
        capabilities: Int,
    )

    /** Send 0x0005 Controller Remove message on [handle]. */
    external fun controllerRemove(
        handle: Int,
        controllerIndex: Int,
    )

    /** Send 0x0008 Controller Type message on [handle]. */
    external fun sendControllerType(
        handle: Int,
        controllerIndex: Int,
        controllerType: Int,
    )

    /**
     * Send 0x000E Controller Caps Update — pushes a fresh capability word
     * for an already-registered controller without unplugging it on the
     * receiver. Same payload shape as `controllerAdd`'s caps field
     * (ctrlIdx + caps BE16). Used when the dish's `MotionCapabilityComposer`
     * emits a new `toCapBits` value mid-session (e.g. the user toggles
     * motion off after the slot was registered with motion on). A
     * pre-extension receiver silently drops the packet — the dish-side
     * listener gate is the load-bearing correctness path; this wire
     * update is purely for the receiver's web-UI snapshot honesty.
     */
    external fun sendControllerCapsUpdate(
        handle: Int,
        controllerIndex: Int,
        capabilities: Int,
    )

    // ── Motion + battery (0x000A, 0x000B) ───────────────────────────────────

    /**
     * Send 0x000A Motion (IMU) message on [handle]. Axes follow the
     * Cemuhook DSU convention (right-handed; +X right, +Y up, +Z toward
     * player). Scale: gyro LSB = `2000/32767` deg/s; accel LSB = `4/32767` g.
     *
     * Rate-limiting (≤ 250 Hz default) and unit scaling are the caller's
     * responsibility — see [MotionRateLimiter] for the per-device gate.
     * `timestampDeltaUs` is microseconds since the previous emitted motion
     * packet for the same controller. 0 on the first packet.
     */
    @Suppress("LongParameterList")
    external fun sendMotion(
        handle: Int,
        controllerIndex: Int,
        gyroX: Short,
        gyroY: Short,
        gyroZ: Short,
        accelX: Short,
        accelY: Short,
        accelZ: Short,
        timestampDeltaUs: Int,
    )

    /**
     * Send 0x000B Battery message on [handle]. `level` is 0..100 inclusive
     * or `0xFF` (unknown). `status` is one of the `BATTERY_STATUS_*`
     * constants in [BatteryValidator]. The caller validates the tuple
     * ([BatteryValidator.publish]) before this call; identical samples are
     * **not** deduped because MSG_BATTERY is a fixed 30 s heartbeat.
     */
    external fun sendBattery(
        handle: Int,
        controllerIndex: Int,
        level: Int,
        status: Int,
    )

    // ── Touchpad (0x000C) ───────────────────────────────────────────────────

    /**
     * Send 0x000C Touchpad on [handle]. Coordinates are normalized int16
     * (`-32768..32767`); the receiver maps to whichever virtual device
     * coordinate space the active touchpad mode demands (DS4 1920×943 for
     * `Pad`, host screen pixels for `Mouse`). `trackingId` is per-finger
     * monotonic — bump it when a finger lifts and a new one lands so the
     * receiver knows not to interpolate cursor motion across the gap. The
     * pacing (≤250 Hz, deadline-based) is the caller's job, same as the
     * gamepad overlay's resend loop — this JNI call is one encode + one
     * encrypted `sendto`.
     */
    @Suppress("LongParameterList")
    external fun sendTouchpad(
        handle: Int,
        controllerIndex: Int,
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
    )

    // ── Heartbeat ───────────────────────────────────────────────────────────

    /** Start the heartbeat sender thread for [handle] (sends 0x0002 every 2s). */
    external fun startHeartbeat(handle: Int)

    /** Stop the heartbeat sender thread for [handle]. */
    external fun stopHeartbeat(handle: Int)

    /** Check if [handle]'s connection is still alive (no missed ACK timeout). */
    external fun isConnectionAlive(handle: Int): Boolean

    /**
     * Returns the last controller ACK for [handle] as a packed int32:
     *   bits 31-16: requestType (0x0004 or 0x0005)
     *   bits 15-8:  controllerIndex
     *   bits 7-0:   result code (0x00=OK, 0x01=VIGEM_UNAVAIL, etc.)
     * Returns -1 if no ACK has been received yet.
     */
    external fun getLastControllerAck(handle: Int): Int

    /** Reset the controller ACK state for [handle] to -1. */
    external fun resetControllerAck(handle: Int)

    /**
     * Motion-status byte from the most recent MSG_CONTROLLER_ACK on [handle],
     * or -1 when no extended ACK has been observed (no ACK at all, or a
     * pre-extension satellite that only sent the legacy 4-byte payload).
     * Bits mirror the satellite's `ACK_MOTION_FLAG_*` constants:
     *  - bit 0 (`0x01`): receiver's backend supports IMU for the slot's
     *    chosen controller type (`supportsMotionForType`).
     *  - bit 1 (`0x02`): receiver's backend successfully created the
     *    per-serial IMU sink at plug-in (`motionBackendOk`).
     *
     * The Kotlin caller (`SatelliteConnection.registerController`) reads
     * this immediately after a successful ACK and threads the result into
     * the per-slot motion-backend store; the `-1` sentinel collapses to
     * "unknown" so an old satellite is not misread as a permanent
     * "backend broken" state.
     */
    external fun getLastControllerMotionFlags(handle: Int): Int

    /** ViGEm availability from the latest 0x0007 Server Status on [handle]. */
    external fun getVigemAvailable(handle: Int): Int

    /** Active controller count from the latest 0x0007 Server Status on [handle]. */
    external fun getActiveControllerCount(handle: Int): Int

    /**
     * Receive and process one UDP packet on [handle] (heartbeat/controller ACK
     * or server status). Non-blocking with 500ms timeout. Call from background.
     */
    external fun receiveAck(handle: Int)

    // ── Discovery ───────────────────────────────────────────────────────────
    // The connection API (POST/DELETE /api/connections) and PIN pairing
    // (POST /api/pair) are HTTPS now and handled in Kotlin by
    // SatelliteHttpClient — they are no longer part of the JNI surface.

    /**
     * Listen on discPort for timeoutMs milliseconds, collecting Satellite beacon
     * broadcasts. Returns a JSON array of server objects (name, ip, udpPort, pairPort).
     * BLOCKING — call on Dispatchers.IO.
     */
    external fun discoverServers(
        discPort: Int,
        timeoutMs: Int,
    ): String

    // ── Physical-slot bindings for the native input pipeline ────────────────
    // The GameActivity native input thread owns per-device gamepad state and
    // routes reports based on these bindings. Push them whenever a slot's
    // reachability changes (slot bound/unbound, satellite connection
    // CONNECTED/disconnected, controller registered, BT host connected).

    /**
     * Bind [deviceId] to a SATELLITE slot identified by ([sessionHandle],
     * [controllerIndex]). Subsequent gamepad events from this device are
     * encrypted and sent inline by the native input thread.
     */
    external fun bindPhysicalSlotSatellite(
        deviceId: Int,
        sessionHandle: Int,
        controllerIndex: Int,
    )

    /**
     * Bind [deviceId] to a BLUETOOTH slot identified by [connectionId]. The
     * native input thread calls back into [BluetoothGamepadBridge] for each
     * report so the Java BT HID layer (Binder IPC) can do the actual send.
     */
    external fun bindPhysicalSlotBluetooth(
        deviceId: Int,
        connectionId: String,
    )

    /** Drop the binding for [deviceId]; subsequent events for it are ignored. */
    external fun unbindPhysicalSlot(deviceId: Int)

    /** Drop every physical-slot binding (used on shutdown / activity stop). */
    external fun clearAllPhysicalSlots()

    /**
     * Push the device's per-axis flat (deadzone) values, queried once from
     * [android.view.InputDevice.getMotionRange] when the device first appears.
     * Without this, axes near rest stream tiny non-zero values to the server.
     */
    external fun setDeviceDeadzones(
        deviceId: Int,
        flatX: Float,
        flatY: Float,
        flatZ: Float,
        flatRZ: Float,
    )

    /**
     * Force every bound device to release-all (zero buttons/axes/triggers) and
     * emit one report apiece. Used on window-focus-lost so no button stays
     * held server-side across focus loss.
     */
    external fun releaseAllPhysicalReports()

    // ── Activity-level dispatch entry points ────────────────────────────────
    // Called from MainActivity.dispatchKeyEvent / dispatchGenericMotionEvent
    // to intercept gamepad events *before* Android's input system can
    // synthesize fallback DPAD key presses from unconsumed joystick MOVEs.
    // Both functions return true if the event was a recognised gamepad
    // input; the caller should consume the event in that case.

    /** Process a gamepad KEY event at the Activity dispatch level. */
    external fun processGamepadKeyEvent(
        deviceId: Int,
        source: Int,
        action: Int,
        keyCode: Int,
    ): Boolean

    /**
     * Process a gamepad MOTION event at the Activity dispatch level.
     *
     * Parameter count is dictated by the JNI signature — each axis must be a
     * primitive Float on the wire (packing into an Axes data class would
     * allocate on every motion event, undoing the inline-dispatch latency
     * win).
     */
    @Suppress("LongParameterList")
    external fun processGamepadMotionEvent(
        deviceId: Int,
        source: Int,
        action: Int,
        x: Float,
        y: Float,
        z: Float,
        rz: Float,
        rx: Float,
        ry: Float,
        hatX: Float,
        hatY: Float,
        ltrigger: Float,
        rtrigger: Float,
        brake: Float,
        gas: Float,
    ): Boolean
}
