// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import java.io.ByteArrayInputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The dish's persistent Moonlight client identity: a self-signed X.509
 * certificate and its RSA key, generated once and reused for every host (Wolf
 * http-pairing.adoc). The client cert PEM is sent in pairing phase 1 and the
 * key authenticates every later HTTPS call.
 *
 * Certificate GENERATION is platform-specific (Android keystore / provider) and
 * lives behind this interface; the crypto that consumes it stays pure and
 * host-testable. This mirrors how the repo keeps TLS/identity at the edges and
 * the protocol crypto ([MoonlightCrypto]) pure.
 */
interface MoonlightIdentity {
    /** PEM of the self-signed client certificate (sent as hex in phase 1). */
    val certificatePem: String

    /** The certificate's X.509 signature bytes (used in the pairing hashes). */
    val certificateSignature: ByteArray

    /** RSA private key: signs the client pairing secret and TLS challenges. */
    val privateKey: PrivateKey
}

/**
 * Parses a peer certificate PEM into the fields the pairing crypto needs.
 * Uses the platform [CertificateFactory] (present on Android and the host JVM),
 * so this is exercised in unit tests without any keystore.
 */
object MoonlightCert {
    /** X.509 signature bytes of the certificate encoded in [pem]. */
    fun signatureOf(pem: String): ByteArray = parse(pem).signature

    fun publicKeyOf(pem: String): PublicKey = parse(pem).publicKey

    fun sha256FingerprintHex(pem: String): String = bytesHex(MoonlightCrypto.sha256(parse(pem).encoded))

    fun parse(pem: String): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII)).use {
            factory.generateCertificate(it) as X509Certificate
        }
    }

    private val hexDigits = "0123456789abcdef".toCharArray()

    private fun bytesHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = hexDigits[v ushr 4]
            out[i * 2 + 1] = hexDigits[v and 0x0F]
        }
        return String(out)
    }
}
