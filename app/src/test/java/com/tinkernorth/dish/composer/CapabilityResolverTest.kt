// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.CatalogFeatureDto
import com.tinkernorth.dish.core.model.CatalogTypeDto
import com.tinkernorth.dish.core.model.Direction
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.repository.TouchpadModeValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityResolverTest {
    private fun catalogType(vararg supported: String): CatalogTypeDto =
        CatalogTypeDto(features = supported.associateWith { CatalogFeatureDto(supported = true) })

    @Test
    fun `typeCapabilities always passes through GAMEPAD, MOUSE, and KEYBOARD`() {
        // MOUSE/KEYBOARD are host-injected, so the type layer always carries them for the host to gate.
        val caps = CapabilityResolver.typeCapabilities(CatalogTypeDto())
        assertTrue(Feature.GAMEPAD in caps)
        assertTrue(Feature.MOUSE in caps)
        assertTrue(Feature.KEYBOARD in caps)
    }

    @Test
    fun `typeCapabilities maps supported catalog feature slugs`() {
        val caps = CapabilityResolver.typeCapabilities(catalogType("motion", "rumble", "touchpad"))
        assertTrue(Feature.MOTION in caps)
        assertTrue(Feature.RUMBLE in caps)
        assertTrue(Feature.TOUCHPAD in caps)
    }

    @Test
    fun `typeCapabilities ignores unsupported catalog features`() {
        val type = CatalogTypeDto(features = mapOf("motion" to CatalogFeatureDto(supported = false)))
        assertFalse(Feature.MOTION in CapabilityResolver.typeCapabilities(type))
    }

    @Test
    fun `userEnabledCapabilities always carries GAMEPAD and ANALOG_TRIGGERS`() {
        val caps = CapabilityResolver.userEnabledCapabilities(motionOn = false, rumbleOn = false, touchpadMode = TouchpadModeValue.OFF)
        assertTrue(Feature.GAMEPAD in caps)
        assertTrue(Feature.ANALOG_TRIGGERS in caps)
        assertFalse(Feature.MOTION in caps)
        assertFalse(Feature.RUMBLE in caps)
    }

    @Test
    fun `userEnabledCapabilities reflects motion and rumble toggles`() {
        val caps = CapabilityResolver.userEnabledCapabilities(motionOn = true, rumbleOn = true, touchpadMode = TouchpadModeValue.OFF)
        assertTrue(Feature.MOTION in caps)
        assertTrue(Feature.RUMBLE in caps)
    }

    @Test
    fun `userEnabledCapabilities maps ds4 touchpad mode to TOUCHPAD`() {
        val caps = CapabilityResolver.userEnabledCapabilities(motionOn = false, rumbleOn = false, touchpadMode = TouchpadModeValue.DS4)
        assertTrue(Feature.TOUCHPAD in caps)
        assertFalse(Feature.MOUSE in caps)
    }

    @Test
    fun `userEnabledCapabilities maps mouse touchpad mode to MOUSE`() {
        val caps = CapabilityResolver.userEnabledCapabilities(motionOn = false, rumbleOn = false, touchpadMode = TouchpadModeValue.MOUSE)
        assertTrue(Feature.MOUSE in caps)
        assertFalse(Feature.TOUCHPAD in caps)
    }

    @Test
    fun `available is the intersection of all four inherent layers, not userEnabled`() {
        val all = CapabilitySet(Feature.entries.toSet())
        val resolved =
            CapabilityResolver.resolve(
                controller = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION, Feature.RUMBLE),
                transport = all,
                type = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION),
                host = all,
                userEnabled = CapabilitySet.EMPTY,
                runtimeDown = CapabilitySet.EMPTY,
            )
        // MOTION is in all four inherent layers; RUMBLE is dropped by type; userEnabled does not gate available.
        assertTrue(resolved.isAvailable(Feature.MOTION))
        assertTrue(resolved.isAvailable(Feature.GAMEPAD))
        assertFalse(resolved.isAvailable(Feature.RUMBLE))
    }

    @Test
    fun `enabled intersects available with userEnabled`() {
        val all = CapabilitySet(Feature.entries.toSet())
        val resolved =
            CapabilityResolver.resolve(
                controller = all,
                transport = all,
                type = all,
                host = all,
                userEnabled = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION),
                runtimeDown = CapabilitySet.EMPTY,
            )
        assertTrue(resolved.isEnabled(Feature.MOTION))
        assertFalse(resolved.isEnabled(Feature.RUMBLE))
    }

    @Test
    fun `live subtracts runtimeDown from enabled`() {
        val all = CapabilitySet(Feature.entries.toSet())
        val resolved =
            CapabilityResolver.resolve(
                controller = all,
                transport = all,
                type = all,
                host = all,
                userEnabled = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION),
                runtimeDown = CapabilitySet.of(Feature.MOTION),
            )
        assertTrue(Feature.MOTION in resolved.enabled)
        assertFalse(Feature.MOTION in resolved.live)
        assertTrue(Feature.GAMEPAD in resolved.live)
    }

    @Test
    fun `column helpers report the limiting layer per feature`() {
        val resolved =
            CapabilityResolver.resolve(
                controller = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION),
                transport = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION),
                type = CapabilitySet.of(Feature.GAMEPAD),
                host = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION),
                userEnabled = CapabilitySet.EMPTY,
                runtimeDown = CapabilitySet.EMPTY,
            )
        assertTrue(resolved.inputOk(Feature.MOTION))
        assertTrue(resolved.destinationOk(Feature.MOTION))
        assertFalse(resolved.typeOk(Feature.MOTION))
        assertTrue(resolved.typeOk(Feature.GAMEPAD))
    }

    @Test
    fun `destinationOk requires both transport and host`() {
        val resolved =
            CapabilityResolver.resolve(
                controller = CapabilitySet.of(Feature.MOTION),
                transport = CapabilitySet.of(Feature.MOTION),
                type = CapabilitySet.of(Feature.MOTION),
                host = CapabilitySet.EMPTY,
                userEnabled = CapabilitySet.EMPTY,
                runtimeDown = CapabilitySet.EMPTY,
            )
        assertFalse(resolved.destinationOk(Feature.MOTION))
    }

    @Test
    fun `sends and receives partition by direction`() {
        val set = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION, Feature.RUMBLE, Feature.LIGHTBAR)
        assertTrue(set.sends().all { it.direction == Direction.SEND })
        assertTrue(set.receives().all { it.direction == Direction.RECEIVE })
        assertTrue(Feature.RUMBLE in set.receives())
        assertTrue(Feature.GAMEPAD in set.sends())
    }
}
