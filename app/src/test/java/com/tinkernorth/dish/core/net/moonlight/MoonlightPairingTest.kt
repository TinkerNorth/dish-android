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
 * round-trips through MoonlightCrypto.kt), so a fresh pair each run is fine.
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

    private fun newServer(pin: String) = MoonlightReferenceServer(pin, serverIdentity, clientIdentity.certificatePem)

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

    @Test
    fun `phase 1 names the phrase and carries the client cert as hex`() {
        val p1 = newPairing("0451").phase1Params("dish-uid")
        assertEquals("roth", p1["devicename"])
        assertEquals("1", p1["updateState"])
        assertEquals("getservercert", p1["phrase"])
        assertEquals("dish-uid", p1["uniqueid"])
        assertEquals(bytesToHex(clientIdentity.certificatePem.toByteArray(Charsets.US_ASCII)), p1["clientcert"])
    }

    @Test
    fun `phase 5 asks for the pairchallenge phrase`() {
        assertEquals(mapOf("phrase" to "pairchallenge", "uniqueid" to "dish-uid"), newPairing("0451").phase5Params("dish-uid"))
    }

    @Test
    fun `onPhase2 refuses a challenge response too short to hold the hash and the challenge`() {
        val pairing = newPairing("0451")
        val twoBlocksOnly = bytesToHex(ByteArray(32))
        assertFalse(pairing.onPhase2(twoBlocksOnly))
    }

    @Test
    fun `onPhase3 refuses a pairing secret too short to carry a signature`() {
        val pairing = newPairing("0451")
        val secretWithoutASignature = bytesToHex(ByteArray(16 + 63))
        assertFalse(pairing.onPhase3(secretWithoutASignature))
    }

    @Test
    fun `onPhase3 refuses a server that cannot sign its own secret`() {
        val pin = "0451"
        val server = newServer(pin)
        val pairing = newPairing(pin)
        pairing.onPhase1(server.getServerCert(pairing.phase1Params("dish-uid").getValue("salt")))
        assertTrue(pairing.onPhase2(server.challengeResponse(pairing.phase2Params("dish-uid").getValue("clientchallenge"))))
        val honest = server.clientHashResponse(pairing.phase3Params("dish-uid").getValue("serverchallengeresp"))
        val forgedSignature = flipLastByte(honest)
        assertFalse(pairing.onPhase3(forgedSignature))
    }

    @Test
    fun `onPhase3 refuses a server whose secret does not match the hash it committed to`() {
        val pin = "0451"
        val server = newServer(pin)
        val pairing = newPairing(pin)
        pairing.onPhase1(server.getServerCert(pairing.phase1Params("dish-uid").getValue("salt")))
        assertTrue(pairing.onPhase2(server.challengeResponse(pairing.phase2Params("dish-uid").getValue("clientchallenge"))))
        val honest = server.clientHashResponse(pairing.phase3Params("dish-uid").getValue("serverchallengeresp"))
        val swappedSecret = flipFirstByte(honest)
        assertFalse(pairing.onPhase3(swappedSecret))
    }

    private fun flipLastByte(hex: String): String {
        val bytes = hexToBytes(hex)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        return bytesToHex(bytes)
    }

    private fun flipFirstByte(hex: String): String {
        val bytes = hexToBytes(hex)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        return bytesToHex(bytes)
    }

    private companion object {
        // Minted once for the whole class: JUnit builds a fresh test instance per
        // method and RSA-2048 keygen is the slowest thing in this file.
        val CLIENT = throwawayIdentity("dish-pairing-test-client")
        val SERVER = throwawayIdentity("dish-pairing-test-server")
    }
}
