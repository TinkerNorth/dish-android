// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.jni

/**
 * USB Direct: pads whose HID interface the app claims and reads itself (usb_host.cpp),
 * their feedback outputs, and the per-device counters of that path.
 */
object UsbDirectNative {
    init {
        System.loadLibrary("satellite")
    }

    // Returns the synthetic device id, or 0 when the attach failed. Cold path (one call per
    // plug), so the claim rides as an object that native reads field by field.
    external fun attachUsbDevice(
        fd: Int,
        vendorId: Int,
        productId: Int,
        claim: UsbInterfaceClaim,
    ): Int

    external fun detachUsbDevice(syntheticDeviceId: Int)

    // strong/weak are wire-scale 0..65535; native maps to each device's rumble output report.
    external fun sendUsbRumble(
        syntheticDeviceId: Int,
        strong: Int,
        weak: Int,
    )

    // left/right are wire-scale 0..65535 impulse-trigger magnitudes; only the GIP family has the
    // motors, everything else drops the write in native.
    external fun sendUsbTriggerRumble(
        syntheticDeviceId: Int,
        leftMagnitude: Int,
        rightMagnitude: Int,
    )

    external fun sendUsbLightbar(
        syntheticDeviceId: Int,
        r: Int,
        g: Int,
        b: Int,
    )

    // ledMask bit 0 = leftmost LED (DualSense bits 0..4, Switch Pro bits 0..3).
    external fun sendUsbPlayerLeds(
        syntheticDeviceId: Int,
        ledMask: Int,
    )

    // blocks = 22 bytes: the left then right raw 11-byte DualSense trigger-effect fields.
    external fun sendUsbTriggerEffects(
        syntheticDeviceId: Int,
        blocks: ByteArray,
    )

    // MSG_MIC_LED's own state: 0 off, 1 on, 2 pulse. The DualSense is the only family with the
    // lamp, so native drops the write for everything else; the same report also mutes the pad's
    // microphone amplifier, because the lamp and the amp are one thing on that pad.
    external fun sendUsbMicMuteLed(
        syntheticDeviceId: Int,
        state: Int,
    )

    external fun getDeviceUrbCount(deviceId: Int): Long

    external fun getDeviceUrbErrorCount(deviceId: Int): Long

    // A Direct-claimed pad's own charge as its last report carried it, packed level shl 8 or
    // status (usb_parsers.h PAD_BATTERY_*), or -1 when the device is gone or nothing has
    // carried a reading yet. See directPadSample.
    external fun getDirectPadBattery(deviceId: Int): Int

    // Direct-mode MSG_MOTION sends for a synthetic device (post 125 Hz throttle).
    external fun getDeviceMotionCount(deviceId: Int): Long

    // {"model","parser","init","reportBytes","endpointOut","lastUrbStatus"}; empty for an unknown device.
    external fun deviceInfoJson(deviceId: Int): String

    // Per-device stage-1 / URB-gap percentiles; n=0 blocks while the bench is off.
    external fun deviceLatencyJson(deviceId: Int): String
}
