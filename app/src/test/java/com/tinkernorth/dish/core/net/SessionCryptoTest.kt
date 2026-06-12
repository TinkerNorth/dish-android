// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pinned against the SAME interop vectors the satellite's
 * tests/test_windows_platform.cpp asserts — any drift on either end is a
 * cross-end protocol break, not a refactor.
 */
class SessionCryptoTest {
    private val pairingKey = ByteArray(32) { (it + 1).toByte() } // 01..20

    @Test
    fun `hmacProof matches the satellite's pinned vector`() {
        assertEquals(
            "05a035a10c55fdfe254c9df5df55a614ac128b123a5de225ea33b41f1d4eedde",
            SessionCrypto.hmacProof(pairingKey, "device-1"),
        )
    }

    @Test
    fun `hmacProof is device-bound and key-bound`() {
        val p1 = SessionCrypto.hmacProof(pairingKey, "device-1")
        assertNotEquals(p1, SessionCrypto.hmacProof(pairingKey, "device-2"))
        val otherKey = pairingKey.copyOf().also { it[0] = 0x7F }
        assertNotEquals(p1, SessionCrypto.hmacProof(otherKey, "device-1"))
        assertEquals(64, p1.length)
    }

    @Test
    fun `deriveSessionKey matches the satellite's pinned HKDF vector`() {
        val salt = byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(), 0xE5.toByte(), 0xF6.toByte(), 0x07, 0x18)
        val token = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val key = SessionCrypto.deriveSessionKey(pairingKey, salt, token)
        assertEquals(
            "946f704cf07e2dde5e9995a70d3d103753b4687a7ed9656bc6481b06065a8584",
            key.joinToString("") { "%02x".format(it.toInt() and 0xFF) },
        )
    }

    @Test
    fun `deriveSessionKey never returns the raw pairing key and varies with inputs`() {
        val salt = ByteArray(8) { 1 }
        val token = byteArrayOf(0, 0, 0, 1)
        val k1 = SessionCrypto.deriveSessionKey(pairingKey, salt, token)
        assertNotEquals(pairingKey.toList(), k1.toList())
        val k2 = SessionCrypto.deriveSessionKey(pairingKey, salt, byteArrayOf(0, 0, 0, 2))
        assertNotEquals(k1.toList(), k2.toList())
        val k3 = SessionCrypto.deriveSessionKey(pairingKey, ByteArray(8) { 2 }, token)
        assertNotEquals(k1.toList(), k3.toList())
    }

    @Test
    fun `deriveSessionKey rejects malformed inputs instead of deriving garbage`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionCrypto.deriveSessionKey(ByteArray(16), ByteArray(8), ByteArray(4))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionCrypto.deriveSessionKey(pairingKey, ByteArray(4), ByteArray(4))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionCrypto.deriveSessionKey(pairingKey, ByteArray(8), ByteArray(2))
        }
    }
}
