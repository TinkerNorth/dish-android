// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class TofuPinningTest {
    @Test
    fun `no prior pin trusts first use`() {
        assertEquals(TofuVerdict.TRUST_FIRST_USE, tofuVerdict(stored = null, presented = "aabb"))
    }

    @Test
    fun `equal fingerprint is a match`() {
        assertEquals(TofuVerdict.MATCH, tofuVerdict(stored = "aabb", presented = "aabb"))
    }

    @Test
    fun `match is case-insensitive on hex`() {
        assertEquals(TofuVerdict.MATCH, tofuVerdict(stored = "AABBcc", presented = "aabbCC"))
    }

    @Test
    fun `different fingerprint is a mismatch`() {
        assertEquals(TofuVerdict.MISMATCH, tofuVerdict(stored = "aabb", presented = "ccdd"))
    }

    @Test
    fun `empty stored string is not treated as absent and can mismatch`() {
        // Only null means "never pinned"; an empty stored value is a present, non-matching pin.
        assertEquals(TofuVerdict.MISMATCH, tofuVerdict(stored = "", presented = "aabb"))
    }

    @Test
    fun `sha256 of empty array matches the known vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256FingerprintHex(ByteArray(0)),
        )
    }

    @Test
    fun `sha256 of abc matches the known vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256FingerprintHex(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())),
        )
    }

    @Test
    fun `sha256 output is lowercase and 64 hex chars`() {
        val hex = sha256FingerprintHex(byteArrayOf(0x00, 0x7F, 0xFF.toByte()))
        assertEquals(64, hex.length)
        assertEquals(hex.lowercase(), hex)
    }
}
