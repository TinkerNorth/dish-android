// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.security.keystore.KeyProperties

/** What [MoonlightIdentityProvider] should do with what the keystore holds. */
internal enum class MoonlightIdentityDecision {
    /** Nothing stored yet: mint the identity. */
    GENERATE,

    /** Stored, but unusable: drop it and mint a replacement. */
    REGENERATE,

    /** Stored and fit for both the pairing signature and TLS client auth. */
    REUSE,
}

/**
 * The keystore-key decision, kept pure so it is unit-tested off-device.
 *
 * The interesting case is [MoonlightIdentityDecision.REGENERATE]. Shipped
 * builds generated the client key with PURPOSE_SIGN + PKCS1 + SHA-256 only,
 * which is enough to sign the pairing secret but not enough for Conscrypt to
 * drive TLS client auth, so mutual TLS died with INCOMPATIBLE_PADDING_MODE.
 * Broadening the KeyGenParameterSpec does NOT retro-authorize a key that
 * already exists (a keystore key's authorization list is fixed at generation),
 * so the legacy key has to be detected and replaced.
 *
 * Discarding it is harmless: pairing has never succeeded on any build, so no
 * host holds the old certificate. A host that somehow did would simply see an
 * unknown client and ask to be paired again.
 */
internal fun decideMoonlightIdentity(
    aliasPresent: Boolean,
    entryReadable: Boolean,
    tlsClientAuthCapable: Boolean,
): MoonlightIdentityDecision =
    when {
        !aliasPresent -> MoonlightIdentityDecision.GENERATE
        // A half-written entry (cert without key, or a key of the wrong type)
        // is as unusable as a legacy one and takes the same path.
        !entryReadable -> MoonlightIdentityDecision.REGENERATE
        !tlsClientAuthCapable -> MoonlightIdentityDecision.REGENERATE
        else -> MoonlightIdentityDecision.REUSE
    }

/**
 * Whether a stored key's authorizations cover Conscrypt's TLS client-auth path,
 * read off android.security.keystore.KeyInfo.
 *
 * Conscrypt (CryptoUpcalls.rsaSignDigestWithPrivateKey) asks a non-Conscrypt
 * provider for `Cipher.getInstance("RSA/ECB/NoPadding").init(ENCRYPT_MODE, key)`
 * when BoringSSL needs a raw private-key operation, which is what TLS 1.3 and
 * RSA-PSS reduce to once BoringSSL has done the PSS encoding itself. On
 * AndroidKeyStore that lands in AndroidKeyStoreRSACipherSpi.NoPadding, whose
 * adjustConfigForEncryptingWithPrivateKey() overrides the keymaster purpose to
 * SIGN and asks the key for KM_PAD_NONE with KM_DIGEST_NONE. KM_PAD_NONE is
 * what KeyProperties spells ENCRYPTION_PADDING_NONE (encryption and signature
 * paddings are merged into one KM_TAG_PADDING list at generation), so the three
 * checks below are exactly that operation's authorization requirements.
 *
 * The TLS 1.2 route asks for `RSA/ECB/PKCS1Padding` instead, which the same SPI
 * maps to KM_PAD_RSA_PKCS1_1_5_SIGN + KM_DIGEST_NONE: covered by the same
 * DIGEST_NONE check plus the PKCS1 signature padding the pairing signature
 * already needs.
 */
internal fun supportsTlsClientAuth(
    purposes: Int,
    digests: Array<String>,
    encryptionPaddings: Array<String>,
): Boolean =
    (purposes and KeyProperties.PURPOSE_SIGN) != 0 &&
        digests.any { it.equals(KeyProperties.DIGEST_NONE, ignoreCase = true) } &&
        encryptionPaddings.any { it.equals(KeyProperties.ENCRYPTION_PADDING_NONE, ignoreCase = true) }
