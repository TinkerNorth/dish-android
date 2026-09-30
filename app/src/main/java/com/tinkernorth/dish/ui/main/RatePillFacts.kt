// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.inputrate.SlotInputRates

// The three things a bound card measures. The label names the pill when it has no number
// to show (Off or pending).
internal enum class RateGlyph(
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int,
) {
    SCREEN(R.string.binding_func_touchpad, R.drawable.ic_touchpad),
    GYRO(R.string.binding_func_gyro, R.drawable.ic_motion),
    CONTROLLER(R.string.setup_cfg_flow_controller, R.drawable.ic_gamepad),
}

internal data class RatePillFact(
    val glyph: RateGlyph,
    val reading: RateReading,
    val tone: PillTone,
)

// The measurement line always renders every pill the slot can have (value, pending, or Off),
// so a bound card's height never changes as measurements arrive. A physical slot measures
// screen, gyro, and controller; the virtual slot has no controller, so it measures screen and
// gyro. Direct streams reports continuously, so the live window is the measurement and it
// renders in the success tone; routed paths and touch only deliver events while the user is
// pressing, so their peak window approximates the delivery rate and is shown with "~".
internal fun ratePillFacts(row: ControllerRow): List<RatePillFact> {
    val direct = row.pathCard?.currentMode == InputPathMode.Direct
    val measuredTone = if (direct) PillTone.SUCCESS else PillTone.FACT
    val facts = mutableListOf(screenRateFact(row), gyroRateFact(row, measuredTone))
    if (row.slot.inputType == SlotInputType.PHYSICAL) {
        facts.add(controllerRateFact(row.inputRates, direct, measuredTone))
    }
    return facts
}

private fun screenRateFact(row: ControllerRow): RatePillFact {
    val computes =
        screenRateUserFacingOn(
            inputType = row.slot.inputType,
            boundKind = row.slot.boundStatus?.kind,
            pointer = row.pointer,
        )
    return when {
        !computes -> RatePillFact(RateGlyph.SCREEN, RateReading.Off, PillTone.OFF)
        row.screenPeakHz > 0 -> RatePillFact(RateGlyph.SCREEN, RateReading.PeakHz(row.screenPeakHz), PillTone.FACT)
        else -> RatePillFact(RateGlyph.SCREEN, RateReading.Pending, PillTone.CAP)
    }
}

private fun gyroRateFact(
    row: ControllerRow,
    measuredTone: PillTone,
): RatePillFact {
    val gyroHz = row.inputRates?.gyroHz ?: 0
    return when {
        !motionRateUserFacingOn(row.motionCap, row.slot.boundStatus) ->
            RatePillFact(RateGlyph.GYRO, RateReading.Off, PillTone.OFF)
        gyroHz > 0 -> RatePillFact(RateGlyph.GYRO, RateReading.LiveHz(gyroHz), measuredTone)
        else -> RatePillFact(RateGlyph.GYRO, RateReading.Pending, PillTone.CAP)
    }
}

private fun controllerRateFact(
    rates: SlotInputRates?,
    direct: Boolean,
    measuredTone: PillTone,
): RatePillFact {
    val reading = rates?.let { controllerReading(it, direct) }
    if (reading == null) return RatePillFact(RateGlyph.CONTROLLER, RateReading.Pending, PillTone.CAP)
    return RatePillFact(RateGlyph.CONTROLLER, reading, measuredTone)
}

private fun controllerReading(
    rates: SlotInputRates,
    direct: Boolean,
): RateReading? =
    when {
        direct && rates.controllerHz > 0 -> RateReading.LiveHz(rates.controllerHz)
        rates.controllerPeakHz > 0 -> RateReading.PeakHz(rates.controllerPeakHz)
        else -> null
    }
