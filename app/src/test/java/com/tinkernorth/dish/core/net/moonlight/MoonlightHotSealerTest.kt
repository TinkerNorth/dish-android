// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Test

class MoonlightHotSealerTest {
    private val key = hexToBytes("edf04a215c4fbea20934120c8480d855")

    @Test
    fun `hot sealer output equals the reference encoder plus packet framing`() {
        val sealer = MoonlightHotSealer(key)
        val reference = MoonlightControlPacket(key)
        for (seq in 0..3) {
            val buttons = if (seq % 2 == 0) MoonlightControlProtocol.BTN_A else MoonlightControlProtocol.BTN_B
            val hot = sealer.sealControllerMulti(0, 1, buttons, 0, 0, 0, 0, 0, 0)
            val plaintext = MoonlightInputEncoder.controllerMulti(0, 1, buttons, 0, 0, 0, 0, 0, 0)
            val expected = reference.sealWithSeq(seq, plaintext)
            assertEquals("seq $seq", bytesToHex(expected), bytesToHex(hot))
        }
    }

    @Test
    fun `sealed packets round-trip through the receiver and advance seq`() {
        val sealer = MoonlightHotSealer(key)
        val receiver = MoonlightControlPacket(key)
        val first = sealer.sealControllerMulti(0, 1, MoonlightControlProtocol.BTN_X, 10, 20, 0, 0, 0, 0)
        assertEquals(1, sealer.nextSeq)
        val decoded = receiver.open(first)!!
        val event = MoonlightEventDecoder.decode(decoded)
        // CONTROLLER_MULTI is an INPUT_DATA type the decoder classifies as Unknown (host does not send it back);
        // the point is the seal decrypts cleanly and the plaintext matches the encoder.
        assertEquals(MoonlightEvent.Unknown(MoonlightControlProtocol.CTRL_INPUT_DATA), event)
        assertEquals(
            bytesToHex(MoonlightInputEncoder.controllerMulti(0, 1, MoonlightControlProtocol.BTN_X, 10, 20, 0, 0, 0, 0)),
            bytesToHex(decoded),
        )
    }
}
