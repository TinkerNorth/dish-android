// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import androidx.annotation.StringRes
import com.tinkernorth.dish.R

// The text side of the Moonlight render contract: the note line, and the title, body and
// action label each state fills in. The resources themselves live on the states
// (MoonlightSessionUi.titleRes / bodyRes); the buttons and tones are in
// MoonlightSessionActions.kt.

// Format arguments travel with the state that carries them, so a string that grows a
// placeholder cannot quietly be handed the wrong one. A 0 resource means no line at all
// rather than an empty one, so the view hides the row instead of leaving a gap.
@StringRes
fun MoonlightSessionUi.noteRes(): Int =
    when {
        this is MoonlightSessionUi.PairingPin -> R.string.ml_pair_waiting
        this is MoonlightSessionUi.NewSession && selectedAppId == null -> R.string.ml_session_default_note
        else -> 0
    }

// Null for a state with no title line at all, which the view reads as "hide the row".
fun MoonlightSessionUi.title(
    hostLabel: String,
    strings: StringLookup,
): String? =
    when {
        titleRes == 0 -> null
        this is MoonlightSessionUi.Joining -> strings.format(titleRes, appName?.takeIf { it.isNotBlank() } ?: hostLabel)
        this is MoonlightSessionUi.Refused -> strings.format(titleRes, hostLabel, hostMessage)
        else -> strings.format(titleRes, hostLabel)
    }

fun MoonlightSessionUi.body(
    hostLabel: String,
    strings: StringLookup,
): String =
    when (this) {
        is MoonlightSessionUi.PairingPin -> strings.format(bodyRes, pin, hostLabel)
        is MoonlightSessionUi.Joining -> strings.format(bodyRes, hostLabel, controllerNumber)
        is MoonlightSessionUi.Live -> strings.format(bodyRes, appName?.takeIf { it.isNotBlank() } ?: hostLabel, controllerNumber)
        else -> strings.format(bodyRes, hostLabel)
    }
