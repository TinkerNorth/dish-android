// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
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
    fun `a moonlight slot offers the mouse but never the satellite touchpad surface`() {
        val moonlight = summary(kind = ConnectionKind.MOONLIGHT)
        val actions =
            computeCardActions(
                row(slot(SlotInputType.PHYSICAL, bound = moonlight), pointer = pointer(touchpad = true, mouse = true)),
            )
        assertEquals(listOf(CardActionKind.MOUSE), kinds(actions))
    }

    @Test
    fun `a bound virtual moonlight slot reads like the satellite one, gamepad plus mouse`() {
        val moonlight = summary(kind = ConnectionKind.MOONLIGHT)
        val actions =
            computeCardActions(
                row(slot(SlotInputType.VIRTUAL, bound = moonlight), pointer = pointer(touchpad = true, mouse = true)),
            )
        assertEquals(listOf(CardActionKind.GAMEPAD, CardActionKind.MOUSE), kinds(actions))
    }

    @Test
    fun `pointer surfaces need a connected host`() {
        val disconnected = summary(live = LinkState.Connecting)
        val offline =
            computeCardActions(
                row(slot(SlotInputType.PHYSICAL, bound = disconnected), pointer = pointer(touchpad = true, mouse = true)),
            )
        assertEquals(emptyList<CardActionKind>(), kinds(offline))

        val moonlightDown = summary(kind = ConnectionKind.MOONLIGHT, live = LinkState.Connecting)
        val moonlightOffline =
            computeCardActions(
                row(slot(SlotInputType.PHYSICAL, bound = moonlightDown), pointer = pointer(touchpad = true, mouse = true)),
            )
        assertEquals(emptyList<CardActionKind>(), kinds(moonlightOffline))
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

    private fun padTypeCaps(): SlotCapabilities =
        SlotCapabilities(
            controller = CapabilitySet.EMPTY,
            transport = CapabilitySet.EMPTY,
            type = CapabilitySet.of(Feature.TOUCHPAD),
            host = CapabilitySet.EMPTY,
            userEnabled = CapabilitySet.EMPTY,
            runtimeDown = CapabilitySet.EMPTY,
        )

    @Test
    fun `a ds4-routed slot reports the pad on plus the on-demand mouse chip`() {
        val facts =
            pointerFuncFacts(
                row(slot(SlotInputType.VIRTUAL), pointer = pointer(mouse = true)).copy(motionCap = padTypeCaps()),
            )
        assertEquals(listOf(PointerPillFact.PAD_ON, PointerPillFact.MOUSE_READY), facts)
    }

    @Test
    fun `a mouse-routed slot without a pad-bearing type reports only the mouse chip`() {
        val mouseRow =
            row(
                slot(SlotInputType.VIRTUAL),
                pointer = PointerSlotUi(mode = TouchpadModeValue.MOUSE, touchpadOpenable = false, mouseOpenable = true),
            )
        assertEquals(listOf(PointerPillFact.MOUSE_READY), pointerFuncFacts(mouseRow))
    }

    @Test
    fun `a pad-bearing type whose route is off reports the pad off`() {
        val offRow =
            row(
                slot(SlotInputType.PHYSICAL),
                pointer = PointerSlotUi(mode = TouchpadModeValue.OFF, touchpadOpenable = false, mouseOpenable = false),
            ).copy(motionCap = padTypeCaps())
        assertEquals(listOf(PointerPillFact.PAD_OFF), pointerFuncFacts(offRow))
    }

    @Test
    fun `the direct-mode nudge outranks the routing pills`() {
        val nudgeRow =
            row(slot(SlotInputType.PHYSICAL), pathCard = pathCard(suggestDirectForTouch = true))
                .copy(motionCap = padTypeCaps())
        assertEquals(listOf(PointerPillFact.PAD_NEEDS_DIRECT), pointerFuncFacts(nudgeRow))
    }

    @Test
    fun `a slot with neither surface reports no pointer pills`() {
        assertEquals(emptyList<PointerPillFact>(), pointerFuncFacts(row(slot(SlotInputType.PHYSICAL))))
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
