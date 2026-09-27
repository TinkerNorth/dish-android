// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Client side of the contract's crypto (satellite docs/contract.md §Crypto /
 * §hmacProof). Pure JVM so it unit-tests against the satellite's pinned
 * interop vectors; the derived session key is handed to the native layer,
 * which only ever sees per-session material, never the pairing key.
 */
private const val HMAC_ALGORITHM = "HmacSHA256"
private const val PROOF_CONTEXT = "satellite-proof:"
private const val HKDF_INFO_LABEL = "satellite-session-v1"
private const val HKDF_FIRST_BLOCK: Byte = 0x01

// Wire sizes of the pairing and session material (docs/contract.md §Crypto).
const val PAIRING_KEY_BYTES = 32
const val PAIRING_KEY_HEX_LEN = PAIRING_KEY_BYTES * 2
const val SESSION_SALT_BYTES = 8
const val TOKEN_BYTES = 4

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
): String = bytesToHex(hmacSha256(pairingKey, (PROOF_CONTEXT + deviceId).toByteArray(Charsets.UTF_8)))

/**
 * sessionKey = HKDF-SHA256(ikm = pairingKey, salt = sessionSalt,
 * info = "satellite-session-v1" || token(4 BE)). RFC 5869, one output
 * block. Token and salt come from the session PUT response; both ends
 * derive the same key, so counters restart per session with no
 * cross-session nonce reuse.
 */
fun deriveSessionKey(
    pairingKey: ByteArray,
    sessionSalt: ByteArray,
    token: ByteArray,
): ByteArray {
    require(pairingKey.size == PAIRING_KEY_BYTES) { "pairing key must be $PAIRING_KEY_BYTES bytes" }
    require(sessionSalt.size == SESSION_SALT_BYTES) { "session salt must be $SESSION_SALT_BYTES bytes" }
    require(token.size == TOKEN_BYTES) { "token must be $TOKEN_BYTES bytes" }
    val prk = hmacSha256(sessionSalt, pairingKey)
    val info = HKDF_INFO_LABEL.toByteArray(Charsets.US_ASCII) + token + byteArrayOf(HKDF_FIRST_BLOCK)
    return hmacSha256(prk, info)
}
