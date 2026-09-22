// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.jni

/**
 * The HID interface a USB Direct claim hands to native: the interface number the app
 * claimed, its interrupt endpoints, and the class triple usb_parsers.cpp classifies the
 * pad by.
 *
 * Read field by field from satellite_jni.cpp (attachUsbDevice), so the field names are
 * part of the JNI contract: proguard-rules.pro keeps them, and a rename here must be
 * mirrored there. [endpointOut] is 0 for a pad with no OUT endpoint.
 */
data class UsbInterfaceClaim(
    val interfaceNumber: Int,
    val endpointIn: Int,
    val endpointInMaxPacket: Int,
    val endpointOut: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
)
