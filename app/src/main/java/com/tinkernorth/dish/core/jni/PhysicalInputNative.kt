// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.jni

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhysicalInputNative
    @Inject
    constructor() {
        fun isKnownFastLaneModel(
            vendorId: Int,
            productId: Int,
        ): Boolean = SatelliteNative.isKnownFastLaneModel(vendorId, productId)

        fun modelHasImu(
            vendorId: Int,
            productId: Int,
        ): Boolean = SatelliteNative.modelHasImu(vendorId, productId)

        fun modelHasRumble(
            vendorId: Int,
            productId: Int,
        ): Boolean = SatelliteNative.modelHasRumble(vendorId, productId)

        fun lookupKnownModelName(
            vendorId: Int,
            productId: Int,
        ): String = SatelliteNative.lookupKnownModelName(vendorId, productId)

        fun setDeviceDeadzones(
            deviceId: Int,
            flatX: Float,
            flatY: Float,
            flatZ: Float,
            flatRZ: Float,
        ) {
            SatelliteNative.setDeviceDeadzones(deviceId, flatX, flatY, flatZ, flatRZ)
        }

        @Suppress("LongParameterList")
        fun attachUsbDevice(
            fd: Int,
            vendorId: Int,
            productId: Int,
            interfaceNumber: Int,
            endpointIn: Int,
            endpointInMaxPacket: Int,
            endpointOut: Int,
        ): Int =
            SatelliteNative.attachUsbDevice(
                fd = fd,
                vendorId = vendorId,
                productId = productId,
                interfaceNumber = interfaceNumber,
                endpointIn = endpointIn,
                endpointInMaxPacket = endpointInMaxPacket,
                endpointOut = endpointOut,
            )

        fun detachUsbDevice(syntheticDeviceId: Int) {
            SatelliteNative.detachUsbDevice(syntheticDeviceId)
        }

        fun sendUsbRumble(
            syntheticDeviceId: Int,
            strong: Int,
            weak: Int,
        ) {
            SatelliteNative.sendUsbRumble(syntheticDeviceId, strong, weak)
        }

        fun getDeviceUrbCount(deviceId: Int): Long = SatelliteNative.getDeviceUrbCount(deviceId)
    }
