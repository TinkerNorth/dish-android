// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.source.store.MIC_LED_OFF
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** The surfaces a game sets on a pad and the inspector's bench borrows; each one's state goes back. */
enum class StateSurface(
    internal val index: Int,
) {
    LIGHTBAR(0),
    PLAYER_LEDS(1),
    TRIGGER_EFFECTS(2),
    MIC_LED(3),
    ;

    internal val heldBit = 1 shl index
}

// One trigger's DualSense effect block, and the MSG_TRIGGER_EFFECTS payload: the left block, then the right.
internal const val TRIGGER_EFFECT_BLOCK_BYTES = 11
internal const val TRIGGER_EFFECTS_BYTES = 2 * TRIGGER_EFFECT_BLOCK_BYTES

// A test writes its value this many times at most while a game write that passed the check before
// its hold is still on its way; one that never lands (a send that ended abnormally) then loses.
internal const val MOST_WRITES_A_TAKE_MAKES = 8

private const val NOTHING_HELD = 0
private val SURFACE_COUNT = StateSurface.entries.size
private const val LIGHTBAR_OFF = 0L
private const val PLAYER_LEDS_OFF = 0L

/**
 * What one Direct pad's state surfaces show when no bench test holds them: the game's last value for
 * each, or the surface off until the game sends one. Nothing can ask a claimed pad what it shows, so
 * this is the only record the bench can put back when its test ends; while a test holds a surface,
 * the game's values for it land here instead of on the pad.
 *
 * The session receive threads write it per feedback message with no lock and no allocation: each
 * value is one atomic word, and the trigger blocks are the array their message arrived in, which
 * nothing writes again. Each game value a surface's check lets through to the pad is [landed] once
 * its write is posted, so a test that takes the surface can tell when no such write is still on
 * its way.
 */
class DirectSurfaces {
    private val lightbar = AtomicLong(LIGHTBAR_OFF)
    private val playerLeds = AtomicLong(PLAYER_LEDS_OFF)
    private val micLed = AtomicLong(MIC_LED_OFF.toLong())
    private val triggerEffects = AtomicReference(ByteArray(TRIGGER_EFFECTS_BYTES))
    private val held = AtomicInteger(NOTHING_HELD)
    private val gameLandingsStarted = Array(SURFACE_COUNT) { AtomicInteger() }
    private val gameLandingsDone = Array(SURFACE_COUNT) { AtomicInteger() }

    /** The game's color, packed as feedbackRgb packs it; true when no test holds the bar, so it goes to the pad now. */
    fun gameLightbar(rgb: Long): Boolean {
        lightbar.set(rgb)
        return startLanding(StateSurface.LIGHTBAR)
    }

    fun gamePlayerLeds(ledMask: Int): Boolean {
        playerLeds.set(ledMask.toLong())
        return startLanding(StateSurface.PLAYER_LEDS)
    }

    fun gameTriggerEffects(blocks: ByteArray): Boolean {
        triggerEffects.set(blocks)
        return startLanding(StateSurface.TRIGGER_EFFECTS)
    }

    fun gameMicLed(state: Int): Boolean {
        micLed.set(state.toLong())
        return startLanding(StateSurface.MIC_LED)
    }

    /** A bench test takes [surface] until it gives it back. */
    fun hold(surface: StateSurface) {
        held.accumulateAndGet(surface.heldBit, Int::or)
    }

    /**
     * A bench test takes [surface] and writes its own value, again while a game value that passed the
     * check before the hold may still land after it, so the test's value is the one the pad shows.
     */
    fun take(
        surface: StateSurface,
        write: () -> Unit,
    ) {
        hold(surface)
        var writes = 0
        do {
            val noGameWriteOnItsWay = noGameLandingInFlight(surface)
            write()
            writes++
            val outOfWrites = writes == MOST_WRITES_A_TAKE_MAKES
        } while (!noGameWriteOnItsWay && !outOfWrites)
    }

    /** The game's value that [gameLightbar] and its siblings let through is posted to the pad. */
    fun landed(surface: StateSurface) {
        gameLandingsDone[surface.index].incrementAndGet()
    }

    // A give-back makes the surface the game's again, then writes the game's last value until that
    // value holds still: one the game sent just as the hold lifted, whose own write may have reached
    // the pad first, is then still the one the pad is left showing.

    fun giveBackLightbar(write: (rgb: Long) -> Unit) {
        release(StateSurface.LIGHTBAR)
        writeUntilSettled(lightbar, write)
    }

    fun giveBackPlayerLeds(write: (ledMask: Int) -> Unit) {
        release(StateSurface.PLAYER_LEDS)
        writeUntilSettled(playerLeds) { mask -> write(mask.toInt()) }
    }

    fun giveBackTriggerEffects(write: (blocks: ByteArray) -> Unit) {
        release(StateSurface.TRIGGER_EFFECTS)
        writeUntilSettled(triggerEffects, write)
    }

    fun giveBackMicLed(write: (state: Int) -> Unit) {
        release(StateSurface.MIC_LED)
        writeUntilSettled(micLed) { state -> write(state.toInt()) }
    }

    // The landing counts itself before it reads the hold, so one that starts after a test's hold sees it.
    private fun startLanding(surface: StateSurface): Boolean {
        gameLandingsStarted[surface.index].incrementAndGet()
        val toThePad = isFree(surface)
        if (!toThePad) landed(surface)
        return toThePad
    }

    // Done is read before started: equal, nothing was in flight at the first read nor began before the second.
    private fun noGameLandingInFlight(surface: StateSurface): Boolean {
        val done = gameLandingsDone[surface.index].get()
        val started = gameLandingsStarted[surface.index].get()
        return done == started
    }

    private fun isFree(surface: StateSurface): Boolean = (held.get() and surface.heldBit) == NOTHING_HELD

    private fun release(surface: StateSurface) {
        held.accumulateAndGet(surface.heldBit.inv(), Int::and)
    }
}

/** Every Direct pad's surfaces by slot id: a lookup takes no lock, and allocates only for a slot's first. */
class DirectSurfacesBySlot {
    private val bySlot = ConcurrentHashMap<String, DirectSurfaces>()

    fun of(slotId: String): DirectSurfaces = bySlot.getOrPut(slotId, ::DirectSurfaces)
}

private fun writeUntilSettled(
    value: AtomicLong,
    write: (Long) -> Unit,
) {
    do {
        val shown = value.get()
        write(shown)
    } while (value.get() != shown)
}

private fun writeUntilSettled(
    value: AtomicReference<ByteArray>,
    write: (ByteArray) -> Unit,
) {
    do {
        val shown = value.get()
        write(shown)
    } while (value.get() !== shown)
}
