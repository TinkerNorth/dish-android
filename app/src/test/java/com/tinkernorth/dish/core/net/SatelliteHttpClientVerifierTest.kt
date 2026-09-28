// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import com.tinkernorth.dish.repository.SatellitePinRepository
import com.tinkernorth.dish.repository.mapBackedPrefs
import com.tinkernorth.dish.repository.sha256FingerprintHex
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession

// The hostname verifier runs after TofuTrustManager has pinned inside the handshake; it never
// pins itself, it only confirms the negotiated session carries the pinned certificate. Every case
// goes through the verifier openConnection installs, so a connection wired to anything weaker
// fails here. Opening a connection does not touch the network; nothing here connects.
class SatelliteHttpClientVerifierTest {
    private val sat = "satellite:mid:test"
    private val pinnedDer = byteArrayOf(1, 2, 3)
    private val otherDer = byteArrayOf(9, 9, 9)
    private val satelliteUrl = URI("https://192.0.2.1:47990/api/connections").toURL()
    private val lanHost = "192.0.2.1"

    private fun sessionWith(der: ByteArray): SSLSession {
        val cert = mockk<X509Certificate>()
        every { cert.encoded } returns der
        val session = mockk<SSLSession>()
        every { session.peerCertificates } returns arrayOf<Certificate>(cert)
        return session
    }

    private fun pinRepo(): SatellitePinRepository = SatellitePinRepository(mapBackedPrefs().first)

    private fun clientPinnedTo(der: ByteArray): SatelliteHttpClient {
        val pins = pinRepo()
        pins.pin(sat, sha256FingerprintHex(der))
        return SatelliteHttpClient(pins)
    }

    private fun ignoreMismatch() = Unit

    private fun installedVerifier(client: SatelliteHttpClient): HostnameVerifier =
        client.openConnection(satelliteUrl, "GET", sat, ::ignoreMismatch).hostnameVerifier

    @Test
    fun `a session presenting the pinned cert is accepted`() {
        val verifier = installedVerifier(clientPinnedTo(pinnedDer))

        assertTrue(verifier.verify(lanHost, sessionWith(pinnedDer)))
    }

    @Test
    fun `a session presenting another cert is rejected`() {
        val verifier = installedVerifier(clientPinnedTo(pinnedDer))

        assertFalse(verifier.verify(lanHost, sessionWith(otherDer)))
    }

    @Test
    fun `an unpinned satellite never passes the verifier, pinning is the trust manager's job`() {
        val pins = pinRepo()
        val verifier = installedVerifier(SatelliteHttpClient(pins))

        assertFalse(verifier.verify(lanHost, sessionWith(pinnedDer)))
        assertNull("the verifier must not pin", pins.pinnedFingerprint(sat))
    }

    @Test
    fun `a session without peer certificates is rejected`() {
        val session = mockk<SSLSession>()
        every { session.peerCertificates } returns emptyArray()
        val verifier = installedVerifier(clientPinnedTo(pinnedDer))

        assertFalse(verifier.verify(lanHost, session))
    }

    @Test
    fun `a missing session is rejected`() {
        val verifier = installedVerifier(clientPinnedTo(pinnedDer))

        assertFalse(verifier.verify(lanHost, null))
    }
}
