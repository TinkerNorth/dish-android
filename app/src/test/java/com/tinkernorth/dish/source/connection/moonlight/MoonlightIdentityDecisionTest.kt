// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keystore-key migration decision, off-device. The KeyProperties values it
 * compares are compile-time constants, so nothing here needs a real keystore.
 */
class MoonlightIdentityDecisionTest {
    // What an earlier build generated: enough to sign the pairing secret, not
    // enough for Conscrypt's raw-RSA TLS client auth.
    private val legacyDigests = arrayOf("SHA-256")
    private val legacyEncryptionPaddings = emptyArray<String>()

    // What this build generates.
    private val currentDigests = arrayOf("NONE", "SHA-256")
    private val currentEncryptionPaddings = arrayOf("NoPadding")

    private val purposeSign = 1 shl 2

    @Test
    fun `no stored alias generates a fresh identity`() {
        assertEquals(
            MoonlightIdentityDecision.GENERATE,
            decideMoonlightIdentity(aliasPresent = false, entryReadable = false, tlsClientAuthCapable = false),
        )
    }

    @Test
    fun `an alias whose entry will not read is replaced`() {
        assertEquals(
            MoonlightIdentityDecision.REGENERATE,
            decideMoonlightIdentity(aliasPresent = true, entryReadable = false, tlsClientAuthCapable = false),
        )
    }

    @Test
    fun `a readable legacy key is replaced rather than reused`() {
        assertEquals(
            MoonlightIdentityDecision.REGENERATE,
            decideMoonlightIdentity(aliasPresent = true, entryReadable = true, tlsClientAuthCapable = false),
        )
    }

    @Test
    fun `a key that can do TLS client auth is kept`() {
        assertEquals(
            MoonlightIdentityDecision.REUSE,
            decideMoonlightIdentity(aliasPresent = true, entryReadable = true, tlsClientAuthCapable = true),
        )
    }

    @Test
    fun `the key this build generates is TLS-client-auth capable`() {
        assertTrue(supportsTlsClientAuth(purposeSign, currentDigests, currentEncryptionPaddings))
    }

    @Test
    fun `the key earlier builds generated is not`() {
        assertFalse(supportsTlsClientAuth(purposeSign, legacyDigests, legacyEncryptionPaddings))
    }

    @Test
    fun `a key missing DIGEST_NONE cannot take an already-digested block`() {
        assertFalse(supportsTlsClientAuth(purposeSign, arrayOf("SHA-256"), currentEncryptionPaddings))
    }

    @Test
    fun `a key missing the NONE padding cannot run the raw private-key operation`() {
        assertFalse(supportsTlsClientAuth(purposeSign, currentDigests, arrayOf("PKCS1Padding")))
    }

    @Test
    fun `a key that may not sign cannot authenticate a handshake`() {
        val purposeVerifyOnly = 1 shl 3
        assertFalse(supportsTlsClientAuth(purposeVerifyOnly, currentDigests, currentEncryptionPaddings))
    }

    @Test
    fun `the keymaster spellings are matched case-insensitively`() {
        assertTrue(supportsTlsClientAuth(purposeSign, arrayOf("none", "sha-256"), arrayOf("nopadding")))
    }

    @Test
    fun `extra purposes alongside sign are fine`() {
        val purposeDecrypt = 1 shl 1
        assertTrue(
            supportsTlsClientAuth(purposeSign or purposeDecrypt, currentDigests, currentEncryptionPaddings),
        )
    }
}
