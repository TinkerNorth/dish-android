// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.bytesToHex

/**
 * Client side of the Moonlight PIN pairing (Wolf http-pairing.adoc, mirrored
 * from Wolf's SERVER implementation in moonlight.cpp / endpoints.hpp). Five
 * phases derive a shared trust: the dish shows a PIN, the user types it into the
 * host UI, and a challenge/response over AES-ECB plus RSA signatures
 * authenticates both ends.
 *
 * This class owns only the crypto and the phase ordering, so it round-trips in
 * unit tests against a reference server built from the same primitives. The
 * manager drives the actual HTTP; each `phaseN...` returns the query parameters
 * to send and each `onPhaseN` folds the host's XML-extracted value back in.
 *
 * Randomness is injected so tests are deterministic; production passes
 * [MoonlightCrypto.randomBytes].
 */
class MoonlightPairing(
    private val identity: MoonlightIdentity,
    private val pin: String,
    private val randomBytes: (Int) -> ByteArray = MoonlightCrypto::randomBytes,
) {
    private val salt: ByteArray = randomBytes(SALT_LEN)
    private val aesKey: ByteArray = MoonlightCrypto.pairingKey(salt, pin)
    private val clientChallenge: ByteArray = randomBytes(BLOCK_LEN)
    private val clientSecret: ByteArray = randomBytes(BLOCK_LEN)

    private lateinit var serverCertPem: String
    private lateinit var serverCertSignature: ByteArray
    private var serverChallenge: ByteArray = ByteArray(0)

    /** Phase 1: send salt + client cert. `pin` is shown to the user separately. */
    fun phase1Params(uniqueId: String): Map<String, String> =
        mapOf(
            "devicename" to "roth",
            "updateState" to "1",
            "phrase" to "getservercert",
            "salt" to bytesToHex(salt),
            "clientcert" to bytesToHex(identity.certificatePem.toByteArray(Charsets.US_ASCII)),
            "uniqueid" to uniqueId,
        )

    /** Phase 1 response: the host's plaincert (server cert PEM). */
    fun onPhase1(serverCertPem: String) {
        this.serverCertPem = serverCertPem
        serverCertSignature = MoonlightCert.signatureOf(serverCertPem)
    }

    /** Phase 2: send the client challenge (AES-ECB encrypted). */
    fun phase2Params(uniqueId: String): Map<String, String> =
        mapOf(
            "clientchallenge" to bytesToHex(MoonlightCrypto.aesEcbEncrypt(aesKey, clientChallenge)),
            "uniqueid" to uniqueId,
        )

    /**
     * Phase 2 response: the host's challengeresponse. Decrypts to
     * serverHash(32) || serverChallenge(16); the server hash is verified later
     * once phase 3 reveals the server secret.
     */
    fun onPhase2(challengeResponseHex: String): Boolean {
        val decrypted = MoonlightCrypto.aesEcbDecrypt(aesKey, hexToBytes(challengeResponseHex))
        if (decrypted.size < HASH_LEN + BLOCK_LEN) return false
        serverResponseHash = decrypted.copyOfRange(0, HASH_LEN)
        serverChallenge = decrypted.copyOfRange(HASH_LEN, HASH_LEN + BLOCK_LEN)
        return true
    }

    /**
     * Phase 3: send serverchallengeresp = ECB( SHA256(serverChallenge ||
     * clientCertSig || clientSecret) ). The host decrypts and stores this as our
     * client hash for the phase 4 check.
     */
    fun phase3Params(uniqueId: String): Map<String, String> {
        val clientHash = MoonlightCrypto.sha256(serverChallenge, identity.certificateSignature, clientSecret)
        return mapOf(
            "serverchallengeresp" to bytesToHex(MoonlightCrypto.aesEcbEncrypt(aesKey, clientHash)),
            "uniqueid" to uniqueId,
        )
    }

    /**
     * Phase 3 response: the host's pairingsecret = serverSecret(16) ||
     * serverSignature. Authenticates the host: the earlier server hash must
     * equal SHA256(clientChallenge || serverCertSig || serverSecret) and the
     * secret must be RSA-signed by the server cert.
     */
    fun onPhase3(pairingSecretHex: String): Boolean {
        val secret = hexToBytes(pairingSecretHex)
        if (secret.size < BLOCK_LEN + MIN_SIGNATURE_LEN) return false
        val serverSecret = secret.copyOfRange(0, BLOCK_LEN)
        val serverSignature = secret.copyOfRange(BLOCK_LEN, secret.size)
        val expectedHash = MoonlightCrypto.sha256(clientChallenge, serverCertSignature, serverSecret)
        if (!MoonlightCrypto.constantTimeEquals(expectedHash, serverResponseHash)) return false
        val serverPublicKey = MoonlightCert.publicKeyOf(serverCertPem)
        return MoonlightCrypto.verifyRsaSha256(serverPublicKey, serverSecret, serverSignature)
    }

    /**
     * Phase 4: send clientpairingsecret = clientSecret(16) ||
     * RSA-sign(clientSecret). The host verifies the hash and the signature, then
     * marks us paired.
     */
    fun phase4Params(uniqueId: String): Map<String, String> {
        val signature = MoonlightCrypto.signRsaSha256(identity.privateKey, clientSecret)
        return mapOf(
            "clientpairingsecret" to bytesToHex(clientSecret + signature),
            "uniqueid" to uniqueId,
        )
    }

    /** Phase 5 (HTTPS): the client-cert-authenticated pairchallenge. */
    fun phase5Params(uniqueId: String): Map<String, String> =
        mapOf(
            "phrase" to "pairchallenge",
            "uniqueid" to uniqueId,
        )

    private var serverResponseHash: ByteArray = ByteArray(0)

    private fun hexToBytes(hex: String): ByteArray =
        com.tinkernorth.dish.core.net
            .hexToBytes(hex)

    companion object {
        const val SALT_LEN = 16
        private const val BLOCK_LEN = 16
        private const val HASH_LEN = 32

        // A 2048-bit RSA signature is 256 bytes; accept any reasonable length so a
        // differently sized host key still pairs.
        private const val MIN_SIGNATURE_LEN = 64
    }
}
