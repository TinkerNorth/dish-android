// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MoonlightEventDecoderTest {
    private fun plaintext(
        type: Int,
        body: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(4 + body.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(type.toShort())
            .putShort(body.size.toShort())
            .put(body)
            .array()

    private fun le(vararg shorts: Int): ByteArray {
        val buf = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        shorts.forEach { buf.putShort(it.toShort()) }
        return buf.array()
    }

    @Test
    fun `decodes RUMBLE_DATA`() {
        val body = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(0) // unused
        body.putShort(1) // ctrl
        body.putShort(0x1234) // low
        body.putShort(0x5678) // high
        val event = MoonlightEventDecoder.decode(plaintext(MoonlightControlProtocol.EVENT_RUMBLE_DATA, body.array()))
        assertEquals(MoonlightEvent.Rumble(1, 0x1234, 0x5678), event)
    }

    @Test
    fun `decodes RUMBLE_TRIGGERS`() {
        val event =
            MoonlightEventDecoder.decode(
                plaintext(MoonlightControlProtocol.EVENT_RUMBLE_TRIGGERS, le(2, 0x00FF, 0xFF00)),
            )
        assertEquals(MoonlightEvent.RumbleTriggers(2, 0x00FF, 0xFF00), event)
    }

    @Test
    fun `decodes MOTION_EVENT (start gyro at rate)`() {
        val body = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(0) // ctrl
        body.putShort(100) // rate
        body.put(MoonlightControlProtocol.MOTION_TYPE_GYRO.toByte())
        val event = MoonlightEventDecoder.decode(plaintext(MoonlightControlProtocol.EVENT_MOTION, body.array()))
        assertEquals(MoonlightEvent.MotionRequest(0, 100, MoonlightControlProtocol.MOTION_TYPE_GYRO), event)
    }

    @Test
    fun `decodes RGB_LED`() {
        val body = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(0) // ctrl
        body.put(0x10)
        body.put(0x20)
        body.put(0x30)
        val event = MoonlightEventDecoder.decode(plaintext(MoonlightControlProtocol.EVENT_RGB_LED, body.array()))
        assertEquals(MoonlightEvent.RgbLed(0, 0x10, 0x20, 0x30), event)
    }

    @Test
    fun `unknown control type decodes to Unknown, not a crash`() {
        val event = MoonlightEventDecoder.decode(plaintext(0x0200, ByteArray(4)))
        assertTrue(event is MoonlightEvent.Unknown)
        assertEquals(0x0200, (event as MoonlightEvent.Unknown).type)
    }

    @Test
    fun `too-short buffer returns null instead of over-reading`() {
        assertNull(MoonlightEventDecoder.decode(byteArrayOf(0x0B, 0x01))) // header only, truncated
        // Recognized type but a truncated body must not index past the end.
        val short = plaintext(MoonlightControlProtocol.EVENT_RGB_LED, byteArrayOf(0, 0)) // missing r,g,b
        assertNull(MoonlightEventDecoder.decode(short))
    }

    @Test
    fun `a lying length cannot drive an over-read`() {
        // plen claims a full rumble body but only 2 bytes follow.
        val bytes =
            ByteBuffer
                .allocate(6)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(MoonlightControlProtocol.EVENT_RUMBLE_DATA.toShort())
                .putShort(10) // lies
                .putShort(0)
                .array()
        assertNull(MoonlightEventDecoder.decode(bytes))
    }
}
