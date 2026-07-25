// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.composer.CapabilityResolver
import com.tinkernorth.dish.core.model.CatalogDto
import com.tinkernorth.dish.core.model.CatalogHostFeatureDto
import com.tinkernorth.dish.core.model.CatalogTypeDto
import com.tinkernorth.dish.core.model.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCatalogTranslatorTest {
    private val translator = LegacyCatalogTranslator()

    @Test
    fun `an absent version normalizes to the hardcoded v1 catalog`() {
        // CatalogDto() defaults catalogVersion to 1: a satellite predating the field.
        val out = translator.normalize(CatalogDto())

        assertEquals(1, out.catalogVersion)
        assertEquals(2, out.controllerTypes.size)

        val xbox = out.controllerTypes[0]
        assertEquals(0, xbox.id)
        assertEquals("xbox360", xbox.slug)
        assertEquals(setOf("analogTriggers", "rumble"), xbox.features.filterValues { it.supported }.keys)

        val ds4 = out.controllerTypes[1]
        assertEquals(1, ds4.id)
        assertEquals("ds4", ds4.slug)
        assertEquals(
            setOf("analogTriggers", "rumble", "motion", "touchpad", "lightbar"),
            ds4.features.filterValues { it.supported }.keys,
        )
        assertEquals(listOf("ds4"), ds4.features["touchpad"]?.modes) // the DS4 pad-mode gate
    }

    @Test
    fun `the hardcoded features drive the expected type capabilities`() {
        val out = translator.normalize(CatalogDto())
        val xboxCaps = CapabilityResolver.typeCapabilities(out.controllerTypes[0])
        val ds4Caps = CapabilityResolver.typeCapabilities(out.controllerTypes[1])

        assertTrue(Feature.RUMBLE in xboxCaps)
        assertFalse(Feature.MOTION in xboxCaps)
        assertFalse(Feature.TOUCHPAD in xboxCaps)
        assertFalse(Feature.LIGHTBAR in xboxCaps)

        assertTrue(Feature.RUMBLE in ds4Caps)
        assertTrue(Feature.MOTION in ds4Caps)
        assertTrue(Feature.TOUCHPAD in ds4Caps)
        assertTrue(Feature.LIGHTBAR in ds4Caps)
    }

    @Test
    fun `a legacy body's own controllerTypes are discarded, not echoed`() {
        val junk =
            CatalogDto(
                controllerTypes =
                    listOf(
                        CatalogTypeDto(id = 99, slug = "frobnicator"),
                        CatalogTypeDto(id = 42, slug = "ds4", name = "Wrong"),
                    ),
            )
        val out = translator.normalize(junk)

        assertEquals(listOf("xbox360", "ds4"), out.controllerTypes.map { it.slug })
        assertEquals(listOf(0, 1), out.controllerTypes.map { it.id })
    }

    @Test
    fun `legacy normalization passes locale, server version and host features through`() {
        val legacy =
            CatalogDto(
                locale = "de",
                serverVersion = "1.6.0",
                controllerTypes = listOf(CatalogTypeDto(id = 7, slug = "hyperpad")),
                hostFeatures = mapOf("mouseControl" to CatalogHostFeatureDto(supported = true)),
            )
        val out = translator.normalize(legacy)

        assertEquals("de", out.locale)
        assertEquals("1.6.0", out.serverVersion)
        assertEquals(true, out.hostFeatures["mouseControl"]?.supported)
    }

    @Test
    fun `a current catalog passes through untouched`() {
        val modern =
            CatalogDto(
                catalogVersion = 2,
                controllerTypes =
                    listOf(
                        CatalogTypeDto(id = 0, slug = "xbox360"),
                        CatalogTypeDto(id = 1, slug = "ds4"),
                        CatalogTypeDto(id = 2, slug = "dualsense"),
                        CatalogTypeDto(id = 3, slug = "switchpro"),
                    ),
            )
        val out = translator.normalize(modern)

        assertSame(modern, out) // no copy, no substitution
        assertEquals(4, out.controllerTypes.size)
    }

    @Test
    fun `a newer-than-current catalog also passes through`() {
        val future = CatalogDto(catalogVersion = 3, controllerTypes = listOf(CatalogTypeDto(id = 5, slug = "future")))
        assertSame(future, translator.normalize(future))
    }
}
