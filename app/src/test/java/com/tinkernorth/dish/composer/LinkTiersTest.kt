// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTiersTest {
    @Test
    fun `satellite is the fastest tier`() {
        assertEquals(LinkTier.FASTEST, linkTierFor(ConnectionKind.SATELLITE))
    }

    @Test
    fun `moonlight host is the fast tier`() {
        assertEquals(LinkTier.FAST, linkTierFor(ConnectionKind.MOONLIGHT))
    }

    @Test
    fun `bluetooth is the basic tier`() {
        assertEquals(LinkTier.BASIC, linkTierFor(ConnectionKind.BLUETOOTH))
    }

    @Test
    fun `every kind resolves to a tier`() {
        for (kind in ConnectionKind.entries) linkTierFor(kind)
    }

    @Test
    fun `tiers are declared best-first`() {
        assertTrue(LinkTier.FASTEST.ordinal < LinkTier.FAST.ordinal)
        assertTrue(LinkTier.FAST.ordinal < LinkTier.BASIC.ordinal)
    }

    @Test
    fun `each tier is claimed by exactly one kind`() {
        val tiers = ConnectionKind.entries.map { linkTierFor(it) }
        assertEquals(LinkTier.entries.toSet(), tiers.toSet())
        assertEquals(tiers.size, tiers.toSet().size)
    }

    @Test
    fun `comparator orders satellite before moonlight before bluetooth`() {
        val sorted =
            listOf(
                ConnectionKind.BLUETOOTH,
                ConnectionKind.MOONLIGHT,
                ConnectionKind.SATELLITE,
            ).sortedWith(comparatorByLinkTier { it })

        assertEquals(
            listOf(ConnectionKind.SATELLITE, ConnectionKind.MOONLIGHT, ConnectionKind.BLUETOOTH),
            sorted,
        )
    }

    @Test
    fun `comparator treats same-kind entries as equal`() {
        val cmp = comparatorByLinkTier<ConnectionKind> { it }
        for (kind in ConnectionKind.entries) {
            assertEquals(0, cmp.compare(kind, kind))
        }
    }

    @Test
    fun `comparator is consistent with the tier declaration order`() {
        val cmp = comparatorByLinkTier<ConnectionKind> { it }
        assertTrue(cmp.compare(ConnectionKind.SATELLITE, ConnectionKind.MOONLIGHT) < 0)
        assertTrue(cmp.compare(ConnectionKind.MOONLIGHT, ConnectionKind.BLUETOOTH) < 0)
        assertTrue(cmp.compare(ConnectionKind.SATELLITE, ConnectionKind.BLUETOOTH) < 0)
        assertTrue(cmp.compare(ConnectionKind.BLUETOOTH, ConnectionKind.SATELLITE) > 0)
    }

    @Test
    fun `comparator extracts the kind through the selector`() {
        data class Row(
            val name: String,
            val kind: ConnectionKind,
        )

        val sorted =
            listOf(
                Row("bt", ConnectionKind.BLUETOOTH),
                Row("sat", ConnectionKind.SATELLITE),
                Row("ml", ConnectionKind.MOONLIGHT),
            ).sortedWith(comparatorByLinkTier(Row::kind))

        assertEquals(listOf("sat", "ml", "bt"), sorted.map { it.name })
    }
}
