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
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

// Exercises the real TOFU trust decision end to end (leaf cert -> fingerprint ->
// pin/match/mismatch) against a real pin repo, with only the certificate mocked.
// The live-socket handshake path remains integration-only.
class TofuTrustManagerTest {
    private val sat = "satellite:mid:test"

    @Before
    fun stubAndroidLog() {
        // TofuTrustManager logs via android.util.Log, which is unmocked under plain JUnit.
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @After
    fun unstub() {
        unmockkStatic(Log::class)
    }

    private fun chainWith(der: ByteArray): Array<X509Certificate> {
        val cert = mockk<X509Certificate>()
        every { cert.encoded } returns der
        return arrayOf(cert)
    }

    private fun pinRepo(): SatellitePinRepository = SatellitePinRepository(mapBackedPrefs().first)

    @Test
    fun `first contact pins the presented cert and accepts`() {
        val pins = pinRepo()

        TofuTrustManager(sat, pins).checkServerTrusted(chainWith(byteArrayOf(1, 2, 3)), "ECDHE_ECDSA")

        assertEquals(
            "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
            pins.pinnedFingerprint(sat),
        )
    }

    @Test
    fun `same cert on a later contact matches and accepts without re-pinning`() {
        val pins = pinRepo()
        val manager = TofuTrustManager(sat, pins)
        manager.checkServerTrusted(chainWith(byteArrayOf(1, 2, 3)), "ECDHE_ECDSA")
        val firstPin = pins.pinnedFingerprint(sat)

        manager.checkServerTrusted(chainWith(byteArrayOf(1, 2, 3)), "ECDHE_ECDSA")

        assertEquals("pin must be unchanged on a match", firstPin, pins.pinnedFingerprint(sat))
    }

    @Test
    fun `a different cert after pinning fails the handshake and the pin is left intact`() {
        val pins = pinRepo()
        val manager = TofuTrustManager(sat, pins)
        manager.checkServerTrusted(chainWith(byteArrayOf(1, 2, 3)), "ECDHE_ECDSA")
        val firstPin = pins.pinnedFingerprint(sat)

        assertThrows("an attacker cert must fail the trust manager", CertificateException::class.java) {
            manager.checkServerTrusted(chainWith(byteArrayOf(9, 9, 9)), "ECDHE_ECDSA")
        }
        assertEquals("a mismatch must not overwrite the trusted pin", firstPin, pins.pinnedFingerprint(sat))
    }

    @Test
    fun `an empty chain is rejected`() {
        assertThrows(CertificateException::class.java) {
            TofuTrustManager(sat, pinRepo()).checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
        }
        assertThrows(CertificateException::class.java) {
            TofuTrustManager(sat, pinRepo()).checkServerTrusted(null, "ECDHE_ECDSA")
        }
    }

    @Test
    fun `a client certificate is never vouched for`() {
        assertThrows(CertificateException::class.java) {
            TofuTrustManager(sat, pinRepo()).checkClientTrusted(chainWith(byteArrayOf(1, 2, 3)), "RSA")
        }
    }

    @Test
    fun `a mismatch reports through the onMismatch callback`() {
        val pins = pinRepo()
        var mismatches = 0
        val manager = TofuTrustManager(sat, pins, onMismatch = { mismatches++ })
        manager.checkServerTrusted(chainWith(byteArrayOf(1, 2, 3)), "ECDHE_ECDSA")

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(chainWith(byteArrayOf(9, 9, 9)), "ECDHE_ECDSA")
        }

        assertEquals(1, mismatches)
    }

    @Test
    fun `first use and match never invoke onMismatch`() {
        val pins = pinRepo()
        var mismatches = 0
        val manager = TofuTrustManager(sat, pins, onMismatch = { mismatches++ })

        manager.checkServerTrusted(chainWith(byteArrayOf(1, 2, 3)), "ECDHE_ECDSA")
        manager.checkServerTrusted(chainWith(byteArrayOf(1, 2, 3)), "ECDHE_ECDSA")

        assertEquals(0, mismatches)
    }

    @Test
    fun `a missing certificate does not count as a pin mismatch`() {
        var mismatches = 0

        assertThrows(CertificateException::class.java) {
            TofuTrustManager(sat, pinRepo(), onMismatch = { mismatches++ }).checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
        }

        assertEquals(0, mismatches)
    }

    @Test
    fun `pins are keyed per host id`() {
        val pins = pinRepo()
        TofuTrustManager("a", pins).checkServerTrusted(chainWith(byteArrayOf(1, 2, 3)), "ECDHE_ECDSA")
        // Same cert presented for a different id is still a first-use pin, not a cross-id match.
        TofuTrustManager("b", pins).checkServerTrusted(chainWith(byteArrayOf(4, 5, 6)), "ECDHE_ECDSA")

        // Both were first-use accepts on different ids: no mismatch was logged.
        verify(exactly = 0) { Log.e(any<String>(), any<String>()) }
        assertEquals(64, pins.pinnedFingerprint("a")?.length)
        assertEquals(64, pins.pinnedFingerprint("b")?.length)
    }
}
