// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BindingHostOrderTest {
    private fun host(
        id: String,
        kind: ConnectionKind,
    ) = BindingHost(id = id, label = id, kind = kind)

    @Test
    fun `satellites come before moonlight hosts before bluetooth`() {
        val ordered =
            listOf(
                host("bt:1", ConnectionKind.BLUETOOTH),
                host("ml:1", ConnectionKind.MOONLIGHT),
                host("s:1", ConnectionKind.SATELLITE),
            ).orderedForPicker()

        assertEquals(listOf("s:1", "ml:1", "bt:1"), ordered.map { it.id })
    }

    @Test
    fun `hosts of the same kind keep their incoming order`() {
        val ordered =
            listOf(
                host("s:b", ConnectionKind.SATELLITE),
                host("s:a", ConnectionKind.SATELLITE),
                host("ml:b", ConnectionKind.MOONLIGHT),
                host("ml:a", ConnectionKind.MOONLIGHT),
                host("bt:b", ConnectionKind.BLUETOOTH),
                host("bt:a", ConnectionKind.BLUETOOTH),
            ).orderedForPicker()

        assertEquals(listOf("s:b", "s:a", "ml:b", "ml:a", "bt:b", "bt:a"), ordered.map { it.id })
    }

    @Test
    fun `interleaved kinds sort into contiguous tier blocks with stable inner order`() {
        val ordered =
            listOf(
                host("bt:1", ConnectionKind.BLUETOOTH),
                host("s:1", ConnectionKind.SATELLITE),
                host("ml:1", ConnectionKind.MOONLIGHT),
                host("bt:2", ConnectionKind.BLUETOOTH),
                host("s:2", ConnectionKind.SATELLITE),
                host("ml:2", ConnectionKind.MOONLIGHT),
            ).orderedForPicker()

        assertEquals(listOf("s:1", "s:2", "ml:1", "ml:2", "bt:1", "bt:2"), ordered.map { it.id })
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList<BindingHost>(), emptyList<BindingHost>().orderedForPicker())
    }

    @Test
    fun `single host is untouched`() {
        val only = host("bt:1", ConnectionKind.BLUETOOTH)
        val ordered = listOf(only).orderedForPicker()
        assertEquals(listOf(only), ordered)
        assertSame(only, ordered[0])
    }

    @Test
    fun `ordering is idempotent`() {
        val input =
            listOf(
                host("ml:1", ConnectionKind.MOONLIGHT),
                host("bt:1", ConnectionKind.BLUETOOTH),
                host("s:1", ConnectionKind.SATELLITE),
            )
        val once = input.orderedForPicker()
        assertEquals(once, once.orderedForPicker())
    }

    @Test
    fun `ordering does not mutate the input list`() {
        val input =
            mutableListOf(
                host("bt:1", ConnectionKind.BLUETOOTH),
                host("s:1", ConnectionKind.SATELLITE),
            )
        val snapshot = input.toList()

        input.orderedForPicker()

        assertEquals(snapshot, input)
    }

    @Test
    fun `ordering preserves host identity`() {
        val sat = host("s:1", ConnectionKind.SATELLITE)
        val bt = host("bt:1", ConnectionKind.BLUETOOTH)

        val ordered = listOf(bt, sat).orderedForPicker()

        assertSame(sat, ordered[0])
        assertSame(bt, ordered[1])
    }
}
