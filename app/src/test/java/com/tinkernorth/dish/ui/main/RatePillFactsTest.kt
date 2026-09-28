// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_DS4
import com.tinkernorth.dish.source.inputrate.SlotInputRates
import com.tinkernorth.dish.source.usb.PathChoice
import org.junit.Assert.assertEquals
import org.junit.Test

// The measurement line: every pill the slot can have, with a live window on Direct and a
// peak window everywhere else.
class RatePillFactsTest {
    private val bound =
        ConnectionSummary(
            id = "host",
            kind = ConnectionKind.SATELLITE,
            label = "PC",
            detail = "",
            live = LinkState.Connected,
            boundSlotIds = listOf("9"),
        )

    private val motionOn =
        SlotCapabilities(
            controller = CapabilitySet.of(Feature.MOTION),
            transport = CapabilitySet.of(Feature.MOTION),
            type = CapabilitySet.of(Feature.MOTION),
            host = CapabilitySet.of(Feature.MOTION),
            userEnabled = CapabilitySet.of(Feature.MOTION),
            runtimeDown = CapabilitySet.EMPTY,
        )

    private val phoneSurfaces = PointerSlotUi(mode = TOUCHPAD_MODE_DS4, touchpadOpenable = true, mouseOpenable = true)

    private fun slot(inputType: SlotInputType) =
        ControllerSlot(
            id = if (inputType == SlotInputType.VIRTUAL) VIRTUAL_SLOT_ID else "9",
            inputType = inputType,
            name = "Pad",
            boundConnectionId = bound.id,
            boundStatus = bound,
        )

    private fun card(mode: InputPathMode) =
        PathCard(
            currentMode = mode,
            selected = if (mode == InputPathMode.Direct) PathChoice.Direct else PathChoice.Standard,
            transport = Transport.Usb,
            directAvailable = true,
            recognized = true,
            restoring = false,
            standard = PathCapabilities(rumble = false, motion = false),
            direct = PathCapabilities(rumble = false, motion = false),
            directPollHz = 0,
            risk = PathRisk.None,
        )

    private fun rates(
        controllerHz: Int = 0,
        controllerPeakHz: Int = 0,
        gyroHz: Int = 0,
    ) = SlotInputRates(controllerHz = controllerHz, controllerPeakHz = controllerPeakHz, gyroHz = gyroHz)

    private fun physical(
        mode: InputPathMode = InputPathMode.Standard,
        rates: SlotInputRates? = null,
        cap: SlotCapabilities = SlotCapabilities.NONE,
        pointer: PointerSlotUi? = null,
        screenPeakHz: Int = 0,
    ) = ControllerRow(
        slot = slot(SlotInputType.PHYSICAL),
        connections = listOf(bound),
        motionCap = cap,
        pointer = pointer,
        pathCard = card(mode),
        inputRates = rates,
        screenPeakHz = screenPeakHz,
    )

    private fun glyphs(row: ControllerRow): List<RateGlyph> = ratePillFacts(row).map { it.glyph }

    private fun factFor(
        row: ControllerRow,
        glyph: RateGlyph,
    ): RatePillFact = ratePillFacts(row).first { it.glyph == glyph }

    @Test
    fun `the virtual slot measures screen and gyro, a physical slot the controller too`() {
        val virtual = ControllerRow(slot = slot(SlotInputType.VIRTUAL), connections = listOf(bound))
        assertEquals(listOf(RateGlyph.SCREEN, RateGlyph.GYRO), glyphs(virtual))
        assertEquals(listOf(RateGlyph.SCREEN, RateGlyph.GYRO, RateGlyph.CONTROLLER), glyphs(physical()))
    }

    @Test
    fun `the controller rate is live on direct and peak on standard`() {
        val measured = rates(controllerHz = 1000, controllerPeakHz = 250)
        assertEquals(
            RatePillFact(RateGlyph.CONTROLLER, RateReading.LiveHz(1000), PillTone.SUCCESS),
            factFor(physical(InputPathMode.Direct, measured), RateGlyph.CONTROLLER),
        )
        assertEquals(
            RatePillFact(RateGlyph.CONTROLLER, RateReading.PeakHz(250), PillTone.FACT),
            factFor(physical(InputPathMode.Standard, measured), RateGlyph.CONTROLLER),
        )
    }

    @Test
    fun `a direct pad with no live window yet falls back to its peak`() {
        val peakOnly = rates(controllerHz = 0, controllerPeakHz = 125)
        assertEquals(
            RatePillFact(RateGlyph.CONTROLLER, RateReading.PeakHz(125), PillTone.SUCCESS),
            factFor(physical(InputPathMode.Direct, peakOnly), RateGlyph.CONTROLLER),
        )
    }

    @Test
    fun `an unmeasured controller shows the pending placeholder`() {
        assertEquals(
            RatePillFact(RateGlyph.CONTROLLER, RateReading.Pending, PillTone.CAP),
            factFor(physical(rates = rates()), RateGlyph.CONTROLLER),
        )
        assertEquals(
            RatePillFact(RateGlyph.CONTROLLER, RateReading.Pending, PillTone.CAP),
            factFor(physical(rates = null), RateGlyph.CONTROLLER),
        )
    }

    @Test
    fun `the screen pill reads off without a phone surface, then peak or pending`() {
        assertEquals(RatePillFact(RateGlyph.SCREEN, RateReading.Off, PillTone.OFF), factFor(physical(), RateGlyph.SCREEN))
        assertEquals(
            RatePillFact(RateGlyph.SCREEN, RateReading.Pending, PillTone.CAP),
            factFor(physical(pointer = phoneSurfaces), RateGlyph.SCREEN),
        )
        assertEquals(
            RatePillFact(RateGlyph.SCREEN, RateReading.PeakHz(120), PillTone.FACT),
            factFor(physical(pointer = phoneSurfaces, screenPeakHz = 120), RateGlyph.SCREEN),
        )
    }

    @Test
    fun `the screen peak keeps the plain fact tone even on direct`() {
        val row = physical(InputPathMode.Direct, pointer = phoneSurfaces, screenPeakHz = 120)
        assertEquals(PillTone.FACT, factFor(row, RateGlyph.SCREEN).tone)
    }

    @Test
    fun `the gyro pill reads off when motion is not user-facing on`() {
        assertEquals(RatePillFact(RateGlyph.GYRO, RateReading.Off, PillTone.OFF), factFor(physical(), RateGlyph.GYRO))
    }

    @Test
    fun `the gyro pill reads pending then the measured rate in the path's tone`() {
        assertEquals(
            RatePillFact(RateGlyph.GYRO, RateReading.Pending, PillTone.CAP),
            factFor(physical(cap = motionOn), RateGlyph.GYRO),
        )
        assertEquals(
            RatePillFact(RateGlyph.GYRO, RateReading.LiveHz(200), PillTone.FACT),
            factFor(physical(cap = motionOn, rates = rates(gyroHz = 200)), RateGlyph.GYRO),
        )
        assertEquals(
            RatePillFact(RateGlyph.GYRO, RateReading.LiveHz(200), PillTone.SUCCESS),
            factFor(physical(InputPathMode.Direct, cap = motionOn, rates = rates(gyroHz = 200)), RateGlyph.GYRO),
        )
    }

    // The label and glyph each measurement pill wore when the adapter still built them itself
    // (screenRatePill, gyroRatePill and controllerRatePill), entry by entry.
    @Test
    fun `every rate glyph wears the label and glyph the card always gave it`() {
        val expected =
            mapOf(
                RateGlyph.SCREEN to GlyphLook(R.string.binding_func_touchpad, R.drawable.ic_touchpad),
                RateGlyph.GYRO to GlyphLook(R.string.binding_func_gyro, R.drawable.ic_motion),
                RateGlyph.CONTROLLER to GlyphLook(R.string.setup_cfg_flow_controller, R.drawable.ic_gamepad),
            )
        assertEquals(expected, RateGlyph.entries.associateWith(::lookOf))
    }

    private data class GlyphLook(
        val labelRes: Int,
        val iconRes: Int,
    )

    private fun lookOf(glyph: RateGlyph): GlyphLook = GlyphLook(glyph.labelRes, glyph.iconRes)
}
