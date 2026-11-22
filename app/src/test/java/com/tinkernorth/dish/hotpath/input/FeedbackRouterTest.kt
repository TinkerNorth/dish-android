// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.source.lights.FrameworkLightGateway
import com.tinkernorth.dish.source.lights.LightSource
import com.tinkernorth.dish.source.store.FeedbackActivityStore
import com.tinkernorth.dish.source.store.FeedbackKind
import com.tinkernorth.dish.source.store.MIC_LED_OFF
import com.tinkernorth.dish.source.store.MIC_LED_ON
import com.tinkernorth.dish.source.store.MIC_LED_PULSE
import com.tinkernorth.dish.source.store.RumbleEnabledStore
import com.tinkernorth.dish.source.store.VirtualPadFeedbackStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CONNECTION_ID = "c"
private val MOONLIGHT_PAD = LightSource("moonlight:pc", 0)

private const val DIRECT_PAD = -1000
private const val DIRECT_SLOT = "-1000"
private const val OTHER_DIRECT_SLOT = "-1001"

// One light bar write that reached a Direct pad.
private data class LightbarWrite(
    val deviceId: Int,
    val r: Int,
    val g: Int,
    val b: Int,
)

/**
 * The router's one job: land feedback on what the slot can actuate — a
 * Direct-claimed pad's OUT endpoint, the virtual pad's skin/vibrator, or a
 * framework pad's light bar through the Android lights gateway. The other
 * framework surfaces (trigger effects, player LEDs, the mic lamp) have no
 * Android API and are swallowed silently.
 */
class FeedbackRouterTest {
    private val native: PhysicalInputNative = mockk(relaxed = true)
    private val store = VirtualPadFeedbackStore()
    private val rumble: RumbleRouter = mockk(relaxed = true)
    private val frameworkLights: FrameworkLightGateway = mockk(relaxed = true)
    private val activity = FeedbackActivityStore()

    // Each slot's rumble switch, on unless a test turns it off.
    private val rumbleOff = mutableSetOf<String>()
    private val rumbleEnabled: RumbleEnabledStore =
        mockk { every { isEnabled(any()) } answers { firstArg<String>() !in rumbleOff } }

    // Every write a Direct pad received per surface, oldest first: the last one is what it shows.
    private val lightbarWrites = mutableListOf<LightbarWrite>()
    private val ledWrites = mutableListOf<Int>()
    private val triggerWrites = mutableListOf<ByteArray>()
    private val lampWrites = mutableListOf<Int>()

    init {
        every { native.sendUsbLightbar(any(), any(), any(), any()) } answers {
            lightbarWrites += LightbarWrite(arg(0), arg(1), arg(2), arg(3))
        }
        every { native.sendUsbPlayerLeds(DIRECT_PAD, any()) } answers { ledWrites += secondArg<Int>() }
        every { native.sendUsbTriggerEffects(DIRECT_PAD, any()) } answers { triggerWrites += secondArg<ByteArray>() }
        every { native.sendUsbMicMuteLed(DIRECT_PAD, any()) } answers { lampWrites += secondArg<Int>() }
    }

    private fun managerWith(
        handle: Int,
        slotId: String,
        controllerIndex: Int = 0,
    ): SatelliteConnectionManager {
        val conn = mockk<SatelliteConnection>()
        every { conn.id } returns CONNECTION_ID
        every { conn.handle } returns handle
        every { conn.state } returns MutableStateFlow(SatelliteSessionState.Live)
        every { conn.slots } returns
            MutableStateFlow(
                mapOf(slotId to SatelliteConnection.SlotBinding(controllerIndex = controllerIndex, controllerType = 2, registered = true)),
            )
        val manager = mockk<SatelliteConnectionManager>()
        every { manager.connections } returns MutableStateFlow(mapOf(CONNECTION_ID to conn))
        return manager
    }

    private fun router(manager: SatelliteConnectionManager = mockk(relaxed = true)) =
        FeedbackRouter(manager, native, store, rumble, activity, frameworkLights, rumbleEnabled)

    private fun lastKindNoted(slotId: String): FeedbackKind? = activity.snapshot()[slotId]?.lastKind

    @Test
    fun `lightbar reaches a Direct-claimed pad through the session resolve`() {
        router(managerWith(handle = 7, slotId = "-1000"))
            .dispatchLightbar(sessionHandle = 7, controllerIndex = 0, r = 1, g = 2, b = 3)
        verify(exactly = 1) { native.sendUsbLightbar(-1000, 1, 2, 3) }
    }

    @Test
    fun `trigger effects reach a Direct-claimed pad as the raw block bytes`() {
        val blocks = ByteArray(22) { it.toByte() }
        router(managerWith(handle = 7, slotId = "-1000"))
            .dispatchTriggerEffects(sessionHandle = 7, controllerIndex = 0, blocks = blocks)
        verify(exactly = 1) { native.sendUsbTriggerEffects(-1000, blocks) }
    }

    @Test
    fun `player LEDs reach a Direct-claimed pad`() {
        router(managerWith(handle = 7, slotId = "-1000"))
            .dispatchPlayerLeds(sessionHandle = 7, controllerIndex = 0, ledMask = 0x1F)
        verify(exactly = 1) { native.sendUsbPlayerLeds(-1000, 0x1F) }
    }

    @Test
    fun `the virtual pad renders lightbar and LEDs through the feedback store`() {
        val r = router(managerWith(handle = 7, slotId = VIRTUAL_SLOT_ID))
        r.dispatchLightbar(sessionHandle = 7, controllerIndex = 0, r = 0x11, g = 0x22, b = 0x33)
        r.dispatchPlayerLeds(sessionHandle = 7, controllerIndex = 0, ledMask = 0x05)
        assertEquals(0xFF112233.toInt(), store.state.value.lightbarColor)
        assertEquals(0x05, store.state.value.playerLedMask)
        verify(exactly = 0) { native.sendUsbLightbar(any(), any(), any(), any()) }
        verify(exactly = 0) { native.sendUsbPlayerLeds(any(), any()) }
    }

    @Test
    fun `the virtual pad shows which triggers hold a non-neutral effect`() {
        val r = router(managerWith(handle = 7, slotId = VIRTUAL_SLOT_ID))
        // Wire order left (0..10) then right (11..21); byte 0 of each is the mode.
        val leftOnly = ByteArray(22).also { it[0] = 0x21 }
        r.dispatchTriggerEffects(sessionHandle = 7, controllerIndex = 0, blocks = leftOnly)
        assertTrue(store.state.value.leftTriggerEffect)
        assertFalse(store.state.value.rightTriggerEffect)
        val bothOff = ByteArray(22)
        r.dispatchTriggerEffects(sessionHandle = 7, controllerIndex = 0, blocks = bothOff)
        assertFalse(store.state.value.leftTriggerEffect)
        assertFalse(store.state.value.rightTriggerEffect)
        verify(exactly = 0) { native.sendUsbTriggerEffects(any(), any()) }
    }

    @Test
    fun `the mic-mute lamp paints the virtual pad's lamp and only the lamp`() {
        val r = router(managerWith(handle = 7, slotId = VIRTUAL_SLOT_ID))
        r.dispatchMicLed(sessionHandle = 7, controllerIndex = 0, state = MIC_LED_ON)
        // The lamp is the host's alone; what the user muted lives in MicMuteStore and paints
        // the pill's face directly, so nothing a host sends can reach it through here.
        assertEquals(MIC_LED_ON, store.state.value.micLedState)

        r.dispatchMicLed(sessionHandle = 7, controllerIndex = 0, state = MIC_LED_PULSE)
        assertEquals(MIC_LED_PULSE, store.state.value.micLedState)
    }

    @Test
    fun `a Direct-claimed pad's lamp reaches its own output report`() {
        val r = router(managerWith(handle = 7, slotId = "-1000"))
        for (state in listOf(MIC_LED_OFF, MIC_LED_ON, MIC_LED_PULSE)) {
            r.dispatchMicLed(sessionHandle = 7, controllerIndex = 0, state = state)
            verify(exactly = 1) { native.sendUsbMicMuteLed(-1000, state) }
        }
        // And never onto the phone's skin: that pad is not the phone.
        assertEquals(MIC_LED_OFF, store.state.value.micLedState)
    }

    @Test
    fun `the lamp routes per target exactly like the lightbar`() {
        // Direct pads write, the phone paints, framework pads drop. The three arms are the same
        // three the lightbar resolves to, which is the point: one resolve, one target set.
        router(managerWith(handle = 7, slotId = "-1000")).dispatchMicLed(7, 0, MIC_LED_ON)
        verify(exactly = 1) { native.sendUsbMicMuteLed(-1000, MIC_LED_ON) }

        router(managerWith(handle = 7, slotId = VIRTUAL_SLOT_ID)).dispatchMicLed(7, 0, MIC_LED_PULSE)
        assertEquals(MIC_LED_PULSE, store.state.value.micLedState)

        router(managerWith(handle = 7, slotId = "9")).dispatchMicLed(7, 0, MIC_LED_OFF)
        verify(exactly = 0) { native.sendUsbMicMuteLed(9, any()) }

        // An unknown session or controller index resolves to nothing at all.
        val r = router(managerWith(handle = 7, slotId = "-1000"))
        r.dispatchMicLed(sessionHandle = 8, controllerIndex = 0, state = MIC_LED_ON)
        r.dispatchMicLed(sessionHandle = 7, controllerIndex = 3, state = MIC_LED_ON)
        verify(exactly = 1) { native.sendUsbMicMuteLed(any(), any()) }
    }

    @Test
    fun `a framework pad's lightbar reaches the framework light gateway, not the USB writer or the skin`() {
        router(managerWith(handle = 7, slotId = "9"))
            .dispatchLightbar(7, 0, 1, 2, 3)
        verify(exactly = 1) { frameworkLights.setColor(9, LightSource(CONNECTION_ID, 0), 1, 2, 3) }
        verify(exactly = 0) { native.sendUsbLightbar(any(), any(), any(), any()) }
        assertEquals(null, store.state.value.lightbarColor)
    }

    @Test
    fun `a framework pad still drops the surfaces Android cannot reach`() {
        // Lightbar is the one framework surface with an Android API; trigger effects, player LEDs
        // and the mic lamp have none, so they stay dropped for a framework target.
        val r = router(managerWith(handle = 7, slotId = "9"))
        r.dispatchTriggerEffects(7, 0, ByteArray(22) { it.toByte() })
        r.dispatchPlayerLeds(7, 0, 0x1F)
        r.dispatchMicLed(7, 0, 1)
        verify(exactly = 0) { native.sendUsbTriggerEffects(any(), any()) }
        verify(exactly = 0) { native.sendUsbPlayerLeds(any(), any()) }
        verify(exactly = 0) { native.sendUsbMicMuteLed(any(), any()) }
        verify(exactly = 0) { frameworkLights.setColor(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the slot-addressed lightbar reaches the framework gateway for a framework pad`() {
        // The Moonlight path and the diagnostics bench come in slot-addressed; a framework slot id
        // must land on the gateway the same way the session-addressed satellite path does.
        router().dispatchLightbarToSlot("9", MOONLIGHT_PAD, 5, 6, 7)
        verify(exactly = 1) { frameworkLights.setColor(9, MOONLIGHT_PAD, 5, 6, 7) }
        verify(exactly = 0) { native.sendUsbLightbar(any(), any(), any(), any()) }
    }

    @Test
    fun `an unknown session or controller index resolves to nothing`() {
        val r = router(managerWith(handle = 7, slotId = "-1000"))
        r.dispatchLightbar(sessionHandle = 8, controllerIndex = 0, r = 1, g = 2, b = 3)
        r.dispatchLightbar(sessionHandle = 7, controllerIndex = 3, r = 1, g = 2, b = 3)
        verify(exactly = 0) { native.sendUsbLightbar(any(), any(), any(), any()) }
    }

    @Test
    fun `moonlight slot-addressed dispatch actuates Direct pads and the virtual sinks`() {
        val r = router()
        r.dispatchTriggerRumbleToSlot("-1000", 100, 200)
        r.dispatchLightbarToSlot("-1000", MOONLIGHT_PAD, 5, 6, 7)
        verify(exactly = 1) { rumble.driveDirectTriggers(-1000, 100, 200, any()) }
        verify(exactly = 1) { native.sendUsbLightbar(-1000, 5, 6, 7) }

        // Virtual: trigger rumble folds through the rumble path (toggle + stop
        // rules apply there); the lightbar paints the skin.
        r.dispatchTriggerRumbleToSlot(VIRTUAL_SLOT_ID, 300, 400)
        verify(exactly = 1) { rumble.dispatchToSlot(VIRTUAL_SLOT_ID, 300, 400, any()) }
        r.dispatchLightbarToSlot(VIRTUAL_SLOT_ID, MOONLIGHT_PAD, 9, 8, 7)
        assertEquals(0xFF090807.toInt(), store.state.value.lightbarColor)

        // Framework: nothing reachable.
        r.dispatchTriggerRumbleToSlot("9", 1, 2)
        verify(exactly = 0) { rumble.driveDirectTriggers(9, any(), any(), any()) }
        verify(exactly = 0) { rumble.dispatchToSlot("9", any(), any(), any()) }
    }

    @Test
    fun `a Direct pad's trigger rumble goes through the rumble router, which stops it when its host goes quiet`() {
        router().dispatchTriggerRumbleToSlot("-1000", 100, 200)

        verify(exactly = 1) { rumble.driveDirectTriggers(-1000, 100, 200, any()) }
        verify(exactly = 0) { native.sendUsbTriggerRumble(any(), any(), any()) }
    }

    // ---- trigger rumble is rumble, so the slot's rumble switch covers it ----

    @Test
    fun `a host's trigger rumble to a Direct pad whose rumble is off lands as a stop`() {
        rumbleOff += "-1000"

        router().dispatchTriggerRumbleToSlot("-1000", 100, 200)

        verify(exactly = 0) { rumble.driveDirectTriggers(-1000, 100, 200, any()) }
        verify(exactly = 1) { rumble.driveDirectTriggers(-1000, 0, 0, any()) }
    }

    @Test
    fun `a host's trigger rumble to the virtual pad whose rumble is off asks the rumble path for a stop`() {
        rumbleOff += VIRTUAL_SLOT_ID

        router().dispatchTriggerRumbleToSlot(VIRTUAL_SLOT_ID, 300, 400)

        verify(exactly = 0) { rumble.dispatchToSlot(VIRTUAL_SLOT_ID, 300, 400, any()) }
        verify(exactly = 1) { rumble.dispatchToSlot(VIRTUAL_SLOT_ID, 0, 0, any()) }
    }

    @Test
    fun `a host's trigger rumble to a slot whose rumble is off is still noted as host activity`() {
        rumbleOff += "-1000"

        router().dispatchTriggerRumbleToSlot("-1000", 100, 200)

        assertEquals(FeedbackKind.TRIGGER_RUMBLE, lastKindNoted("-1000"))
    }

    @Test
    fun `the bench's trigger rumble ignores the rumble switch, as the motors' test buzz does`() {
        rumbleOff += "-1000"

        router().testTriggerRumble("-1000", 100, 200)

        verify(exactly = 1) { rumble.driveDirectTriggers(-1000, 100, 200, any()) }
    }

    // ---- the inspector's light bar bench ----

    @Test
    fun `the bench paints a framework pad's light bar without it counting as the host's`() {
        router().testLightbar("9", 1, 2, 3)

        verify(exactly = 1) { frameworkLights.paint(9, 1, 2, 3) }
        verify(exactly = 0) { frameworkLights.setColor(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the bench drives a Direct pad's light bar`() {
        router().testLightbar("-1000", 1, 2, 3)

        verify(exactly = 1) { native.sendUsbLightbar(-1000, 1, 2, 3) }
    }

    @Test
    fun `ending the bench shows a framework pad's host color again`() {
        router().endLightbarTest("9")

        verify(exactly = 1) { frameworkLights.showHostColor(9) }
        verify(exactly = 0) { frameworkLights.setColor(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ending the bench turns a Direct pad's light bar off when the game never sent it one`() {
        router().endLightbarTest(DIRECT_SLOT)

        assertEquals(listOf(LightbarWrite(DIRECT_PAD, 0, 0, 0)), lightbarWrites)
    }

    @Test
    fun `ending the bench gives a Direct pad's light bar back the game's last color`() {
        val r = router(managerWith(handle = 7, slotId = DIRECT_SLOT))
        r.dispatchLightbar(7, 0, 1, 2, 3)
        r.testLightbar(DIRECT_SLOT, 0xFF, 0, 0)

        r.endLightbarTest(DIRECT_SLOT)

        assertEquals(LightbarWrite(DIRECT_PAD, 1, 2, 3), lightbarWrites.last())
    }

    @Test
    fun `a color the game sends while the bench holds a Direct pad's bar is kept, not written`() {
        val r = router(managerWith(handle = 7, slotId = DIRECT_SLOT))
        r.testLightbar(DIRECT_SLOT, 0xFF, 0, 0)

        r.dispatchLightbar(7, 0, 4, 5, 6)

        assertEquals(listOf(LightbarWrite(DIRECT_PAD, 0xFF, 0, 0)), lightbarWrites)
    }

    @Test
    fun `the color the game sent during the test is the one given back`() {
        val r = router(managerWith(handle = 7, slotId = DIRECT_SLOT))
        r.dispatchLightbar(7, 0, 1, 2, 3)
        r.testLightbar(DIRECT_SLOT, 0xFF, 0, 0)
        r.dispatchLightbar(7, 0, 4, 5, 6)

        r.endLightbarTest(DIRECT_SLOT)

        assertEquals(LightbarWrite(DIRECT_PAD, 4, 5, 6), lightbarWrites.last())
    }

    @Test
    fun `a Moonlight host's color is given back like a satellite's`() {
        val r = router()
        r.dispatchLightbarToSlot(DIRECT_SLOT, MOONLIGHT_PAD, 1, 2, 3)
        r.testLightbar(DIRECT_SLOT, 0xFF, 0, 0)

        r.endLightbarTest(DIRECT_SLOT)

        assertEquals(LightbarWrite(DIRECT_PAD, 1, 2, 3), lightbarWrites.last())
    }

    @Test
    fun `once the bench gives the bar back the game's colors reach the pad again`() {
        val r = router(managerWith(handle = 7, slotId = DIRECT_SLOT))
        r.testLightbar(DIRECT_SLOT, 0xFF, 0, 0)
        r.endLightbarTest(DIRECT_SLOT)

        r.dispatchLightbar(7, 0, 7, 8, 9)

        assertEquals(LightbarWrite(DIRECT_PAD, 7, 8, 9), lightbarWrites.last())
    }

    @Test
    fun `the bench's light bar test leaves the pad's other surfaces to the game`() {
        val r = router(managerWith(handle = 7, slotId = DIRECT_SLOT))
        r.testLightbar(DIRECT_SLOT, 0xFF, 0, 0)

        r.dispatchPlayerLeds(7, 0, 0x1F)

        verify(exactly = 1) { native.sendUsbPlayerLeds(DIRECT_PAD, 0x1F) }
    }

    @Test
    fun `the bench holding one Direct pad's bar leaves another pad's bar to the game`() {
        val r = router(managerWith(handle = 7, slotId = DIRECT_SLOT))
        r.testLightbar(OTHER_DIRECT_SLOT, 0xFF, 0, 0)

        r.dispatchLightbar(7, 0, 4, 5, 6)

        assertEquals(LightbarWrite(DIRECT_PAD, 4, 5, 6), lightbarWrites.last())
    }

    @Test
    fun `a framework pad's color still reaches the gateway while the bench paints it, which keeps it for the test's end`() {
        val r = router(managerWith(handle = 7, slotId = "9"))
        r.testLightbar("9", 0xFF, 0, 0)

        r.dispatchLightbar(7, 0, 4, 5, 6)

        verify(exactly = 1) { frameworkLights.setColor(9, LightSource(CONNECTION_ID, 0), 4, 5, 6) }
    }

    // ---- the bench's player LEDs, trigger effects and mic lamp on a Direct pad ----

    @Test
    fun `the bench reaches a Direct-claimed pad's LEDs, triggers and lamp without a session`() {
        val r = router()
        val blocks = ByteArray(22) { it.toByte() }

        r.testPlayerLeds(DIRECT_SLOT, 0x03)
        r.testMicLed(DIRECT_SLOT, MIC_LED_PULSE)
        r.testTriggerEffects(DIRECT_SLOT, blocks)

        assertEquals(listOf(0x03), ledWrites)
        assertEquals(listOf(MIC_LED_PULSE), lampWrites)
        assertEquals(listOf(blocks), triggerWrites)
    }

    @Test
    fun `ending the LED test gives a Direct pad back the game's mask`() {
        val r = router(managerWith(handle = 7, slotId = DIRECT_SLOT))
        r.dispatchPlayerLeds(7, 0, 0x04)
        r.testPlayerLeds(DIRECT_SLOT, 0x01)

        r.endPlayerLedsTest(DIRECT_SLOT)

        assertEquals(0x04, ledWrites.last())
    }

    @Test
    fun `ending the LED test turns a Direct pad's LEDs off when the game never set them`() {
        val r = router()
        r.testPlayerLeds(DIRECT_SLOT, 0x01)

        r.endPlayerLedsTest(DIRECT_SLOT)

        assertEquals(listOf(0x01, 0x00), ledWrites)
    }

    @Test
    fun `a mask the game sends during the LED test is kept, not written, and given back`() {
        val r = router(managerWith(handle = 7, slotId = DIRECT_SLOT))
        r.testPlayerLeds(DIRECT_SLOT, 0x01)

        r.dispatchPlayerLeds(7, 0, 0x08)
        assertEquals(listOf(0x01), ledWrites)

        r.endPlayerLedsTest(DIRECT_SLOT)
        assertEquals(0x08, ledWrites.last())
    }

    @Test
    fun `ending the trigger test gives a Direct pad back the game's effect`() {
        val r = router(managerWith(handle = 7, slotId = DIRECT_SLOT))
        val gameEffect = ByteArray(22).also { it[0] = 0x21 }
        r.dispatchTriggerEffects(7, 0, gameEffect)
        r.testTriggerEffects(DIRECT_SLOT, ByteArray(22) { 0x01 })

        r.endTriggerEffectsTest(DIRECT_SLOT)

        assertTrue(gameEffect.contentEquals(triggerWrites.last()))
    }

    @Test
    fun `ending the trigger test releases both triggers when the game never set an effect`() {
        val r = router()
        r.testTriggerEffects(DIRECT_SLOT, ByteArray(22) { 0x01 })

        r.endTriggerEffectsTest(DIRECT_SLOT)

        assertTrue(ByteArray(22).contentEquals(triggerWrites.last()))
    }

    @Test
    fun `an effect the game sends during the trigger test is kept, not written, and given back`() {
        val r = router(managerWith(handle = 7, slotId = DIRECT_SLOT))
        val benchEffect = ByteArray(22) { 0x01 }
        val gameEffect = ByteArray(22).also { it[11] = 0x26 }
        r.testTriggerEffects(DIRECT_SLOT, benchEffect)

        r.dispatchTriggerEffects(7, 0, gameEffect)
        assertEquals(listOf(benchEffect), triggerWrites)

        r.endTriggerEffectsTest(DIRECT_SLOT)
        assertTrue(gameEffect.contentEquals(triggerWrites.last()))
    }

    @Test
    fun `ending the lamp test gives a Direct pad back the game's lamp`() {
        val r = router(managerWith(handle = 7, slotId = DIRECT_SLOT))
        r.dispatchMicLed(7, 0, MIC_LED_PULSE)
        r.testMicLed(DIRECT_SLOT, MIC_LED_ON)

        r.endMicLedTest(DIRECT_SLOT)

        assertEquals(MIC_LED_PULSE, lampWrites.last())
    }

    @Test
    fun `ending the lamp test turns a Direct pad's lamp off when the game never set it`() {
        val r = router()
        r.testMicLed(DIRECT_SLOT, MIC_LED_ON)

        r.endMicLedTest(DIRECT_SLOT)

        assertEquals(listOf(MIC_LED_ON, MIC_LED_OFF), lampWrites)
    }

    @Test
    fun `a lamp state the game sends during the lamp test is kept, not written, and given back`() {
        val r = router(managerWith(handle = 7, slotId = DIRECT_SLOT))
        r.testMicLed(DIRECT_SLOT, MIC_LED_PULSE)

        r.dispatchMicLed(7, 0, MIC_LED_ON)
        assertEquals(listOf(MIC_LED_PULSE), lampWrites)

        r.endMicLedTest(DIRECT_SLOT)
        assertEquals(MIC_LED_ON, lampWrites.last())
    }

    @Test
    fun `the bench leaves the on-screen pad's skin and a framework pad's unreachable surfaces alone`() {
        val r = router()
        for (slot in listOf(VIRTUAL_SLOT_ID, "9")) {
            r.testPlayerLeds(slot, 0x1F)
            r.testTriggerEffects(slot, ByteArray(22) { 0x01 })
            r.testMicLed(slot, MIC_LED_ON)
            r.endPlayerLedsTest(slot)
            r.endTriggerEffectsTest(slot)
            r.endMicLedTest(slot)
        }

        assertEquals(VirtualPadFeedbackStore().state.value, store.state.value)
        verify(exactly = 0) { native.sendUsbPlayerLeds(any(), any()) }
        verify(exactly = 0) { native.sendUsbTriggerEffects(any(), any()) }
        verify(exactly = 0) { native.sendUsbMicMuteLed(any(), any()) }
    }

    // ---- host activity, the diagnostics "last feedback" line ----

    @Test
    fun `session-addressed feedback records host activity against the resolved slot`() {
        router(managerWith(handle = 7, slotId = VIRTUAL_SLOT_ID)).dispatchLightbar(7, 0, 1, 2, 3)
        router(managerWith(handle = 7, slotId = "9")).dispatchLightbar(7, 0, 1, 2, 3)
        router(managerWith(handle = 7, slotId = "-1000")).dispatchLightbar(7, 0, 1, 2, 3)

        assertEquals(FeedbackKind.LIGHTBAR, lastKindNoted(VIRTUAL_SLOT_ID))
        assertEquals(FeedbackKind.LIGHTBAR, lastKindNoted("9"))
        assertEquals(FeedbackKind.LIGHTBAR, lastKindNoted("-1000"))
    }

    @Test
    fun `session-addressed feedback that resolves to nothing records no activity`() {
        router(managerWith(handle = 7, slotId = "-1000")).dispatchLightbar(sessionHandle = 8, controllerIndex = 0, r = 1, g = 2, b = 3)
        assertTrue(activity.snapshot().isEmpty())
    }

    @Test
    fun `each feedback kind is noted under its own kind`() {
        val r = router(managerWith(handle = 7, slotId = "-1000"))
        r.dispatchTriggerEffects(7, 0, ByteArray(22))
        assertEquals(FeedbackKind.TRIGGER_EFFECTS, lastKindNoted("-1000"))
        r.dispatchPlayerLeds(7, 0, 0x1F)
        assertEquals(FeedbackKind.PLAYER_LEDS, lastKindNoted("-1000"))
        r.dispatchMicLed(7, 0, MIC_LED_ON)
        assertEquals(FeedbackKind.MIC_LED, lastKindNoted("-1000"))
    }

    @Test
    fun `trigger rumble to a slot notes host activity but the bench entry does not`() {
        val r = router()
        r.dispatchTriggerRumbleToSlot("-1000", 100, 200)
        assertEquals(FeedbackKind.TRIGGER_RUMBLE, lastKindNoted("-1000"))
        assertEquals(1L, activity.snapshot()["-1000"]?.count)

        r.testTriggerRumble("-1000", 100, 200)

        assertEquals(1L, activity.snapshot()["-1000"]?.count)
        verify(exactly = 2) { rumble.driveDirectTriggers(-1000, 100, 200, any()) }
    }

    @Test
    fun `a Moonlight light bar and the bench's writes are not counted as host activity`() {
        val r = router()
        r.dispatchLightbarToSlot(DIRECT_SLOT, MOONLIGHT_PAD, 1, 2, 3)
        r.testLightbar(DIRECT_SLOT, 1, 2, 3)
        r.testTriggerEffects(DIRECT_SLOT, ByteArray(22))
        r.testPlayerLeds(DIRECT_SLOT, 0x1F)
        r.testMicLed(DIRECT_SLOT, MIC_LED_ON)
        r.endLightbarTest(DIRECT_SLOT)
        r.endTriggerEffectsTest(DIRECT_SLOT)
        r.endPlayerLedsTest(DIRECT_SLOT)
        r.endMicLedTest(DIRECT_SLOT)
        assertTrue(activity.snapshot().isEmpty())
    }

    @Test
    fun `a truncated block array treats the missing trigger as inactive`() {
        val r = router(managerWith(handle = 7, slotId = VIRTUAL_SLOT_ID))
        val leftOnlyAndShort = ByteArray(5).also { it[0] = 0x21 }
        r.dispatchTriggerEffects(7, 0, leftOnlyAndShort)
        assertTrue(store.state.value.leftTriggerEffect)
        assertFalse(store.state.value.rightTriggerEffect)

        r.dispatchTriggerEffects(7, 0, ByteArray(0))
        assertFalse(store.state.value.leftTriggerEffect)
        assertFalse(store.state.value.rightTriggerEffect)
    }
}
