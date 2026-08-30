// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The hot-path sealer for the control stream: encodes a CONTROLLER_MULTI packet
 * and seals it into a full ENet-ready encrypted control packet with a single
 * reused [Cipher] and reused buffers, so a steady stream of input changes does
 * not allocate per packet (the brief's hot-path rule; mirrors the repo's
 * satellite_jni.cpp fixed-buffer discipline).
 *
 * NOT thread-safe: one instance per control session, driven from the single
 * input-dispatch thread. The AES-GCM IV comes from the monotonically increasing
 * ENet-level control seq (Wolf control.hpp), so [nextSeq] must advance once per
 * sealed packet.
 */
class MoonlightHotSealer(
    gcmKey: ByteArray,
) {
    private val keySpec = SecretKeySpec(gcmKey, "AES")
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    // Reused across every packet: the plaintext scratch, the GCM output, and the
    // final framed datagram body.
    private val plaintext = ByteBuffer.allocate(MoonlightInputEncoder.CONTROLLER_MULTI_LEN).order(ByteOrder.LITTLE_ENDIAN)
    private val cipherOut = ByteArray(MoonlightInputEncoder.CONTROLLER_MULTI_LEN + MoonlightCrypto.GCM_TAG_LEN)
    private val iv = ByteArray(GCM_IV_LEN)
    private val framed =
        ByteBuffer
            .allocate(FRAME_HEADER_LEN + SEQ_LEN + MoonlightInputEncoder.CONTROLLER_MULTI_LEN + MoonlightCrypto.GCM_TAG_LEN)
            .order(ByteOrder.LITTLE_ENDIAN)

    private var seq = 0

    val nextSeq: Int get() = seq

    /**
     * Encode [controllerNumber]'s state and return a freshly framed encrypted
     * control packet (`[type][len][seq][tag][ciphertext]`) ready to hand to the
     * ENet reliable send. Only the returned array is allocated; the encode and
     * encrypt stages reuse buffers. Advances the seq.
     */
    @Suppress("LongParameterList")
    fun sealControllerMulti(
        controllerNumber: Int,
        activeMask: Int,
        buttons: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        leftStickX: Int,
        leftStickY: Int,
        rightStickX: Int,
        rightStickY: Int,
    ): ByteArray {
        MoonlightInputEncoder.encodeControllerMulti(
            plaintext,
            controllerNumber,
            activeMask,
            buttons,
            leftTrigger,
            rightTrigger,
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY,
        )
        val currentSeq = seq
        writeIv(currentSeq)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
        // doFinal(ByteBuffer, ByteBuffer-free) form: input from the flipped plaintext
        // into the reused cipherOut array; returns ct||tag.
        val written = cipher.doFinal(plaintext.array(), 0, plaintext.limit(), cipherOut, 0)
        seq = currentSeq + 1

        val ctLen = written - MoonlightCrypto.GCM_TAG_LEN
        val len = SEQ_LEN + written
        framed.clear()
        framed.putShort(MoonlightControlProtocol.PACKET_TYPE_ENCRYPTED.toShort())
        framed.putShort(len.toShort())
        framed.putInt(currentSeq)
        // Moonlight wants the tag first, then the ciphertext.
        framed.put(cipherOut, ctLen, MoonlightCrypto.GCM_TAG_LEN)
        framed.put(cipherOut, 0, ctLen)
        framed.flip()
        val out = ByteArray(framed.remaining())
        framed.get(out)
        return out
    }

    /**
     * Seal an arbitrary control plaintext (arrival, ping, termination) with the
     * SAME advancing seq as the hot path, so the whole outbound control stream
     * carries one monotonic sequence and never reuses a GCM IV. Not on the hot
     * path, so a small allocation here is fine.
     */
    fun seal(plaintext: ByteArray): ByteArray {
        val currentSeq = seq
        val tagThenCt = MoonlightCrypto.controlSeal(keySpec.encoded, currentSeq, plaintext)
        seq = currentSeq + 1
        val len = SEQ_LEN + tagThenCt.size
        val out = ByteBuffer.allocate(FRAME_HEADER_LEN + len).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(MoonlightControlProtocol.PACKET_TYPE_ENCRYPTED.toShort())
        out.putShort(len.toShort())
        out.putInt(currentSeq)
        out.put(tagThenCt)
        return out.array()
    }

    /** The low byte of the seq and nothing else; see MoonlightCrypto.controlIv. */
    private fun writeIv(currentSeq: Int) {
        iv.fill(0)
        iv[0] = (currentSeq and 0xFF).toByte()
    }

    private companion object {
        const val GCM_IV_LEN = 16
        const val GCM_TAG_BITS = 128
        const val FRAME_HEADER_LEN = 4
        const val SEQ_LEN = 4
    }
}
