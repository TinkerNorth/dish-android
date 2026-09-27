// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned against Wolf's captured session vectors (tests/testCrypto.cpp,
 * tests/testControl.cpp). Any drift here is a cross-end Moonlight protocol
 * break, not a refactor.
 */
class MoonlightCryptoTest {
    @Test
    fun `pairingKey matches Wolf's gen_aes_key vector`() {
        val salt = hexToBytes("ff5dc6eda99339a8a0793e216c4257c4")
        val key = pairingKey(salt, "5338")
        assertEquals("5ea186ffba663c75aec82187ce502647", bytesToHex(key))
    }

    @Test
    fun `AES-ECB round-trips and matches Wolf's decrypted challenge`() {
        val key = hexToBytes("5ea186ffba663c75aec82187ce502647")
        val challenge = hexToBytes("c05930ac81d7bd426344235436046018")
        val decrypted = aesEcbDecrypt(key, challenge)
        assertEquals("e3a915cccb4c60206077d7e9a12316a5", bytesToHex(decrypted))
        assertEquals(challenge.toList(), aesEcbEncrypt(key, decrypted).toList())
    }

    @Test
    fun `RSA sign and verify round-trip with a generated key`() {
        val kp =
            java.security.KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
        val data = "pairing-secret".toByteArray()
        val sig = signRsaSha256(kp.private, data)
        assertTrue(verifyRsaSha256(kp.public, data, sig))
        assertFalse(verifyRsaSha256(kp.public, "other".toByteArray(), sig))
    }

    @Test
    fun `randomBytes answers exactly the length asked for`() {
        assertEquals(0, randomBytes(0).size)
        assertEquals(1, randomBytes(1).size)
        assertEquals(GCM_TAG_LEN, randomBytes(GCM_TAG_LEN).size)
    }

    @Test
    fun `randomBytes does not repeat itself, so a salt is never reused`() {
        val seen = (1..64).map { randomBytes(16).toList() }.toSet()
        assertEquals(64, seen.size)
    }
}
