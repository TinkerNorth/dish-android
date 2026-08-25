// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Pinned against Wolf's captured session vectors (tests/testCrypto.cpp,
 * tests/testControl.cpp). Any drift here is a cross-end Moonlight protocol
 * break, not a refactor.
 */
class MoonlightCryptoTest {
    @Test
    fun `pairingKey matches Wolf's gen_aes_key vector`() {
        val salt = hexToBytes("ff5dc6eda99339a8a0793e216c4257c4")
        val key = MoonlightCrypto.pairingKey(salt, "5338")
        assertEquals("5ea186ffba663c75aec82187ce502647", bytesToHex(key))
    }

    @Test
    fun `AES-ECB round-trips and matches Wolf's decrypted challenge`() {
        val key = hexToBytes("5ea186ffba663c75aec82187ce502647")
        val challenge = hexToBytes("c05930ac81d7bd426344235436046018")
        val decrypted = MoonlightCrypto.aesEcbDecrypt(key, challenge)
        assertEquals("e3a915cccb4c60206077d7e9a12316a5", bytesToHex(decrypted))
        assertEquals(challenge.toList(), MoonlightCrypto.aesEcbEncrypt(key, decrypted).toList())
    }

    @Test
    fun `controlSeal matches Wolf's captured GCM packet body`() {
        // testControl.cpp "30 bytes": key EDF0..D855, seq 0, payload 020302000000.
        val key = hexToBytes("edf04a215c4fbea20934120c8480d855")
        val sealed = MoonlightCrypto.controlSeal(key, seq = 0, plaintext = hexToBytes("020302000000"))
        // tag(16) || ciphertext(6).
        assertEquals("bf0eb6da10e47c702ec8644eb87d9cf7b6fac9ff75ca", bytesToHex(sealed))
    }

    @Test
    fun `controlOpen reverses controlSeal across evolving seq`() {
        val key = hexToBytes("edf04a215c4fbea20934120c8480d855")
        for (seq in intArrayOf(0, 1, 2, 6, 255, 256, 70000)) {
            val plaintext = "ping-$seq".toByteArray()
            val sealed = MoonlightCrypto.controlSeal(key, seq, plaintext)
            assertEquals(plaintext.toList(), MoonlightCrypto.controlOpen(key, seq, sealed).toList())
        }
    }

    @Test
    fun `controlOpen rejects a tampered payload`() {
        val key = hexToBytes("edf04a215c4fbea20934120c8480d855")
        val sealed = MoonlightCrypto.controlSeal(key, seq = 3, plaintext = "secret".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) {
            MoonlightCrypto.controlOpen(key, seq = 3, tagThenCiphertext = sealed)
        }
    }

    @Test
    fun `controlOpen rejects the wrong seq (IV mismatch)`() {
        val key = hexToBytes("edf04a215c4fbea20934120c8480d855")
        val sealed = MoonlightCrypto.controlSeal(key, seq = 4, plaintext = "hello".toByteArray())
        assertThrows(AEADBadTagException::class.java) {
            MoonlightCrypto.controlOpen(key, seq = 5, tagThenCiphertext = sealed)
        }
    }

    @Test
    fun `RSA sign and verify round-trip with a generated key`() {
        val kp =
            java.security.KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
        val data = "pairing-secret".toByteArray()
        val sig = MoonlightCrypto.signRsaSha256(kp.private, data)
        assertTrue(MoonlightCrypto.verifyRsaSha256(kp.public, data, sig))
        assertTrue(!MoonlightCrypto.verifyRsaSha256(kp.public, "other".toByteArray(), sig))
    }
}
