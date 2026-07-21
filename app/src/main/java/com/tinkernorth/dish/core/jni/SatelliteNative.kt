// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.jni

@Suppress("TooManyFunctions")
object SatelliteNative {
    init {
        System.loadLibrary("satellite")
    }

    external fun openSocket(
        ip: String,
        port: Int,
    ): Int

    external fun closeSocket(handle: Int)

    external fun setConnectionParams(
        handle: Int,
        token: ByteArray,
        key: ByteArray,
    )

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

    // Cemuhook DSU axes; gyro LSB = 2000/32767 deg/s, accel LSB = 4/32767 g.
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

    // level: 0..100 or 0xFF (unknown); status: BatteryValidator.BATTERY_STATUS_*.
    external fun sendBattery(
        handle: Int,
        controllerIndex: Int,
        level: Int,
        status: Int,
    )

    // Coords are normalized int16 (-32768..32767); receiver maps to active touchpad mode space.
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

    external fun startHeartbeat(handle: Int)

    external fun stopHeartbeat(handle: Int)

    external fun isConnectionAlive(handle: Int): Boolean

    // Session epoch from the latest enriched heartbeat ack; -1 until one lands.
    // Compared against the applied epoch from the last PUT/GET: mismatch means
    // the server's topology moved involuntarily and a REST reconcile is due.
    external fun getServerEpoch(handle: Int): Int

    // Active-controller bitmap (bit i = ctrlIdx i live) from the latest ack; -1 until one lands.
    external fun getActiveBitmap(handle: Int): Int

    // CLOSE_REASON_* from an authenticated session-close notify (0x000F); -1 = none.
    // Terminal: the session is gone server-side the moment this is non-negative.
    external fun getSessionCloseReason(handle: Int): Int

    // Send counter for the proactive re-key poll. Clamped at 0xFFFFFFFF past
    // exhaustion so it can never read below the re-PUT threshold again.
    external fun getSendCounter(handle: Int): Long

    external fun getVigemAvailable(handle: Int): Int

    external fun getActiveControllerCount(handle: Int): Int

    // Blocks ≤500ms (socket recv timeout); call from background. Returns
    // 1 = datagram consumed, 0 = timeout/rejected datagram, -1 = session or
    // socket dead (terminal: the caller's drain loop must stop).
    external fun receiveAck(handle: Int): Int

    // BLOCKING. Call on Dispatchers.IO. Returns JSON array of beacon objects.
    external fun discoverServers(
        discPort: Int,
        timeoutMs: Int,
    ): String

    external fun bindPhysicalSlotSatellite(
        deviceId: Int,
        sessionHandle: Int,
        controllerIndex: Int,
    )

    external fun bindPhysicalSlotBluetooth(
        deviceId: Int,
        connectionId: String,
    )

    external fun unbindPhysicalSlot(deviceId: Int)

    external fun clearAllPhysicalSlots()

    external fun forgetPhysicalDevice(deviceId: Int)

    // Without this, axes near rest stream tiny non-zero values upstream.
    external fun setDeviceDeadzones(
        deviceId: Int,
        flatX: Float,
        flatY: Float,
        flatZ: Float,
        flatRZ: Float,
    )

    external fun setDeviceQuirk(
        deviceId: Int,
        quirk: Int,
    )

    external fun releaseAllPhysicalReports()

    external fun processGamepadKeyEvent(
        deviceId: Int,
        source: Int,
        action: Int,
        keyCode: Int,
    ): Boolean

    external fun attachUsbDevice(
        fd: Int,
        vendorId: Int,
        productId: Int,
        interfaceNumber: Int,
        endpointIn: Int,
        endpointInMaxPacket: Int,
        endpointOut: Int,
    ): Int

    external fun detachUsbDevice(syntheticDeviceId: Int)

    // strong/weak are wire-scale 0..65535; native maps to each device's rumble output report.
    external fun sendUsbRumble(
        syntheticDeviceId: Int,
        strong: Int,
        weak: Int,
    )

    external fun isKnownFastLaneModel(
        vendorId: Int,
        productId: Int,
    ): Boolean

    external fun modelHasImu(
        vendorId: Int,
        productId: Int,
    ): Boolean

    external fun modelHasRumble(
        vendorId: Int,
        productId: Int,
    ): Boolean

    // Parser-level: true for the DS4/DualSense report families whose touch bytes the
    // USB-direct path parses and streams.
    external fun modelHasTouchpad(
        vendorId: Int,
        productId: Int,
    ): Boolean

    external fun lookupKnownModelName(
        vendorId: Int,
        productId: Int,
    ): String

    external fun getDeviceUrbCount(deviceId: Int): Long

    // Direct-mode MSG_MOTION sends for a synthetic device (post 125 Hz throttle).
    external fun getDeviceMotionCount(deviceId: Int): Long

    // Framework KeyEvent/MotionEvent updates applied for a routed device (USB Standard or Bluetooth).
    external fun getDeviceInputEventCount(deviceId: Int): Long

    // Opt-in latency benchmark: stage-1 USB-direct hot path (URB reap -> sendto) and
    // stage-2 heartbeat RTT. Off by default; one relaxed atomic load when disabled.
    external fun setHotPathBench(on: Boolean)

    // JSON snapshot of the benchmark window (microsecond percentiles). reset clears it.
    external fun hotPathBenchJson(reset: Boolean): String

    // Heartbeat probe mode: densifies pings for RTT sampling while the diagnostics
    // latency panel is open; steady-state cadence resumes when off.
    external fun setLatencyProbe(on: Boolean)

    // Inspector mirror gate: while on, USB-direct reports also copy motion/touch into the
    // snapshot state. Off costs one relaxed atomic load per report, like the bench markers.
    external fun setInputInspection(on: Boolean)

    // JSON snapshot of a device's wire-facing input state; empty string for an unknown device.
    external fun deviceStateJson(deviceId: Int): String

    // Flat parameter list (not packed) so each axis stays primitive Float: no per-event allocation.
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
