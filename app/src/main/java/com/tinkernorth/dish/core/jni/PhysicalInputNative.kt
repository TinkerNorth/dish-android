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
        ): Boolean = ModelTableNative.isKnownFastLaneModel(vendorId, productId)

        fun modelHasImu(
            vendorId: Int,
            productId: Int,
        ): Boolean = ModelTableNative.modelHasImu(vendorId, productId)

        fun modelHasRumble(
            vendorId: Int,
            productId: Int,
        ): Boolean = ModelTableNative.modelHasRumble(vendorId, productId)

        fun modelHasLightbar(
            vendorId: Int,
            productId: Int,
        ): Boolean = ModelTableNative.modelHasLightbar(vendorId, productId)

        fun modelHasPlayerLeds(
            vendorId: Int,
            productId: Int,
        ): Boolean = ModelTableNative.modelHasPlayerLeds(vendorId, productId)

        fun modelHasTriggerEffects(
            vendorId: Int,
            productId: Int,
        ): Boolean = ModelTableNative.modelHasTriggerEffects(vendorId, productId)

        fun modelHasHapticLanes(
            vendorId: Int,
            productId: Int,
        ): Boolean = ModelTableNative.modelHasHapticLanes(vendorId, productId)

        fun modelHasTriggerRumble(
            vendorId: Int,
            productId: Int,
        ): Boolean = ModelTableNative.modelHasTriggerRumble(vendorId, productId)

        fun modelFrameworkRumbleUnreliable(
            vendorId: Int,
            productId: Int,
        ): Boolean = ModelTableNative.modelFrameworkRumbleUnreliable(vendorId, productId)

        fun modelHasTouchpad(
            vendorId: Int,
            productId: Int,
        ): Boolean = ModelTableNative.modelHasTouchpad(vendorId, productId)

        fun modelExpectsFrameworkGamepad(
            vendorId: Int,
            productId: Int,
        ): Boolean = ModelTableNative.modelExpectsFrameworkGamepad(vendorId, productId)

        fun lookupKnownModelName(
            vendorId: Int,
            productId: Int,
        ): String = ModelTableNative.lookupKnownModelName(vendorId, productId)

        fun setDeviceDeadzones(
            deviceId: Int,
            flatX: Float,
            flatY: Float,
            flatZ: Float,
            flatRZ: Float,
        ) {
            PhysicalSlotNative.setDeviceDeadzones(deviceId, flatX, flatY, flatZ, flatRZ)
        }

        fun setDeviceQuirk(
            deviceId: Int,
            quirk: Int,
        ) {
            PhysicalSlotNative.setDeviceQuirk(deviceId, quirk)
        }

        fun attachUsbDevice(
            fd: Int,
            vendorId: Int,
            productId: Int,
            claim: UsbInterfaceClaim,
        ): Int = UsbDirectNative.attachUsbDevice(fd, vendorId, productId, claim)

        fun detachUsbDevice(syntheticDeviceId: Int) {
            UsbDirectNative.detachUsbDevice(syntheticDeviceId)
        }

        fun sendUsbRumble(
            syntheticDeviceId: Int,
            strong: Int,
            weak: Int,
        ) {
            UsbDirectNative.sendUsbRumble(syntheticDeviceId, strong, weak)
        }

        fun sendUsbTriggerRumble(
            syntheticDeviceId: Int,
            leftMagnitude: Int,
            rightMagnitude: Int,
        ) {
            UsbDirectNative.sendUsbTriggerRumble(syntheticDeviceId, leftMagnitude, rightMagnitude)
        }

        fun sendUsbLightbar(
            syntheticDeviceId: Int,
            r: Int,
            g: Int,
            b: Int,
        ) {
            UsbDirectNative.sendUsbLightbar(syntheticDeviceId, r, g, b)
        }

        fun sendUsbPlayerLeds(
            syntheticDeviceId: Int,
            ledMask: Int,
        ) {
            UsbDirectNative.sendUsbPlayerLeds(syntheticDeviceId, ledMask)
        }

        fun sendUsbTriggerEffects(
            syntheticDeviceId: Int,
            blocks: ByteArray,
        ) {
            UsbDirectNative.sendUsbTriggerEffects(syntheticDeviceId, blocks)
        }

        fun sendUsbMicMuteLed(
            syntheticDeviceId: Int,
            state: Int,
        ) {
            UsbDirectNative.sendUsbMicMuteLed(syntheticDeviceId, state)
        }

        fun getDeviceUrbCount(deviceId: Int): Long = UsbDirectNative.getDeviceUrbCount(deviceId)

        fun getDirectPadBattery(deviceId: Int): Int = UsbDirectNative.getDirectPadBattery(deviceId)

        fun getDeviceMotionCount(deviceId: Int): Long = UsbDirectNative.getDeviceMotionCount(deviceId)

        fun getDeviceInputEventCount(deviceId: Int): Long = PhysicalSlotNative.getDeviceInputEventCount(deviceId)

        fun getDeviceUrbErrorCount(deviceId: Int): Long = UsbDirectNative.getDeviceUrbErrorCount(deviceId)

        fun deviceInfoJson(deviceId: Int): String = UsbDirectNative.deviceInfoJson(deviceId)

        fun deviceLatencyJson(deviceId: Int): String = UsbDirectNative.deviceLatencyJson(deviceId)

        // Opt-in latency benchmark (stage-1 USB-direct hot path + stage-2 heartbeat RTT).
        fun setHotPathBench(on: Boolean) = InstrumentationNative.setHotPathBench(on)

        fun hotPathBenchJson(reset: Boolean): String = InstrumentationNative.hotPathBenchJson(reset)

        fun setLatencyProbe(on: Boolean) = InstrumentationNative.setLatencyProbe(on)

        fun setInputInspection(on: Boolean) = InstrumentationNative.setInputInspection(on)

        fun deviceStateJson(deviceId: Int): String = PhysicalSlotNative.deviceStateJson(deviceId)
    }
