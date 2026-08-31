// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.composer.CONTROLLER_TYPE_DUALSENSE
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_SWITCHPRO
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import org.junit.Assert.assertEquals
import org.junit.Test

// The two id tables overlap (catalog PLAYSTATION and Moonlight XBOX are both 1), so
// each mapper is pinned against the other's values as well as its own.
class GamepadSkinTest {
    @Test
    fun `every catalog type wears its own skin`() {
        assertEquals(GamepadSkin.Xbox360, GamepadSkin.forControllerType(CONTROLLER_TYPE_XBOX))
        assertEquals(GamepadSkin.PlayStation, GamepadSkin.forControllerType(CONTROLLER_TYPE_PLAYSTATION))
        assertEquals(GamepadSkin.DualSense, GamepadSkin.forControllerType(CONTROLLER_TYPE_DUALSENSE))
        assertEquals(GamepadSkin.Switch, GamepadSkin.forControllerType(CONTROLLER_TYPE_SWITCHPRO))
    }

    @Test
    fun `an unknown catalog id falls back to the emulated Xbox 360 pad`() {
        assertEquals(GamepadSkin.Xbox360, GamepadSkin.forControllerType(99))
    }

    @Test
    fun `a Moonlight Xbox pad wears the Xbox skin, not the catalog skin its id collides with`() {
        assertEquals(GamepadSkin.Xbox, GamepadSkin.forMoonlightType(MoonlightEmulatedType.XBOX))
        assertEquals(GamepadSkin.PlayStation, GamepadSkin.forMoonlightType(MoonlightEmulatedType.PLAYSTATION))
        assertEquals(GamepadSkin.Switch, GamepadSkin.forMoonlightType(MoonlightEmulatedType.NINTENDO))
    }

    @Test
    fun `an unresolved Moonlight value lands on the generic Xbox skin`() {
        assertEquals(GamepadSkin.Xbox, GamepadSkin.forMoonlightType(MoonlightEmulatedType.AUTO))
        assertEquals(GamepadSkin.Xbox, GamepadSkin.forMoonlightType(0))
    }

    @Test
    fun `Bluetooth profiles keep their two skins`() {
        assertEquals(GamepadSkin.PlayStation, GamepadSkin.forBtProfile("PlayStation"))
        assertEquals(GamepadSkin.Xbox, GamepadSkin.forBtProfile("Xbox"))
        assertEquals(GamepadSkin.Xbox, GamepadSkin.forBtProfile(null))
    }

    @Test
    fun `only the PlayStation-family skins carry a lightbar`() {
        assertEquals(
            setOf(GamepadSkin.PlayStation, GamepadSkin.DualSense),
            GamepadSkin.entries.filter { it.hasLightbar }.toSet(),
        )
    }

    @Test
    fun `every skin name round-trips through the intent extra`() {
        GamepadSkin.entries.forEach { skin ->
            assertEquals(skin, GamepadSkin.fromName(skin.name))
        }
        assertEquals(GamepadSkin.Xbox, GamepadSkin.fromName(null))
        assertEquals(GamepadSkin.Xbox, GamepadSkin.fromName("NotASkin"))
    }
}
