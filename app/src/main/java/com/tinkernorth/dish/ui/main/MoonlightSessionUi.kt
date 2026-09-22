// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

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
    // The title and body every state renders with. Carried by the state itself so a new
    // one cannot exist without both: the compiler, not a `when`, keeps the contract total.
    // A 0 title means the state has none, and the view hides the line.
    @get:StringRes
    val titleRes: Int

    @get:StringRes
    val bodyRes: Int

    data object Checking : MoonlightSessionUi {
        override val titleRes: Int = 0
        override val bodyRes: Int = R.string.ml_state_checking
    }

    data object NotPaired : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_state_unpaired_title
        override val bodyRes: Int = R.string.ml_state_unpaired_body
    }

    data class PairingPin(
        val pin: String,
    ) : MoonlightSessionUi {
        override val titleRes: Int = 0
        override val bodyRes: Int = R.string.ml_pair_pin_body
    }

    data object PairFailed : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_pair_failed_title
        override val bodyRes: Int = R.string.ml_pair_failed_body
    }

    data object Unreachable : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_state_unreachable_title
        override val bodyRes: Int = R.string.ml_state_unreachable_body
    }

    data object Remembered : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_state_unreachable_title
        override val bodyRes: Int = R.string.ml_state_remembered_body
    }

    data object TrustLost : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_state_trust_lost_title
        override val bodyRes: Int = R.string.ml_state_trust_lost_body
    }

    data object HostReplaced : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_state_replaced_title
        override val bodyRes: Int = R.string.ml_state_replaced_body
    }

    data object AppsLoading : MoonlightSessionUi {
        override val titleRes: Int = 0
        override val bodyRes: Int = R.string.ml_apps_loading
    }

    data class NewSession(
        val apps: List<MoonlightAppUi>,
        val selectedAppId: String?,
    ) : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_session_new_title
        override val bodyRes: Int = R.string.ml_session_new_body
    }

    data object AppsEmpty : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_apps_empty_title
        override val bodyRes: Int = R.string.ml_apps_empty_body
    }

    data object AppsFailed : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_apps_failed_title
        override val bodyRes: Int = R.string.ml_apps_failed_body
    }

    data class Joining(
        val controllerNumber: Int,
        val appName: String?,
    ) : MoonlightSessionUi {
        override val titleRes: Int
            get() = if (appName.isNullOrBlank()) R.string.ml_session_join_title_unnamed else R.string.ml_session_join_title
        override val bodyRes: Int = R.string.ml_session_join_body
    }

    data object HostFull : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_full_title
        override val bodyRes: Int = R.string.ml_full_body
    }

    data object BusyOther : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_busy_other_title
        override val bodyRes: Int = R.string.ml_busy_other_body
    }

    data object ResumeFailed : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_resume_failed_title
        override val bodyRes: Int = R.string.ml_resume_failed_body
    }

    data class Refused(
        val hostMessage: String,
    ) : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_refused_title
        override val bodyRes: Int = R.string.ml_refused_body
    }

    data object SetupFailed : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_setup_failed_title
        override val bodyRes: Int = R.string.ml_setup_failed_body
    }

    data class Live(
        val controllerNumber: Int,
        val appName: String?,
    ) : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_session_live_title
        override val bodyRes: Int = R.string.ml_session_live_body
    }

    data object Dropped : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_dropped_title
        override val bodyRes: Int = R.string.ml_dropped_body
    }

    data object EndedByHost : MoonlightSessionUi {
        override val titleRes: Int = R.string.ml_ended_title
        override val bodyRes: Int = R.string.ml_ended_body
    }
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
