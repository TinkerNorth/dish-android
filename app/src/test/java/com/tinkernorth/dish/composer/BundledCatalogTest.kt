// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.Feature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledCatalogTest {
    @Test
    fun `xbox360 lacks motion and touchpad`() {
        val caps = BundledCatalog.typeCapabilities(BundledCatalog.SLUG_XBOX360)!!
        assertTrue(Feature.GAMEPAD in caps)
        assertTrue(Feature.ANALOG_TRIGGERS in caps)
        assertTrue(Feature.RUMBLE in caps)
        // MOUSE/KEYBOARD are host-injected, so every type passes them through.
        assertTrue(Feature.MOUSE in caps)
        assertTrue(Feature.KEYBOARD in caps)
        assertFalse(Feature.MOTION in caps)
        assertFalse(Feature.TOUCHPAD in caps)
    }

    @Test
    fun `ds4 carries motion touchpad and lightbar`() {
        val caps = BundledCatalog.typeCapabilities(BundledCatalog.SLUG_DS4)!!
        assertTrue(Feature.MOTION in caps)
        assertTrue(Feature.TOUCHPAD in caps)
        assertTrue(Feature.LIGHTBAR in caps)
        assertTrue(Feature.RUMBLE in caps)
        assertTrue(Feature.MOUSE in caps)
        assertTrue(Feature.KEYBOARD in caps)
    }

    @Test
    fun `dualsense carries motion touchpad and lightbar`() {
        val caps = BundledCatalog.typeCapabilities(BundledCatalog.SLUG_DUALSENSE)!!
        assertTrue(Feature.MOTION in caps)
        assertTrue(Feature.TOUCHPAD in caps)
        assertTrue(Feature.LIGHTBAR in caps)
        assertTrue(Feature.RUMBLE in caps)
    }

    @Test
    fun `switchpro carries motion and rumble but no touchpad or lightbar`() {
        val caps = BundledCatalog.typeCapabilities(BundledCatalog.SLUG_SWITCHPRO)!!
        assertTrue(Feature.MOTION in caps)
        assertTrue(Feature.RUMBLE in caps)
        assertFalse(Feature.TOUCHPAD in caps)
        assertFalse(Feature.LIGHTBAR in caps)
    }

    @Test
    fun `an unknown slug falls through to the server catalog`() {
        assertNull(BundledCatalog.typeCapabilities("gamecube"))
    }

    @Test
    fun `only the two Sony types carry audio endpoints`() {
        // The composite personas that give an emulated pad real speaker and microphone
        // endpoints exist for DualSense and DualShock 4 v2 only.
        for (slug in listOf(BundledCatalog.SLUG_DS4, BundledCatalog.SLUG_DUALSENSE)) {
            val caps = BundledCatalog.typeCapabilities(slug)!!
            assertTrue(slug, Feature.MIC in caps)
            assertTrue(slug, Feature.SPEAKER in caps)
        }
        for (slug in listOf(BundledCatalog.SLUG_XBOX360, BundledCatalog.SLUG_SWITCHPRO)) {
            val caps = BundledCatalog.typeCapabilities(slug)!!
            assertFalse(slug, Feature.MIC in caps)
            assertFalse(slug, Feature.SPEAKER in caps)
        }
    }

    @Test
    fun `typeCapabilitiesById carries audio for the Sony ids only`() {
        assertTrue(Feature.MIC in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_PLAYSTATION))
        assertTrue(Feature.SPEAKER in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_PLAYSTATION))
        assertTrue(Feature.MIC in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_DUALSENSE))
        assertTrue(Feature.SPEAKER in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_DUALSENSE))
        assertFalse(Feature.MIC in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_SWITCHPRO))
        assertFalse(Feature.SPEAKER in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_XBOX))
    }

    @Test
    fun `typeCapabilitiesById lights motion for every pad but Xbox`() {
        assertTrue(Feature.MOTION in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_PLAYSTATION))
        assertTrue(Feature.MOTION in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_DUALSENSE))
        assertTrue(Feature.MOTION in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_SWITCHPRO))
        assertFalse(Feature.MOTION in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_XBOX))
        // Switch Pro is the only motion pad without a touchpad.
        assertTrue(Feature.TOUCHPAD in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_DUALSENSE))
        assertFalse(Feature.TOUCHPAD in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_SWITCHPRO))
    }
}
