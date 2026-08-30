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

    /**
     * The control-stream GCM IV: sixteen zero bytes with the LOW BYTE of [seq]
     * in byte 0, and nothing else.
     *
     * ONLY THE LOW BYTE, however wrong that looks. The host builds the same IV
     * with `std::array<std::uint8_t, 16> iv_data = {0}; iv_data[0] = seq;`
     * (Wolf control.hpp encrypt_packet and decrypt_packet), where assigning a
     * u32 into a u8 element drops the top three bytes. The packet header still
     * carries the full 32-bit sequence, so only the IV wraps. Writing all four
     * bytes here, as this used to, agrees with the host for the first 256
     * packets and disagrees forever after: a live Sunshine host accepted 256
     * sealed control packets and answered the 257th with "Failed to verify tag",
     * then ended the session. At two packets a second that is a session that
     * dies after about two minutes, every time, which is exactly why it hid
     * behind the faults that used to end the session in six.
     *
     * The IV therefore repeats every 256 packets on one session key. That is the
     * protocol's property and not a choice available to a client that wants to
     * interoperate. What limits it is that the key is the rikey, minted fresh
     * for every /launch and never reused across sessions.
     */
    private fun controlIv(seq: Int): ByteArray {
        val iv = ByteArray(CONTROL_IV_LEN)
        iv[0] = (seq and 0xFF).toByte()
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
