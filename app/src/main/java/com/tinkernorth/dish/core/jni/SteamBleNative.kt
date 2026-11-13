// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.jni

/**
 * Steam Controllers on Bluetooth LE as Direct pads (usb_host.cpp): their reports come down from
 * the app's own GATT client, and their writes go back up through SteamBleBridge. A pad's id comes
 * from the allocator the USB Direct pads draw from, so the two never collide.
 */
object SteamBleNative {
    init {
        System.loadLibrary("satellite")
    }

    // The pad's id, or 0 when the model is no Bluetooth LE Steam Controller or every Direct entry
    // is taken. Nothing is written to the pad yet.
    external fun attachBlePad(
        vendorId: Int,
        productId: Int,
        inputReportId: Int,
    ): Int

    // The writes that take the pad out of lizard mode, then its refresh and rumble worker; false
    // when a write was refused.
    external fun startBlePad(padId: Int): Boolean

    // restore gives the 2015 pad its stand-alone settings back first, over a link still up.
    external fun detachBlePad(
        padId: Int,
        restore: Boolean,
    )

    // One notification of the pad's input characteristic, as the framework handed it over: the
    // one JNI call on the Bluetooth LE input path.
    external fun processBleReport(
        padId: Int,
        value: ByteArray,
    )
}
