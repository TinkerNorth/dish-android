// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Hands the handshake the identity's own [PrivateKey]. The Android keystore key behind it exports
 * no key material, so it cannot pass through a KeyStore the way a KeyManagerFactory wants it to.
 */
internal class MoonlightIdentityKeyManager(
    private val identity: MoonlightIdentity,
) : X509ExtendedKeyManager() {
    private val chain: Array<X509Certificate> by lazy { arrayOf(parseMoonlightCert(identity.certificatePem)) }

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String = ALIAS

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String = ALIAS

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String> = arrayOf(ALIAS)

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = chain.takeIf { alias == ALIAS }

    override fun getPrivateKey(alias: String?): PrivateKey? = identity.privateKey.takeIf { alias == ALIAS }

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = null

    private companion object {
        const val ALIAS = "client"
    }
}
