// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.data.network.ConnectionKind
import com.tinkernorth.dish.data.network.ConnectionSummary
import com.tinkernorth.dish.data.network.LinkState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Integration tests for the picker filter against a realistic [MainUiState].
 *
 * The unit test in [ConnectionsVisibleInPickerTest] pins the pure rule;
 * this class drives it through the same shape the [ControllerAdapter] sees
 * at runtime — [ControllerSlot.boundConnectionId] derived from
 * [MainUiState.slots], a per-kind [ConnectionSummary] list from
 * [MainUiState.connections], and a virtual slot that's always present plus
 * physical slots that come and go.
 *
 * Each test models a step the user is likely to walk through, so a future
 * regression maps to a recognisable user-facing failure ("after unbind, the
 * offline row should disappear"). The "spec —" prefixed cases are direct
 * translations of the feature spec.
 */
class PickerFromMainUiStateTest {
    private fun summary(
        id: String,
        live: LinkState,
        kind: ConnectionKind = ConnectionKind.SATELLITE,
        boundSlots: List<String> = emptyList(),
    ) = ConnectionSummary(
        id = id,
        kind = kind,
        label = id,
        detail = "",
        live = live,
        boundSlotIds = boundSlots,
    )

    private fun virtualSlot(boundTo: String? = null) =
        ControllerSlot(
            id = VIRTUAL_SLOT_ID,
            inputType = SlotInputType.VIRTUAL,
            name = "Virtual",
            boundConnectionId = boundTo,
        )

    private fun physicalSlot(
        id: String,
        physicalDeviceId: Int,
        boundTo: String? = null,
    ) = ControllerSlot(
        id = id,
        inputType = SlotInputType.PHYSICAL,
        name = "Pad $id",
        physicalDeviceId = physicalDeviceId,
        boundConnectionId = boundTo,
    )

    /** Convenience: filter the picker for the given slot using the state's connection list. */
    private fun pickerFor(
        state: MainUiState,
        slot: ControllerSlot,
    ): List<ConnectionSummary> = connectionsVisibleInPicker(state.connections, slot.boundConnectionId)

    // ── Baseline ────────────────────────────────────────────────────────────

    @Test
    fun `default state has an empty picker for the virtual slot`() {
        val state = MainUiState()
        assertEquals(emptyList<ConnectionSummary>(), pickerFor(state, state.virtualSlot))
    }

    @Test
    fun `freshly discovered satellites populate the picker even unbound`() {
        // No bindings yet; everything online/ready/found should be claimable
        // from the unbound virtual slot.
        val a = summary("s:1", LinkState.Connected)
        val b = summary("s:2", LinkState.Ready)
        val c = summary("s:3", LinkState.Found)
        val state =
            MainUiState(
                slots = listOf(virtualSlot()),
                connections = listOf(a, b, c),
            )

        assertEquals(listOf(a, b, c), pickerFor(state, state.virtualSlot))
    }

    @Test
    fun `remembered-but-offline satellites are hidden from an unbound slot`() {
        val offline = summary("s:1", LinkState.Saved)
        val state =
            MainUiState(
                slots = listOf(virtualSlot()),
                connections = listOf(offline),
            )

        assertEquals(emptyList<ConnectionSummary>(), pickerFor(state, state.virtualSlot))
    }

    // ── Lifecycle steps the user actually walks ─────────────────────────────

    @Test
    fun `spec — after bind, picker still shows the bound connection`() {
        val online = summary("s:1", LinkState.Connected, boundSlots = listOf(VIRTUAL_SLOT_ID))
        val state =
            MainUiState(
                slots = listOf(virtualSlot(boundTo = "s:1")),
                connections = listOf(online),
            )

        assertEquals(listOf(online), pickerFor(state, state.virtualSlot))
    }

    @Test
    fun `spec — when the bound connection drops to Saved, picker keeps it as the holdover`() {
        // The connection went offline while the user was bound. The summary
        // reports LinkState.Saved but boundSlotIds still lists the slot — the
        // hub keeps the binding until the user clears it or auto-reconnect
        // restores the link.
        val offlineHeld = summary("s:1", LinkState.Saved, boundSlots = listOf(VIRTUAL_SLOT_ID))
        val state =
            MainUiState(
                slots = listOf(virtualSlot(boundTo = "s:1")),
                connections = listOf(offlineHeld),
            )

        assertEquals(listOf(offlineHeld), pickerFor(state, state.virtualSlot))
    }

    @Test
    fun `spec — auto-recovery restores normal availability`() {
        // After the Saved → Connected transition, the summary flips back to
        // Connected; nothing about the bind has to change for the picker to
        // render it as a normal online entry.
        val recovered = summary("s:1", LinkState.Connected, boundSlots = listOf(VIRTUAL_SLOT_ID))
        val state =
            MainUiState(
                slots = listOf(virtualSlot(boundTo = "s:1")),
                connections = listOf(recovered),
            )

        assertEquals(listOf(recovered), pickerFor(state, state.virtualSlot))
    }

    @Test
    fun `spec — unbind while offline drops the row from the picker`() {
        // The hub clears boundSlotIds on unbind, so the summary's bound list
        // goes empty and the slot's boundConnectionId is null. The filter
        // sees Saved + null and drops it.
        val nowUnboundOffline = summary("s:1", LinkState.Saved, boundSlots = emptyList())
        val state =
            MainUiState(
                slots = listOf(virtualSlot(boundTo = null)),
                connections = listOf(nowUnboundOffline),
            )

        assertEquals(emptyList<ConnectionSummary>(), pickerFor(state, state.virtualSlot))
    }

    @Test
    fun `spec — Stale on the bound connection also stays as a holdover`() {
        val stale = summary("s:1", LinkState.Stale, boundSlots = listOf(VIRTUAL_SLOT_ID))
        val state =
            MainUiState(
                slots = listOf(virtualSlot(boundTo = "s:1")),
                connections = listOf(stale),
            )

        assertEquals(listOf(stale), pickerFor(state, state.virtualSlot))
    }

    // ── Multi-slot scenarios ────────────────────────────────────────────────

    @Test
    fun `two slots bound to two different live connections each see both`() {
        // Two physical pads each bound to their own satellite. The picker is
        // global — both pads see the full set of available connections; bind
        // status only matters for the holdover rule.
        val a = summary("s:a", LinkState.Connected, boundSlots = listOf("p1"))
        val b = summary("s:b", LinkState.Connected, boundSlots = listOf("p2"))
        val state =
            MainUiState(
                slots =
                    listOf(
                        virtualSlot(),
                        physicalSlot("p1", physicalDeviceId = 1, boundTo = "s:a"),
                        physicalSlot("p2", physicalDeviceId = 2, boundTo = "s:b"),
                    ),
                connections = listOf(a, b),
            )

        val p1 = state.slots.first { it.id == "p1" }
        val p2 = state.slots.first { it.id == "p2" }
        assertEquals(listOf(a, b), pickerFor(state, state.virtualSlot))
        assertEquals(listOf(a, b), pickerFor(state, p1))
        assertEquals(listOf(a, b), pickerFor(state, p2))
    }

    @Test
    fun `slot A keeps offline holdover, slot B does not — bound-ness is per-slot`() {
        // Slot A bound to Sat 1 (offline → held over for A only).
        // Slot B unbound; Sat 1 should not appear in B's picker.
        val sat1Off = summary("s:1", LinkState.Saved, boundSlots = listOf("p1"))
        val sat2On = summary("s:2", LinkState.Connected)
        val state =
            MainUiState(
                slots =
                    listOf(
                        virtualSlot(boundTo = null),
                        physicalSlot("p1", physicalDeviceId = 1, boundTo = "s:1"),
                        physicalSlot("p2", physicalDeviceId = 2, boundTo = null),
                    ),
                connections = listOf(sat1Off, sat2On),
            )

        val p1 = state.slots.first { it.id == "p1" }
        val p2 = state.slots.first { it.id == "p2" }

        // p1 sees its offline holdover + the live alternative.
        assertEquals(listOf(sat1Off, sat2On), pickerFor(state, p1))
        // p2 only sees the live one — Sat 1 is hidden there.
        assertEquals(listOf(sat2On), pickerFor(state, p2))
        // Virtual is unbound, same as p2.
        assertEquals(listOf(sat2On), pickerFor(state, state.virtualSlot))
    }

    @Test
    fun `bound Bluetooth host that drops offline still shows in the picker`() {
        // Bluetooth is single-host: HID Device profile only supports one
        // active connection. When the host walks away the summary flips to
        // Saved; the bound slot still wants the holdover.
        val btHostOffline =
            summary("bt:AA:BB", LinkState.Saved, kind = ConnectionKind.BLUETOOTH, boundSlots = listOf("p1"))
        val state =
            MainUiState(
                slots = listOf(virtualSlot(), physicalSlot("p1", physicalDeviceId = 1, boundTo = "bt:AA:BB")),
                connections = listOf(btHostOffline),
            )

        val p1 = state.slots.first { it.id == "p1" }
        assertEquals(listOf(btHostOffline), pickerFor(state, p1))
        // Virtual is unbound — BT host is hidden there.
        assertEquals(emptyList<ConnectionSummary>(), pickerFor(state, state.virtualSlot))
    }

    @Test
    fun `mixed Bluetooth and Satellite live connections appear together`() {
        val btOn = summary("bt:1", LinkState.Connected, kind = ConnectionKind.BLUETOOTH)
        val satOn = summary("s:1", LinkState.Connected, kind = ConnectionKind.SATELLITE)
        val state =
            MainUiState(
                slots = listOf(virtualSlot()),
                connections = listOf(satOn, btOn),
            )

        assertEquals(listOf(satOn, btOn), pickerFor(state, state.virtualSlot))
    }

    @Test
    fun `recovery brings the bound row back to available styling for every slot`() {
        // Pre-recovery: held-over offline only visible to the bound slot.
        val held = summary("s:1", LinkState.Saved, boundSlots = listOf("p1"))
        val pre =
            MainUiState(
                slots = listOf(virtualSlot(), physicalSlot("p1", physicalDeviceId = 1, boundTo = "s:1")),
                connections = listOf(held),
            )
        val p1 = pre.slots.first { it.id == "p1" }
        assertEquals(listOf(held), pickerFor(pre, p1))
        assertEquals(emptyList<ConnectionSummary>(), pickerFor(pre, pre.virtualSlot))

        // Post-recovery: same id, now Connected. Visible to *every* slot.
        val recovered = summary("s:1", LinkState.Connected, boundSlots = listOf("p1"))
        val post =
            MainUiState(
                slots = listOf(virtualSlot(), physicalSlot("p1", physicalDeviceId = 1, boundTo = "s:1")),
                connections = listOf(recovered),
            )
        val p1Post = post.slots.first { it.id == "p1" }
        assertEquals(listOf(recovered), pickerFor(post, p1Post))
        assertEquals(listOf(recovered), pickerFor(post, post.virtualSlot))
    }

    // ── Realistic dashboard ─────────────────────────────────────────────────

    @Test
    fun `crowded picker — one online, one connecting, one stale-held, one offline-unbound`() {
        val online = summary("s:1", LinkState.Connected)
        val connecting = summary("s:2", LinkState.Connecting)
        val staleHeld = summary("s:3", LinkState.Stale, boundSlots = listOf(VIRTUAL_SLOT_ID))
        val offlineUnbound = summary("s:4", LinkState.Saved)
        val state =
            MainUiState(
                slots = listOf(virtualSlot(boundTo = "s:3")),
                connections = listOf(online, connecting, staleHeld, offlineUnbound),
            )

        val virtualPicker = pickerFor(state, state.virtualSlot)
        // s:4 is offline + unbound → hidden.
        // s:3 is stale + bound to virtual → held over.
        // s:1/s:2 are available → always shown.
        // Order is the original input order.
        assertEquals(listOf(online, connecting, staleHeld), virtualPicker)
    }
}
