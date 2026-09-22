// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.jni

import com.tinkernorth.dish.source.connection.TouchpadReport
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ControllerRepository
    @Inject
    constructor() {
        fun openSocket(
            ip: String,
            port: Int,
        ): Int = SessionNative.openSocket(ip, port)

        fun closeSocket(handle: Int) {
            SessionNative.closeSocket(handle)
        }

        fun setConnectionParams(
            handle: Int,
            token: ByteArray,
            key: ByteArray,
            protocolVersion: Int,
        ) {
            SessionNative.setConnectionParams(handle, token, key, protocolVersion)
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
            SlotReportNative.sendReport(handle, index, buttons, lt, rt, lx, ly, rx, ry)
        }

        fun sendMicFrame(
            handle: Int,
            index: Int,
            pcmMono: ShortArray,
        ): Boolean = SlotReportNative.sendMicFrame(handle, index, pcmMono)

        fun getVigemAvailable(handle: Int): Int = SessionNative.getVigemAvailable(handle)

        fun getActiveControllerCount(handle: Int): Int = SessionNative.getActiveControllerCount(handle)

        fun getServerEpoch(handle: Int): Int = SessionNative.getServerEpoch(handle)

        fun getActiveBitmap(handle: Int): Int = SessionNative.getActiveBitmap(handle)

        fun getSessionCloseReason(handle: Int): Int = SessionNative.getSessionCloseReason(handle)

        fun getSendCounter(handle: Int): Long = SessionNative.getSendCounter(handle)

        fun sessionStatsJson(handle: Int): String = SessionNative.sessionStatsJson(handle)

        fun getSlotSendCount(
            handle: Int,
            controllerIndex: Int,
        ): Long = SlotReportNative.getSlotSendCount(handle, controllerIndex)

        fun getSlotMotionCount(
            handle: Int,
            controllerIndex: Int,
        ): Long = SlotReportNative.getSlotMotionCount(handle, controllerIndex)

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
            SlotReportNative.sendMotion(
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
            SlotReportNative.sendBattery(handle, index, level, status)
        }

        fun sendTouchpad(
            handle: Int,
            index: Int,
            report: TouchpadReport,
        ) {
            SlotReportNative.sendTouchpad(
                handle,
                index,
                report.finger0Active,
                report.finger1Active,
                report.buttonPressed,
                report.rightPressed,
                report.middlePressed,
                report.finger0TrackingId,
                report.finger0X,
                report.finger0Y,
                report.finger1TrackingId,
                report.finger1X,
                report.finger1Y,
                report.eventTimeMs,
                report.scrollDelta,
            )
        }

        fun startHeartbeat(handle: Int) {
            SessionNative.startHeartbeat(handle)
        }

        fun stopHeartbeat(handle: Int) {
            SessionNative.stopHeartbeat(handle)
        }

        fun isConnectionAlive(handle: Int): Boolean = SessionNative.isConnectionAlive(handle)

        fun receiveAck(handle: Int): Int = SessionNative.receiveAck(handle)
    }
