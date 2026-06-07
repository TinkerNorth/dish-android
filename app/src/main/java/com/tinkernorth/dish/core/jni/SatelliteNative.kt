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

    external fun controllerAdd(
        handle: Int,
        controllerIndex: Int,
        capabilities: Int,
    )

    external fun controllerRemove(
        handle: Int,
        controllerIndex: Int,
    )

    external fun sendControllerType(
        handle: Int,
        controllerIndex: Int,
        controllerType: Int,
    )

    external fun sendControllerCapsUpdate(
        handle: Int,
        controllerIndex: Int,
        capabilities: Int,
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

    // Packed int32: [31:16]=requestType, [15:8]=ctrlIdx, [7:0]=resultCode; -1 = no ACK.
    external fun getLastControllerAck(handle: Int): Int

    external fun resetControllerAck(handle: Int)

    // Bits mirror satellite ACK_MOTION_FLAG_*: 0x01 = type supports IMU, 0x02 = sink created.
    // -1 sentinel: no ACK seen, or pre-extension satellite (collapses to "unknown").
    external fun getLastControllerMotionFlags(handle: Int): Int

    external fun getVigemAvailable(handle: Int): Int

    external fun getActiveControllerCount(handle: Int): Int

    // Non-blocking, 500ms timeout; call from background.
    external fun receiveAck(handle: Int)

    // BLOCKING — call on Dispatchers.IO. Returns JSON array of beacon objects.
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

    external fun lookupKnownModelName(
        vendorId: Int,
        productId: Int,
    ): String

    external fun getDeviceUrbCount(deviceId: Int): Long

    // Flat parameter list (not packed) so each axis stays primitive Float — no per-event allocation.
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
