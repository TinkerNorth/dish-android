// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.CatalogFeatureDto
import com.tinkernorth.dish.core.model.CatalogTypeDto
import com.tinkernorth.dish.core.model.Direction
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.ControllerDescriptor
import org.junit.Assert.assertEquals
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
    fun `typeCapabilities offers the pad when touchpad advertises the ds4 mode`() {
        val type =
            CatalogTypeDto(
                features = mapOf("touchpad" to CatalogFeatureDto(supported = true, modes = listOf("ds4"))),
            )
        assertTrue(Feature.TOUCHPAD in CapabilityResolver.typeCapabilities(type))
    }

    @Test
    fun `typeCapabilities gates the pad off when touchpad has modes but no ds4`() {
        // A touchpad-bearing type with only a mouse mode (or a non-DS4 pad) must not
        // offer the DS4 pad — read from modes, not inferred from the type id.
        val type =
            CatalogTypeDto(
                features = mapOf("touchpad" to CatalogFeatureDto(supported = true, modes = listOf("mouse"))),
            )
        assertFalse(Feature.TOUCHPAD in CapabilityResolver.typeCapabilities(type))
    }

    @Test
    fun `typeCapabilities treats a supported touchpad with no modes as pad-capable (back-compat)`() {
        // A pre-modes catalog omits the array; fall back to the legacy assumption.
        val type = CatalogTypeDto(features = mapOf("touchpad" to CatalogFeatureDto(supported = true)))
        assertTrue(Feature.TOUCHPAD in CapabilityResolver.typeCapabilities(type))
    }

    @Test
    fun `userEnabledCapabilities always carries GAMEPAD and ANALOG_TRIGGERS`() {
        val caps = CapabilityResolver.userEnabledCapabilities(motionOn = false, rumbleOn = false)
        assertTrue(Feature.GAMEPAD in caps)
        assertTrue(Feature.ANALOG_TRIGGERS in caps)
        assertFalse(Feature.MOTION in caps)
        assertFalse(Feature.RUMBLE in caps)
    }

    @Test
    fun `userEnabledCapabilities reflects motion and rumble toggles`() {
        val caps = CapabilityResolver.userEnabledCapabilities(motionOn = true, rumbleOn = true)
        assertTrue(Feature.MOTION in caps)
        assertTrue(Feature.RUMBLE in caps)
    }

    @Test
    fun `touch and mouse have no user toggle and always ride userEnabled`() {
        val caps = CapabilityResolver.userEnabledCapabilities(motionOn = false, rumbleOn = false)
        assertTrue(Feature.TOUCHPAD in caps)
        assertTrue(Feature.MOUSE in caps)
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

    // ---- wireCaps: the descriptor projection (analog+rumble base, motion gated on input+toggle) ----

    private val analogRumble = ControllerDescriptor.CAP_ANALOG_TRIGGERS or ControllerDescriptor.CAP_RUMBLE

    // wireCaps reads ONLY controller (the input gyro) and userEnabled; type and runtimeDown are set
    // to the opposite of the motion bit to prove the wire projection ignores them.
    private fun slot(
        gyro: Boolean,
        userMotion: Boolean,
        typeMotion: Boolean = !gyro,
        runtimeMotionDown: Boolean = gyro,
    ): SlotCapabilities {
        fun motionSet(present: Boolean) = if (present) CapabilitySet.of(Feature.MOTION) else CapabilitySet.EMPTY
        return SlotCapabilities(
            controller = motionSet(gyro),
            transport = CapabilitySet(Feature.entries.toSet()),
            type = motionSet(typeMotion),
            host = CapabilitySet(Feature.entries.toSet()),
            userEnabled = motionSet(userMotion),
            runtimeDown = motionSet(runtimeMotionDown),
        )
    }

    @Test
    fun `wireCaps is analog+rumble base plus motion only when gyro and userEnabled, independent of type and runtime`() {
        for (gyro in listOf(true, false)) {
            for (userMotion in listOf(true, false)) {
                val expected = if (gyro && userMotion) analogRumble or ControllerDescriptor.CAP_MOTION else analogRumble
                assertEquals(
                    "gyro=$gyro userMotion=$userMotion",
                    expected,
                    CapabilityResolver.wireCaps(slot(gyro = gyro, userMotion = userMotion)),
                )
            }
        }
    }

    @Test
    fun `wireCaps decisive cases - gyro pad with motion on is 0x0007, no-gyro pad is 0x0003`() {
        // An xbox360-typed (no motion sink) pad with a gyro and motion enabled still advertises CAP_MOTION:
        // the wire describes the emulated pad's input, not the host's sink.
        assertEquals(0x0007, CapabilityResolver.wireCaps(slot(gyro = true, userMotion = true, typeMotion = false)))
        // A ds4-typed (motion sink present) pad with NO gyro carries only analog+rumble.
        assertEquals(0x0003, CapabilityResolver.wireCaps(slot(gyro = false, userMotion = true, typeMotion = true)))
    }

    @Test
    fun `wireCaps carries the feedback caps only when the input can actuate them`() {
        // The motion-only slots above have no LED/trigger surfaces: nothing rides.
        for (gyro in listOf(true, false)) {
            for (userMotion in listOf(true, false)) {
                val caps = CapabilityResolver.wireCaps(slot(gyro = gyro, userMotion = userMotion))
                assertEquals(0, caps and ControllerDescriptor.CAP_LIGHTBAR)
                assertEquals(0, caps and ControllerDescriptor.CAP_TRIGGER_EFFECTS)
                assertEquals(0, caps and ControllerDescriptor.CAP_PLAYER_LEDS)
            }
        }
        // A Direct-claimed DualSense (lightbar + trigger effects + player LEDs in its
        // controller layer) advertises all three, independent of type and toggles.
        val ds5 =
            SlotCapabilities(
                controller =
                    CapabilitySet.of(
                        Feature.GAMEPAD,
                        Feature.LIGHTBAR,
                        Feature.TRIGGER_EFFECTS,
                        Feature.PLAYER_LEDS,
                    ),
                transport = CapabilitySet(Feature.entries.toSet()),
                type = CapabilitySet.of(Feature.GAMEPAD),
                host = CapabilitySet(Feature.entries.toSet()),
                userEnabled = CapabilitySet.EMPTY,
                runtimeDown = CapabilitySet.EMPTY,
            )
        val caps = CapabilityResolver.wireCaps(ds5)
        assertEquals(ControllerDescriptor.CAP_LIGHTBAR, caps and ControllerDescriptor.CAP_LIGHTBAR)
        assertEquals(
            ControllerDescriptor.CAP_TRIGGER_EFFECTS,
            caps and ControllerDescriptor.CAP_TRIGGER_EFFECTS,
        )
        assertEquals(
            ControllerDescriptor.CAP_PLAYER_LEDS,
            caps and ControllerDescriptor.CAP_PLAYER_LEDS,
        )
    }

    @Test
    fun `battery and trigger rumble pass the type layer without a catalog slug`() {
        // A catalog type that advertises nothing still lets the slug-less
        // features through; the transport/host layers are their real gates.
        val bare = CapabilityResolver.typeCapabilities(CatalogTypeDto(features = emptyMap()))
        assertTrue(Feature.BATTERY in bare)
        assertTrue(Feature.TRIGGER_RUMBLE in bare)
        assertFalse(Feature.TRIGGER_EFFECTS in bare)
        assertFalse(Feature.PLAYER_LEDS in bare)
    }

    @Test
    fun `trigger rumble follows the rumble toggle, the feedback surfaces have none`() {
        val off = CapabilityResolver.userEnabledCapabilities(motionOn = false, rumbleOn = false)
        assertFalse(Feature.TRIGGER_RUMBLE in off)
        assertTrue(Feature.LIGHTBAR in off)
        assertTrue(Feature.TRIGGER_EFFECTS in off)
        assertTrue(Feature.PLAYER_LEDS in off)
        assertTrue(Feature.BATTERY in off)
        val on = CapabilityResolver.userEnabledCapabilities(motionOn = false, rumbleOn = true)
        assertTrue(Feature.TRIGGER_RUMBLE in on)
    }

    @Test
    fun `wireCaps carries CAP_MOTION when controller motion comes via the static-DB path`() {
        // R2 delta: a physical pad whose MOTION rode in through native.modelHasImu (not a live gyro
        // probe) lands in the controller layer the same way, so the wire advertises CAP_MOTION.
        val staticDbPad =
            SlotCapabilities(
                controller = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION),
                transport = CapabilitySet(Feature.entries.toSet()),
                type = CapabilitySet.of(Feature.GAMEPAD),
                host = CapabilitySet(Feature.entries.toSet()),
                userEnabled = CapabilitySet.of(Feature.GAMEPAD, Feature.MOTION),
                runtimeDown = CapabilitySet.EMPTY,
            )
        assertEquals(
            analogRumble or ControllerDescriptor.CAP_MOTION,
            CapabilityResolver.wireCaps(staticDbPad),
        )
    }
}
