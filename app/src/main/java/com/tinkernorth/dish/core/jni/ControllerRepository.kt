// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.jni

import com.tinkernorth.dish.source.sensor.MotionRateLimiter
import com.tinkernorth.dish.source.sensor.PhoneMotionSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [SatelliteNative] that forwards every call with the
 * session [handle] returned from [openSocket]. Multiple sessions run in
 * parallel; state is kept inside the native library keyed by handle.
 */
@Singleton
class ControllerRepository
    @Inject
    constructor() {
        /** Returns a session handle >= 1 on success, or -1 on failure. */
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
        ) {
            SatelliteNative.controllerAdd(handle, index, capabilities)
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

        /**
         * Motion-status byte from the most recent MSG_CONTROLLER_ACK on
         * [handle], or -1 if no extended ACK was observed (pre-extension
         * satellite, or no ACK at all). See [SatelliteNative
         * .getLastControllerMotionFlags] for the bit definitions and the
         * `-1`-means-unknown semantics.
         */
        fun getLastControllerMotionFlags(handle: Int): Int = SatelliteNative.getLastControllerMotionFlags(handle)

        fun sendControllerType(
            handle: Int,
            index: Int,
            type: Int,
        ) {
            SatelliteNative.sendControllerType(handle, index, type)
        }

        /**
         * Send a fresh capability word for an already-registered
         * controller (0x000E). Reactive replacement for the snapshot the
         * dish-side took at addController time — used when the
         * [com.tinkernorth.dish.composer.MotionCapabilityComposer]'s
         * `toCapBits(slotId)` flips after registration (e.g. user toggle
         * change). The receiver overwrites `Controller::caps` in place;
         * no replug, no fresh ACK. See [SatelliteNative
         * .sendControllerCapsUpdate].
         */
        fun sendControllerCapsUpdate(
            handle: Int,
            index: Int,
            capabilities: Int,
        ) {
            SatelliteNative.sendControllerCapsUpdate(handle, index, capabilities)
        }

        /**
         * Forward an IMU sample (0x000A). Axes are pre-scaled to the wire
         * int16 form and rate-limited by the caller (see [PhoneMotionSource]
         * / [com.tinkernorth.dish.source.sensor.MotionRateLimiter]).
         */
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

        /** Forward a battery snapshot (0x000B). Deduped by the caller. */
        fun sendBattery(
            handle: Int,
            index: Int,
            level: Int,
            status: Int,
        ) {
            SatelliteNative.sendBattery(handle, index, level, status)
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
