// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
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
object MoonlightCrypto {
    private const val AES_KEY_LEN = 16
    private const val GCM_TAG_BITS = 128
    const val GCM_TAG_LEN = 16

    // The control stream IV is 16 bytes: the little-endian seq in the low bytes,
    // the rest zero (Wolf control.hpp decrypt_packet / encrypt_packet).
    private const val CONTROL_IV_LEN = 16

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

    private fun ecb(
        mode: Int,
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"))
            doFinal(data)
        }

    /**
     * Seal one control-stream payload: returns tag(16) || ciphertext, keyed by
     * [gcmKey] (the 16-byte rikey) with the IV derived from [seq]. Matches
     * Wolf's ControlEncryptedPacket body layout (control-specs.adoc): the tag
     * precedes the ciphertext on the wire.
     */
    fun controlSeal(
        gcmKey: ByteArray,
        seq: Int,
        plaintext: ByteArray,
    ): ByteArray {
        val out =
            gcm(Cipher.ENCRYPT_MODE, gcmKey, controlIv(seq)) {
                it.doFinal(plaintext)
            }
        // JCA appends the tag; Moonlight wants tag first.
        val ctLen = out.size - GCM_TAG_LEN
        val framed = ByteArray(out.size)
        System.arraycopy(out, ctLen, framed, 0, GCM_TAG_LEN)
        System.arraycopy(out, 0, framed, GCM_TAG_LEN, ctLen)
        return framed
    }

    /**
     * Open one control-stream payload of the form tag(16) || ciphertext.
     * Throws [javax.crypto.AEADBadTagException] on a tampered packet, so the
     * caller drops it rather than acting on forged input.
     */
    fun controlOpen(
        gcmKey: ByteArray,
        seq: Int,
        tagThenCiphertext: ByteArray,
    ): ByteArray {
        require(tagThenCiphertext.size >= GCM_TAG_LEN) { "control payload shorter than GCM tag" }
        // JCA expects ciphertext || tag; re-order from Moonlight's tag-first layout.
        val ctLen = tagThenCiphertext.size - GCM_TAG_LEN
        val ctThenTag = ByteArray(tagThenCiphertext.size)
        System.arraycopy(tagThenCiphertext, GCM_TAG_LEN, ctThenTag, 0, ctLen)
        System.arraycopy(tagThenCiphertext, 0, ctThenTag, ctLen, GCM_TAG_LEN)
        return gcm(Cipher.DECRYPT_MODE, gcmKey, controlIv(seq)) {
            it.doFinal(ctThenTag)
        }
    }

    private inline fun gcm(
        mode: Int,
        key: ByteArray,
        iv: ByteArray,
        block: (Cipher) -> ByteArray,
    ): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            block(this)
        }

    private fun controlIv(seq: Int): ByteArray {
        val iv = ByteArray(CONTROL_IV_LEN)
        // Little-endian seq in the low 4 bytes; Wolf only ever populates byte 0
        // for small seqs but keeps the full 32-bit LE value here for parity.
        iv[0] = (seq and 0xFF).toByte()
        iv[1] = ((seq ushr 8) and 0xFF).toByte()
        iv[2] = ((seq ushr 16) and 0xFF).toByte()
        iv[3] = ((seq ushr 24) and 0xFF).toByte()
        return iv
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
}
