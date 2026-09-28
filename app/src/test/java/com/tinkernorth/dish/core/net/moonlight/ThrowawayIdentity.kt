// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import okhttp3.tls.HeldCertificate
import java.security.PrivateKey

/**
 * A disposable self-signed certificate, minted per test run so that no key material is committed
 * to the repo: a pinned key would make a leaked test fixture look like a leaked client certificate.
 * The pairing tests take it as an identity; the gateway test also hands it to a real TLS endpoint.
 *
 * RSA-2048 rather than the builder's default ECDSA: Moonlight pairing signs with SHA256withRSA,
 * and the real client identity is RSA-2048 as well.
 */
internal fun throwawayCertificate(commonName: String): HeldCertificate =
    HeldCertificate
        .Builder()
        .commonName(commonName)
        .rsa2048()
        .build()

internal fun throwawayIdentity(commonName: String): MoonlightIdentity = identityOf(throwawayCertificate(commonName))

internal fun identityOf(held: HeldCertificate): MoonlightIdentity = HeldIdentity(held)

private class HeldIdentity(
    held: HeldCertificate,
) : MoonlightIdentity {
    override val certificatePem: String = held.certificatePem()
    override val certificateSignature: ByteArray = held.certificate.signature
    override val privateKey: PrivateKey = held.keyPair.private
}
