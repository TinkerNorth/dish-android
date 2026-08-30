// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import com.tinkernorth.dish.core.net.moonlight.MoonlightIdentity
import java.math.BigInteger
import java.security.KeyFactory
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
 * [com.tinkernorth.dish.core.net.moonlight.MoonlightCrypto.signRsaSha256], and
 * the same key authenticates the dish on every mutual-TLS call afterwards, so
 * it is generated with the authorizations both of those need.
 *
 * A key stored by an earlier build carries only the narrower pairing-signature
 * authorizations and cannot do the second job; [decideMoonlightIdentity] spots
 * that and this class replaces it.
 *
 * The pairing crypto is unit-tested against throwaway generated identities and
 * the migration decision is unit-tested on its own; this keystore path is the
 * runtime supplier and is exercised only on device.
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
            val stored = readEntry(keyStore)
            val decision =
                decideMoonlightIdentity(
                    aliasPresent = keyStore.containsAlias(ALIAS),
                    entryReadable = stored != null,
                    tlsClientAuthCapable = stored != null && isTlsClientAuthCapable(stored.privateKey),
                )
            if (decision == MoonlightIdentityDecision.REUSE) return checkNotNull(stored)
            if (decision == MoonlightIdentityDecision.REGENERATE) {
                Log.i(TAG, "replacing the stored Moonlight identity: its key cannot do TLS client auth")
                keyStore.deleteEntry(ALIAS)
            }
            generateKeyPair()
            return checkNotNull(readEntry(keyStore)) { "keystore did not return the identity it just generated" }
        }

        /** The stored cert+key, or null when the alias holds nothing usable. */
        private fun readEntry(keyStore: KeyStore): LoadedIdentity? {
            val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate ?: return null
            val privateKey = keyStore.getKey(ALIAS, null) as? PrivateKey ?: return null
            return LoadedIdentity(certificate, toPem(certificate), privateKey)
        }

        /**
         * Reads [key]'s own authorization list and asks [supportsTlsClientAuth]
         * about it. A key whose KeyInfo cannot be read at all (not a keystore key,
         * or a provider that will not describe it) counts as incapable, which
         * routes it to regeneration rather than to another failed handshake.
         */
        private fun isTlsClientAuthCapable(key: PrivateKey): Boolean =
            runCatching {
                val info =
                    KeyFactory
                        .getInstance(key.algorithm, ANDROID_KEYSTORE)
                        .getKeySpec(key, KeyInfo::class.java)
                supportsTlsClientAuth(info.purposes, info.digests, info.encryptionPaddings)
            }.getOrDefault(false)

        /**
         * Mints the client identity. The authorizations are wider than the pairing
         * signature alone needs because the same key also has to satisfy Conscrypt
         * during TLS client auth; [supportsTlsClientAuth] documents which keymaster
         * operation each one unlocks. The key itself stays non-exportable in
         * AndroidKeyStore, so widening what it may be asked to do does not widen
         * who can extract it.
         */
        private fun generateKeyPair() {
            val notBefore = Calendar.getInstance()
            val notAfter = (notBefore.clone() as Calendar).apply { add(Calendar.YEAR, CERT_VALIDITY_YEARS) }
            val spec =
                KeyGenParameterSpec
                    .Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(RSA_KEY_SIZE)
                    // DIGEST_NONE: raw-RSA TLS signing hands over an already-digested
                    // (and, for PSS, already-encoded) block. SHA-256: the pairing signature.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    // Spells KM_PAD_NONE, the padding a raw private-key operation runs
                    // under. Both padding setters feed one KM_TAG_PADDING list, and the
                    // purpose stays SIGN-only, so this authorizes raw signing, not
                    // decryption.
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
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
            const val TAG = "MoonlightIdentity"
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
            const val ALIAS = "dish-moonlight-client"
            const val RSA_KEY_SIZE = 2048
            const val CERT_VALIDITY_YEARS = 20
            const val CERT_SUBJECT = "CN=NVIDIA GameStream Client"
            const val PEM_LINE_LEN = 64
        }
    }
