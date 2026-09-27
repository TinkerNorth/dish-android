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
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession

// The hostname verifier runs after TofuTrustManager has pinned inside the handshake; it never
// pins itself, it only confirms the negotiated session carries the pinned certificate.
class SatelliteHttpClientVerifierTest {
    private val sat = "satellite:mid:test"
    private val pinnedDer = byteArrayOf(1, 2, 3)
    private val otherDer = byteArrayOf(9, 9, 9)

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

    @Test
    fun `a session presenting the pinned cert is accepted`() {
        assertTrue(clientPinnedTo(pinnedDer).verifyPinnedSession(sat, sessionWith(pinnedDer)))
    }

    @Test
    fun `a session presenting another cert is rejected`() {
        assertFalse(clientPinnedTo(pinnedDer).verifyPinnedSession(sat, sessionWith(otherDer)))
    }

    @Test
    fun `an unpinned satellite never passes the verifier, pinning is the trust manager's job`() {
        val pins = pinRepo()

        assertFalse(SatelliteHttpClient(pins).verifyPinnedSession(sat, sessionWith(pinnedDer)))
        assertNull("the verifier must not pin", pins.pinnedFingerprint(sat))
    }

    @Test
    fun `a session without peer certificates is rejected`() {
        val session = mockk<SSLSession>()
        every { session.peerCertificates } returns emptyArray()

        assertFalse(clientPinnedTo(pinnedDer).verifyPinnedSession(sat, session))
    }

    @Test
    fun `a missing session is rejected`() {
        assertFalse(clientPinnedTo(pinnedDer).verifyPinnedSession(sat, null))
    }
}
