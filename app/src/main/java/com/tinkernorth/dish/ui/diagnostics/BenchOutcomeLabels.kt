// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.store.BenchTest
import com.tinkernorth.dish.source.store.OUTCOME_WORKED
import com.tinkernorth.dish.source.store.TestOutcome

@StringRes
internal fun benchTestLabel(test: BenchTest): Int =
    when (test) {
        BenchTest.RUMBLE_WEAK -> R.string.inspector_outcome_test_rumble_weak
        BenchTest.RUMBLE_STRONG -> R.string.inspector_outcome_test_rumble_strong
        BenchTest.RUMBLE_BOTH -> R.string.inspector_outcome_test_rumble_both
        BenchTest.TRIGGER_RUMBLE_LEFT -> R.string.inspector_outcome_test_trigger_rumble_left
        BenchTest.TRIGGER_RUMBLE_RIGHT -> R.string.inspector_outcome_test_trigger_rumble_right
        BenchTest.TRIGGER_RUMBLE_BOTH -> R.string.inspector_outcome_test_trigger_rumble_both
        BenchTest.LIGHTBAR_CYCLE -> R.string.inspector_outcome_test_lightbar
        BenchTest.PLAYER_LEDS_CYCLE -> R.string.inspector_outcome_test_player_leds
        BenchTest.TRIGGER_PULSE -> R.string.inspector_outcome_test_trigger_pulse
        BenchTest.LAMP_ON -> R.string.inspector_outcome_test_lamp_on
        BenchTest.LAMP_PULSE -> R.string.inspector_outcome_test_lamp_pulse
        BenchTest.TONE -> R.string.inspector_outcome_test_tone
        BenchTest.HAPTICS -> R.string.inspector_outcome_test_haptics
        BenchTest.MIC -> R.string.inspector_outcome_test_mic
        BenchTest.STICK_DRIFT -> R.string.inspector_outcome_test_stick_drift
        BenchTest.STICK_RANGE -> R.string.inspector_outcome_test_stick_range
    }

// The outcome's test by its bundle name; null for a name this build does not ask about.
internal fun benchTestOf(wire: String): BenchTest? = BenchTest.entries.firstOrNull { it.wire == wire }

@StringRes
internal fun outcomeResultLabel(outcome: TestOutcome): Int =
    if (outcome.result == OUTCOME_WORKED) R.string.inspector_outcome_worked else R.string.inspector_outcome_failed

// "Last test: Rumble, strong · worked · 12 s ago".
internal fun Context.lastOutcomeText(
    outcome: TestOutcome,
    nowMs: Long,
): String? {
    val test = benchTestOf(outcome.test) ?: return null
    return getString(
        R.string.inspector_outcome_last,
        getString(benchTestLabel(test)),
        getString(outcomeResultLabel(outcome)),
        agoLabel(nowMs, outcome.atMs),
    )
}
