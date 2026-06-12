// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Client side of the contract's crypto (satellite docs/contract.md §Crypto /
 * §hmacProof). Pure JVM so it unit-tests against the satellite's pinned
 * interop vectors; the derived session key is handed to the native layer,
 * which only ever sees per-session material — never the pairing key.
 */
object SessionCrypto {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val PROOF_CONTEXT = "satellite-proof:"
    private const val HKDF_INFO_LABEL = "satellite-session-v1"

    private fun hmacSha256(
        key: ByteArray,
        message: ByteArray,
    ): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(SecretKeySpec(key, HMAC_ALGORITHM))
            doFinal(message)
        }

    /** hex( HMAC-SHA256( pairingKey, "satellite-proof:" + deviceId ) ). */
    fun hmacProof(
        pairingKey: ByteArray,
        deviceId: String,
    ): String = hmacSha256(pairingKey, (PROOF_CONTEXT + deviceId).toByteArray(Charsets.UTF_8)).toHex()

    /**
     * sessionKey = HKDF-SHA256(ikm = pairingKey, salt = sessionSalt,
     * info = "satellite-session-v1" || token(4 BE)) — RFC 5869, one output
     * block. Token and salt come from the session PUT response; both ends
     * derive the same key, so counters restart per session with no
     * cross-session nonce reuse.
     */
    fun deriveSessionKey(
        pairingKey: ByteArray,
        sessionSalt: ByteArray,
        token: ByteArray,
    ): ByteArray {
        require(pairingKey.size == 32) { "pairing key must be 32 bytes" }
        require(sessionSalt.size == 8) { "session salt must be 8 bytes" }
        require(token.size == 4) { "token must be 4 bytes" }
        val prk = hmacSha256(sessionSalt, pairingKey)
        val info = HKDF_INFO_LABEL.toByteArray(Charsets.US_ASCII) + token + byteArrayOf(0x01)
        return hmacSha256(prk, info)
    }

    private fun ByteArray.toHex(): String =
        buildString(size * 2) {
            for (b in this@toHex) {
                append("%02x".format(b.toInt() and 0xFF))
            }
        }
}
