// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import javax.security.auth.x500.X500Principal

/**
 * The dish's persistent Moonlight client identity, generated once and stored in
 * the Android keystore (Wolf http-pairing.adoc: a self-signed cert + RSA key
 * reused for every host). AndroidKeyStore auto-generates the self-signed
 * certificate for us, keeping the platform-APIs-only, BouncyCastle-free rule and
 * keeping the private key non-exportable. Pairing signs with it via
 * [com.tinkernorth.dish.core.net.moonlight.MoonlightCrypto.signRsaSha256].
 *
 * The pairing crypto is unit-tested against file-backed identities; this
 * keystore path is the runtime supplier and is exercised only on device.
 */
@Singleton
class MoonlightIdentityProvider
    @Inject
    constructor() : MoonlightIdentity {
        private val identity: LoadedIdentity by lazy { loadOrCreate() }

        override val certificatePem: String get() = identity.certificatePem
        override val certificateSignature: ByteArray get() = identity.certificate.signature
        override val privateKey: PrivateKey get() = identity.privateKey

        private data class LoadedIdentity(
            val certificate: X509Certificate,
            val certificatePem: String,
            val privateKey: PrivateKey,
        )

        private fun loadOrCreate(): LoadedIdentity {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(ALIAS)) generateKeyPair()
            val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
            val privateKey = keyStore.getKey(ALIAS, null) as PrivateKey
            return LoadedIdentity(certificate, toPem(certificate), privateKey)
        }

        private fun generateKeyPair() {
            val notBefore = Calendar.getInstance()
            val notAfter = (notBefore.clone() as Calendar).apply { add(Calendar.YEAR, CERT_VALIDITY_YEARS) }
            val spec =
                KeyGenParameterSpec
                    .Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(RSA_KEY_SIZE)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal(CERT_SUBJECT))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(notBefore.time)
                    .setCertificateNotAfter(notAfter.time)
                    .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
                initialize(spec)
                generateKeyPair()
            }
        }

        private fun toPem(certificate: X509Certificate): String {
            // android.util.Base64 (API 1) instead of java.util.Base64 (API 26); wrap at the
            // PEM 64-char width manually since NO_WRAP emits a single line.
            val body =
                android.util.Base64
                    .encodeToString(certificate.encoded, android.util.Base64.NO_WRAP)
                    .chunked(PEM_LINE_LEN)
                    .joinToString("\n")
            return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
        }

        private companion object {
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
            const val ALIAS = "dish-moonlight-client"
            const val RSA_KEY_SIZE = 2048
            const val CERT_VALIDITY_YEARS = 20
            const val CERT_SUBJECT = "CN=NVIDIA GameStream Client"
            const val PEM_LINE_LEN = 64
        }
    }
