// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import okhttp3.tls.HeldCertificate
import java.security.PrivateKey

/**
 * Disposable self-signed Moonlight identities, minted per test run so that no
 * key material is committed to the repo. Shared by the pairing tests, which
 * need the identity, and the gateway test, which also hands the certificate to
 * a real TLS endpoint.
 *
 * RSA-2048 rather than the builder's default ECDSA: Moonlight pairing signs
 * with SHA256withRSA, and the real client identity is RSA-2048 as well.
 */
object ThrowawayIdentity {
    fun heldCertificate(commonName: String): HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName(commonName)
            .rsa2048()
            .build()

    fun of(held: HeldCertificate): MoonlightIdentity =
        object : MoonlightIdentity {
            override val certificatePem: String = held.certificatePem()
            override val certificateSignature: ByteArray = held.certificate.signature
            override val privateKey: PrivateKey = held.keyPair.private
        }

    fun named(commonName: String): MoonlightIdentity = of(heldCertificate(commonName))
}
