// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.connection.moonlight.MoonlightTrustState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The whole render contract for the Moonlight session section: one state at a time,
// evaluated top to bottom, with the strings and actions each one owns. Every state
// keeps Apply reachable except the host that already carries its four controllers.
class MoonlightSessionUiTest {
    private fun ui(
        trust: MoonlightTrustState = MoonlightTrustState.PAIRED,
        pairing: MoonlightPairingUi? = null,
        apps: MoonlightApps = MoonlightApps.Ready(listOf(MoonlightAppUi("1", "Desktop"))),
        phase: MoonlightPhase = MoonlightPhase.Idle,
        failure: MoonlightFailure? = null,
        selectedAppId: String? = null,
    ) = moonlightSessionUi(
        MoonlightSessionInput(
            trust = trust,
            pairing = pairing,
            apps = apps,
            phase = phase,
            failure = failure,
            selectedAppId = selectedAppId,
        ),
    )

    @Test
    fun `M1 a probe in flight with nothing cached is checking`() {
        val state = ui(trust = MoonlightTrustState.CHECKING)
        assertEquals(MoonlightSessionUi.Checking, state)
        assertEquals(0, state.titleRes())
        assertEquals(R.string.ml_state_checking, state.bodyRes())
        assertTrue(state.showsSpinner)
        assertEquals(emptyList<MoonlightAction>(), state.actions())
    }

    @Test
    fun `M2 an answering host with no stored cert is not paired`() {
        val state = ui(trust = MoonlightTrustState.NOT_PAIRED)
        assertEquals(R.string.ml_state_unpaired_title, state.titleRes())
        assertEquals(R.string.ml_state_unpaired_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.PAIR), state.actions())
    }

    @Test
    fun `M3 the PIN outranks the trust word that produced it`() {
        val state = ui(trust = MoonlightTrustState.NOT_PAIRED, pairing = MoonlightPairingUi.Pin("1234"))
        assertEquals(MoonlightSessionUi.PairingPin("1234"), state)
        assertEquals(R.string.ml_pair_pin_body, state.bodyRes())
        assertEquals(R.string.ml_pair_waiting, state.noteRes())
        assertEquals(listOf("1234", "PC"), state.bodyArgs("PC"))
        assertEquals(listOf(MoonlightAction.NEW_CODE, MoonlightAction.CANCEL), state.actions())
        assertTrue(state.showsSpinner)
    }

    @Test
    fun `M4 a refused PIN offers another go`() {
        val state = ui(trust = MoonlightTrustState.NOT_PAIRED, pairing = MoonlightPairingUi.Failed)
        assertEquals(R.string.ml_pair_failed_title, state.titleRes())
        assertEquals(R.string.ml_pair_failed_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.TRY_AGAIN), state.actions())
        assertEquals(MoonlightTone.ERROR, state.tone())
    }

    @Test
    fun `M5 a never-paired host that does not answer is unreachable`() {
        val state = ui(trust = MoonlightTrustState.UNREACHABLE)
        assertEquals(R.string.ml_state_unreachable_title, state.titleRes())
        assertEquals(R.string.ml_state_unreachable_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.RETRY), state.actions())
    }

    @Test
    fun `M6 a remembered host that does not answer says the pairing still stands`() {
        val state = ui(trust = MoonlightTrustState.REMEMBERED)
        assertEquals(R.string.ml_state_unreachable_title, state.titleRes())
        assertEquals(R.string.ml_state_remembered_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.RETRY), state.actions())
    }

    @Test
    fun `M7 trust lost asks for a new pairing`() {
        val state = ui(trust = MoonlightTrustState.TRUST_LOST)
        assertEquals(R.string.ml_state_trust_lost_title, state.titleRes())
        assertEquals(R.string.ml_state_trust_lost_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.PAIR_AGAIN), state.actions())
    }

    @Test
    fun `M8 a replaced host asks for a new pairing and says why`() {
        val state = ui(trust = MoonlightTrustState.REPLACED)
        assertEquals(R.string.ml_state_replaced_title, state.titleRes())
        assertEquals(R.string.ml_state_replaced_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.PAIR_AGAIN), state.actions())
    }

    @Test
    fun `M9 a paired host with the app list in flight is loading`() {
        val state = ui(apps = MoonlightApps.Loading)
        assertEquals(MoonlightSessionUi.AppsLoading, state)
        assertEquals(0, state.titleRes())
        assertEquals(R.string.ml_apps_loading, state.bodyRes())
        assertTrue(state.showsSpinner)
    }

    @Test
    fun `M10 a new session offers the app rows and the default note until one is picked`() {
        val apps = listOf(MoonlightAppUi("1", "Desktop"), MoonlightAppUi("2", "Steam Big Picture"))
        val unpicked = ui(apps = MoonlightApps.Ready(apps))
        assertEquals(MoonlightSessionUi.NewSession(apps, null), unpicked)
        assertEquals(R.string.ml_session_new_title, unpicked.titleRes())
        assertEquals(R.string.ml_session_new_body, unpicked.bodyRes())
        assertEquals(R.string.ml_session_default_note, unpicked.noteRes())
        assertEquals(emptyList<MoonlightAction>(), unpicked.actions())

        val picked = ui(apps = MoonlightApps.Ready(apps), selectedAppId = "2")
        assertEquals(0, picked.noteRes())
    }

    @Test
    fun `M11 an empty app list is not an error and still offers a retry`() {
        assertEquals(MoonlightSessionUi.AppsEmpty, ui(apps = MoonlightApps.Empty))
        val fetchedEmpty = ui(apps = MoonlightApps.Ready(emptyList()))
        assertEquals(MoonlightSessionUi.AppsEmpty, fetchedEmpty)
        assertEquals(R.string.ml_apps_empty_title, fetchedEmpty.titleRes())
        assertEquals(R.string.ml_apps_empty_body, fetchedEmpty.bodyRes())
        assertEquals(listOf(MoonlightAction.RETRY), fetchedEmpty.actions())
        assertEquals(MoonlightTone.NEUTRAL, fetchedEmpty.tone())
    }

    @Test
    fun `M12 an unreadable app list is an error with a retry`() {
        val state = ui(apps = MoonlightApps.Failed)
        assertEquals(R.string.ml_apps_failed_title, state.titleRes())
        assertEquals(R.string.ml_apps_failed_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.RETRY), state.actions())
        assertEquals(MoonlightTone.ERROR, state.tone())
    }

    @Test
    fun `M13 joining our own session names the app and shows no picker`() {
        val state = ui(phase = MoonlightPhase.Joining(controllerNumber = 2, appName = "Steam Big Picture"))
        assertEquals(R.string.ml_session_join_title, state.titleRes())
        assertEquals(listOf<Any>("Steam Big Picture"), state.titleArgs("PC"))
        assertEquals(R.string.ml_session_join_body, state.bodyRes())
        assertEquals(listOf<Any>("PC", 2), state.bodyArgs("PC"))
        assertEquals(emptyList<MoonlightAction>(), state.actions())
    }

    @Test
    fun `M13 an unresolvable app name falls back to the host, still with no picker`() {
        val state = ui(phase = MoonlightPhase.Joining(controllerNumber = 1, appName = null))
        assertEquals(R.string.ml_session_join_title_unnamed, state.titleRes())
        assertEquals(listOf<Any>("PC"), state.titleArgs("PC"))
        assertEquals(emptyList<MoonlightAction>(), state.actions())
    }

    @Test
    fun `M14 a full host is the one state that blocks Apply`() {
        val state = ui(failure = MoonlightFailure.HostFull)
        assertEquals(R.string.ml_full_title, state.titleRes())
        assertEquals(R.string.ml_full_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.SEE_BINDINGS), state.actions())
        assertTrue(state.blocksApply)
    }

    @Test
    fun `M15 a session held by another device offers the close and a retry`() {
        val state = ui(failure = MoonlightFailure.BusyOther)
        assertEquals(R.string.ml_busy_other_title, state.titleRes())
        assertEquals(R.string.ml_busy_other_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.QUIT_APP, MoonlightAction.RETRY), state.actions())
    }

    @Test
    fun `M16 a refused rejoin offers the close and a retry`() {
        val state = ui(failure = MoonlightFailure.ResumeFailed)
        assertEquals(R.string.ml_resume_failed_title, state.titleRes())
        assertEquals(R.string.ml_resume_failed_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.QUIT_APP, MoonlightAction.RETRY), state.actions())
    }

    @Test
    fun `M17 a refusal quotes the hosts own wording`() {
        val state = ui(failure = MoonlightFailure.Refused("Unauthorized"))
        assertEquals(R.string.ml_refused_title, state.titleRes())
        assertEquals(listOf<Any>("PC", "Unauthorized"), state.titleArgs("PC"))
        assertEquals(R.string.ml_refused_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.RETRY), state.actions())
    }

    @Test
    fun `M18 a stream that never came up says the app was closed again`() {
        val state = ui(failure = MoonlightFailure.SetupFailed)
        assertEquals(R.string.ml_setup_failed_title, state.titleRes())
        assertEquals(R.string.ml_setup_failed_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.RETRY), state.actions())
    }

    @Test
    fun `M19 a live session names the app and the controller number`() {
        val state = ui(phase = MoonlightPhase.Live(controllerNumber = 3, appName = "Desktop"))
        assertEquals(R.string.ml_session_live_title, state.titleRes())
        assertEquals(listOf<Any>("PC"), state.titleArgs("PC"))
        assertEquals(R.string.ml_session_live_body, state.bodyRes())
        assertEquals(listOf<Any>("Desktop", 3), state.bodyArgs("PC"))
        assertEquals(listOf(MoonlightAction.QUIT_APP), state.actions())
        assertEquals(MoonlightTone.SUCCESS, state.tone())
    }

    @Test
    fun `M20 a drop is recoverable and offers a reconnect`() {
        val state = ui(phase = MoonlightPhase.Dropped)
        assertEquals(R.string.ml_dropped_title, state.titleRes())
        assertEquals(R.string.ml_dropped_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.RECONNECT), state.actions())
    }

    @Test
    fun `M21 a host-ended session is not a drop and offers a new session`() {
        val state = ui(phase = MoonlightPhase.Ended)
        assertEquals(R.string.ml_ended_title, state.titleRes())
        assertEquals(R.string.ml_ended_body, state.bodyRes())
        assertEquals(listOf(MoonlightAction.START_SESSION), state.actions())
    }

    @Test
    fun `a failure outranks the live session it interrupted`() {
        val state =
            ui(
                phase = MoonlightPhase.Live(controllerNumber = 1, appName = "Desktop"),
                failure = MoonlightFailure.SetupFailed,
            )
        assertEquals(MoonlightSessionUi.SetupFailed, state)
    }

    @Test
    fun `a session of any kind outranks the app list`() {
        val joining = ui(phase = MoonlightPhase.Joining(1, "Desktop"), apps = MoonlightApps.Loading)
        assertTrue(joining is MoonlightSessionUi.Joining)
        val failed = ui(failure = MoonlightFailure.BusyOther, apps = MoonlightApps.Loading)
        assertEquals(MoonlightSessionUi.BusyOther, failed)
    }

    @Test
    fun `only a full host blocks Apply`() {
        val everyState =
            listOf(
                ui(trust = MoonlightTrustState.CHECKING),
                ui(trust = MoonlightTrustState.NOT_PAIRED),
                ui(trust = MoonlightTrustState.NOT_PAIRED, pairing = MoonlightPairingUi.Pin("1234")),
                ui(trust = MoonlightTrustState.NOT_PAIRED, pairing = MoonlightPairingUi.Failed),
                ui(trust = MoonlightTrustState.UNREACHABLE),
                ui(trust = MoonlightTrustState.REMEMBERED),
                ui(trust = MoonlightTrustState.TRUST_LOST),
                ui(trust = MoonlightTrustState.REPLACED),
                ui(apps = MoonlightApps.Loading),
                ui(),
                ui(apps = MoonlightApps.Empty),
                ui(apps = MoonlightApps.Failed),
                ui(phase = MoonlightPhase.Joining(1, "Desktop")),
                ui(failure = MoonlightFailure.BusyOther),
                ui(failure = MoonlightFailure.ResumeFailed),
                ui(failure = MoonlightFailure.Refused("no")),
                ui(failure = MoonlightFailure.SetupFailed),
                ui(phase = MoonlightPhase.Live(1, "Desktop")),
                ui(phase = MoonlightPhase.Dropped),
                ui(phase = MoonlightPhase.Ended),
            )
        assertEquals(20, everyState.size)
        everyState.forEach { assertFalse(it.toString(), it.blocksApply) }
        assertTrue(ui(failure = MoonlightFailure.HostFull).blocksApply)
    }

    @Test
    fun `the host-scoped actions carry the host name and the rest carry nothing`() {
        assertEquals(listOf<Any>("PC"), MoonlightAction.QUIT_APP.labelArgs("PC"))
        assertEquals(listOf<Any>("PC"), MoonlightAction.SEE_BINDINGS.labelArgs("PC"))
        assertEquals(emptyList<Any>(), MoonlightAction.RETRY.labelArgs("PC"))
        assertEquals(R.string.ml_action_quit_app, MoonlightAction.QUIT_APP.labelRes())
    }

    @Test
    fun `the trust chip says one of two words and never lights up`() {
        assertEquals(R.string.ml_trust_paired, MoonlightTrustState.PAIRED.chipTextRes())
        assertEquals(R.string.ml_trust_paired, MoonlightTrustState.REMEMBERED.chipTextRes())
        assertEquals(R.string.ml_trust_paired, MoonlightTrustState.CHECKING.chipTextRes())
        assertEquals(R.string.ml_trust_paired, MoonlightTrustState.UNREACHABLE.chipTextRes())
        assertEquals(R.string.ml_trust_not_paired, MoonlightTrustState.NOT_PAIRED.chipTextRes())
        assertEquals(R.string.ml_trust_not_paired, MoonlightTrustState.TRUST_LOST.chipTextRes())
        assertEquals(R.string.ml_trust_not_paired, MoonlightTrustState.REPLACED.chipTextRes())
    }

    @Test
    fun `holdsPairing tracks the chip word exactly`() {
        for (state in MoonlightTrustState.entries) {
            assertEquals(
                state.name,
                state.chipTextRes() == R.string.ml_trust_paired,
                state.holdsPairing(),
            )
        }
    }
}
