// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.util.Log
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import com.tinkernorth.dish.repository.SatellitePinRepository
import com.tinkernorth.dish.repository.TofuVerdict
import com.tinkernorth.dish.repository.sha256FingerprintHex
import com.tinkernorth.dish.repository.tofuVerdict
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Opens the Moonlight HTTP (47989, plaintext) and HTTPS (47984, mutual-TLS)
 * requests. HTTPS presents the dish's client certificate (the host authorises
 * paired clients purely by the client cert, Wolf custom-https.cpp) and pins the
 * host cert on first use, mirroring the satellite
 * [com.tinkernorth.dish.core.net.SatelliteHttpClient] TOFU verifier.
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

        /** Plaintext GET (serverinfo / pair phases 1-4). */
        fun getHttp(url: String): Reply = request(url, secure = false, hostId = null)

        /** Mutual-TLS GET (serverinfo / pair phase 5 / applist / launch / resume / cancel). */
        fun getHttps(
            url: String,
            hostId: String,
        ): Reply = request(url, secure = true, hostId = hostId)

        private fun request(
            urlString: String,
            secure: Boolean,
            hostId: String?,
        ): Reply {
            val url = URL(urlString)
            var connection: java.net.HttpURLConnection? = null
            return try {
                connection =
                    openConnection(url, secure, hostId).apply {
                        requestMethod = "GET"
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
                    }
                readReply(connection)
            } catch (e: IOException) {
                Log.w(TAG, "request failed for ${url.path}: ${e.message}")
                Reply(0, "")
            } finally {
                connection?.disconnect()
            }
        }

        private fun openConnection(
            url: URL,
            secure: Boolean,
            hostId: String?,
        ): java.net.HttpURLConnection {
            val raw = url.openConnection()
            if (!secure) return raw as java.net.HttpURLConnection
            return (raw as HttpsURLConnection).apply {
                sslSocketFactory = mutualTlsFactory()
                hostnameVerifier = tofuVerifier(hostId!!)
            }
        }

        private fun readReply(connection: java.net.HttpURLConnection): Reply {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return Reply(status, text)
        }

        // Present the client certificate; the host authorises by it after pairing.
        private fun mutualTlsFactory(): javax.net.ssl.SSLSocketFactory {
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
        private fun tofuVerifier(hostId: String): HostnameVerifier =
            HostnameVerifier { _: String?, session: SSLSession? ->
                val cert = session?.peerCertificates?.firstOrNull() ?: return@HostnameVerifier false
                val presented = sha256FingerprintHex(cert.encoded)
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

        private companion object {
            const val TAG = "MoonlightHttpGateway"
            const val TIMEOUT_MS = 5_000
        }
    }
