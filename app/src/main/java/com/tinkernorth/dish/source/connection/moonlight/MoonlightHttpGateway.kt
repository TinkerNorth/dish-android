// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.util.Log
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.repository.SatellitePinRepository
import com.tinkernorth.dish.repository.TofuVerdict
import com.tinkernorth.dish.repository.sha256FingerprintHex
import com.tinkernorth.dish.repository.tofuVerdict
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Opens the Moonlight HTTP (47989, plaintext) and HTTPS (47984, mutual-TLS)
 * requests. HTTPS presents the dish's client certificate (the host authorises
 * paired clients purely by the client cert, Wolf custom-https.cpp) and pins the
 * host cert on first use, mirroring the satellite
 * [com.tinkernorth.dish.core.net.SatelliteHttpClient] TOFU verifier.
 *
 * Both halves ride one raw socket per request via [MoonlightHttp11Client],
 * which carries the reasoning for keeping the platform URL stack out of this
 * path: cleartext is denied to it app-wide, and its connection pool cannot
 * reuse a connection whose per-host verifier differs, so it leaked one open TLS
 * session per call. [getHttps] documents what that did to real hosts.
 *
 * All methods BLOCK; call from Dispatchers.IO. This is runtime plumbing; the URL
 * building and XML parsing it drives are unit-tested separately.
 */
@Singleton
class MoonlightHttpGateway
    @Inject
    constructor(
        private val identity: MoonlightIdentity,
        private val pins: SatellitePinRepository,
    ) {
        data class Reply(
            val status: Int,
            val body: String,
        ) {
            val unreachable: Boolean get() = status == 0 || body.isBlank()
            val ok: Boolean get() = status in 200..299
        }

        private val plain = MoonlightHttp11Client(TIMEOUT_MS, TIMEOUT_MS)

        /**
         * Present the client certificate; the host authorises by it after pairing.
         *
         * Built once. It is what a TLS connection is keyed on, it is not cheap
         * (a keystore load and a KeyManagerFactory per call), and the identity
         * behind it never changes for the life of the process.
         */
        private val tlsFactory: SSLSocketFactory by lazy { mutualTlsFactory() }

        /**
         * Plaintext GET (serverinfo / pair phases 1-4).
         *
         * Goes over a raw socket, not the URL stack: those phases are plaintext by
         * protocol and res/xml/network_security_config.xml denies cleartext to the
         * URL stack app-wide on purpose. [MoonlightHttp11Client] documents why
         * the carve-out is scoped this way and why it is safe.
         *
         * [readTimeoutMs] is the caller's to raise for a request the host holds
         * open on purpose; see [PAIR_PIN_TIMEOUT_MS].
         */
        fun getHttp(
            url: String,
            readTimeoutMs: Int = TIMEOUT_MS,
        ): Reply = plain.get(url, readTimeoutMs)

        /**
         * Mutual-TLS GET (serverinfo / pair phase 5 / applist / launch / resume /
         * cancel), over its own socket, closed as soon as the host has answered.
         *
         * This used to ride HttpsURLConnection, and against a real Sunshine host
         * every call after the first one timed out. The URL stack pools
         * connections and reuses one only when the Address matches, and an
         * Address carries the SSLSocketFactory and HostnameVerifier instances.
         * Both were built per call, the verifier necessarily per host, so no two
         * calls ever matched: each one dialled a fresh TLS connection, and
         * `disconnect()` parked the old one in the pool instead of closing it.
         * The host was left holding one idle session per call we had made
         * (measured on the host's own socket table: eleven, none of them closed
         * by us until the app's process died), and its HTTPS listener answered
         * nothing at all from then on, ours or anyone else's, so every later
         * request sat in its TLS handshake until the read timeout. One socket
         * per request, closed here, with the `Connection: close`
         * [MoonlightHttp11Client] already sends, leaves the host holding nothing
         * of ours between calls, which is how the plaintext half has always
         * behaved and the half that never had this problem.
         */
        fun getHttps(
            urlString: String,
            hostId: String,
        ): Reply =
            MoonlightHttp11Client(HTTPS_TIMEOUT_MS, HTTPS_TIMEOUT_MS) { socket, host, port ->
                openTls(socket, host, port, hostId)
            }.get(urlString)

        /**
         * Hands back a handshaken TLS socket that presents the dish's client
         * certificate, or throws once the host's certificate fails the pin.
         * Throwing is the rejection: [MoonlightHttp11Client] never writes a
         * request through a socket it did not get back.
         */
        private fun openTls(
            socket: Socket,
            host: String,
            port: Int,
            hostId: String,
        ): Socket {
            val tls = tlsFactory.createSocket(socket, host, port, true) as SSLSocket
            tls.startHandshake()
            val presented =
                tls.session
                    .peerCertificates
                    ?.firstOrNull()
                    ?: throw SSLPeerUnverifiedException("$host presented no certificate")
            if (!pinAccepts(hostId, sha256FingerprintHex(presented.encoded))) {
                tls.close()
                throw SSLPeerUnverifiedException("cert pin mismatch for $hostId")
            }
            return tls
        }

        // Present the client certificate; the host authorises by it after pairing.
        private fun mutualTlsFactory(): SSLSocketFactory {
            val keyStore =
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null)
                    setKeyEntry(
                        "client",
                        identity.privateKey,
                        CharArray(0),
                        arrayOf(
                            com.tinkernorth.dish.core.net.moonlight.MoonlightCert
                                .parse(identity.certificatePem),
                        ),
                    )
                }
            val kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(keyStore, CharArray(0))
                }
            return SSLContext
                .getInstance("TLS")
                .apply { init(kmf.keyManagers, arrayOf(trustAll), SecureRandom()) }
                .socketFactory
        }

        // TOFU: accept any self-signed host cert on first contact and pin it, then
        // reject any future mismatch (the sole MITM gate; the LAN cert has no CA).
        private fun pinAccepts(
            hostId: String,
            presented: String,
        ): Boolean =
            when (tofuVerdict(pins.pinnedFingerprint(hostId), presented)) {
                TofuVerdict.TRUST_FIRST_USE -> {
                    pins.pin(hostId, presented)
                    true
                }
                TofuVerdict.MATCH -> true
                TofuVerdict.MISMATCH -> {
                    Log.e(TAG, "cert pin MISMATCH for $hostId, aborting (possible MITM)")
                    false
                }
            }

        @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
        private val trustAll: TrustManager =
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) = Unit

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) = Unit

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

        companion object {
            private const val TAG = "MoonlightHttpGateway"
            private const val TIMEOUT_MS = 5_000

            /**
             * The HTTPS half's budget. Wider than the plaintext one because every
             * call now pays for its own TLS handshake, and the dish's half of that
             * handshake is a signature from a hardware-backed keystore key: the
             * first one after a cold start waits on keystore IPC and, on a locked
             * or busy device, on the secure element itself. Still short enough to
             * fail a probe of an absent host quickly.
             */
            private const val HTTPS_TIMEOUT_MS = 10_000

            /**
             * Read timeout for pairing phase 1. The host does not answer that one
             * until a human has typed the displayed PIN into its own web UI
             * (Sunshine parks the response and only completes it on PIN entry), so
             * the ordinary 5s probe timeout tears the request down before anybody
             * could reach a browser, and the host drops its half-open pairing
             * session with it. This is the human's window, not the network's.
             */
            const val PAIR_PIN_TIMEOUT_MS = 120_000
        }
    }
