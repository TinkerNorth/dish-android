// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the full 5-phase client pairing against a reference server built
 * from the same crypto primitives (mirroring Wolf's server in moonlight.cpp).
 * Both directions are checked: the client authenticates the server, and the
 * server authenticates the client. Randomness is pinned so the exchange is
 * deterministic.
 *
 * The two RSA identities are throwaway ones generated when the class loads, the
 * way the androidTest FakeSatellite mints its cert: no key material is committed
 * to the repo. Nothing below is pinned to specific key bytes (the assertions are
 * round-trips through [MoonlightCrypto]), so a fresh pair each run is fine.
 */
class MoonlightPairingTest {
    private val clientIdentity: MoonlightIdentity = CLIENT
    private val serverIdentity: MoonlightIdentity = SERVER

    // Fixed random material so the exchange is byte-deterministic.
    private val clientSalt = ByteArray(16) { (it + 1).toByte() }
    private val clientChallenge = ByteArray(16) { (0x40 + it).toByte() }
    private val clientSecret = ByteArray(16) { (0x80 + it).toByte() }
    private val clientRandom =
        object {
            private val queue = ArrayDeque(listOf(clientSalt, clientChallenge, clientSecret))

            fun next(size: Int): ByteArray = queue.removeFirst().also { require(it.size == size) }
        }

    private fun newPairing(pin: String) = MoonlightPairing(clientIdentity, pin) { clientRandom.next(it) }

    private fun newServer(pin: String) = ReferenceServer(pin, serverIdentity, clientIdentity.certificatePem)

    @Test
    fun `full pairing round-trip authenticates both ends`() {
        val pin = "0451"
        val server = newServer(pin)
        val pairing = newPairing(pin)

        // Phase 1.
        val p1 = pairing.phase1Params("dish-uid")
        assertEquals(bytesToHex(clientSalt), p1["salt"])
        pairing.onPhase1(server.getServerCert(p1.getValue("salt")))

        // Phase 2.
        assertTrue(pairing.onPhase2(server.challengeResponse(pairing.phase2Params("dish-uid").getValue("clientchallenge"))))

        // Phase 3: the client verifies the server here.
        assertTrue(pairing.onPhase3(server.clientHashResponse(pairing.phase3Params("dish-uid").getValue("serverchallengeresp"))))

        // Phase 4: the server verifies the client.
        assertTrue(server.verifyClient(pairing.phase4Params("dish-uid").getValue("clientpairingsecret")))
    }

    @Test
    fun `wrong PIN derives a different key and fails phase 2`() {
        val server = newServer("0451")
        val pairing = newPairing("9999")
        val p1 = pairing.phase1Params("dish-uid")
        pairing.onPhase1(server.getServerCert(p1.getValue("salt")))
        // The server derives the key from the real PIN; the client's blob will not decrypt to a
        // valid challenge, so the server's response hash cannot be reproduced by the client.
        val response = server.challengeResponse(pairing.phase2Params("dish-uid").getValue("clientchallenge"))
        pairing.onPhase2(response)
        // onPhase3 is where the server-authentication check fails on a wrong key.
        assertFalse(pairing.onPhase3(server.clientHashResponse(pairing.phase3Params("dish-uid").getValue("serverchallengeresp"))))
    }

    /** A minimal Wolf-equivalent server, driven purely by [MoonlightCrypto]. */
    private class ReferenceServer(
        pin: String,
        private val identity: MoonlightIdentity,
        private val clientCertPem: String,
    ) {
        private val pinBytes = pin
        private var aesKey = ByteArray(0)
        private val serverSecret = ByteArray(16) { (0x10 + it).toByte() }
        private val serverChallenge = ByteArray(16) { (0x20 + it).toByte() }
        private var storedClientHash = ByteArray(0)
        private var clientChallenge = ByteArray(0)

        fun getServerCert(saltHex: String): String {
            aesKey = MoonlightCrypto.pairingKey(hexToBytes(saltHex), pinBytes)
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
            val clientSecret = secret.copyOfRange(0, 16)
            val clientSignature = secret.copyOfRange(16, secret.size)
            val expected =
                MoonlightCrypto.sha256(serverChallenge, MoonlightCert.signatureOf(clientCertPem), clientSecret)
            if (!MoonlightCrypto.constantTimeEquals(expected, storedClientHash)) return false
            return MoonlightCrypto.verifyRsaSha256(MoonlightCert.publicKeyOf(clientCertPem), clientSecret, clientSignature)
        }
    }

    private companion object {
        // Minted once for the whole class: JUnit builds a fresh test instance per
        // method and RSA-2048 keygen is the slowest thing in this file.
        val CLIENT = throwawayIdentity("dish-pairing-test-client")
        val SERVER = throwawayIdentity("dish-pairing-test-server")

        /** A disposable self-signed identity that lives only for this test run. */
        fun throwawayIdentity(commonName: String): MoonlightIdentity = ThrowawayIdentity.named(commonName)
    }
}
