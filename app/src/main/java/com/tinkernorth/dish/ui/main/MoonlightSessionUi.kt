// SPDX-License-Identifier: LGPL-3.0-or-later

@file:Suppress("TooManyFunctions")

package com.tinkernorth.dish.ui.main

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.connection.moonlight.MoonlightTrustState

const val MOONLIGHT_MAX_PADS = 4

enum class MoonlightTone { NEUTRAL, PROGRESS, WARN, ERROR, SUCCESS }

enum class MoonlightAction {
    PAIR,
    PAIR_AGAIN,
    NEW_CODE,
    CANCEL,
    TRY_AGAIN,
    RETRY,
    QUIT_APP,
    RECONNECT,
    START_SESSION,
    SEE_BINDINGS,
}

data class MoonlightAppUi(
    val id: String,
    val title: String,
)

sealed interface MoonlightPairingUi {
    data class Pin(
        val pin: String,
    ) : MoonlightPairingUi

    data object Failed : MoonlightPairingUi
}

sealed interface MoonlightApps {
    data object Loading : MoonlightApps

    data class Ready(
        val apps: List<MoonlightAppUi>,
    ) : MoonlightApps

    data object Empty : MoonlightApps

    data object Failed : MoonlightApps
}

sealed interface MoonlightPhase {
    data object Idle : MoonlightPhase

    data class Joining(
        val controllerNumber: Int,
        val appName: String?,
    ) : MoonlightPhase

    data class Live(
        val controllerNumber: Int,
        val appName: String?,
    ) : MoonlightPhase

    data object Dropped : MoonlightPhase

    data object Ended : MoonlightPhase
}

sealed interface MoonlightFailure {
    data object HostFull : MoonlightFailure

    data object BusyOther : MoonlightFailure

    data object ResumeFailed : MoonlightFailure

    data class Refused(
        val hostMessage: String,
    ) : MoonlightFailure

    data object SetupFailed : MoonlightFailure
}

data class MoonlightSessionInput(
    val trust: MoonlightTrustState = MoonlightTrustState.CHECKING,
    val pairing: MoonlightPairingUi? = null,
    val apps: MoonlightApps = MoonlightApps.Loading,
    val phase: MoonlightPhase = MoonlightPhase.Idle,
    val failure: MoonlightFailure? = null,
    val selectedAppId: String? = null,
)

sealed interface MoonlightSessionUi {
    data object Checking : MoonlightSessionUi

    data object NotPaired : MoonlightSessionUi

    data class PairingPin(
        val pin: String,
    ) : MoonlightSessionUi

    data object PairFailed : MoonlightSessionUi

    data object Unreachable : MoonlightSessionUi

    data object Remembered : MoonlightSessionUi

    data object TrustLost : MoonlightSessionUi

    data object HostReplaced : MoonlightSessionUi

    data object AppsLoading : MoonlightSessionUi

    data class NewSession(
        val apps: List<MoonlightAppUi>,
        val selectedAppId: String?,
    ) : MoonlightSessionUi

    data object AppsEmpty : MoonlightSessionUi

    data object AppsFailed : MoonlightSessionUi

    data class Joining(
        val controllerNumber: Int,
        val appName: String?,
    ) : MoonlightSessionUi

    data object HostFull : MoonlightSessionUi

    data object BusyOther : MoonlightSessionUi

    data object ResumeFailed : MoonlightSessionUi

    data class Refused(
        val hostMessage: String,
    ) : MoonlightSessionUi

    data object SetupFailed : MoonlightSessionUi

    data class Live(
        val controllerNumber: Int,
        val appName: String?,
    ) : MoonlightSessionUi

    data object Dropped : MoonlightSessionUi

    data object EndedByHost : MoonlightSessionUi
}

// The pairing flow is checked before the trust word it supersedes: a probe that
// answered "not paired" is exactly why a PIN is on screen, so reading the probe
// first would make the PIN state unreachable.
fun moonlightSessionUi(input: MoonlightSessionInput): MoonlightSessionUi =
    pairingUi(input.pairing)
        ?: trustUi(input.trust)
        ?: appsUi(input)
        ?: joiningUi(input.phase)
        ?: failureUi(input.failure)
        ?: liveUi(input.phase)
        ?: MoonlightSessionUi.Checking

private fun pairingUi(pairing: MoonlightPairingUi?): MoonlightSessionUi? =
    when (pairing) {
        is MoonlightPairingUi.Pin -> MoonlightSessionUi.PairingPin(pairing.pin)
        MoonlightPairingUi.Failed -> MoonlightSessionUi.PairFailed
        null -> null
    }

private fun trustUi(trust: MoonlightTrustState): MoonlightSessionUi? =
    when (trust) {
        MoonlightTrustState.CHECKING -> MoonlightSessionUi.Checking
        MoonlightTrustState.NOT_PAIRED -> MoonlightSessionUi.NotPaired
        MoonlightTrustState.UNREACHABLE -> MoonlightSessionUi.Unreachable
        MoonlightTrustState.REMEMBERED -> MoonlightSessionUi.Remembered
        MoonlightTrustState.TRUST_LOST -> MoonlightSessionUi.TrustLost
        MoonlightTrustState.REPLACED -> MoonlightSessionUi.HostReplaced
        MoonlightTrustState.PAIRED -> null
    }

private fun appsUi(input: MoonlightSessionInput): MoonlightSessionUi? {
    if (input.phase != MoonlightPhase.Idle || input.failure != null) return null
    return when (val apps = input.apps) {
        MoonlightApps.Loading -> MoonlightSessionUi.AppsLoading
        is MoonlightApps.Ready ->
            if (apps.apps.isEmpty()) {
                MoonlightSessionUi.AppsEmpty
            } else {
                MoonlightSessionUi.NewSession(apps.apps, input.selectedAppId)
            }
        MoonlightApps.Empty -> MoonlightSessionUi.AppsEmpty
        MoonlightApps.Failed -> MoonlightSessionUi.AppsFailed
    }
}

private fun joiningUi(phase: MoonlightPhase): MoonlightSessionUi? =
    (phase as? MoonlightPhase.Joining)?.let { MoonlightSessionUi.Joining(it.controllerNumber, it.appName) }

private fun failureUi(failure: MoonlightFailure?): MoonlightSessionUi? =
    when (failure) {
        MoonlightFailure.HostFull -> MoonlightSessionUi.HostFull
        MoonlightFailure.BusyOther -> MoonlightSessionUi.BusyOther
        MoonlightFailure.ResumeFailed -> MoonlightSessionUi.ResumeFailed
        is MoonlightFailure.Refused -> MoonlightSessionUi.Refused(failure.hostMessage)
        MoonlightFailure.SetupFailed -> MoonlightSessionUi.SetupFailed
        null -> null
    }

private fun liveUi(phase: MoonlightPhase): MoonlightSessionUi? =
    when (phase) {
        is MoonlightPhase.Live -> MoonlightSessionUi.Live(phase.controllerNumber, phase.appName)
        MoonlightPhase.Dropped -> MoonlightSessionUi.Dropped
        MoonlightPhase.Ended -> MoonlightSessionUi.EndedByHost
        else -> null
    }

// One exhaustive branch per state is the render contract itself; splitting it would
// hide which state carries which string rather than simplify anything.
@StringRes
@Suppress("CyclomaticComplexMethod")
fun MoonlightSessionUi.titleRes(): Int =
    when (this) {
        MoonlightSessionUi.Checking, is MoonlightSessionUi.PairingPin, MoonlightSessionUi.AppsLoading -> 0
        MoonlightSessionUi.NotPaired -> R.string.ml_state_unpaired_title
        MoonlightSessionUi.PairFailed -> R.string.ml_pair_failed_title
        MoonlightSessionUi.Unreachable, MoonlightSessionUi.Remembered -> R.string.ml_state_unreachable_title
        MoonlightSessionUi.TrustLost -> R.string.ml_state_trust_lost_title
        MoonlightSessionUi.HostReplaced -> R.string.ml_state_replaced_title
        is MoonlightSessionUi.NewSession -> R.string.ml_session_new_title
        MoonlightSessionUi.AppsEmpty -> R.string.ml_apps_empty_title
        MoonlightSessionUi.AppsFailed -> R.string.ml_apps_failed_title
        is MoonlightSessionUi.Joining ->
            if (appName.isNullOrBlank()) R.string.ml_session_join_title_unnamed else R.string.ml_session_join_title
        MoonlightSessionUi.HostFull -> R.string.ml_full_title
        MoonlightSessionUi.BusyOther -> R.string.ml_busy_other_title
        MoonlightSessionUi.ResumeFailed -> R.string.ml_resume_failed_title
        is MoonlightSessionUi.Refused -> R.string.ml_refused_title
        MoonlightSessionUi.SetupFailed -> R.string.ml_setup_failed_title
        is MoonlightSessionUi.Live -> R.string.ml_session_live_title
        MoonlightSessionUi.Dropped -> R.string.ml_dropped_title
        MoonlightSessionUi.EndedByHost -> R.string.ml_ended_title
    }

@StringRes
@Suppress("CyclomaticComplexMethod")
fun MoonlightSessionUi.bodyRes(): Int =
    when (this) {
        MoonlightSessionUi.Checking -> R.string.ml_state_checking
        MoonlightSessionUi.NotPaired -> R.string.ml_state_unpaired_body
        is MoonlightSessionUi.PairingPin -> R.string.ml_pair_pin_body
        MoonlightSessionUi.PairFailed -> R.string.ml_pair_failed_body
        MoonlightSessionUi.Unreachable -> R.string.ml_state_unreachable_body
        MoonlightSessionUi.Remembered -> R.string.ml_state_remembered_body
        MoonlightSessionUi.TrustLost -> R.string.ml_state_trust_lost_body
        MoonlightSessionUi.HostReplaced -> R.string.ml_state_replaced_body
        MoonlightSessionUi.AppsLoading -> R.string.ml_apps_loading
        is MoonlightSessionUi.NewSession -> R.string.ml_session_new_body
        MoonlightSessionUi.AppsEmpty -> R.string.ml_apps_empty_body
        MoonlightSessionUi.AppsFailed -> R.string.ml_apps_failed_body
        is MoonlightSessionUi.Joining -> R.string.ml_session_join_body
        MoonlightSessionUi.HostFull -> R.string.ml_full_body
        MoonlightSessionUi.BusyOther -> R.string.ml_busy_other_body
        MoonlightSessionUi.ResumeFailed -> R.string.ml_resume_failed_body
        is MoonlightSessionUi.Refused -> R.string.ml_refused_body
        MoonlightSessionUi.SetupFailed -> R.string.ml_setup_failed_body
        is MoonlightSessionUi.Live -> R.string.ml_session_live_body
        MoonlightSessionUi.Dropped -> R.string.ml_dropped_body
        MoonlightSessionUi.EndedByHost -> R.string.ml_ended_body
    }

@StringRes
fun MoonlightSessionUi.noteRes(): Int =
    when {
        this is MoonlightSessionUi.PairingPin -> R.string.ml_pair_waiting
        this is MoonlightSessionUi.NewSession && selectedAppId == null -> R.string.ml_session_default_note
        else -> 0
    }

fun MoonlightSessionUi.titleArgs(hostLabel: String): List<Any> =
    when (this) {
        is MoonlightSessionUi.Joining -> listOf(appName?.takeIf { it.isNotBlank() } ?: hostLabel)
        is MoonlightSessionUi.Refused -> listOf(hostLabel, hostMessage)
        else -> listOf(hostLabel)
    }

fun MoonlightSessionUi.bodyArgs(hostLabel: String): List<Any> =
    when (this) {
        is MoonlightSessionUi.PairingPin -> listOf(pin, hostLabel)
        is MoonlightSessionUi.Joining -> listOf(hostLabel, controllerNumber)
        is MoonlightSessionUi.Live -> listOf(appName?.takeIf { it.isNotBlank() } ?: hostLabel, controllerNumber)
        else -> listOf(hostLabel)
    }

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

val MoonlightSessionUi.blocksApply: Boolean
    get() = this is MoonlightSessionUi.HostFull

@StringRes
fun MoonlightAction.labelRes(): Int =
    when (this) {
        MoonlightAction.PAIR -> R.string.ml_action_pair
        MoonlightAction.PAIR_AGAIN -> R.string.ml_action_pair_again
        MoonlightAction.NEW_CODE -> R.string.ml_action_new_code
        MoonlightAction.CANCEL -> R.string.ml_action_cancel
        MoonlightAction.TRY_AGAIN -> R.string.ml_action_try_again
        MoonlightAction.RETRY -> R.string.ml_action_retry
        MoonlightAction.QUIT_APP -> R.string.ml_action_quit_app
        MoonlightAction.RECONNECT -> R.string.ml_action_reconnect
        MoonlightAction.START_SESSION -> R.string.ml_action_start_session
        MoonlightAction.SEE_BINDINGS -> R.string.ml_action_see_bindings
    }

fun MoonlightAction.labelArgs(hostLabel: String): List<Any> =
    when (this) {
        MoonlightAction.QUIT_APP, MoonlightAction.SEE_BINDINGS -> listOf(hostLabel)
        else -> emptyList()
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

@StringRes
fun MoonlightTrustState.chipTextRes(): Int =
    when (this) {
        MoonlightTrustState.PAIRED -> R.string.ml_trust_paired
        MoonlightTrustState.REMEMBERED, MoonlightTrustState.CHECKING -> R.string.ml_trust_remembered
        MoonlightTrustState.UNREACHABLE -> R.string.ml_trust_remembered
        MoonlightTrustState.NOT_PAIRED, MoonlightTrustState.TRUST_LOST, MoonlightTrustState.REPLACED -> R.string.ml_trust_not_paired
    }
