// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the host -> client events carried in the DECRYPTED control-stream
 * plaintext (Wolf control-specs.adoc). The plaintext begins with the control
 * header `[ptype u16 LE][plen u16 LE]` followed by the event body. Every field
 * is little-endian.
 *
 * Unknown types decode to [MoonlightEvent.Unknown] and short/malformed buffers
 * to null, so a forged or truncated packet degrades gracefully instead of
 * throwing on the receive thread (contract §control stream: ignore unknown
 * types).
 */
sealed interface MoonlightEvent {
    /** RUMBLE_DATA 0x010b {unused u32, ctrl u16, low u16, high u16}. */
    data class Rumble(
        val controllerNumber: Int,
        val lowFrequency: Int,
        val highFrequency: Int,
    ) : MoonlightEvent

    /** RUMBLE_TRIGGERS 0x5500 {ctrl u16, left u16, right u16}. */
    data class RumbleTriggers(
        val controllerNumber: Int,
        val left: Int,
        val right: Int,
    ) : MoonlightEvent

    /** MOTION_EVENT 0x5501 {ctrl u16, rate u16, type u8}: start sending motion. */
    data class MotionRequest(
        val controllerNumber: Int,
        val reportRateHz: Int,
        val motionType: Int,
    ) : MoonlightEvent

    /** RGB_LED 0x5502 {ctrl u16, r u8, g u8, b u8}. */
    data class RgbLed(
        val controllerNumber: Int,
        val red: Int,
        val green: Int,
        val blue: Int,
    ) : MoonlightEvent

    /** A recognized control type this path does not act on (e.g. PERIODIC_PING echo). */
    data class Unknown(
        val type: Int,
    ) : MoonlightEvent
}

object MoonlightEventDecoder {
    private const val HEADER_LEN = 4

    /**
     * Decode one control plaintext. Returns null when the buffer is too short to
     * even hold the header, or when a recognized type is present but its body is
     * truncated (a tampered length must not be trusted to index past the end).
     */
    fun decode(plaintext: ByteArray): MoonlightEvent? {
        if (plaintext.size < HEADER_LEN) return null
        val buf = ByteBuffer.wrap(plaintext).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf.short.toInt() and 0xFFFF
        // plen is advisory; we validate against the real remaining bytes so a
        // lying length can never drive an over-read.
        buf.short
        return when (type) {
            MoonlightControlProtocol.EVENT_RUMBLE_DATA -> decodeRumble(buf)
            MoonlightControlProtocol.EVENT_RUMBLE_TRIGGERS -> decodeTriggers(buf)
            MoonlightControlProtocol.EVENT_MOTION -> decodeMotion(buf)
            MoonlightControlProtocol.EVENT_RGB_LED -> decodeLed(buf)
            else -> MoonlightEvent.Unknown(type)
        }
    }

    private fun decodeRumble(buf: ByteBuffer): MoonlightEvent? {
        if (buf.remaining() < RUMBLE_BODY) return null
        buf.int // unused
        val ctrl = u16(buf)
        val low = u16(buf)
        val high = u16(buf)
        return MoonlightEvent.Rumble(ctrl, low, high)
    }

    private fun decodeTriggers(buf: ByteBuffer): MoonlightEvent? {
        if (buf.remaining() < TRIGGERS_BODY) return null
        val ctrl = u16(buf)
        val left = u16(buf)
        val right = u16(buf)
        return MoonlightEvent.RumbleTriggers(ctrl, left, right)
    }

    private fun decodeMotion(buf: ByteBuffer): MoonlightEvent? {
        if (buf.remaining() < MOTION_BODY) return null
        val ctrl = u16(buf)
        val rate = u16(buf)
        val motionType = buf.get().toInt() and 0xFF
        return MoonlightEvent.MotionRequest(ctrl, rate, motionType)
    }

    private fun decodeLed(buf: ByteBuffer): MoonlightEvent? {
        if (buf.remaining() < LED_BODY) return null
        val ctrl = u16(buf)
        val r = buf.get().toInt() and 0xFF
        val g = buf.get().toInt() and 0xFF
        val b = buf.get().toInt() and 0xFF
        return MoonlightEvent.RgbLed(ctrl, r, g, b)
    }

    private fun u16(buf: ByteBuffer): Int = buf.short.toInt() and 0xFFFF

    private const val RUMBLE_BODY = 10
    private const val TRIGGERS_BODY = 6
    private const val MOTION_BODY = 5
    private const val LED_BODY = 5
}
