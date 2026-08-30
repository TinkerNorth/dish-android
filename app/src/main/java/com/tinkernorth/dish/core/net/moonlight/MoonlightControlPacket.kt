// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The encrypted control-stream packet framing (Wolf control-specs.adoc /
 * control.hpp ControlEncryptedPacket). All little-endian:
 *
 *   [type u16 = 0x0001][len u16][seq u32][GCM tag 16B][ciphertext]
 *
 * `len` = seq(4) + tag(16) + ciphertext. `seq` is monotonically increasing and
 * seeds the AES-GCM IV. The seal is pinned against Wolf's captured session
 * vectors in the unit tests, so any drift is a real interop break.
 */
class MoonlightControlPacket(
    private val gcmKey: ByteArray,
) {
    private var sendSeq = 0

    /** Seal [plaintext] into a full encrypted control packet; advances the seq. */
    fun seal(plaintext: ByteArray): ByteArray {
        val seq = sendSeq
        sendSeq += 1
        return sealWithSeq(seq, plaintext)
    }

    fun sealWithSeq(
        seq: Int,
        plaintext: ByteArray,
    ): ByteArray {
        val tagThenCt = MoonlightCrypto.controlSeal(gcmKey, seq, plaintext)
        val len = SEQ_LEN + tagThenCt.size
        val buf = ByteBuffer.allocate(HEADER_LEN + len).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(MoonlightControlProtocol.PACKET_TYPE_ENCRYPTED.toShort())
        buf.putShort(len.toShort())
        buf.putInt(seq)
        buf.put(tagThenCt)
        return buf.array()
    }

    /**
     * Open a received encrypted control packet, returning the decrypted
     * plaintext. Returns null on a short/malformed frame and throws
     * [javax.crypto.AEADBadTagException] (via [MoonlightCrypto.controlOpen]) on a
     * tampered payload, so a forged packet is dropped, never acted on.
     */
    fun open(packet: ByteArray): ByteArray? {
        if (packet.size < HEADER_LEN + SEQ_LEN + MoonlightCrypto.GCM_TAG_LEN) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf.short.toInt() and 0xFFFF
        if (type != MoonlightControlProtocol.PACKET_TYPE_ENCRYPTED) return null
        val len = buf.short.toInt() and 0xFFFF
        if (len < SEQ_LEN + MoonlightCrypto.GCM_TAG_LEN) return null
        if (buf.remaining() < len) return null
        val seq = buf.int
        val tagThenCt = ByteArray(len - SEQ_LEN)
        buf.get(tagThenCt)
        return MoonlightCrypto.controlOpen(gcmKey, seq, tagThenCt)
    }

    companion object {
        private const val HEADER_LEN = 4
        private const val SEQ_LEN = 4
    }
}
