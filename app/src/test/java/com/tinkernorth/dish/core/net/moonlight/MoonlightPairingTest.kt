// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Exercises the full 5-phase client pairing against a reference server built
 * from the same crypto primitives (mirroring Wolf's server in moonlight.cpp).
 * Both directions are checked: the client authenticates the server, and the
 * server authenticates the client. Randomness is pinned so the exchange is
 * deterministic.
 */
class MoonlightPairingTest {
    private val clientCertPem = resource("moonlight/client_cert.pem")
    private val clientKey = privateKey(resource("moonlight/client_key.pem"))
    private val serverCertPem = resource("moonlight/server_cert.pem")
    private val serverKey = privateKey(resource("moonlight/server_key.pem"))

    private val clientIdentity =
        object : MoonlightIdentity {
            override val certificatePem = clientCertPem
            override val certificateSignature = MoonlightCert.signatureOf(clientCertPem)
            override val privateKey = clientKey
        }

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

    @Test
    fun `full pairing round-trip authenticates both ends`() {
        val pin = "0451"
        val server = ReferenceServer(pin, serverCertPem, serverKey, MoonlightCert.signatureOf(serverCertPem))
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
        val server = ReferenceServer("0451", serverCertPem, serverKey, MoonlightCert.signatureOf(serverCertPem))
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
        private val serverCertPem: String,
        private val serverKey: PrivateKey,
        private val serverCertSignature: ByteArray,
    ) {
        private val pinBytes = pin
        private var aesKey = ByteArray(0)
        private val serverSecret = ByteArray(16) { (0x10 + it).toByte() }
        private val serverChallenge = ByteArray(16) { (0x20 + it).toByte() }
        private var storedClientHash = ByteArray(0)
        private var clientChallenge = ByteArray(0)

        fun getServerCert(saltHex: String): String {
            aesKey = MoonlightCrypto.pairingKey(hexToBytes(saltHex), pinBytes)
            return serverCertPem
        }

        fun challengeResponse(clientChallengeHex: String): String {
            clientChallenge = MoonlightCrypto.aesEcbDecrypt(aesKey, hexToBytes(clientChallengeHex))
            val hash = MoonlightCrypto.sha256(clientChallenge, serverCertSignature, serverSecret)
            return bytesToHex(MoonlightCrypto.aesEcbEncrypt(aesKey, hash + serverChallenge))
        }

        fun clientHashResponse(serverChallengeRespHex: String): String {
            storedClientHash = MoonlightCrypto.aesEcbDecrypt(aesKey, hexToBytes(serverChallengeRespHex))
            val signature = MoonlightCrypto.signRsaSha256(serverKey, serverSecret)
            return bytesToHex(serverSecret + signature)
        }

        fun verifyClient(clientPairingSecretHex: String): Boolean {
            val secret = hexToBytes(clientPairingSecretHex)
            val clientSecret = secret.copyOfRange(0, 16)
            val clientSignature = secret.copyOfRange(16, secret.size)
            val clientCertPem = MoonlightPairingTestCerts.CLIENT_CERT
            val expected =
                MoonlightCrypto.sha256(serverChallenge, MoonlightCert.signatureOf(clientCertPem), clientSecret)
            if (!MoonlightCrypto.constantTimeEquals(expected, storedClientHash)) return false
            return MoonlightCrypto.verifyRsaSha256(MoonlightCert.publicKeyOf(clientCertPem), clientSecret, clientSignature)
        }
    }

    private object MoonlightPairingTestCerts {
        val CLIENT_CERT: String = resource("moonlight/client_cert.pem")
    }

    private companion object {
        fun resource(path: String): String =
            MoonlightPairingTest::class.java.classLoader!!
                .getResourceAsStream(path)!!
                .use { it.readBytes().toString(Charsets.US_ASCII) }

        fun privateKey(pem: String): PrivateKey {
            val base64 =
                pem
                    .lineSequence()
                    .filterNot { it.startsWith("-----") }
                    .joinToString("")
                    .trim()
            val der =
                java.util.Base64
                    .getDecoder()
                    .decode(base64)
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        }
    }
}
