// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import android.util.Log
import com.tinkernorth.dish.repository.SatellitePinRepository
import com.tinkernorth.dish.repository.mapBackedPrefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession

// Exercises the real TOFU hostname verifier end to end (peer-cert read -> fingerprint ->
// pin/match/mismatch) against a real pin repo, with only the SSLSession + cert mocked.
// The live-socket handshake path remains integration-only.
class SatelliteHttpClientVerifierTest {
    private val sat = "satellite:mid:test"

    @Before
    fun stubAndroidLog() {
        // SatelliteHttpClient logs via android.util.Log, which is unmocked under plain JUnit.
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @After
    fun unstub() {
        unmockkStatic(Log::class)
    }

    private fun sessionWith(der: ByteArray): SSLSession {
        val cert = mockk<X509Certificate>()
        every { cert.encoded } returns der
        val session = mockk<SSLSession>()
        every { session.peerCertificates } returns arrayOf<Certificate>(cert)
        return session
    }

    private fun pinRepo(): SatellitePinRepository = SatellitePinRepository(mapBackedPrefs().first)

    @Test
    fun `first contact pins the presented cert and accepts`() {
        val pins = pinRepo()
        val verifier = SatelliteHttpClient.tofuHostnameVerifier(sat, pins)

        assertTrue(verifier.verify("1.2.3.4", sessionWith(byteArrayOf(1, 2, 3))))
        assertEquals(
            "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
            pins.pinnedFingerprint(sat),
        )
    }

    @Test
    fun `same cert on a later contact matches and accepts without re-pinning`() {
        val pins = pinRepo()
        val verifier = SatelliteHttpClient.tofuHostnameVerifier(sat, pins)
        verifier.verify("1.2.3.4", sessionWith(byteArrayOf(1, 2, 3)))
        val firstPin = pins.pinnedFingerprint(sat)

        assertTrue(verifier.verify("1.2.3.4", sessionWith(byteArrayOf(1, 2, 3))))
        assertEquals("pin must be unchanged on a match", firstPin, pins.pinnedFingerprint(sat))
    }

    @Test
    fun `a different cert after pinning is rejected and the pin is left intact`() {
        val pins = pinRepo()
        val verifier = SatelliteHttpClient.tofuHostnameVerifier(sat, pins)
        verifier.verify("1.2.3.4", sessionWith(byteArrayOf(1, 2, 3)))
        val firstPin = pins.pinnedFingerprint(sat)

        assertFalse(
            "an attacker cert must fail the verifier",
            verifier.verify("1.2.3.4", sessionWith(byteArrayOf(9, 9, 9))),
        )
        assertEquals("a mismatch must not overwrite the trusted pin", firstPin, pins.pinnedFingerprint(sat))
    }

    @Test
    fun `a session without peer certificates is rejected`() {
        val pins = pinRepo()
        val session = mockk<SSLSession>()
        every { session.peerCertificates } returns emptyArray()

        assertFalse(SatelliteHttpClient.tofuHostnameVerifier(sat, pins).verify("1.2.3.4", session))
    }

    @Test
    fun `a mismatch reports through the onMismatch callback`() {
        val pins = pinRepo()
        var mismatches = 0
        val verifier = SatelliteHttpClient.tofuHostnameVerifier(sat, pins, onMismatch = { mismatches++ })
        verifier.verify("1.2.3.4", sessionWith(byteArrayOf(1, 2, 3)))

        verifier.verify("1.2.3.4", sessionWith(byteArrayOf(9, 9, 9)))

        assertEquals(1, mismatches)
    }

    @Test
    fun `first use and match never invoke onMismatch`() {
        val pins = pinRepo()
        var mismatches = 0
        val verifier = SatelliteHttpClient.tofuHostnameVerifier(sat, pins, onMismatch = { mismatches++ })

        verifier.verify("1.2.3.4", sessionWith(byteArrayOf(1, 2, 3)))
        verifier.verify("1.2.3.4", sessionWith(byteArrayOf(1, 2, 3)))

        assertEquals(0, mismatches)
    }

    @Test
    fun `a missing peer certificate does not count as a pin mismatch`() {
        val pins = pinRepo()
        var mismatches = 0
        val session = mockk<SSLSession>()
        every { session.peerCertificates } returns emptyArray()

        SatelliteHttpClient
            .tofuHostnameVerifier(sat, pins, onMismatch = { mismatches++ })
            .verify("1.2.3.4", session)

        assertEquals(0, mismatches)
    }

    @Test
    fun `pins are keyed per satellite id`() {
        val pins = pinRepo()
        SatelliteHttpClient.tofuHostnameVerifier("a", pins).verify("h", sessionWith(byteArrayOf(1, 2, 3)))
        // Same cert presented for a different id is still a first-use pin, not a cross-id match.
        assertTrue(SatelliteHttpClient.tofuHostnameVerifier("b", pins).verify("h", sessionWith(byteArrayOf(4, 5, 6))))

        // Both were first-use accepts on different ids: no mismatch was logged.
        verify(exactly = 0) { Log.e(any<String>(), any<String>()) }
        assertEquals(64, pins.pinnedFingerprint("a")?.length)
        assertEquals(64, pins.pinnedFingerprint("b")?.length)
    }
}
