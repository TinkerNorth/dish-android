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
    fun `an unknown slug falls through to the server catalog`() {
        assertNull(BundledCatalog.typeCapabilities("gamecube"))
    }

    @Test
    fun `typeCapabilitiesById maps PlayStation to the ds4 set and others to xbox360`() {
        assertTrue(Feature.MOTION in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_PLAYSTATION))
        assertFalse(Feature.MOTION in BundledCatalog.typeCapabilitiesById(CONTROLLER_TYPE_XBOX))
    }
}
