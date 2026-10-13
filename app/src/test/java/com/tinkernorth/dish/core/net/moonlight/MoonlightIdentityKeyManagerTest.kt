// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MoonlightIdentityKeyManagerTest {
    private val held = throwawayCertificate("dish-key-manager-test")
    private val identity = opaqueIdentityOf(held)
    private val manager = MoonlightIdentityKeyManager(identity)

    @Test
    fun `offers its one client alias whatever key type the host asks for`() {
        val alias = manager.chooseClientAlias(arrayOf("EC", "RSA"), null, null)

        assertEquals(alias, manager.chooseEngineClientAlias(null, null, null))
        assertArrayEquals(arrayOf(alias), manager.getClientAliases("RSA", null))
    }

    @Test
    fun `hands over the identity's key object itself, and its certificate`() {
        val alias = manager.chooseClientAlias(null, null, null)

        assertSame(identity.privateKey, manager.getPrivateKey(alias))
        assertArrayEquals(held.certificate.encoded, manager.getCertificateChain(alias)?.single()?.encoded)
    }

    @Test
    fun `knows no other alias and serves no server side`() {
        assertNull(manager.getPrivateKey("other"))
        assertNull(manager.getCertificateChain("other"))
        assertNull(manager.chooseServerAlias("RSA", null, null))
        assertNull(manager.getServerAliases("RSA", null))
    }
}
