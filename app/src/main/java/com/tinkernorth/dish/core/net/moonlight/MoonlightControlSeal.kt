// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val GCM_TAG_BITS = 128
private const val CONTROL_IV_LEN = 16

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
