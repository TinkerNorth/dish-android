// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.connection.moonlight.MoonlightTrustState

// The action and tone side of the Moonlight render contract: which buttons a state offers,
// what colour it reads in, and how the trust word and the action labels are shown.

// An empty list is a decision, not a gap: NewSession's action is the app row itself,
// Joining is transient, and the two loading states have nothing to offer until the
// answer arrives.
fun MoonlightSessionUi.actions(): List<MoonlightAction> =
    when (this) {
        MoonlightSessionUi.Checking, MoonlightSessionUi.AppsLoading -> emptyList()
        is MoonlightSessionUi.NewSession, is MoonlightSessionUi.Joining -> emptyList()
        MoonlightSessionUi.NotPaired -> listOf(MoonlightAction.PAIR)
        is MoonlightSessionUi.PairingPin -> listOf(MoonlightAction.NEW_CODE, MoonlightAction.CANCEL)
        MoonlightSessionUi.PairFailed -> listOf(MoonlightAction.TRY_AGAIN)
        MoonlightSessionUi.Unreachable, MoonlightSessionUi.Remembered -> listOf(MoonlightAction.RETRY)
        MoonlightSessionUi.TrustLost, MoonlightSessionUi.HostReplaced -> listOf(MoonlightAction.PAIR_AGAIN)
        MoonlightSessionUi.AppsEmpty, MoonlightSessionUi.AppsFailed -> listOf(MoonlightAction.RETRY)
        MoonlightSessionUi.HostFull -> listOf(MoonlightAction.SEE_BINDINGS)
        MoonlightSessionUi.BusyOther, MoonlightSessionUi.ResumeFailed ->
            listOf(MoonlightAction.QUIT_APP, MoonlightAction.RETRY)
        is MoonlightSessionUi.Refused, MoonlightSessionUi.SetupFailed -> listOf(MoonlightAction.RETRY)
        is MoonlightSessionUi.Live -> listOf(MoonlightAction.QUIT_APP)
        MoonlightSessionUi.Dropped -> listOf(MoonlightAction.RECONNECT)
        MoonlightSessionUi.EndedByHost -> listOf(MoonlightAction.START_SESSION)
    }

fun MoonlightSessionUi.tone(): MoonlightTone =
    when (this) {
        MoonlightSessionUi.Checking, is MoonlightSessionUi.PairingPin, MoonlightSessionUi.AppsLoading ->
            MoonlightTone.PROGRESS
        MoonlightSessionUi.NotPaired, is MoonlightSessionUi.NewSession,
        MoonlightSessionUi.AppsEmpty, is MoonlightSessionUi.Joining,
        -> MoonlightTone.NEUTRAL
        MoonlightSessionUi.PairFailed, MoonlightSessionUi.AppsFailed,
        is MoonlightSessionUi.Refused, MoonlightSessionUi.SetupFailed,
        -> MoonlightTone.ERROR
        is MoonlightSessionUi.Live -> MoonlightTone.SUCCESS
        else -> MoonlightTone.WARN
    }

val MoonlightSessionUi.showsSpinner: Boolean
    get() = this is MoonlightSessionUi.Checking || this is MoonlightSessionUi.PairingPin || this is MoonlightSessionUi.AppsLoading

// The only state that stops the binding being saved. Everything else is recoverable
// afterwards and a binding is a durable intent, so it may be applied against a host that
// is unpaired, unreachable, or asleep. Four controllers is a protocol ceiling instead:
// there is no fifth number to hand out.
val MoonlightSessionUi.blocksApply: Boolean
    get() = this is MoonlightSessionUi.HostFull

@StringRes
fun MoonlightAction.labelRes(): Int =
    when (this) {
        MoonlightAction.PAIR -> R.string.ml_action_pair
        MoonlightAction.PAIR_AGAIN -> R.string.action_repair_short
        MoonlightAction.NEW_CODE -> R.string.ml_action_new_code
        MoonlightAction.CANCEL -> R.string.ml_action_cancel
        MoonlightAction.TRY_AGAIN -> R.string.ml_action_try_again
        MoonlightAction.RETRY -> R.string.ml_action_retry
        MoonlightAction.QUIT_APP -> R.string.ml_action_quit_app
        MoonlightAction.RECONNECT -> R.string.ml_action_reconnect
        MoonlightAction.START_SESSION -> R.string.ml_action_start_session
        MoonlightAction.SEE_BINDINGS -> R.string.ml_action_see_bindings
    }

// The host-scoped actions name the host; the rest carry nothing.
fun MoonlightAction.label(
    hostLabel: String,
    strings: StringLookup,
): String =
    when (this) {
        MoonlightAction.QUIT_APP, MoonlightAction.SEE_BINDINGS -> strings.format(labelRes(), hostLabel)
        else -> strings.format(labelRes())
    }

@ColorRes
fun MoonlightTone.colorRes(): Int =
    when (this) {
        MoonlightTone.NEUTRAL -> R.color.colorOnSurfaceVariant
        MoonlightTone.PROGRESS -> R.color.colorPrimary
        MoonlightTone.WARN -> R.color.colorWarning
        MoonlightTone.ERROR -> R.color.colorError
        MoonlightTone.SUCCESS -> R.color.colorSuccess
    }

// Seven states, two words. Holding a pairing record reads as paired, whether or not
// this visit has re-proven it; only a state with no usable record reads as not paired.
@StringRes
fun MoonlightTrustState.chipTextRes(): Int =
    when (this) {
        MoonlightTrustState.PAIRED, MoonlightTrustState.REMEMBERED,
        MoonlightTrustState.CHECKING, MoonlightTrustState.UNREACHABLE,
        -> R.string.ml_trust_paired
        MoonlightTrustState.NOT_PAIRED, MoonlightTrustState.TRUST_LOST, MoonlightTrustState.REPLACED -> R.string.ml_trust_not_paired
    }

fun MoonlightTrustState.holdsPairing(): Boolean = chipTextRes() == R.string.ml_trust_paired
