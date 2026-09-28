// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The hot-path sealer for the control stream: encodes a CONTROLLER_MULTI, a
 * CONTROLLER_TOUCH or a mouse packet and seals it into a full ENet-ready encrypted control packet with
 * a single reused [Cipher] and reused buffers, so the encode and encrypt stages
 * build nothing per packet (the brief's hot-path rule; mirrors the repo's
 * satellite_jni.cpp fixed-buffer discipline). What a packet still allocates is
 * the returned frame and the cipher's re-init for the packet's IV, which JCA
 * takes as a fresh parameter spec.
 *
 * NOT thread-safe: one instance per control session, driven from the single
 * input-dispatch thread. The AES-GCM IV comes from the low byte of the
 * monotonically increasing control seq (Wolf control.hpp), so [nextSeq] must
 * advance once per sealed packet.
 */
class MoonlightHotSealer(
    gcmKey: ByteArray,
) {
    private val keySpec = SecretKeySpec(gcmKey, "AES")
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    // Reused across every packet: the plaintext scratch (sized for the longest hot
    // message, CONTROLLER_MULTI), the GCM output, and the final framed datagram body.
    private val plaintext = ByteBuffer.allocate(CONTROLLER_MULTI_LEN).order(ByteOrder.LITTLE_ENDIAN)
    private val plaintextWriter = ControllerMultiWriter(plaintext)
    private val cipherOut = ByteArray(CONTROLLER_MULTI_LEN + GCM_TAG_LEN)
    private val iv = ByteArray(GCM_IV_LEN)
    private val framed =
        ByteBuffer
            .allocate(FRAME_HEADER_LEN + SEQ_LEN + CONTROLLER_MULTI_LEN + GCM_TAG_LEN)
            .order(ByteOrder.LITTLE_ENDIAN)

    private var seq = 0

    val nextSeq: Int get() = seq

    /**
     * Encode [controllerNumber]'s state and return a freshly framed encrypted
     * control packet (`[type][len][seq][tag][ciphertext]`) ready to hand to the
     * ENet reliable send. The encode and encrypt stages reuse buffers. Advances
     * the seq.
     */
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
        plaintextWriter.encode(
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
        return sealPlaintextScratch()
    }

    /** MOUSE_MOVE_REL, encoded and sealed as [sealControllerMulti] does. */
    fun sealMouseMoveRel(
        deltaX: Int,
        deltaY: Int,
    ): ByteArray {
        writeMouseMoveRel(plaintext, deltaX, deltaY)
        return sealPlaintextScratch()
    }

    /** MOUSE_BUTTON_DOWN/UP, encoded and sealed as [sealControllerMulti] does. */
    fun sealMouseButton(
        down: Boolean,
        button: Int,
    ): ByteArray {
        writeMouseButton(plaintext, down, button)
        return sealPlaintextScratch()
    }

    /** MOUSE_SCROLL, encoded and sealed as [sealControllerMulti] does. */
    fun sealMouseScroll(amount: Int): ByteArray {
        writeMouseScroll(plaintext, amount)
        return sealPlaintextScratch()
    }

    /** CONTROLLER_TOUCH, encoded and sealed as [sealControllerMulti] does. */
    fun sealControllerTouch(
        controllerNumber: Int,
        eventType: Int,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float,
    ): ByteArray {
        writeControllerTouch(plaintext, controllerNumber, eventType, pointerId, x, y, pressure)
        return sealPlaintextScratch()
    }

    // Seals the message the plaintext scratch was just flipped to, under the next seq.
    private fun sealPlaintextScratch(): ByteArray {
        val currentSeq = seq
        initCipherFor(currentSeq)
        // doFinal(ByteBuffer, ByteBuffer-free) form: input from the flipped plaintext
        // into the reused cipherOut array; returns ct||tag.
        val written = cipher.doFinal(plaintext.array(), 0, plaintext.limit(), cipherOut, 0)
        seq = currentSeq + 1

        val ctLen = written - GCM_TAG_LEN
        val len = SEQ_LEN + written
        framed.clear()
        framed.putShort(PACKET_TYPE_ENCRYPTED.toShort())
        framed.putShort(len.toShort())
        framed.putInt(currentSeq)
        // Moonlight wants the tag first, then the ciphertext.
        framed.put(cipherOut, ctLen, GCM_TAG_LEN)
        framed.put(cipherOut, 0, ctLen)
        framed.flip()
        val out = ByteArray(framed.remaining())
        framed.get(out)
        return out
    }

    /**
     * Seal an arbitrary control plaintext (arrival, ping, motion,
     * termination) with the SAME advancing seq as the hot path, so the whole
     * outbound control stream carries one sequence. The IV is only that
     * sequence's low byte, as the host's is (see controlIv), so it repeats
     * every 256 packets under the session's rikey. Not on the hot path, so a
     * small allocation here is fine.
     */
    fun seal(plaintext: ByteArray): ByteArray {
        val currentSeq = seq
        initCipherFor(currentSeq)
        val ctThenTag = cipher.doFinal(plaintext)
        seq = currentSeq + 1
        val ctLen = ctThenTag.size - GCM_TAG_LEN
        val len = SEQ_LEN + ctThenTag.size
        val out = ByteBuffer.allocate(FRAME_HEADER_LEN + len).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(PACKET_TYPE_ENCRYPTED.toShort())
        out.putShort(len.toShort())
        out.putInt(currentSeq)
        // Moonlight wants the tag first, then the ciphertext.
        out.put(ctThenTag, ctLen, GCM_TAG_LEN)
        out.put(ctThenTag, 0, ctLen)
        return out.array()
    }

    // Hot and cold seals share this one cipher, so every init follows the one for the previous
    // seq and never repeats the IV the cipher saw last. JCA refuses to re-init GCM encryption
    // under the key and IV it was last given, which a second cipher for the cold path would hit
    // whenever a whole IV cycle of cold packets fell between two hot ones.
    private fun initCipherFor(currentSeq: Int) {
        writeIv(currentSeq)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
    }

    /** The low byte of the seq and nothing else; see controlIv. */
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
