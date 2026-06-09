// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.jni

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ControllerRepository
    @Inject
    constructor() {
        fun openSocket(
            ip: String,
            port: Int,
        ): Int = SatelliteNative.openSocket(ip, port)

        fun closeSocket(handle: Int) {
            SatelliteNative.closeSocket(handle)
        }

        fun setConnectionParams(
            handle: Int,
            token: ByteArray,
            key: ByteArray,
        ) {
            SatelliteNative.setConnectionParams(handle, token, key)
        }

        fun sendReport(
            handle: Int,
            index: Int,
            buttons: Int,
            lt: Int,
            rt: Int,
            lx: Int,
            ly: Int,
            rx: Int,
            ry: Int,
        ) {
            SatelliteNative.sendReport(handle, index, buttons, lt, rt, lx, ly, rx, ry)
        }

        fun addController(
            handle: Int,
            index: Int,
            capabilities: Int,
            controllerType: Int,
        ) {
            SatelliteNative.controllerAdd(handle, index, capabilities, controllerType)
        }

        fun removeController(
            handle: Int,
            index: Int,
        ) {
            SatelliteNative.controllerRemove(handle, index)
        }

        fun getVigemAvailable(handle: Int): Int = SatelliteNative.getVigemAvailable(handle)

        fun getActiveControllerCount(handle: Int): Int = SatelliteNative.getActiveControllerCount(handle)

        fun getLastControllerAck(handle: Int): Int = SatelliteNative.getLastControllerAck(handle)

        fun getLastControllerMotionFlags(handle: Int): Int = SatelliteNative.getLastControllerMotionFlags(handle)

        fun sendControllerType(
            handle: Int,
            index: Int,
            type: Int,
        ) {
            SatelliteNative.sendControllerType(handle, index, type)
        }

        fun sendControllerCapsUpdate(
            handle: Int,
            index: Int,
            capabilities: Int,
        ) {
            SatelliteNative.sendControllerCapsUpdate(handle, index, capabilities)
        }

        @Suppress("LongParameterList")
        fun sendMotion(
            handle: Int,
            index: Int,
            gyroX: Short,
            gyroY: Short,
            gyroZ: Short,
            accelX: Short,
            accelY: Short,
            accelZ: Short,
            timestampDeltaUs: Int,
        ) {
            SatelliteNative.sendMotion(
                handle,
                index,
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
            handle: Int,
            index: Int,
            level: Int,
            status: Int,
        ) {
            SatelliteNative.sendBattery(handle, index, level, status)
        }

        @Suppress("LongParameterList")
        fun sendTouchpad(
            handle: Int,
            index: Int,
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
            SatelliteNative.sendTouchpad(
                handle,
                index,
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

        fun resetControllerAck(handle: Int) {
            SatelliteNative.resetControllerAck(handle)
        }

        fun startHeartbeat(handle: Int) {
            SatelliteNative.startHeartbeat(handle)
        }

        fun stopHeartbeat(handle: Int) {
            SatelliteNative.stopHeartbeat(handle)
        }

        fun isConnectionAlive(handle: Int): Boolean = SatelliteNative.isConnectionAlive(handle)

        fun receiveAck(handle: Int) {
            SatelliteNative.receiveAck(handle)
        }
    }
