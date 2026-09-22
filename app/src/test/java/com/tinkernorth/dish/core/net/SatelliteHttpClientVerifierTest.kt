// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import com.tinkernorth.dish.repository.SatellitePinRepository
import com.tinkernorth.dish.repository.mapBackedPrefs
import com.tinkernorth.dish.repository.sha256FingerprintHex
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession

// The hostname verifier runs after TofuTrustManager has pinned inside the handshake; it never
// pins itself, it only confirms the negotiated session carries the pinned certificate.
class SatelliteHttpClientVerifierTest {
    private val sat = "satellite:mid:test"

    private fun sessionWith(der: ByteArray): SSLSession {
        val cert = mockk<X509Certificate>()
        every { cert.encoded } returns der
        val session = mockk<SSLSession>()
        every { session.peerCertificates } returns arrayOf<Certificate>(cert)
        return session
    }

    private fun pinRepo(): SatellitePinRepository = SatellitePinRepository(mapBackedPrefs().first)

    @Test
    fun `a session presenting the pinned cert is accepted`() {
        val pins = pinRepo()
        pins.pin(sat, sha256FingerprintHex(byteArrayOf(1, 2, 3)))

        assertTrue(SatelliteHttpClient(pins).pinnedSessionVerifier(sat).verify("1.2.3.4", sessionWith(byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `a session presenting another cert is rejected`() {
        val pins = pinRepo()
        pins.pin(sat, sha256FingerprintHex(byteArrayOf(1, 2, 3)))

        assertFalse(SatelliteHttpClient(pins).pinnedSessionVerifier(sat).verify("1.2.3.4", sessionWith(byteArrayOf(9, 9, 9))))
    }

    @Test
    fun `an unpinned satellite never passes the verifier, pinning is the trust manager's job`() {
        val pins = pinRepo()

        assertFalse(SatelliteHttpClient(pins).pinnedSessionVerifier(sat).verify("1.2.3.4", sessionWith(byteArrayOf(1, 2, 3))))
        assertFalse("the verifier must not pin", pins.pinnedFingerprint(sat) != null)
    }

    @Test
    fun `a session without peer certificates is rejected`() {
        val pins = pinRepo()
        pins.pin(sat, sha256FingerprintHex(byteArrayOf(1, 2, 3)))
        val session = mockk<SSLSession>()
        every { session.peerCertificates } returns emptyArray()

        assertFalse(SatelliteHttpClient(pins).pinnedSessionVerifier(sat).verify("1.2.3.4", session))
    }
}
