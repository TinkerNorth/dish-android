// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Pinned against the captured encrypted packets in Wolf's testControl.cpp
 * ("Control AES Encryption"). The full framed packet must match byte-for-byte.
 */
class MoonlightControlPacketTest {
    private val key = hexToBytes("edf04a215c4fbea20934120c8480d855")

    @Test
    fun `seal matches Wolf's captured packets across evolving seq`() {
        val packet = MoonlightControlPacket(key)
        assertEquals(
            "01001a0000000000bf0eb6da10e47c702ec8644eb87d9cf7b6fac9ff75ca",
            bytesToHex(packet.sealWithSeq(0, hexToBytes("020302000000"))),
        )
        assertEquals(
            "010019000100000021dbb8dc0590af3a2b20bce5a347de31d366e5b9c5",
            bytesToHex(packet.sealWithSeq(1, hexToBytes("0703010000"))),
        )
        assertEquals(
            "0100200002000000220722fbaded58a03f2e8898f0f1dcb7c93f6235590618e4186ad990",
            bytesToHex(packet.sealWithSeq(2, hexToBytes("000208000400000000000000"))),
        )
        assertEquals(
            "01002a00060000005a4d999fb2542f85bdd39d99f77eb825254569d2c04e21241b5cec01bd3f93129718ecc1f153",
            bytesToHex(packet.sealWithSeq(6, hexToBytes("060212000000000e05000000033400c00000059f0329"))),
        )
    }

    @Test
    fun `auto-incrementing seal then open round-trips`() {
        val sender = MoonlightControlPacket(key)
        val receiver = MoonlightControlPacket(key)
        val p0 = sender.seal("first".toByteArray())
        val p1 = sender.seal("second".toByteArray())
        assertEquals("first", String(receiver.open(p0)!!))
        assertEquals("second", String(receiver.open(p1)!!))
    }

    @Test
    fun `open rejects a tampered packet`() {
        val packet = MoonlightControlPacket(key)
        val sealed = packet.sealWithSeq(9, "payload".toByteArray())
        sealed[sealed.size - 2] = (sealed[sealed.size - 2].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) { MoonlightControlPacket(key).open(sealed) }
    }

    @Test
    fun `open returns null on a short or wrong-type frame`() {
        val packet = MoonlightControlPacket(key)
        assertNull(packet.open(byteArrayOf(0x01, 0x00, 0x02)))
        // Right length but the packet type is not ENCRYPTED.
        val wrongType = byteArrayOf(0x02, 0x00, 0x1A, 0x00) + ByteArray(26)
        assertNull(packet.open(wrongType))
    }
}
