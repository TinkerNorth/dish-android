// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_PLAYSTATION
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.core.net.moonlight.CONTROLLER_TYPE_UNKNOWN
import com.tinkernorth.dish.core.net.moonlight.NINTENDO
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_DS4
import com.tinkernorth.dish.source.usb.PathChoice
import com.tinkernorth.dish.ui.common.bundledControllerTypeLabelRes
import com.tinkernorth.dish.ui.common.moonlightTypeLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The dashboard card's connection, emulate and function rows as facts, so the rules behind
// each pill are pinned without a view.
class ControllerPillFactsTest {
    private fun summary(kind: ConnectionKind = ConnectionKind.SATELLITE) =
        ConnectionSummary(
            id = "host",
            kind = kind,
            label = "PC",
            detail = "",
            live = LinkState.Connected,
            boundSlotIds = listOf("9"),
        )

    private fun slot(
        inputType: SlotInputType = SlotInputType.PHYSICAL,
        bound: ConnectionSummary? = summary(),
    ) = ControllerSlot(
        id = if (inputType == SlotInputType.VIRTUAL) VIRTUAL_SLOT_ID else "9",
        inputType = inputType,
        name = "Pad",
        boundConnectionId = bound?.id,
        boundStatus = bound,
    )

    private fun card(
        transport: Transport = Transport.Usb,
        mode: InputPathMode = InputPathMode.Standard,
        risk: PathRisk = PathRisk.None,
        standardRumble: Boolean = false,
        directRumble: Boolean = false,
        wiredSwitchAvailable: Boolean = false,
    ) = PathCard(
        currentMode = mode,
        selected = if (mode == InputPathMode.Direct) PathChoice.Direct else PathChoice.Standard,
        transport = transport,
        directAvailable = transport == Transport.Usb,
        recognized = risk == PathRisk.None,
        restoring = false,
        standard = PathCapabilities(rumble = standardRumble, motion = false),
        direct = PathCapabilities(rumble = directRumble, motion = false),
        directPollHz = 0,
        risk = risk,
        wiredSwitchAvailable = wiredSwitchAvailable,
    )

    private fun caps(
        input: Set<Feature> = emptySet(),
        type: Set<Feature> = emptySet(),
        userEnabled: Set<Feature> = emptySet(),
    ) = SlotCapabilities(
        controller = CapabilitySet(input),
        transport = CapabilitySet(Feature.entries.toSet()),
        type = CapabilitySet(type),
        host = CapabilitySet(Feature.entries.toSet()),
        userEnabled = CapabilitySet(userEnabled),
        runtimeDown = CapabilitySet.EMPTY,
    )

    private fun row(
        slot: ControllerSlot = slot(),
        pathCard: PathCard? = null,
        motionCap: SlotCapabilities = SlotCapabilities.NONE,
        pointer: PointerSlotUi? = null,
    ) = ControllerRow(
        slot = slot,
        connections = listOfNotNull(slot.boundStatus),
        motionCap = motionCap,
        pointer = pointer,
        pathCard = pathCard,
    )

    @Test
    fun `the on-screen pad wears the on-screen link pill alone`() {
        assertEquals(listOf(ConnectionPillFact.ONSCREEN), connectionPillFacts(row(slot(SlotInputType.VIRTUAL))))
    }

    @Test
    fun `a usb pad on standard wears the usb link and the standard badge`() {
        val facts = connectionPillFacts(row(pathCard = card()))
        assertEquals(listOf(ConnectionPillFact.USB, ConnectionPillFact.STANDARD), facts)
    }

    @Test
    fun `a direct pad on a known model wears the on-tone direct badge`() {
        val facts = connectionPillFacts(row(pathCard = card(mode = InputPathMode.Direct)))
        assertEquals(listOf(ConnectionPillFact.USB, ConnectionPillFact.DIRECT), facts)
        assertEquals(PillTone.ON, ConnectionPillFact.DIRECT.tone)
    }

    @Test
    fun `a direct pad on an unknown model wears the warn badge`() {
        val facts = connectionPillFacts(row(pathCard = card(mode = InputPathMode.Direct, risk = PathRisk.GuessedLayout)))
        assertEquals(listOf(ConnectionPillFact.USB, ConnectionPillFact.DIRECT_GUESSED), facts)
        assertEquals(PillTone.WARN, ConnectionPillFact.DIRECT_GUESSED.tone)
    }

    @Test
    fun `a bluetooth pad wears the bluetooth link and no path badge`() {
        val facts = connectionPillFacts(row(pathCard = card(transport = Transport.Bluetooth)))
        assertEquals(listOf(ConnectionPillFact.BLUETOOTH), facts)
    }

    @Test
    fun `a bluetooth pad with its cable plugged in is offered the wire`() {
        val facts = connectionPillFacts(row(pathCard = card(transport = Transport.Bluetooth, wiredSwitchAvailable = true)))
        assertEquals(listOf(ConnectionPillFact.BLUETOOTH, ConnectionPillFact.WIRED_AVAILABLE), facts)
    }

    @Test
    fun `a physical pad with no path card yet reads as usb`() {
        assertEquals(listOf(ConnectionPillFact.USB), connectionPillFacts(row()))
    }

    @Test
    fun `a satellite slot emulates its remembered type, xbox when none was chosen`() {
        assertEquals(
            EmulatePill.Bundled(bundledControllerTypeLabelRes(CONTROLLER_TYPE_PLAYSTATION)),
            emulatePillFor(ConnectionKind.SATELLITE, CONTROLLER_TYPE_PLAYSTATION, btProfile = null),
        )
        assertEquals(
            EmulatePill.Bundled(bundledControllerTypeLabelRes(CONTROLLER_TYPE_XBOX_ID)),
            emulatePillFor(ConnectionKind.SATELLITE, storedType = null, btProfile = null),
        )
    }

    @Test
    fun `a bluetooth slot emulates the profile the host fixed, or hides the row`() {
        assertEquals(EmulatePill.Profile("PlayStation"), emulatePillFor(ConnectionKind.BLUETOOTH, null, "PlayStation"))
        assertNull(emulatePillFor(ConnectionKind.BLUETOOTH, null, null))
    }

    @Test
    fun `a moonlight slot reads its type from the moonlight table, never the catalog`() {
        assertEquals(
            EmulatePill.Bundled(moonlightTypeLabelRes(NINTENDO)),
            emulatePillFor(ConnectionKind.MOONLIGHT, NINTENDO, btProfile = null),
        )
    }

    @Test
    fun `an unknown direct layout shows the three unknown pills`() {
        val unknown = card(mode = InputPathMode.Direct, risk = PathRisk.GuessedLayout)
        assertEquals(
            listOf(FunctionPillFact.RumbleUnknown, FunctionPillFact.GyroUnknown, FunctionPillFact.TouchpadUnknown),
            functionPillFacts(row(pathCard = unknown), summary()),
        )
    }

    @Test
    fun `an unknown direct layout still offers the mouse when its surface can open`() {
        val unknown = card(mode = InputPathMode.Direct, risk = PathRisk.GuessedLayout)
        val pointer = PointerSlotUi(mode = TOUCHPAD_MODE_DS4, touchpadOpenable = false, mouseOpenable = true)
        assertEquals(
            FunctionPillFact.Pointer(PointerPillFact.MOUSE_READY),
            functionPillFacts(row(pathCard = unknown, pointer = pointer), summary()).last(),
        )
    }

    @Test
    fun `the rumble pill follows the motor on the path the pad is on`() {
        val standardOnly = card(standardRumble = true, directRumble = false)
        assertEquals(listOf(FunctionPillFact.Rumble), functionPillFacts(row(pathCard = standardOnly), summary()))
        val directOnly = card(mode = InputPathMode.Direct, standardRumble = false, directRumble = true)
        assertEquals(listOf(FunctionPillFact.Rumble), functionPillFacts(row(pathCard = directOnly), summary()))
        val directNoMotor = card(mode = InputPathMode.Direct, standardRumble = true, directRumble = false)
        assertEquals(emptyList<FunctionPillFact>(), functionPillFacts(row(pathCard = directNoMotor), summary()))
    }

    @Test
    fun `a slot with no path card has no rumble pill`() {
        assertEquals(emptyList<FunctionPillFact>(), functionPillFacts(row(), summary()))
    }

    @Test
    fun `the motion pill tone is the user's toggle`() {
        val motion = caps(input = setOf(Feature.MOTION), type = setOf(Feature.MOTION), userEnabled = setOf(Feature.MOTION))
        assertEquals(listOf(FunctionPillFact.Motion(on = true)), functionPillFacts(row(motionCap = motion), summary()))
        val off = caps(input = setOf(Feature.MOTION), type = setOf(Feature.MOTION))
        assertEquals(listOf(FunctionPillFact.Motion(on = false)), functionPillFacts(row(motionCap = off), summary()))
    }

    @Test
    fun `motion needs the input, the type and a host that carries it`() {
        val motion = caps(input = setOf(Feature.MOTION), type = setOf(Feature.MOTION), userEnabled = setOf(Feature.MOTION))
        val noInput = caps(type = setOf(Feature.MOTION), userEnabled = setOf(Feature.MOTION))
        val noType = caps(input = setOf(Feature.MOTION), userEnabled = setOf(Feature.MOTION))
        assertEquals(emptyList<FunctionPillFact>(), functionPillFacts(row(motionCap = noInput), summary()))
        assertEquals(emptyList<FunctionPillFact>(), functionPillFacts(row(motionCap = noType), summary()))
        assertEquals(
            emptyList<FunctionPillFact>(),
            functionPillFacts(row(motionCap = motion), summary(ConnectionKind.BLUETOOTH)),
        )
        assertEquals(
            listOf(FunctionPillFact.Motion(on = true)),
            functionPillFacts(row(motionCap = motion), summary(ConnectionKind.MOONLIGHT)),
        )
    }

    @Test
    fun `pointer facts come from the satellite reducer on a satellite`() {
        val touchType = caps(type = setOf(Feature.TOUCHPAD))
        assertEquals(
            listOf(FunctionPillFact.Pointer(PointerPillFact.PAD_OFF)),
            functionPillFacts(row(motionCap = touchType), summary()),
        )
    }

    @Test
    fun `pointer facts come from the moonlight reducer on a moonlight host`() {
        val mouse = caps(input = setOf(Feature.MOUSE), type = setOf(Feature.MOUSE))
        assertEquals(
            listOf(FunctionPillFact.Pointer(PointerPillFact.MOUSE_READY)),
            functionPillFacts(row(motionCap = mouse), summary(ConnectionKind.MOONLIGHT)),
        )
    }

    @Test
    fun `a bluetooth host lists no pointer facts`() {
        val mouse = caps(input = setOf(Feature.MOUSE), type = setOf(Feature.MOUSE, Feature.TOUCHPAD))
        assertEquals(emptyList<FunctionPillFact>(), functionPillFacts(row(motionCap = mouse), summary(ConnectionKind.BLUETOOTH)))
    }

    @Test
    fun `feedback and audio facts trail rumble, motion and pointer in that order`() {
        val everything =
            caps(
                input = setOf(Feature.MOTION, Feature.LIGHTBAR, Feature.MIC),
                type = setOf(Feature.MOTION, Feature.TOUCHPAD, Feature.LIGHTBAR, Feature.MIC),
                userEnabled = setOf(Feature.MOTION, Feature.MIC),
            )
        val rumbling = card(standardRumble = true)
        val expected =
            listOf(
                FunctionPillFact.Rumble,
                FunctionPillFact.Motion(on = true),
                FunctionPillFact.Pointer(PointerPillFact.PAD_OFF),
                FunctionPillFact.Feedback(FeedbackPillFact.LIGHTBAR),
                FunctionPillFact.Audio(AudioPillFact.MIC),
            )
        assertEquals(expected, functionPillFacts(row(pathCard = rumbling, motionCap = everything), summary()))
    }

    // The label, glyph and tone each connection pill wore when the adapter still built them
    // itself (connectionSpecs and usbModeSpec), entry by entry.
    @Test
    fun `every connection pill wears the label, glyph and tone the card always gave it`() {
        val expected =
            mapOf(
                ConnectionPillFact.ONSCREEN to PillLook(R.string.binding_link_onscreen, R.drawable.ic_gamepad_virtual, PillTone.FACT),
                ConnectionPillFact.BLUETOOTH to PillLook(R.string.binding_link_bluetooth, R.drawable.ic_bluetooth, PillTone.FACT),
                ConnectionPillFact.USB to PillLook(R.string.binding_link_usb, R.drawable.ic_usb, PillTone.FACT),
                ConnectionPillFact.DIRECT to PillLook(R.string.binding_mode_direct, R.drawable.ic_bolt, PillTone.ON),
                ConnectionPillFact.DIRECT_GUESSED to PillLook(R.string.binding_mode_direct, R.drawable.ic_bolt, PillTone.WARN),
                ConnectionPillFact.STANDARD to PillLook(R.string.binding_mode_standard, R.drawable.ic_cable, PillTone.CAP),
                ConnectionPillFact.WIRED_AVAILABLE to PillLook(R.string.binding_usb_available, R.drawable.ic_usb, PillTone.WARN),
            )
        assertEquals(expected, ConnectionPillFact.entries.associateWith(::lookOf))
    }

    @Test
    fun `a moonlight slot with no stored type emulates auto`() {
        assertEquals(
            EmulatePill.Bundled(R.string.ml_type_auto),
            emulatePillFor(ConnectionKind.MOONLIGHT, storedType = null, btProfile = null),
        )
    }

    @Test
    fun `a moonlight slot stored as the legacy unknown type emulates auto`() {
        assertEquals(
            EmulatePill.Bundled(R.string.ml_type_auto),
            emulatePillFor(ConnectionKind.MOONLIGHT, CONTROLLER_TYPE_UNKNOWN, btProfile = null),
        )
    }

    private data class PillLook(
        val labelRes: Int,
        val iconRes: Int,
        val tone: PillTone,
    )

    private fun lookOf(fact: ConnectionPillFact): PillLook = PillLook(fact.labelRes, fact.iconRes, fact.tone)

    private companion object {
        const val CONTROLLER_TYPE_XBOX_ID = com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
    }
}
