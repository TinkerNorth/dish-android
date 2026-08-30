// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.hexToBytes

/**
 * A minimal Wolf-equivalent pairing server, driven purely by [MoonlightCrypto].
 * The host half of the five phases, so the client half can be exercised for real
 * rather than against stubbed hex: every value below is one the live host would
 * have had to produce, and a client that skips a check fails here.
 *
 * Shared by the crypto round-trip test and by the manager-level pairing flow,
 * which drives the same exchange through the HTTP gateway.
 */
internal class MoonlightReferenceServer(
    private val pin: String,
    private val identity: MoonlightIdentity,
    private val clientCertPem: String,
) {
    private var aesKey = ByteArray(0)
    private val serverSecret = ByteArray(BLOCK) { (0x10 + it).toByte() }
    private val serverChallenge = ByteArray(BLOCK) { (0x20 + it).toByte() }
    private var storedClientHash = ByteArray(0)
    private var clientChallenge = ByteArray(0)

    /** True once [verifyClient] has accepted the client, the way a host records a pairing. */
    var paired: Boolean = false
        private set

    fun getServerCert(saltHex: String): String {
        aesKey = MoonlightCrypto.pairingKey(hexToBytes(saltHex), pin)
        return identity.certificatePem
    }

    fun challengeResponse(clientChallengeHex: String): String {
        clientChallenge = MoonlightCrypto.aesEcbDecrypt(aesKey, hexToBytes(clientChallengeHex))
        val hash = MoonlightCrypto.sha256(clientChallenge, identity.certificateSignature, serverSecret)
        return bytesToHex(MoonlightCrypto.aesEcbEncrypt(aesKey, hash + serverChallenge))
    }

    fun clientHashResponse(serverChallengeRespHex: String): String {
        storedClientHash = MoonlightCrypto.aesEcbDecrypt(aesKey, hexToBytes(serverChallengeRespHex))
        val signature = MoonlightCrypto.signRsaSha256(identity.privateKey, serverSecret)
        return bytesToHex(serverSecret + signature)
    }

    fun verifyClient(clientPairingSecretHex: String): Boolean {
        val secret = hexToBytes(clientPairingSecretHex)
        val clientSecret = secret.copyOfRange(0, BLOCK)
        val clientSignature = secret.copyOfRange(BLOCK, secret.size)
        val expected = MoonlightCrypto.sha256(serverChallenge, MoonlightCert.signatureOf(clientCertPem), clientSecret)
        if (!MoonlightCrypto.constantTimeEquals(expected, storedClientHash)) return false
        paired = MoonlightCrypto.verifyRsaSha256(MoonlightCert.publicKeyOf(clientCertPem), clientSecret, clientSignature)
        return paired
    }

    private companion object {
        const val BLOCK = 16
    }
}
