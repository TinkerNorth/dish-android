// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.architecture.testing.fewestAllocatedBytesDuring
import com.tinkernorth.dish.architecture.testing.freshAppInstanceOf
import com.tinkernorth.dish.source.store.MIC_LED_OFF
import com.tinkernorth.dish.source.store.MIC_LED_ON
import com.tinkernorth.dish.source.store.MIC_LED_PULSE
import com.tinkernorth.dish.source.store.feedbackRgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.LongSupplier

private const val PROBE_SLOT = "-1000"
private const val MESSAGES_PER_CYCLE = 1000
private const val GAME_LEDS = 0x04
private const val A_WRITE_LOOP_THAT_NEVER_ENDS_MS = 10_000L
private val GAME_RGB = feedbackRgb(1, 2, 3)

// A session's receive thread landing the game's light bar, LEDs, trigger effects and lamp on a Direct
// pad the bench is not testing. Reached through Runnable (one cycle) and LongSupplier (how many went to
// the pad) because the allocation test makes it in a class loader of its own (freshAppInstanceOf).
internal class GameFeedbackCycles :
    Runnable,
    LongSupplier {
    private val surfaces = DirectSurfacesBySlot()
    private val blocks = ByteArray(TRIGGER_EFFECTS_BYTES)
    private var toThePad = 0L

    init {
        land()
    }

    override fun run() {
        repeat(MESSAGES_PER_CYCLE) { land() }
    }

    private fun land() {
        val pad = surfaces.of(PROBE_SLOT)
        if (pad.gameLightbar(GAME_RGB)) writeToThePad(pad, StateSurface.LIGHTBAR)
        if (pad.gamePlayerLeds(GAME_LEDS)) writeToThePad(pad, StateSurface.PLAYER_LEDS)
        if (pad.gameTriggerEffects(blocks)) writeToThePad(pad, StateSurface.TRIGGER_EFFECTS)
        if (pad.gameMicLed(MIC_LED_ON)) writeToThePad(pad, StateSurface.MIC_LED)
    }

    private fun writeToThePad(
        pad: DirectSurfaces,
        surface: StateSurface,
    ) {
        toThePad++
        pad.landed(surface)
    }

    override fun getAsLong(): Long = toThePad
}

class DirectSurfacesTest {
    private val pad = DirectSurfaces()

    // What each give-back wrote to the pad, oldest first.
    private val lightbarWrites = mutableListOf<Long>()
    private val triggerWrites = mutableListOf<ByteArray>()
    private val newestEffect = ByteArray(TRIGGER_EFFECTS_BYTES).also { it[0] = 0x21 }
    private val testEffect = ByteArray(TRIGGER_EFFECTS_BYTES).also { it[0] = 0x26 }

    // What the pad shows, and how many times a test wrote it.
    private var lampShown = MIC_LED_OFF
    private var effectShown = ByteArray(TRIGGER_EFFECTS_BYTES)
    private var testWrites = 0

    @Test
    fun `the game's values go to the pad while no test holds their surface`() {
        assertTrue(pad.gameLightbar(GAME_RGB))
        assertTrue(pad.gamePlayerLeds(GAME_LEDS))
        assertTrue(pad.gameTriggerEffects(ByteArray(TRIGGER_EFFECTS_BYTES)))
        assertTrue(pad.gameMicLed(MIC_LED_PULSE))
    }

    @Test
    fun `a held surface keeps the game's value for the test's end instead`() {
        StateSurface.entries.forEach(pad::hold)

        assertFalse(pad.gameLightbar(GAME_RGB))
        assertFalse(pad.gamePlayerLeds(GAME_LEDS))
        assertFalse(pad.gameTriggerEffects(ByteArray(TRIGGER_EFFECTS_BYTES)))
        assertFalse(pad.gameMicLed(MIC_LED_PULSE))
    }

    @Test
    fun `holding one surface leaves the others to the game`() {
        pad.hold(StateSurface.LIGHTBAR)

        assertFalse(pad.gameLightbar(GAME_RGB))
        assertTrue(pad.gamePlayerLeds(GAME_LEDS))
        assertTrue(pad.gameTriggerEffects(ByteArray(TRIGGER_EFFECTS_BYTES)))
        assertTrue(pad.gameMicLed(MIC_LED_PULSE))
    }

    @Test
    fun `a surface given back is the game's again`() {
        val leds = mutableListOf<Int>()
        val lamps = mutableListOf<Int>()
        StateSurface.entries.forEach(pad::hold)

        pad.giveBackLightbar(lightbarWrites::add)
        pad.giveBackPlayerLeds(leds::add)
        pad.giveBackTriggerEffects(triggerWrites::add)
        pad.giveBackMicLed(lamps::add)

        assertTrue(pad.gameLightbar(GAME_RGB))
        assertTrue(pad.gamePlayerLeds(GAME_LEDS))
        assertTrue(pad.gameTriggerEffects(ByteArray(TRIGGER_EFFECTS_BYTES)))
        assertTrue(pad.gameMicLed(MIC_LED_PULSE))
    }

    @Test
    fun `a give-back writes the last value the game sent, the one it sent during the test included`() {
        pad.gameLightbar(feedbackRgb(1, 2, 3))
        pad.hold(StateSurface.LIGHTBAR)
        pad.gameLightbar(feedbackRgb(4, 5, 6))

        pad.giveBackLightbar(lightbarWrites::add)

        assertEquals(listOf(feedbackRgb(4, 5, 6)), lightbarWrites)
    }

    @Test
    fun `each surface the game never set is given back off`() {
        val leds = mutableListOf<Int>()
        val lamps = mutableListOf<Int>()

        pad.giveBackLightbar(lightbarWrites::add)
        pad.giveBackPlayerLeds(leds::add)
        pad.giveBackTriggerEffects(triggerWrites::add)
        pad.giveBackMicLed(lamps::add)

        assertEquals(listOf(feedbackRgb(0, 0, 0)), lightbarWrites)
        assertEquals(listOf(0), leds)
        assertTrue(ByteArray(TRIGGER_EFFECTS_BYTES).contentEquals(triggerWrites.single()))
        assertEquals(listOf(MIC_LED_OFF), lamps)
    }

    @Test
    fun `the LED mask and the lamp state are given back as the game sent them`() {
        val leds = mutableListOf<Int>()
        val lamps = mutableListOf<Int>()
        pad.gamePlayerLeds(0x1F)
        pad.gameMicLed(MIC_LED_PULSE)

        pad.giveBackPlayerLeds(leds::add)
        pad.giveBackMicLed(lamps::add)

        assertEquals(listOf(0x1F), leds)
        assertEquals(listOf(MIC_LED_PULSE), lamps)
    }

    @Test
    fun `the trigger effect given back is the game's own bytes`() {
        val gameEffect = ByteArray(TRIGGER_EFFECTS_BYTES) { it.toByte() }
        pad.gameTriggerEffects(gameEffect)

        pad.giveBackTriggerEffects(triggerWrites::add)

        assertSame(gameEffect, triggerWrites.single())
    }

    // The game's newest value lands after the give-back read the one before it, and the game's own
    // write of it reached the pad first: only a second write leaves the pad on the newest value.

    @Test
    fun `a color the game sends as the bar is given back is written after the one before it`() {
        pad.gameLightbar(feedbackRgb(1, 2, 3))
        pad.hold(StateSurface.LIGHTBAR)

        pad.giveBackLightbar(::writeThenLetTheGameSendANewColor)

        assertEquals(listOf(feedbackRgb(1, 2, 3), feedbackRgb(4, 5, 6)), lightbarWrites)
    }

    @Test
    fun `an effect the game sends as the triggers are given back is written after the one before it`() {
        val firstEffect = ByteArray(TRIGGER_EFFECTS_BYTES)
        pad.gameTriggerEffects(firstEffect)
        pad.hold(StateSurface.TRIGGER_EFFECTS)

        pad.giveBackTriggerEffects(::writeThenLetTheGameSendANewEffect)

        assertEquals(listOf(firstEffect, newestEffect), triggerWrites)
    }

    // The game's value passed the hold check just before the test took the surface, and its write
    // reaches the pad just after the test's own: the test writes again, so its value is what stands.

    @Test
    fun `a lamp the game was already writing when a test took it is written over while the test runs`() {
        val gamePassedTheCheck = pad.gameMicLed(MIC_LED_PULSE)

        pad.take(StateSurface.MIC_LED, ::writeTheTestsLampThenLandTheGames)

        assertTrue(gamePassedTheCheck)
        assertEquals(MIC_LED_ON, lampShown)
    }

    @Test
    fun `that lamp is the one given back when the test ends`() {
        pad.gameMicLed(MIC_LED_PULSE)
        pad.take(StateSurface.MIC_LED, ::writeTheTestsLampThenLandTheGames)

        pad.giveBackMicLed(::showLamp)

        assertEquals(MIC_LED_PULSE, lampShown)
    }

    @Test
    fun `an effect the game was already writing when a test took the triggers is written over while the test runs`() {
        val gamePassedTheCheck = pad.gameTriggerEffects(newestEffect)

        pad.take(StateSurface.TRIGGER_EFFECTS, ::writeTheTestsEffectThenLandTheGames)

        assertTrue(gamePassedTheCheck)
        assertSame(testEffect, effectShown)
    }

    @Test
    fun `that effect is the one given back when the test ends`() {
        pad.gameTriggerEffects(newestEffect)
        pad.take(StateSurface.TRIGGER_EFFECTS, ::writeTheTestsEffectThenLandTheGames)

        pad.giveBackTriggerEffects(::showEffect)

        assertSame(newestEffect, effectShown)
    }

    @Test
    fun `a test that takes a surface no game write is landing on writes once`() {
        pad.gameMicLed(MIC_LED_PULSE)
        pad.landed(StateSurface.MIC_LED)

        pad.take(StateSurface.MIC_LED, ::writeTheTestsLamp)

        assertEquals(1, testWrites)
    }

    @Test(timeout = A_WRITE_LOOP_THAT_NEVER_ENDS_MS)
    fun `a game write that never lands makes the test write a bounded number of times, then the surface is the test's`() {
        pad.gameMicLed(MIC_LED_PULSE)

        pad.take(StateSurface.MIC_LED, ::writeTheTestsLamp)

        assertEquals(MOST_WRITES_A_TAKE_MAKES, testWrites)
        assertEquals(MIC_LED_ON, lampShown)
    }

    @Test
    fun `a game value the hold kept off the pad does not make the test write again`() {
        pad.hold(StateSurface.MIC_LED)
        val toThePad = pad.gameMicLed(MIC_LED_PULSE)

        pad.take(StateSurface.MIC_LED, ::writeTheTestsLamp)

        assertFalse(toThePad)
        assertEquals(1, testWrites)
    }

    @Test
    fun `a game write landing on another surface does not make the test write again`() {
        pad.gameLightbar(GAME_RGB)

        pad.take(StateSurface.MIC_LED, ::writeTheTestsLamp)

        assertEquals(1, testWrites)
    }

    @Test
    fun `each surface counts its game writes in a place of its own`() {
        val indices = StateSurface.entries.map(StateSurface::index)

        assertEquals(StateSurface.entries.indices.toList(), indices.sorted())
    }

    @Test
    fun `a slot keeps one record, and each slot has its own`() {
        val bySlot = DirectSurfacesBySlot()

        assertSame(bySlot.of(PROBE_SLOT), bySlot.of(PROBE_SLOT))
        assertNotSame(bySlot.of(PROBE_SLOT), bySlot.of("-1001"))
    }

    @Test
    fun `the game's feedback to a Direct pad allocates nothing once its slot has been seen`() {
        val cycles = freshAppInstanceOf(GameFeedbackCycles::class.java)
        val cycle = cycles as Runnable
        repeat(WARMUP_CYCLES) { cycle.run() }

        val allocated = fewestAllocatedBytesDuring(MEASURED_RUNS) { cycle.run() }

        assertTrue("every value went to the pad", (cycles as LongSupplier).asLong > 0)
        assertTrue("$allocated bytes over $MESSAGES_PER_CYCLE messages", allocated < MESSAGES_PER_CYCLE * BYTES_PER_MESSAGE_BOUND)
    }

    private fun writeThenLetTheGameSendANewColor(rgb: Long) {
        lightbarWrites += rgb
        val isTheFirstWrite = lightbarWrites.size == 1
        if (isTheFirstWrite) pad.gameLightbar(feedbackRgb(4, 5, 6))
    }

    private fun writeThenLetTheGameSendANewEffect(blocks: ByteArray) {
        triggerWrites += blocks
        val isTheFirstWrite = triggerWrites.size == 1
        if (isTheFirstWrite) pad.gameTriggerEffects(newestEffect)
    }

    private fun writeTheTestsLamp() {
        testWrites++
        lampShown = MIC_LED_ON
    }

    private fun writeTheTestsLampThenLandTheGames() {
        writeTheTestsLamp()
        val isTheFirstWrite = testWrites == 1
        if (isTheFirstWrite) landTheGamesLamp()
    }

    private fun landTheGamesLamp() {
        lampShown = MIC_LED_PULSE
        pad.landed(StateSurface.MIC_LED)
    }

    private fun showLamp(state: Int) {
        lampShown = state
    }

    private fun writeTheTestsEffectThenLandTheGames() {
        testWrites++
        effectShown = testEffect
        val isTheFirstWrite = testWrites == 1
        if (isTheFirstWrite) landTheGamesEffect()
    }

    private fun landTheGamesEffect() {
        effectShown = newestEffect
        pad.landed(StateSurface.TRIGGER_EFFECTS)
    }

    private fun showEffect(blocks: ByteArray) {
        effectShown = blocks
    }

    private companion object {
        const val WARMUP_CYCLES = 20
        const val MEASURED_RUNS = 3

        // Half the smallest object: a boxed value costs 16 bytes or more every message.
        const val BYTES_PER_MESSAGE_BOUND = 8
    }
}
