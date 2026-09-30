// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import com.tinkernorth.dish.repository.SatellitePinRepository
import com.tinkernorth.dish.repository.mapBackedPrefs
import com.tinkernorth.dish.repository.sha256FingerprintHex
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession

// The hostname verifier a connection installs is the handshake's own: it holds the session to the
// certificate TofuTrustManager accepted inside that handshake (TofuTrustManagerTest pins what that
// means) and never reads the pin store or pins itself. Every case goes through the verifier
// openConnection installs, so a connection wired to anything weaker fails here. Opening a
// connection does not touch the network; nothing here connects.
class SatelliteHttpClientVerifierTest {
    private val sat = "satellite:mid:test"
    private val pinnedDer = byteArrayOf(1, 2, 3)
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

    private fun ignoreMismatch() = Unit

    private fun opened(pins: SatellitePinRepository): HttpsURLConnection =
        SatelliteHttpClient(pins).openConnection(satelliteUrl, "GET", sat, ::ignoreMismatch)

    private fun installedVerifier(pins: SatellitePinRepository): HostnameVerifier = opened(pins).hostnameVerifier

    @Test
    fun `before its handshake, the installed verifier accepts nothing, the pinned cert included`() {
        // A verifier that read the pin store would take the pinned certificate here. This one holds
        // the session to what its own handshake accepted, and no handshake has run.
        val pins = pinRepo()
        pins.pin(sat, sha256FingerprintHex(pinnedDer))

        assertFalse(installedVerifier(pins).verify(lanHost, sessionWith(pinnedDer)))
    }

    @Test
    fun `the verifier never pins, pinning is the trust manager's job`() {
        val pins = pinRepo()

        assertFalse(installedVerifier(pins).verify(lanHost, sessionWith(pinnedDer)))
        assertNull(pins.pinnedFingerprint(sat))
    }

    @Test
    fun `a session without peer certificates is rejected`() {
        val session = mockk<SSLSession>()
        every { session.peerCertificates } returns emptyArray()

        assertFalse(installedVerifier(pinRepo()).verify(lanHost, session))
    }

    @Test
    fun `a missing session is rejected`() {
        assertFalse(installedVerifier(pinRepo()).verify(lanHost, null))
    }

    @Test
    fun `the connection's socket factory is the handshake's own, not the platform default`() {
        assertNotSame(HttpsURLConnection.getDefaultSSLSocketFactory(), opened(pinRepo()).sslSocketFactory)
    }
}
