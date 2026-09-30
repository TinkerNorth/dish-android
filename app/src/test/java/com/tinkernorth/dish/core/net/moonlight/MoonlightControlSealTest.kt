// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.hexToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Pinned against Wolf's captured control-stream vectors (tests/testControl.cpp).
 * Any drift here is a cross-end Moonlight protocol break, not a refactor.
 */
class MoonlightControlSealTest {
    private val rikey = hexToBytes("edf04a215c4fbea20934120c8480d855")
    private val anotherSessionsRikey = hexToBytes("00112233445566778899aabbccddeeff")

    @Test
    fun `controlSeal matches Wolf's captured GCM packet body`() {
        // testControl.cpp "30 bytes": key EDF0..D855, seq 0, payload 020302000000.
        val sealed = controlSeal(rikey, seq = 0, plaintext = hexToBytes("020302000000"))
        assertEquals(WOLF_TAG_HEX + WOLF_CIPHERTEXT_HEX, bytesToHex(sealed))
    }

    @Test
    fun `controlSeal matches Wolf's captured bodies at nonzero seqs`() {
        // testControl.cpp "29 bytes", "36 bytes" and "46 bytes": the packet body after the
        // eight-byte [type][len][seq] header. A nonzero seq is what puts the IV byte where the
        // host puts it.
        assertEquals(WOLF_SEQ_1_BODY_HEX, sealedHex(1, WOLF_SEQ_1_PAYLOAD_HEX))
        assertEquals(WOLF_SEQ_2_BODY_HEX, sealedHex(2, WOLF_SEQ_2_PAYLOAD_HEX))
        assertEquals(WOLF_SEQ_6_BODY_HEX, sealedHex(6, WOLF_SEQ_6_PAYLOAD_HEX))
    }

    @Test
    fun `a seq past the wrap seals as Wolf's host seals the seq sharing its low byte`() {
        // Wolf control.hpp: iv_data[0] = seq, a u32 stored into a u8, so the host seals seq 257
        // under the IV of seq 1.
        val pastTheWrap = IV_CYCLE + 1
        assertEquals(WOLF_SEQ_1_BODY_HEX, sealedHex(pastTheWrap, WOLF_SEQ_1_PAYLOAD_HEX))
    }

    @Test
    fun `controlSeal frames the tag ahead of the ciphertext`() {
        val sealed = bytesToHex(controlSeal(rikey, seq = 0, plaintext = hexToBytes("020302000000")))
        val tagHexLength = GCM_TAG_LEN * 2
        assertEquals(WOLF_TAG_HEX, sealed.substring(0, tagHexLength))
        assertEquals(WOLF_CIPHERTEXT_HEX, sealed.substring(tagHexLength))
    }

    @Test
    fun `controlSeal grows the payload by exactly the tag`() {
        val plaintext = "six bytes and more".toByteArray()
        val sealed = controlSeal(rikey, seq = 1, plaintext = plaintext)
        assertEquals(plaintext.size + GCM_TAG_LEN, sealed.size)
    }

    @Test
    fun `controlOpen reverses controlSeal across evolving seq`() {
        for (seq in intArrayOf(0, 1, 2, 6, 255, 256, 70000)) {
            val plaintext = "ping-$seq".toByteArray()
            val sealed = controlSeal(rikey, seq, plaintext)
            assertArrayEquals("seq $seq", plaintext, controlOpen(rikey, seq, sealed))
        }
    }

    @Test
    fun `controlOpen accepts a bare tag as an empty plaintext`() {
        val sealed = controlSeal(rikey, seq = 9, plaintext = ByteArray(0))
        assertEquals(GCM_TAG_LEN, sealed.size)
        assertEquals(0, controlOpen(rikey, seq = 9, tagThenCiphertext = sealed).size)
    }

    @Test
    fun `controlOpen rejects a payload shorter than the tag before touching the cipher`() {
        val shorterThanATag = ByteArray(GCM_TAG_LEN - 1)
        assertThrows(IllegalArgumentException::class.java) {
            controlOpen(rikey, seq = 0, tagThenCiphertext = shorterThanATag)
        }
    }

    @Test
    fun `controlOpen rejects a tampered ciphertext byte`() {
        val sealed = controlSeal(rikey, seq = 3, plaintext = "secret".toByteArray())
        val lastCiphertextByte = sealed.size - 1
        sealed[lastCiphertextByte] = flipLowBit(sealed[lastCiphertextByte])
        assertThrows(AEADBadTagException::class.java) {
            controlOpen(rikey, seq = 3, tagThenCiphertext = sealed)
        }
    }

    @Test
    fun `controlOpen rejects a tampered tag byte`() {
        val sealed = controlSeal(rikey, seq = 3, plaintext = "secret".toByteArray())
        sealed[0] = flipLowBit(sealed[0])
        assertThrows(AEADBadTagException::class.java) {
            controlOpen(rikey, seq = 3, tagThenCiphertext = sealed)
        }
    }

    @Test
    fun `controlOpen rejects the wrong seq (IV mismatch)`() {
        val sealed = controlSeal(rikey, seq = 4, plaintext = "hello".toByteArray())
        assertThrows(AEADBadTagException::class.java) {
            controlOpen(rikey, seq = 5, tagThenCiphertext = sealed)
        }
    }

    @Test
    fun `controlOpen rejects a packet sealed under another session's rikey`() {
        val sealed = controlSeal(rikey, seq = 4, plaintext = "hello".toByteArray())
        assertThrows(AEADBadTagException::class.java) {
            controlOpen(anotherSessionsRikey, seq = 4, tagThenCiphertext = sealed)
        }
    }

    /**
     * The host derives the IV from the LOW BYTE of the sequence number alone
     * (Wolf control.hpp assigns a u32 seq into a u8 array element). A client
     * that uses the whole 32 bits agrees for 256 packets and then diverges: a
     * live Sunshine host accepted 256 sealed control packets and answered the
     * 257th with "Failed to verify tag", ending the session about two minutes
     * in. These tests pin the wrap so that can never come back.
     */
    @Test
    fun `the control IV wraps every 256 packets, as the host's does`() {
        val plaintext = "keepalive".toByteArray()
        assertEquals(
            bytesToHex(controlSeal(rikey, seq = 0, plaintext = plaintext)),
            bytesToHex(controlSeal(rikey, seq = 256, plaintext = plaintext)),
        )
        assertEquals(
            bytesToHex(controlSeal(rikey, seq = 7, plaintext = plaintext)),
            bytesToHex(controlSeal(rikey, seq = 0x0A0B0C07, plaintext = plaintext)),
        )
    }

    @Test
    fun `a packet sealed past the wrap opens under the seq that shares its low byte`() {
        val sealed = controlSeal(rikey, seq = 256, plaintext = "wrapped".toByteArray())
        assertArrayEquals("wrapped".toByteArray(), controlOpen(rikey, seq = 0, tagThenCiphertext = sealed))
    }

    @Test
    fun `a packet sealed past the wrap still opens under its own seq`() {
        val sealed = controlSeal(rikey, seq = 257, plaintext = "past the wrap".toByteArray())
        assertEquals("past the wrap", String(controlOpen(rikey, seq = 257, tagThenCiphertext = sealed)))
    }

    @Test
    fun `the last seq before the wrap and the first after it seal differently`() {
        val plaintext = "edge".toByteArray()
        val at255 = bytesToHex(controlSeal(rikey, seq = 255, plaintext = plaintext))
        val at256 = bytesToHex(controlSeal(rikey, seq = 256, plaintext = plaintext))
        assertNotEquals(at255, at256)
    }

    private fun sealedHex(
        seq: Int,
        payloadHex: String,
    ): String = bytesToHex(controlSeal(rikey, seq, hexToBytes(payloadHex)))

    private fun flipLowBit(byte: Byte): Byte = (byte.toInt() xor 0x01).toByte()

    private companion object {
        const val WOLF_TAG_HEX = "bf0eb6da10e47c702ec8644eb87d9cf7"
        const val WOLF_CIPHERTEXT_HEX = "b6fac9ff75ca"
        const val WOLF_SEQ_1_PAYLOAD_HEX = "0703010000"
        const val WOLF_SEQ_1_BODY_HEX = "21dbb8dc0590af3a2b20bce5a347de31d366e5b9c5"
        const val WOLF_SEQ_2_PAYLOAD_HEX = "000208000400000000000000"
        const val WOLF_SEQ_2_BODY_HEX = "220722fbaded58a03f2e8898f0f1dcb7c93f6235590618e4186ad990"
        const val WOLF_SEQ_6_PAYLOAD_HEX = "060212000000000e05000000033400c00000059f0329"
        const val WOLF_SEQ_6_BODY_HEX = "5a4d999fb2542f85bdd39d99f77eb825254569d2c04e21241b5cec01bd3f93129718ecc1f153"
        const val IV_CYCLE = 256
    }
}
