// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException
import java.lang.IllegalArgumentException

class MoonlightControlSealTest {

    private val gcmKey = ByteArray(16) { 0x01.toByte() }

    @Test
    fun controlSealAndOpen_RoundTrip() {
        val seq = 12345
        val plaintext = "Hello Moonlight".toByteArray()

        val sealed = controlSeal(gcmKey, seq, plaintext)
        val opened = controlOpen(gcmKey, seq, sealed)

        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun controlSealAndOpen_SeqWrapAround() {
        // Test seq 255
        val seq255 = 255
        val plaintext1 = "Packet 255".toByteArray()
        val sealed1 = controlSeal(gcmKey, seq255, plaintext1)
        val opened1 = controlOpen(gcmKey, seq255, sealed1)
        assertArrayEquals(plaintext1, opened1)

        // Test seq 256 (should wrap low byte to 0)
        val seq256 = 256
        val plaintext2 = "Packet 256".toByteArray()
        val sealed2 = controlSeal(gcmKey, seq256, plaintext2)
        val opened2 = controlOpen(gcmKey, seq256, sealed2)
        assertArrayEquals(plaintext2, opened2)
    }

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
            fail("Should have thrown AEADBadTagException")
        } catch (e: AEADBadTagException) {
            // Success
        }
    }

    @Test
    fun controlOpen_TamperedData_ThrowsException() {
        val seq = 100
        val plaintext = "Original".toByteArray()
        val sealed = controlSeal(gcmKey, seq, plaintext)

        // Tamper with the ciphertext part (last byte)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0xFF).toByte()

        try {
            controlOpen(gcmKey, seq, sealed)
            fail("Should have thrown AEADBadTagException")
        } catch (e: AEADBadTagException) {
            // Success
        }
    }

    @Test
    fun controlOpen_ShortPayload_ThrowsException() {
        val seq = 100
        val shortPayload = ByteArray(10) // Less than GCM_TAG_LEN (16)

        try {
            controlOpen(gcmKey, seq, shortPayload)
            fail("Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("shorter than GCM tag"))
        }
    }
}
