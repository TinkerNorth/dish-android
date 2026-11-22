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
        if (pad.gameLightbar(GAME_RGB)) toThePad++
        if (pad.gamePlayerLeds(GAME_LEDS)) toThePad++
        if (pad.gameTriggerEffects(blocks)) toThePad++
        if (pad.gameMicLed(MIC_LED_ON)) toThePad++
    }

    override fun getAsLong(): Long = toThePad
}

class DirectSurfacesTest {
    private val pad = DirectSurfaces()

    // What each give-back wrote to the pad, oldest first.
    private val lightbarWrites = mutableListOf<Long>()
    private val triggerWrites = mutableListOf<ByteArray>()
    private val newestEffect = ByteArray(TRIGGER_EFFECTS_BYTES).also { it[0] = 0x21 }

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

    private companion object {
        const val WARMUP_CYCLES = 20
        const val MEASURED_RUNS = 3

        // Half the smallest object: a boxed value costs 16 bytes or more every message.
        const val BYTES_PER_MESSAGE_BOUND = 8
    }
}
