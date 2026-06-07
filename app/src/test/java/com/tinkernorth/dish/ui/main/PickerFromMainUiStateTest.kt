// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import org.junit.Assert.assertEquals
import org.junit.Test

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

    private fun pickerFor(
        state: MainUiState,
        slot: ControllerSlot,
    ): List<ConnectionSummary> = connectionsVisibleInPicker(state.connections, slot.boundConnectionId)

    @Test
    fun `default state has an empty picker for the virtual slot`() {
        val state = MainUiState()
        assertEquals(emptyList<ConnectionSummary>(), pickerFor(state, state.virtualSlot))
    }

    @Test
    fun `only live connections populate the picker — discovered-but-not-connected are hidden`() {
        val live = summary("s:1", LinkState.Connected)
        val ready = summary("s:2", LinkState.Ready)
        val found = summary("s:3", LinkState.Found)
        val state =
            MainUiState(
                slots = listOf(virtualSlot()),
                connections = listOf(live, ready, found),
            )

        assertEquals(listOf(live), pickerFor(state, state.virtualSlot))
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

    @Test
    fun `two slots bound to two different live connections each see both`() {
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

        assertEquals(listOf(sat1Off, sat2On), pickerFor(state, p1))
        assertEquals(listOf(sat2On), pickerFor(state, p2))
        assertEquals(listOf(sat2On), pickerFor(state, state.virtualSlot))
    }

    @Test
    fun `bound Bluetooth host that drops offline still shows in the picker`() {
        val btHostOffline =
            summary("bt:AA:BB", LinkState.Saved, kind = ConnectionKind.BLUETOOTH, boundSlots = listOf("p1"))
        val state =
            MainUiState(
                slots = listOf(virtualSlot(), physicalSlot("p1", physicalDeviceId = 1, boundTo = "bt:AA:BB")),
                connections = listOf(btHostOffline),
            )

        val p1 = state.slots.first { it.id == "p1" }
        assertEquals(listOf(btHostOffline), pickerFor(state, p1))
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
        val held = summary("s:1", LinkState.Saved, boundSlots = listOf("p1"))
        val pre =
            MainUiState(
                slots = listOf(virtualSlot(), physicalSlot("p1", physicalDeviceId = 1, boundTo = "s:1")),
                connections = listOf(held),
            )
        val p1 = pre.slots.first { it.id == "p1" }
        assertEquals(listOf(held), pickerFor(pre, p1))
        assertEquals(emptyList<ConnectionSummary>(), pickerFor(pre, pre.virtualSlot))

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
        assertEquals(listOf(online, staleHeld), virtualPicker)
    }
}
