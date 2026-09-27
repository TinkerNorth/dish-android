// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.setup

import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.DishProtocolCompat
import com.tinkernorth.dish.ui.main.StringLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The review step's whole story: which nodes appear for each input and destination, and which
// flows each one sends and gets. Strings render as "res|args" so a test pins the resource and
// its arguments in one string.
class ReviewGraphTest {
    private val strings = StringLookup { res, args -> rendered(res, *args) }

    private fun rendered(
        res: Int,
        vararg args: Any,
    ): String = "$res|" + args.joinToString(",")

    private fun caps(vararg features: Feature): SlotCapabilities {
        val set = CapabilitySet.of(*features)
        return SlotCapabilities.NONE.copy(controller = set, transport = set, type = set, host = set, userEnabled = set)
    }

    private fun model(
        vararg available: Feature,
        motionOn: Boolean = true,
        rumbleOn: Boolean = true,
        micOn: Boolean = true,
        speakerOn: Boolean = true,
    ): ReviewModel = reviewModelFor(caps(*available), motionOn, rumbleOn, micOn, speakerOn)

    private fun input(
        onScreenInput: Boolean = false,
        hostKind: ConnectionKind = ConnectionKind.SATELLITE,
        inputUnknown: Boolean = false,
        hostCompat: DishProtocolCompat = DishProtocolCompat.UNKNOWN,
    ) = ReviewInput(
        onScreenInput = onScreenInput,
        inputName = "DualSense",
        inputIcon = R.drawable.ic_gamepad,
        inputLinkLabel = "USB Direct",
        inputUnknown = inputUnknown,
        hostKind = hostKind,
        hostLabel = "Living Room",
        hostCompat = hostCompat,
        padTypeLabel = "Xbox 360",
        moonlightAddress = "10.0.0.7",
    )

    @Test
    fun `a pointer surface is on when either touchpad or mouse is available`() {
        assertTrue(model(Feature.TOUCHPAD).touchpadOn)
        assertTrue(model(Feature.MOUSE).touchpadOn)
        assertFalse(model(Feature.GAMEPAD).touchpadOn)
    }

    @Test
    fun `motion, rumble, mic and speaker follow their toggles once the path carries them`() {
        val on = model(Feature.MOTION, Feature.RUMBLE, Feature.TRIGGER_RUMBLE, Feature.MIC, Feature.SPEAKER)
        assertTrue(on.motionOn)
        assertTrue(on.rumbleOn)
        assertTrue(on.triggerRumbleOn)
        assertTrue(on.micOn)
        assertTrue(on.speakerOn)
        val off =
            model(
                Feature.MOTION,
                Feature.RUMBLE,
                Feature.TRIGGER_RUMBLE,
                Feature.MIC,
                Feature.SPEAKER,
                motionOn = false,
                rumbleOn = false,
                micOn = false,
                speakerOn = false,
            )
        assertFalse(off.motionOn)
        assertFalse(off.rumbleOn)
        assertFalse(off.triggerRumbleOn)
        assertFalse(off.micOn)
        assertFalse(off.speakerOn)
    }

    @Test
    fun `a toggle cannot switch on what the path does not carry`() {
        val m = model(Feature.GAMEPAD, motionOn = true, rumbleOn = true, micOn = true, speakerOn = true)
        assertFalse(m.motionOn)
        assertFalse(m.rumbleOn)
        assertFalse(m.micOn)
        assertFalse(m.speakerOn)
    }

    @Test
    fun `lights and battery follow the capability table alone`() {
        val m = model(Feature.BATTERY, Feature.LIGHTBAR, Feature.TRIGGER_EFFECTS, Feature.PLAYER_LEDS)
        assertTrue(m.batteryOn)
        assertTrue(m.lightbar)
        assertTrue(m.triggerEffects)
        assertTrue(m.playerLeds)
    }

    @Test
    fun `feedback flows come in a fixed order`() {
        val flows =
            feedbackFlows(
                model(
                    Feature.SPEAKER,
                    Feature.PLAYER_LEDS,
                    Feature.TRIGGER_EFFECTS,
                    Feature.LIGHTBAR,
                    Feature.TRIGGER_RUMBLE,
                    Feature.RUMBLE,
                ),
            )
        assertEquals(
            listOf(
                R.string.binding_func_rumble,
                R.string.setup_cap_trigger_rumble,
                R.string.setup_cap_lightbar,
                R.string.setup_cap_trigger_effects,
                R.string.setup_cap_player_leds,
                R.string.setup_cap_speaker,
            ),
            flows.map { it.label },
        )
    }

    @Test
    fun `an on-screen input is one node carrying its pointer flows`() {
        val nodes =
            reviewGraph(input(onScreenInput = true), model(Feature.MOTION, Feature.TOUCHPAD, Feature.MOUSE, Feature.RUMBLE), strings)
        val inputs = nodes.filter { it.kind == R.string.binding_label_input }
        val phone = inputs.single()
        assertEquals(R.drawable.ic_gamepad_virtual, phone.icon)
        assertEquals(rendered(R.string.default_virtual_controller_name), phone.name)
        assertEquals(
            listOf(
                R.string.setup_cfg_flow_controller,
                R.string.binding_func_gyro,
                R.string.touchpad_mode_pad,
                R.string.touchpad_mode_mouse,
            ),
            phone.sends.map { it.label },
        )
        assertEquals(listOf(R.string.binding_func_rumble), phone.gets.map { it.label })
    }

    @Test
    fun `a controller input adds the phone node only when a pointer surface is on`() {
        val withPointer = reviewGraph(input(), model(Feature.TOUCHPAD), strings).filter { it.kind == R.string.binding_label_input }
        assertEquals(listOf("DualSense", rendered(R.string.default_virtual_controller_name)), withPointer.map { it.name })
        assertEquals(listOf(R.string.touchpad_mode_pad), withPointer[1].sends.map { it.label })
        val withoutPointer = reviewGraph(input(), model(Feature.GAMEPAD), strings).filter { it.kind == R.string.binding_label_input }
        assertEquals(listOf("DualSense"), withoutPointer.map { it.name })
    }

    @Test
    fun `a controller whose functions are unknown says so among its sends`() {
        val controller = reviewGraph(input(inputUnknown = true), model(Feature.GAMEPAD), strings).first()
        assertEquals("USB Direct", controller.sublabel)
        assertEquals(listOf(R.string.setup_cfg_flow_controller, R.string.setup_cap_unknown), controller.sends.map { it.label })
    }

    @Test
    fun `a Bluetooth destination is one node that takes the gamepad and returns rumble`() {
        val nodes = reviewGraph(input(hostKind = ConnectionKind.BLUETOOTH), model(Feature.RUMBLE), strings)
        val destination = nodes.single { it.kind == R.string.binding_label_destination }
        assertEquals(R.drawable.ic_bluetooth, destination.icon)
        assertEquals("Living Room", destination.name)
        assertEquals(rendered(R.string.setup_cfg_dest_bluetooth), destination.sublabel)
        assertEquals(listOf(R.string.setup_cfg_flow_controller), destination.gets.map { it.label })
        assertEquals(listOf(R.string.binding_func_rumble), destination.sends.map { it.label })
    }

    @Test
    fun `a Bluetooth destination with rumble off returns nothing`() {
        val nodes = reviewGraph(input(hostKind = ConnectionKind.BLUETOOTH), model(Feature.RUMBLE, rumbleOn = false), strings)
        assertTrue(nodes.single { it.kind == R.string.binding_label_destination }.sends.isEmpty())
    }

    @Test
    fun `a satellite is the PC node wearing the compat chip and taking the mouse, then the emulated pad`() {
        val m = model(Feature.MOUSE, Feature.TOUCHPAD, Feature.MOTION, Feature.BATTERY, Feature.MIC, Feature.RUMBLE)
        val nodes = reviewGraph(input(hostCompat = DishProtocolCompat.SATELLITE_UPDATE_AVAILABLE), m, strings)
        val destinations = nodes.filter { it.kind == R.string.binding_label_destination }
        val pc = destinations[0]
        assertEquals(R.drawable.ic_satellite, pc.icon)
        assertEquals(DishProtocolCompat.SATELLITE_UPDATE_AVAILABLE, pc.compat)
        assertEquals(listOf(R.string.touchpad_mode_mouse), pc.gets.map { it.label })
        assertTrue(pc.sends.isEmpty())
        val pad = destinations[1]
        assertEquals("Xbox 360", pad.name)
        assertEquals(rendered(R.string.setup_cfg_virtual_sublabel), pad.sublabel)
        assertEquals(
            listOf(
                R.string.setup_cfg_flow_controller,
                R.string.binding_func_gyro,
                R.string.touchpad_mode_pad,
                R.string.setup_cap_battery,
                R.string.setup_cap_mic,
            ),
            pad.gets.map { it.label },
        )
        assertEquals(listOf(R.string.binding_func_rumble), pad.sends.map { it.label })
    }

    @Test
    fun `a satellite PC without a mouse surface gets nothing`() {
        val nodes = reviewGraph(input(), model(Feature.GAMEPAD), strings)
        assertTrue(nodes.first { it.kind == R.string.binding_label_destination }.gets.isEmpty())
    }

    @Test
    fun `a Moonlight PC names its address and its pad never gets the mic flow`() {
        val m = model(Feature.MOUSE, Feature.TOUCHPAD, Feature.MOTION, Feature.BATTERY, Feature.MIC)
        val nodes = reviewGraph(input(hostKind = ConnectionKind.MOONLIGHT), m, strings)
        val destinations = nodes.filter { it.kind == R.string.binding_label_destination }
        val pc = destinations[0]
        assertEquals(R.drawable.ic_pc_monitor, pc.icon)
        assertEquals(rendered(R.string.ml_dest_sublabel, "10.0.0.7"), pc.sublabel)
        assertEquals(listOf(R.string.touchpad_mode_mouse), pc.gets.map { it.label })
        val pad = destinations[1]
        assertEquals(
            listOf(
                R.string.setup_cfg_flow_controller,
                R.string.binding_func_gyro,
                R.string.touchpad_mode_pad,
                R.string.setup_cap_battery,
            ),
            pad.gets.map { it.label },
        )
    }

    @Test
    fun `the input nodes come before the destination nodes`() {
        val kinds = reviewGraph(input(), model(Feature.TOUCHPAD), strings).map { it.kind }
        assertEquals(
            listOf(
                R.string.binding_label_input,
                R.string.binding_label_input,
                R.string.binding_label_destination,
                R.string.binding_label_destination,
            ),
            kinds,
        )
    }
}
