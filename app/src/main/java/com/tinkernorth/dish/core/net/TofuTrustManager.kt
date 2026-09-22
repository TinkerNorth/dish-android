// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import android.util.Log
import com.tinkernorth.dish.repository.SatellitePinRepository
import com.tinkernorth.dish.repository.TofuVerdict
import com.tinkernorth.dish.repository.sha256FingerprintHex
import com.tinkernorth.dish.repository.tofuVerdict
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trust-on-first-use for a LAN host that presents a self-signed certificate: the first
 * handshake with [hostId] pins the presented certificate's SHA-256 fingerprint, every later
 * one must present that same certificate or the handshake fails. There is no CA to validate
 * against (satellite and Moonlight hosts mint their own certificate), so the pin is the
 * whole validation and the trust decision lives here, inside the handshake, rather than in a
 * verifier that runs after a permissive one completed.
 *
 * TRADEOFF: the first contact has no prior pin, so that one handshake is unauthenticated (an
 * attacker already on-path at pairing time can still impersonate). Both gateways document
 * this at their pairing entry points.
 *
 * Marker on the class: lint's CustomX509TrustManager reports every X509TrustManager written in
 * source, whatever it checks, because the platform trust store is the safer place for a trust
 * decision. That store needs the certificate in hand before the first handshake, and a
 * certificate you do not have yet is what trust-on-first-use is for, so no platform-managed
 * form of this exists. The check it guards against, a manager that accepts anything, is what
 * [checkServerTrusted] refuses to be.
 */
@Suppress("CustomX509TrustManager")
internal class TofuTrustManager(
    private val hostId: String,
    private val pins: SatellitePinRepository,
    private val onMismatch: () -> Unit = {},
) : X509TrustManager {
    // Only ever the client side of a handshake; a peer asking this side to vouch for a
    // client certificate is outside the contract, so it is refused, never waved through.
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ): Unit = throw CertificateException("client certificates are not accepted for $hostId")

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("$hostId presented no certificate")
        val presented = sha256FingerprintHex(leaf.encoded)
        when (tofuVerdict(pins.pinnedFingerprint(hostId), presented)) {
            TofuVerdict.TRUST_FIRST_USE -> {
                pins.pin(hostId, presented)
                Log.i(TAG, "pinned cert for $hostId on first use")
            }
            TofuVerdict.MATCH -> Unit
            TofuVerdict.MISMATCH -> {
                Log.e(TAG, "cert pin MISMATCH for $hostId, aborting (possible MITM)")
                onMismatch()
                throw CertificateException("cert pin mismatch for $hostId")
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private companion object {
        const val TAG = "TofuTrustManager"
    }
}
