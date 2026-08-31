// SPDX-License-Identifier: LGPL-3.0-or-later

@file:Suppress("TooManyFunctions")

package com.tinkernorth.dish.ui.main

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.connection.moonlight.MoonlightTrustState

// The whole Moonlight session surface as one closed set of states, with the pure
// projections that turn each into a title, a body, a set of buttons and a tone. The
// binding screen and the setup wizard both draw it through bindMoonlightSession, so the
// states live here rather than in either of them: a state added below is a compile error
// in every `when` until it has been given all four. Nothing here touches a View or a
// Context, which is what makes the surface testable without a device.

// One session carries four controllers and no more, so a fifth binding on the same host
// has nowhere to go; that ceiling is what HostFull reports.
const val MOONLIGHT_MAX_PADS = 4

// Five meanings rather than a colour per state, so a new state has to say which of these
// it is instead of introducing a shade of its own.
enum class MoonlightTone { NEUTRAL, PROGRESS, WARN, ERROR, SUCCESS }

// Named for what the user is asking for, not for the work behind it: RETRY, RECONNECT and
// START_SESSION all restart the session, and stay separate only so the button can read
// like the state it sits under.
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

// The four axes below arrive from different places and change independently: pairing from
// the manager's event stream, apps from a probe, the phase from the live session, failure
// from whatever the host last refused. They stay apart rather than fold into one enum for
// that reason, and moonlightSessionUi is the single place their precedence is decided.
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

// `controllerNumber` is 1-based for the reader: the wire index is 0..3 and the caller adds
// one, so "controller 1" on screen is pad 0 in the host's CONTROLLER_ARRIVAL.
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

// Sticky: a failure is the last thing the host said, and it has to survive the re-probe
// that follows so the user can still read why the attempt stopped. HostFull is the one
// exception, re-derived from the live pad count every time.
sealed interface MoonlightFailure {
    data object HostFull : MoonlightFailure

    data object BusyOther : MoonlightFailure

    data object ResumeFailed : MoonlightFailure

    data class Refused(
        val hostMessage: String,
    ) : MoonlightFailure

    data object SetupFailed : MoonlightFailure
}

// Everything known about the chosen host at one moment. Defaulted throughout because a
// screen opens before any of it has been answered, and CHECKING is the honest starting
// point: trust here is remembered locally and only ever confirmed by asking.
data class MoonlightSessionInput(
    val trust: MoonlightTrustState = MoonlightTrustState.CHECKING,
    val pairing: MoonlightPairingUi? = null,
    val apps: MoonlightApps = MoonlightApps.Loading,
    val phase: MoonlightPhase = MoonlightPhase.Idle,
    val failure: MoonlightFailure? = null,
    val selectedAppId: String? = null,
)

// The render contract: one state at a time, flat rather than nested, so each projection
// below is a single exhaustive `when` and no combination can be reached that nobody wrote
// a string for.
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

// Precedence: pairing > trust > apps > joining > failure > live.
//
// The pairing flow is checked before the trust word it supersedes: a probe that
// answered "not paired" is exactly why a PIN is on screen, so reading the probe
// first would make the PIN state unreachable. Joining outranks failure so a fresh
// attempt is not buried under the previous one's message, and failure outranks live
// so a host that refused mid-session says so instead of showing a stream that is no
// longer there. The trailing Checking is not a state anything produces: the apps,
// joining and live legs cover every phase between them, and it is there to keep the
// chain total.
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

// PAIRED is the only word that falls through, because it is the only one that leaves
// nothing for the user to do. The rest are walls, and there is no live link to consult
// behind them: pairing is one-time trust with no liveness in either direction, so it is
// remembered locally and verified lazily when we ask, never polled.
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

// The app is a question only the session's creator gets asked. It is settled once per
// host, not per binding, so as soon as a session exists or an attempt has failed the
// picker would be offering a choice that is no longer there.
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
