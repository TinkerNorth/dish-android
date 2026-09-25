// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

class MoonlightControlSealTest {
    private val gcmKey = ByteArray(16) { 0x01.toByte() }

    /**
     * Seal one control-stream payload: returns tag(16) || ciphertext, keyed by
     * [gcmKey] (the 16-byte rikey) with the IV derived from [seq]. Matches
     * Wolf's ControlEncryptedPacket body layout (control-specs.adoc): the tag
     * precedes the ciphertext on the wire.
     */
    @Test
    fun controlSealAndOpen_RoundTrip() {
        val seq = 12345
        val plaintext = "Hello Moonlight".toByteArray()

        val sealed = controlSeal(gcmKey, seq, plaintext)
        val opened = controlOpen(gcmKey, seq, sealed)
        assertArrayEquals(plaintext, opened)
    }

    /**
     * The control-stream GCM IV: sixteen zero bytes with the LOW BYTE of [seq]
     * in byte 0, and nothing else.
     *
     * ONLY THE LOW BYTE, however wrong that looks. The host builds the same IV
     * with `std::array<std::uint8_t, 16> iv_data = {0}; iv_data[0] = seq;`
     * (Wolf control.hpp encrypt_packet and decrypt_packet), where assigning a
     * u32 into a u8 element drops the top three bytes. The packet header still
     * carries the full 32-bit sequence, so only the IV wraps. Writing all four
     * bytes here, as this used to, agrees with the host for the first 256
     * packets and disagrees forever after: a live Sunshine host accepted 256
     * sealed control packets and answered the 257th with "Failed to verify tag",
     * then ended the session. At two packets a second that is a session that
     * dies after about two minutes, every time, which is exactly why it hid
     * behind the faults that used to end the session in six.
     *
     * The IV therefore repeats every 256 packets on one session key. That is the
     * protocol's property and not a choice available to a client that wants to
     * interoperate. What limits it is that the key is the rikey, minted fresh
     * for every /launch and never reused across sessions.
     */
    @Test
    fun controlSealAndOpen_SeqWrapAround() {
        // Test seq 255
        val seq255 = 255
        val plaintext1 = "Packet 255".toByteArray()
        val sealed1 = controlSeal(gcmKey, seq255, plaintext1)
        val opened1 = controlOpen(gcmKey, seq255, sealed1)
        assertArrayEquals("Failed at 255", plaintext1, opened1)

        // Test seq 256 (should wrap low byte to 0)
        val seq256 = 256
        val plaintext2 = "Packet 256".toByteArray()
        val sealed2 = controlSeal(gcmKey, seq256, plaintext2)
        val opened2 = controlOpen(gcmKey, seq256, sealed2)
        assertArrayEquals("Failed at 256", plaintext2, opened2)
    }

    /**
     * Verifies that a packet sealed with one key cannot be opened with another.
     */
    @Test
    fun controlSealAndOpen_DifferentKeys() {
        val key1 = ByteArray(16) { 0x01.toByte() }
        val key2 = ByteArray(16) { 0x02.toByte() }
        val seq = 100
        val plaintext = "Secret Data".toByteArray()

        val sealed1 = controlSeal(key1, seq, plaintext)

        // Trying to open with key2 should fail
        try {
            controlOpen(key2, seq, sealed1)
            fail("Should have failed due to key mismatch")
        } catch (e: AEADBadTagException) {
            // Success
        }
    }

    /**
     * Verifies that a packet sealed with one sequence number cannot be
     * opened with a different sequence number (IV mismatch).
     */
    @Test
    fun verifySequenceIntegrity() {
        val seq1 = 100
        val seq2 = 101
        val plaintext = "Integrity Test".toByteArray()

        val sealed1 = controlSeal(gcmKey, seq1, plaintext)

        try {
            // Attempting to open seq1's payload with seq2's identifier
            controlOpen(gcmKey, seq2, sealed1)
            fail("Should have failed due to sequence mismatch")
        } catch (e: AEADBadTagException) {
            // Success - integrity maintained
        }
    }
}
