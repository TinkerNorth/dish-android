// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MoonlightHotSealerTest {
    private val key = hexToBytes("edf04a215c4fbea20934120c8480d855")

    @Test
    fun `hot sealer output equals the reference encoder plus packet framing`() {
        val sealer = MoonlightHotSealer(key)
        val reference = MoonlightControlPacket(key)
        for (seq in 0..3) {
            val buttons = if (seq % 2 == 0) BTN_A else BTN_B
            val hot = sealer.sealControllerMulti(0, 1, buttons, 0, 0, 0, 0, 0, 0)
            val plaintext = controllerMulti(0, 1, buttons, 0, 0, 0, 0, 0, 0)
            val expected = reference.sealWithSeq(seq, plaintext)
            assertEquals("seq $seq", bytesToHex(expected), bytesToHex(hot))
        }
    }

    @Test
    fun `sealed packets round-trip through the receiver and advance seq`() {
        val sealer = MoonlightHotSealer(key)
        val receiver = MoonlightControlPacket(key)
        val first = sealer.sealControllerMulti(0, 1, BTN_X, 10, 20, 0, 0, 0, 0)
        assertEquals(1, sealer.nextSeq)
        val decoded = receiver.open(first)!!
        val event = decodeMoonlightEvent(decoded)
        // CONTROLLER_MULTI is an INPUT_DATA type the decoder classifies as Unknown (host does not send it back);
        // the point is the seal decrypts cleanly and the plaintext matches the encoder.
        assertEquals(MoonlightEvent.Unknown(CTRL_INPUT_DATA), event)
        assertEquals(
            bytesToHex(controllerMulti(0, 1, BTN_X, 10, 20, 0, 0, 0, 0)),
            bytesToHex(decoded),
        )
    }

    /**
     * The 257th packet of a session is where the control stream used to die: the
     * IV wraps at 256 on the host and the hot path has to wrap with it. Two
     * packets a second made that a session that ended after about two minutes.
     */
    @Test
    fun `the hot path keeps opening past the 256-packet IV wrap`() {
        val key = ByteArray(16) { it.toByte() }
        val sealer = MoonlightHotSealer(key)
        val receiver = MoonlightControlPacket(key)
        var last = ByteArray(0)
        repeat(PAST_THE_WRAP) {
            last = sealer.sealControllerMulti(0, 1, BTN_A, 0, 0, 0, 0, 0, 0)
        }
        assertEquals(PAST_THE_WRAP, sealer.nextSeq)
        assertEquals(
            bytesToHex(controllerMulti(0, 1, BTN_A, 0, 0, 0, 0, 0, 0)),
            bytesToHex(receiver.open(last)!!),
        )
    }

    /**
     * The periodic ping rides the same seq as the hot path, and it is the packet
     * that actually reaches 256 on an idle session. It has to survive the wrap
     * too.
     */
    @Test
    fun `the periodic ping keeps opening past the wrap`() {
        val key = ByteArray(16) { it.toByte() }
        val sealer = MoonlightHotSealer(key)
        val receiver = MoonlightControlPacket(key)
        var last = ByteArray(0)
        repeat(PAST_THE_WRAP) {
            last = sealer.seal(periodicPing())
        }
        assertEquals(PAST_THE_WRAP, sealer.nextSeq)
        assertEquals(bytesToHex(periodicPing()), bytesToHex(receiver.open(last)!!))
    }

    @Test
    fun `the hot path and the cold seal share one sequence`() {
        val sealer = MoonlightHotSealer(key)
        val receiver = MoonlightControlPacket(key)
        val hot = sealer.sealControllerMulti(0, 1, BTN_A, 0, 0, 0, 0, 0, 0)
        val cold = sealer.seal(periodicPing())
        val hotAgain = sealer.sealControllerMulti(0, 1, BTN_B, 0, 0, 0, 0, 0, 0)
        assertEquals(listOf(0, 1, 2), listOf(hot, cold, hotAgain).map(::seqOf))
        assertEquals(bytesToHex(controllerMulti(0, 1, BTN_A, 0, 0, 0, 0, 0, 0)), bytesToHex(receiver.open(hot)!!))
        assertEquals(bytesToHex(periodicPing()), bytesToHex(receiver.open(cold)!!))
        assertEquals(bytesToHex(controllerMulti(0, 1, BTN_B, 0, 0, 0, 0, 0, 0)), bytesToHex(receiver.open(hotAgain)!!))
    }

    // [type u16][len u16][seq u32]: the seq follows the four-byte frame header, little-endian.
    private fun seqOf(packet: ByteArray): Int = ByteBuffer.wrap(packet, FRAME_HEADER_LEN, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int

    private companion object {
        const val PAST_THE_WRAP = 257
        const val FRAME_HEADER_LEN = 4
    }
}
