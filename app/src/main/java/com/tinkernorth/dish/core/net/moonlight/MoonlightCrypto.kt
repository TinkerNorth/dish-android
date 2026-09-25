// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Client side of the Moonlight (GameStream) crypto (Wolf docs
 * protocols/http-pairing.adoc and control-specs.adoc). Pure JVM APIs (JCA
 * only, no BouncyCastle) so every step unit-tests against Wolf's captured
 * vectors. X.509 identity generation is deliberately NOT here: this object
 * only consumes cert-signature bytes and keys the caller supplies, so it stays
 * host-testable with no Android keystore. The Moonlight path mirrors how
 * [com.tinkernorth.dish.core.net.SessionCrypto] keeps the protocol-1 crypto
 * pure and pushes identity to the edges.
 */
private const val AES_KEY_LEN = 16
const val GCM_TAG_LEN = 16

// The control stream IV is 16 bytes: the little-endian seq in the low bytes,
// the rest zero (Wolf control.hpp decrypt_packet / encrypt_packet).

private val secureRandom = SecureRandom()

fun randomBytes(length: Int): ByteArray = ByteArray(length).also { secureRandom.nextBytes(it) }

fun sha256(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").run {
        for (p in parts) update(p)
        digest()
    }

/**
 * Pairing AES key = SHA-256(salt || pin)[:16] (Wolf moonlight.cpp
 * gen_aes_key). [salt] is the raw 16 random bytes the client generated in
 * phase 1; [pin] is the 4-digit ASCII string shown to the user.
 */
fun pairingKey(
    salt: ByteArray,
    pin: String,
): ByteArray = sha256(salt, pin.toByteArray(Charsets.US_ASCII)).copyOf(AES_KEY_LEN)

// AES-128-ECB, no padding: the pairing challenge blobs are exact 16-byte
// multiples, so PKCS padding would corrupt the round-trip (Wolf uses
// padding=false for the challenge exchange).
fun aesEcbEncrypt(
    key: ByteArray,
    data: ByteArray,
): ByteArray = ecb(Cipher.ENCRYPT_MODE, key, data)

fun aesEcbDecrypt(
    key: ByteArray,
    data: ByteArray,
): ByteArray = ecb(Cipher.DECRYPT_MODE, key, data)

// Marker: lint's GetInstance rejects ECB as a mode, rightly for data. Here it is the block
// primitive the Moonlight pairing protocol prescribes (Wolf moonlight.cpp: the challenge and
// the server-challenge response are AES-128-ECB blobs), and a client that used any other mode
// could not pair with a Sunshine or Wolf host. The fix is a protocol revision upstream.
@Suppress("GetInstance")
private fun ecb(
    mode: Int,
    key: ByteArray,
    data: ByteArray,
): ByteArray =
    Cipher.getInstance("AES/ECB/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"))
        doFinal(data)
    }

/** RSA-SHA256 PKCS#1 v1.5 signature over [data] (Wolf crypto sign()). */
fun signRsaSha256(
    privateKey: PrivateKey,
    data: ByteArray,
): ByteArray =
    Signature.getInstance("SHA256withRSA").run {
        initSign(privateKey)
        update(data)
        sign()
    }

fun verifyRsaSha256(
    publicKey: PublicKey,
    data: ByteArray,
    signature: ByteArray,
): Boolean =
    Signature.getInstance("SHA256withRSA").run {
        initVerify(publicKey)
        update(data)
        verify(signature)
    }

/** Constant-time compare so a hash/tag check does not leak via timing. */
fun constantTimeEquals(
    a: ByteArray,
    b: ByteArray,
): Boolean = MessageDigest.isEqual(a, b)
