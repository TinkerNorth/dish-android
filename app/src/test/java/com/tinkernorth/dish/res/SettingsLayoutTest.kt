// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val SUPPORT_CARD = "@+id/cardSupport"
private const val GAP = "@dimen/card_margin_bottom"

/**
 * The Settings support card hides on a build that takes no tips, so the gap between it and the card
 * after it has to belong to the support card: a hidden view's margins do not count.
 */
class SettingsLayoutTest {
    private val layouts = listOf("layout", "layout-sw600dp").map { File("src/main/res/$it/activity_settings.xml") }

    @Test
    fun `the support card carries the gap below it in every Settings layout`() {
        for (layout in layouts) {
            assertEquals(layout.path, GAP, supportCard(layout).getAttributeNS(ANDROID_NS, "layout_marginBottom"))
        }
    }

    @Test
    fun `the card after the support card adds no gap of its own, so hiding the support card leaves none`() {
        for (layout in layouts) {
            val next = nextElement(supportCard(layout))
            assertNull(layout.path, next.getAttributeNodeNS(ANDROID_NS, "layout_marginTop"))
        }
    }

    private fun supportCard(layout: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val cards = factory.newDocumentBuilder().parse(layout).getElementsByTagName("*")
        return (0 until cards.length)
            .map { cards.item(it) as Element }
            .single { it.getAttributeNS(ANDROID_NS, "id") == SUPPORT_CARD }
    }

    private fun nextElement(element: Element): Element =
        generateSequence(element.nextSibling) { it.nextSibling }
            .filterIsInstance<Element>()
            .first()
}
