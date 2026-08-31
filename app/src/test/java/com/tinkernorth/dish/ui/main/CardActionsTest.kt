// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.repository.TouchpadModeValue
import com.tinkernorth.dish.source.usb.PathChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CardActionsTest {
    private fun summary(
        kind: ConnectionKind = ConnectionKind.SATELLITE,
        live: LinkState = LinkState.Connected,
    ) = ConnectionSummary(
        id = "c:1",
        kind = kind,
        label = "Host",
        detail = "",
        live = live,
        boundSlotIds = listOf("slot"),
    )

    private fun slot(
        inputType: SlotInputType,
        bound: ConnectionSummary? = summary(),
    ) = ControllerSlot(
        id = if (inputType == SlotInputType.VIRTUAL) VIRTUAL_SLOT_ID else "9",
        inputType = inputType,
        name = "Slot",
        boundConnectionId = bound?.id,
        boundStatus = bound,
    )

    private fun pointer(
        touchpad: Boolean = false,
        mouse: Boolean = false,
    ) = PointerSlotUi(mode = TouchpadModeValue.DS4, touchpadOpenable = touchpad, mouseOpenable = mouse)

    private fun pathCard(
        wiredSwitchAvailable: Boolean = false,
        suggestDirectForTouch: Boolean = false,
    ) = PathCard(
        currentMode = InputPathMode.Standard,
        selected = PathChoice.Standard,
        transport = Transport.Usb,
        directAvailable = false,
        recognized = false,
        restoring = false,
        standard = PathCapabilities(rumble = false, motion = false),
        direct = PathCapabilities(rumble = false, motion = false),
        directPollHz = 0,
        risk = PathRisk.None,
        suggestDirectForTouch = suggestDirectForTouch,
        wiredSwitchAvailable = wiredSwitchAvailable,
    )

    private fun row(
        slot: ControllerSlot,
        connections: List<ConnectionSummary> = listOf(summary()),
        pointer: PointerSlotUi? = null,
        pathCard: PathCard? = null,
    ) = ControllerAdapter.Row(slot = slot, connections = connections, pointer = pointer, pathCard = pathCard)

    private fun kinds(actions: CardActions): List<CardActionKind> = actions.filled.map { it.kind }

    @Test
    fun `a bound virtual satellite slot offers gamepad plus mouse and never the touchpad`() {
        val actions =
            computeCardActions(row(slot(SlotInputType.VIRTUAL), pointer = pointer(touchpad = true, mouse = true)))
        assertEquals(listOf(CardActionKind.GAMEPAD, CardActionKind.MOUSE), kinds(actions))
        assertEquals(CardActionKind.CONFIGURE, actions.outlined?.kind)
    }

    @Test
    fun `a bound virtual slot without the mouse surface offers gamepad alone`() {
        val actions = computeCardActions(row(slot(SlotInputType.VIRTUAL), pointer = pointer()))
        assertEquals(listOf(CardActionKind.GAMEPAD), kinds(actions))
        assertEquals(CardActionKind.CONFIGURE, actions.outlined?.kind)
    }

    @Test
    fun `a phone-sourced physical slot offers touchpad and mouse per the host`() {
        val actions =
            computeCardActions(row(slot(SlotInputType.PHYSICAL), pointer = pointer(touchpad = true, mouse = true)))
        assertEquals(listOf(CardActionKind.TOUCHPAD, CardActionKind.MOUSE), kinds(actions))

        val touchOnly =
            computeCardActions(row(slot(SlotInputType.PHYSICAL), pointer = pointer(touchpad = true)))
        assertEquals(listOf(CardActionKind.TOUCHPAD), kinds(touchOnly))

        val mouseOnly =
            computeCardActions(row(slot(SlotInputType.PHYSICAL), pointer = pointer(mouse = true)))
        assertEquals(listOf(CardActionKind.MOUSE), kinds(mouseOnly))
    }

    @Test
    fun `pointer surfaces need a connected satellite`() {
        val moonlight = summary(kind = ConnectionKind.MOONLIGHT)
        val actions =
            computeCardActions(
                row(slot(SlotInputType.PHYSICAL, bound = moonlight), pointer = pointer(touchpad = true, mouse = true)),
            )
        assertEquals(emptyList<CardActionKind>(), kinds(actions))

        val disconnected = summary(live = LinkState.Connecting)
        val offline =
            computeCardActions(
                row(slot(SlotInputType.PHYSICAL, bound = disconnected), pointer = pointer(touchpad = true, mouse = true)),
            )
        assertEquals(emptyList<CardActionKind>(), kinds(offline))
    }

    @Test
    fun `bound cards always close with the outlined configure action`() {
        val actions = computeCardActions(row(slot(SlotInputType.PHYSICAL)))
        assertEquals(CardActionKind.CONFIGURE, actions.outlined?.kind)
        assertEquals(emptyList<CardActionKind>(), kinds(actions))
    }

    @Test
    fun `an unbound slot with no connections offers find-hosts as a filled action`() {
        val actions = computeCardActions(row(slot(SlotInputType.PHYSICAL, bound = null), connections = emptyList()))
        assertEquals(listOf(CardActionKind.FIND_HOSTS), kinds(actions))
        assertNull(actions.outlined)
    }

    @Test
    fun `an unbound slot with connections offers only the outlined configure`() {
        val actions = computeCardActions(row(slot(SlotInputType.PHYSICAL, bound = null)))
        assertEquals(emptyList<CardActionKind>(), kinds(actions))
        assertEquals(CardActionKind.CONFIGURE, actions.outlined?.kind)
    }

    @Test
    fun `the wired switch rides both bound and unbound cards`() {
        val unbound =
            computeCardActions(
                row(slot(SlotInputType.PHYSICAL, bound = null), pathCard = pathCard(wiredSwitchAvailable = true)),
            )
        assertEquals(listOf(CardActionKind.SETUP_WIRED), kinds(unbound))

        val bound =
            computeCardActions(row(slot(SlotInputType.PHYSICAL), pathCard = pathCard(wiredSwitchAvailable = true)))
        assertEquals(listOf(CardActionKind.SETUP_WIRED), kinds(bound))
    }

    @Test
    fun `every reachable action shape maps to a fixed layout`() {
        val reachable =
            listOf(
                computeCardActions(row(slot(SlotInputType.PHYSICAL, bound = null))),
                computeCardActions(row(slot(SlotInputType.PHYSICAL, bound = null), connections = emptyList())),
                computeCardActions(
                    row(
                        slot(SlotInputType.PHYSICAL, bound = null),
                        connections = emptyList(),
                        pathCard = pathCard(wiredSwitchAvailable = true),
                    ),
                ),
                computeCardActions(row(slot(SlotInputType.PHYSICAL))),
                computeCardActions(row(slot(SlotInputType.VIRTUAL), pointer = pointer(mouse = true))),
                computeCardActions(row(slot(SlotInputType.VIRTUAL), pointer = pointer(touchpad = true, mouse = true))),
                computeCardActions(
                    row(
                        slot(SlotInputType.PHYSICAL),
                        pointer = pointer(touchpad = true, mouse = true),
                        pathCard = pathCard(wiredSwitchAvailable = true),
                    ),
                ),
                computeCardActions(
                    row(slot(SlotInputType.PHYSICAL), pathCard = pathCard(suggestDirectForTouch = true, wiredSwitchAvailable = true)),
                ),
            )
        reachable.forEach { actions -> assertNotNull(cardActionsLayoutFor(actions.viewType)) }
    }

    @Test
    fun `the view type encodes the exact button shape`() {
        assertEquals(1, CardActions(emptyList(), CONFIGURE_LIKE).viewType)
        assertEquals(2, CardActions(listOf(CONFIGURE_LIKE), null).viewType)
        assertEquals(3, CardActions(listOf(CONFIGURE_LIKE), CONFIGURE_LIKE).viewType)
        assertEquals(5, CardActions(listOf(CONFIGURE_LIKE, CONFIGURE_LIKE), CONFIGURE_LIKE).viewType)
        assertEquals(7, CardActions(listOf(CONFIGURE_LIKE, CONFIGURE_LIKE, CONFIGURE_LIKE), CONFIGURE_LIKE).viewType)
    }

    private companion object {
        val CONFIGURE_LIKE =
            CardActionSpec(R.drawable.ic_tune, R.string.binding_action_configure, CardActionKind.CONFIGURE)
    }
}
