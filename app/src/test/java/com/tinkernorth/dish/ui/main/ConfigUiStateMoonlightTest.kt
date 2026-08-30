// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.source.connection.moonlight.MoonlightTrustState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// A binding is a durable intent: nothing a Moonlight host says about itself keeps the
// user from saving one, and the type list is answered locally rather than fetched.
class ConfigUiStateMoonlightTest {
    private val hostId = "moonlight:uid:abc"

    private fun summary(
        id: String,
        kind: ConnectionKind,
        live: LinkState = LinkState.Saved,
    ) = ConnectionSummary(id = id, kind = kind, label = "PC", detail = "", live = live, boundSlotIds = emptyList())

    private fun state(
        type: Int? = MoonlightEmulatedType.AUTO,
        moonlight: MoonlightSessionInput? = MoonlightSessionInput(),
        kind: ConnectionKind = ConnectionKind.MOONLIGHT,
    ) = ConfigUiState(
        loaded = true,
        hosts = listOf(BindingHost(hostId, "PC", kind)),
        connections = listOf(summary(hostId, kind)),
        draft =
            BindingDraft(
                hostId = hostId,
                type = type,
                directOn = false,
                motionOn = false,
                touchpadMode = "off",
            ),
        controllerPresent = true,
        moonlight = moonlight,
    )

    @Test
    fun `a Moonlight destination is recognised as one`() {
        assertTrue(state().isMoonlightHost)
        assertFalse(state().isBluetoothHost)
        assertFalse(state(kind = ConnectionKind.SATELLITE).isMoonlightHost)
    }

    // The reported symptom: the type list never populated for a Moonlight id, so the
    // draft carried no type and Apply stayed disabled forever.
    @Test
    fun `a Moonlight host with a seeded type can be applied`() {
        assertTrue(state().canApply)
    }

    @Test
    fun `Apply survives every Moonlight state except a host already carrying four pads`() {
        val reachable =
            listOf(
                MoonlightSessionInput(trust = MoonlightTrustState.CHECKING),
                MoonlightSessionInput(trust = MoonlightTrustState.NOT_PAIRED),
                MoonlightSessionInput(trust = MoonlightTrustState.UNREACHABLE),
                MoonlightSessionInput(trust = MoonlightTrustState.REMEMBERED),
                MoonlightSessionInput(trust = MoonlightTrustState.TRUST_LOST),
                MoonlightSessionInput(trust = MoonlightTrustState.REPLACED),
                paired(pairing = MoonlightPairingUi.Pin("1234")),
                paired(pairing = MoonlightPairingUi.Failed),
                paired(apps = MoonlightApps.Loading),
                paired(apps = MoonlightApps.Empty),
                paired(apps = MoonlightApps.Failed),
                paired(failure = MoonlightFailure.BusyOther),
                paired(failure = MoonlightFailure.ResumeFailed),
                paired(failure = MoonlightFailure.Refused("no")),
                paired(failure = MoonlightFailure.SetupFailed),
                paired(phase = MoonlightPhase.Joining(2, "Desktop")),
                paired(phase = MoonlightPhase.Live(1, "Desktop")),
                paired(phase = MoonlightPhase.Dropped),
                paired(phase = MoonlightPhase.Ended),
            )
        reachable.forEach { input ->
            val rendered = state(moonlight = input).moonlightSession
            assertTrue("$input rendered $rendered", state(moonlight = input).canApply)
        }
        val full = paired(failure = MoonlightFailure.HostFull)
        assertEquals(MoonlightSessionUi.HostFull, state(moonlight = full).moonlightSession)
        assertFalse(state(moonlight = full).canApply)
    }

    private fun paired(
        pairing: MoonlightPairingUi? = null,
        apps: MoonlightApps = MoonlightApps.Ready(listOf(MoonlightAppUi("1", "Desktop"))),
        phase: MoonlightPhase = MoonlightPhase.Idle,
        failure: MoonlightFailure? = null,
    ) = MoonlightSessionInput(
        trust = MoonlightTrustState.PAIRED,
        pairing = pairing,
        apps = apps,
        phase = phase,
        failure = failure,
    )

    @Test
    fun `a Moonlight host never blocks the screen, because there is no live link to lose`() {
        assertNull(state().blocker)
        assertNull(state(moonlight = MoonlightSessionInput(trust = MoonlightTrustState.UNREACHABLE)).blocker)
    }

    @Test
    fun `a lost input still blocks a Moonlight binding`() {
        assertEquals(BindingBlocker.InputLost, state().copy(controllerPresent = false).blocker)
    }

    @Test
    fun `the type is still required before Apply, as it is for a satellite`() {
        assertFalse(state(type = null).canApply)
    }

    @Test
    fun `a satellite host still applies on its own rules and renders no session section`() {
        val satellite = state(type = CONTROLLER_TYPE_XBOX, kind = ConnectionKind.SATELLITE)
        assertTrue(satellite.canApply)
        assertNull(satellite.moonlightSession)
    }

    @Test
    fun `a Moonlight host with nothing probed yet renders the checking state, not nothing`() {
        assertEquals(MoonlightSessionUi.Checking, state(moonlight = null).moonlightSession)
    }
}
