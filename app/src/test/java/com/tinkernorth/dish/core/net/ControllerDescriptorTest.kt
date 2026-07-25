// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerDescriptorTest {
    @Test
    fun `toJson carries the WHOLE descriptor - idx, type, caps object, mode`() {
        val d =
            ControllerDescriptor(
                ctrlIdx = 2,
                type = 1,
                caps =
                    ControllerDescriptor.CAP_RUMBLE or
                        ControllerDescriptor.CAP_MOTION or
                        ControllerDescriptor.CAP_ANALOG_TRIGGERS,
                touchpadMode = "ds4",
            )
        assertEquals(
            """{"ctrlIdx":2,"type":1,"caps":{"rumble":true,"motion":true,"analogTriggers":true,""" +
                """"lightbar":false},"touchpadMode":"ds4"}""",
            d.toJson(),
        )
    }

    @Test
    fun `toJson carries a non-legacy type id verbatim (DualSense = 2)`() {
        val d = ControllerDescriptor(ctrlIdx = 0, type = 2, caps = ControllerDescriptor.CAP_RUMBLE, touchpadMode = "off")
        assertTrue(d.toJson().contains("\"type\":2"))
    }

    @Test
    fun `an unknown touchpad mode is sanitized to off, never sent raw`() {
        val d = ControllerDescriptor(ctrlIdx = 0, type = 0, caps = 0, touchpadMode = "warp-drive")
        assertTrue(d.toJson().contains("\"touchpadMode\":\"off\""))
    }

    @Test
    fun `wantsMouseControl follows the mouse routing mode`() {
        assertTrue(ControllerDescriptor(0, 0, 0, ControllerDescriptor.TOUCHPAD_MODE_MOUSE).wantsMouseControl)
        assertFalse(ControllerDescriptor(0, 0, 0, ControllerDescriptor.TOUCHPAD_MODE_DS4).wantsMouseControl)
        assertFalse(ControllerDescriptor(0, 0, 0, ControllerDescriptor.TOUCHPAD_MODE_OFF).wantsMouseControl)
    }

    @Test
    fun `arrayJson builds the controllers array for the session PUT`() {
        val list =
            listOf(
                ControllerDescriptor(0, 0, 0, "off"),
                ControllerDescriptor(1, 1, ControllerDescriptor.CAP_RUMBLE, "mouse"),
            )
        val json = ControllerDescriptor.arrayJson(list)
        assertTrue(json.startsWith("[{") && json.endsWith("}]"))
        assertTrue(json.contains("\"ctrlIdx\":0"))
        assertTrue(json.contains("\"ctrlIdx\":1"))
        assertEquals("[]", ControllerDescriptor.arrayJson(emptyList()))
    }

    @Test
    fun `cap bits mirror the satellite's wire constants`() {
        assertEquals(0x0001, ControllerDescriptor.CAP_ANALOG_TRIGGERS)
        assertEquals(0x0002, ControllerDescriptor.CAP_RUMBLE)
        assertEquals(0x0004, ControllerDescriptor.CAP_MOTION)
        assertEquals(0x0008, ControllerDescriptor.CAP_LIGHTBAR)
    }
}
