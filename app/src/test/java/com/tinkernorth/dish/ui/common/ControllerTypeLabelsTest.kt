// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_DUALSENSE
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_SWITCHPRO
import com.tinkernorth.dish.core.net.moonlight.NINTENDO
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.core.net.moonlight.XBOX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ControllerTypeLabelsTest {
    @Test
    fun `every bundled catalog id maps to its own label`() {
        assertEquals(R.string.picker_type_playstation, bundledControllerTypeLabelRes(CONTROLLER_TYPE_PLAYSTATION))
        assertEquals(R.string.picker_type_dualsense, bundledControllerTypeLabelRes(CONTROLLER_TYPE_DUALSENSE))
        assertEquals(R.string.picker_type_switchpro, bundledControllerTypeLabelRes(CONTROLLER_TYPE_SWITCHPRO))
    }

    @Test
    fun `an unknown bundled id falls back to the emulated Xbox pad`() {
        assertEquals(R.string.picker_type_xbox, bundledControllerTypeLabelRes(-1))
        assertEquals(R.drawable.ic_ctrl_xbox360, bundledControllerTypeGlyphRes(-1))
    }

    @Test
    fun `every bundled catalog id maps to its own glyph`() {
        assertEquals(R.drawable.ic_ctrl_ds4, bundledControllerTypeGlyphRes(CONTROLLER_TYPE_PLAYSTATION))
        assertEquals(R.drawable.ic_ctrl_dualsense, bundledControllerTypeGlyphRes(CONTROLLER_TYPE_DUALSENSE))
        assertEquals(R.drawable.ic_ctrl_switchpro, bundledControllerTypeGlyphRes(CONTROLLER_TYPE_SWITCHPRO))
    }

    @Test
    fun `every Moonlight id maps to its own label`() {
        assertEquals(R.string.ml_type_xbox, moonlightTypeLabelRes(XBOX))
        assertEquals(R.string.ml_type_playstation, moonlightTypeLabelRes(PLAYSTATION))
        assertEquals(R.string.ml_type_nintendo, moonlightTypeLabelRes(NINTENDO))
    }

    @Test
    fun `an unknown Moonlight id falls back to Auto and the generic pad`() {
        assertEquals(R.string.ml_type_auto, moonlightTypeLabelRes(-1))
        assertEquals(R.drawable.ic_gamepad, moonlightTypeGlyphRes(-1))
    }

    @Test
    fun `every Moonlight id maps to its own glyph`() {
        assertEquals(R.drawable.ic_ctrl_xbox, moonlightTypeGlyphRes(XBOX))
        assertEquals(R.drawable.ic_ctrl_ds4, moonlightTypeGlyphRes(PLAYSTATION))
        assertEquals(R.drawable.ic_ctrl_switchpro, moonlightTypeGlyphRes(NINTENDO))
    }

    @Test
    fun `the two id spaces overlap, so they never share a mapper`() {
        assertEquals(CONTROLLER_TYPE_PLAYSTATION, XBOX)
        assertNotEquals(bundledControllerTypeLabelRes(XBOX), moonlightTypeLabelRes(XBOX))
    }
}
