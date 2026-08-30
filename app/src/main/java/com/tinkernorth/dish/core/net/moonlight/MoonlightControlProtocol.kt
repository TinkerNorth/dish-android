// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

/**
 * Wire constants for the Moonlight control stream (Wolf
 * protocols/control-specs.adoc and protocols/input-data.adoc, cross-checked
 * against Wolf src/moonlight-protocol/moonlight/control.hpp). All values are
 * protocol constants and never localized.
 */
object MoonlightControlProtocol {
    // Encrypted control packet header type (control-specs.adoc): fixed 0x0001.
    const val PACKET_TYPE_ENCRYPTED = 0x0001

    // Decrypted control-message types (the first u16 LE of the plaintext).
    const val CTRL_TERMINATION = 0x0100
    const val CTRL_PERIODIC_PING = 0x0200
    const val CTRL_INPUT_DATA = 0x0206

    // Host -> client events carried inside the control stream.
    const val EVENT_RUMBLE_DATA = 0x010B
    const val EVENT_RUMBLE_TRIGGERS = 0x5500
    const val EVENT_MOTION = 0x5501
    const val EVENT_RGB_LED = 0x5502

    // INPUT_DATA sub-types (input-data.adoc). The wrapper's input-type field is
    // little-endian; these are the host-order values.
    const val INPUT_MOUSE_MOVE_REL = 0x00000007
    const val INPUT_CONTROLLER_MULTI = 0x0000000C
    const val INPUT_CONTROLLER_ARRIVAL = 0x55000004

    // Graceful termination reason (Wolf control.hpp TERMINATE_REASON_GRACEFULL,
    // big-endian on the wire).
    const val TERMINATE_REASON_GRACEFUL = 0x80030023.toInt()

    // Controller-type values for CONTROLLER_ARRIVAL (the emulated-device pick).
    const val CONTROLLER_TYPE_UNKNOWN = 0x00
    const val CONTROLLER_TYPE_XBOX = 0x01
    const val CONTROLLER_TYPE_PS = 0x02
    const val CONTROLLER_TYPE_NINTENDO = 0x03

    // CONTROLLER_ARRIVAL capability bits.
    const val CAP_ANALOG_TRIGGERS = 0x01
    const val CAP_RUMBLE = 0x02
    const val CAP_TRIGGER_RUMBLE = 0x04
    const val CAP_TOUCHPAD = 0x08
    const val CAP_ACCELEROMETER = 0x10
    const val CAP_GYRO = 0x20
    const val CAP_BATTERY = 0x40
    const val CAP_RGB_LED = 0x80

    // CONTROLLER_MULTI button flags (input-data.adoc). effective = flags | (flags2 << 16).
    const val BTN_DPAD_UP = 0x0001
    const val BTN_DPAD_DOWN = 0x0002
    const val BTN_DPAD_LEFT = 0x0004
    const val BTN_DPAD_RIGHT = 0x0008
    const val BTN_START = 0x0010
    const val BTN_BACK = 0x0020
    const val BTN_LEFT_STICK = 0x0040
    const val BTN_RIGHT_STICK = 0x0080
    const val BTN_LEFT_BUTTON = 0x0100
    const val BTN_RIGHT_BUTTON = 0x0200
    const val BTN_HOME = 0x0400
    const val BTN_A = 0x1000
    const val BTN_B = 0x2000
    const val BTN_X = 0x4000
    const val BTN_Y = 0x8000
    const val BTN_PADDLE1 = 0x010000
    const val BTN_PADDLE2 = 0x020000
    const val BTN_PADDLE3 = 0x040000
    const val BTN_PADDLE4 = 0x080000
    const val BTN_TOUCHPAD = 0x100000
    const val BTN_MISC = 0x200000

    // Fixed framing constants moonlight-common-c stamps into the CONTROLLER_MULTI
    // packet, confirmed byte-for-byte against Wolf's input-data.adoc network
    // fixture and testControl.cpp. They are not payload; the host ignores their
    // meaning but expects these exact values.
    const val MULTI_HEADER_B = 0x001A
    const val MULTI_MID_B = 0x0014
    const val MULTI_TAIL_A = 0x009C
    const val MULTI_TAIL_B = 0x0055

    // Motion event type (control-specs.adoc): the host asks the client to START
    // sending this stream at the given rate.
    const val MOTION_TYPE_ACCEL = 0x01
    const val MOTION_TYPE_GYRO = 0x02
}
