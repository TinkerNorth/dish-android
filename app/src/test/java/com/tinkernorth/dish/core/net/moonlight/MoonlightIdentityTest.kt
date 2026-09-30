// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.bytesToHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateException

class MoonlightIdentityTest {
    private val held = throwawayCertificate("dish-identity-test")
    private val pem = held.certificatePem()

    @Test
    fun `parseMoonlightCert reads the certificate the PEM encodes`() {
        assertEquals(held.certificate, parseMoonlightCert(pem))
    }

    @Test
    fun `signatureOf reads the X509 signature field`() {
        assertArrayEquals(held.certificate.signature, signatureOf(pem))
    }

    @Test
    fun `publicKeyOf reads the subject public key`() {
        assertEquals(held.keyPair.public, publicKeyOf(pem))
    }

    @Test
    fun `sha256FingerprintHex hashes the DER encoding of the PEM`() {
        assertEquals(bytesToHex(sha256(held.certificate.encoded)), sha256FingerprintHex(pem))
    }

    @Test
    fun `sha256FingerprintHex is 64 lowercase hex digits`() {
        val fingerprint = sha256FingerprintHex(pem)
        assertEquals(SHA256_HEX_LEN, fingerprint.length)
        assertEquals(fingerprint.lowercase(), fingerprint)
    }

    @Test
    fun `sha256FingerprintHex does not hash the PEM text itself`() {
        assertNotEquals(bytesToHex(sha256(pem.toByteArray(Charsets.US_ASCII))), sha256FingerprintHex(pem))
    }

    @Test
    fun `parseMoonlightCert rejects text that is not a certificate`() {
        assertThrows(CertificateException::class.java) { parseMoonlightCert("not a certificate") }
    }

    private companion object {
        const val SHA256_HEX_LEN = 64
    }
}
