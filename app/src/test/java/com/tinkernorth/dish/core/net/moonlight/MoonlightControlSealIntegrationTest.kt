// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

class MoonlightControlSealIntegrationTest {
    private val gcmKey = ByteArray(16) { 0x0A.toByte() }

    /**
     * Simulates a high-volume sequence of control packets to verify the
     * IV wrap-around logic (which occurs every 256 packets).
     */
    @Test
    fun simulateControlPacketStream() {
        val totalPackets = 300
        val sealedPackets = mutableListOf<ByteArray>()
        val sequences = mutableListOf<Int>()

        // 1. Seal 300 consecutive packets
        for (i in 0 until totalPackets) {
            val seq = i
            val plaintext = "Packet $i".toByteArray()
            val sealed = controlSeal(gcmKey, seq, plaintext)

            sealedPackets.add(sealed)
            sequences.add(seq)
        }

        // 2. Verify all packets can be opened correctly in sequence
        for (i in 0 until totalPackets) {
            val seq = sequences[i]
            val sealed = sealedPackets[i]
            val plaintext = "Packet $i".toByteArray()

            val opened = controlOpen(gcmKey, seq, sealed)
            assertArrayEquals("Failed to open packet $i", plaintext, opened)
        }

        // 3. Verify wrap-around specifically at the boundary
        // Packet 255 and 256
        val seq255 = 255
        val seq256 = 256

        val sealed255 = controlSeal(gcmKey, seq255, "Packet 255".toByteArray())
        val opened255 = controlOpen(gcmKey, seq255, sealed255)
        assertArrayEquals("Failed at 255", "Packet 255".toByteArray(), opened255)

        val sealed256 = controlSeal(gcmKey, seq256, "Packet 256".toByteArray())
        val opened256 = controlOpen(gcmKey, seq256, sealed256)
        assertArrayEquals("Failed at 256", "Packet 256".toByteArray(), opened256)
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

    /**
     * Verifies that a packet sealed with one key cannot be opened with another.
     */
    @Test
    fun verifyKeyIsolation() {
        val key1 = ByteArray(16) { 0x01.toByte() }
        val key2 = ByteArray(16) { 0x02.toByte() }
        val seq = 500
        val plaintext = "Key Isolation".toByteArray()

        val sealed1 = controlSeal(key1, seq, plaintext)

        try {
            controlOpen(key2, seq, sealed1)
            fail("Should have failed due to key mismatch")
        } catch (e: AEADBadTagException) {
            // Success
        }
    }
}
