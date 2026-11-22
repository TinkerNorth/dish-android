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
    internal val heldBit: Int,
) {
    LIGHTBAR(1 shl 0),
    PLAYER_LEDS(1 shl 1),
    TRIGGER_EFFECTS(1 shl 2),
    MIC_LED(1 shl 3),
}

// One trigger's DualSense effect block, and the MSG_TRIGGER_EFFECTS payload: the left block, then the right.
internal const val TRIGGER_EFFECT_BLOCK_BYTES = 11
internal const val TRIGGER_EFFECTS_BYTES = 2 * TRIGGER_EFFECT_BLOCK_BYTES

private const val NOTHING_HELD = 0
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
 * nothing writes again.
 */
class DirectSurfaces {
    private val lightbar = AtomicLong(LIGHTBAR_OFF)
    private val playerLeds = AtomicLong(PLAYER_LEDS_OFF)
    private val micLed = AtomicLong(MIC_LED_OFF.toLong())
    private val triggerEffects = AtomicReference(ByteArray(TRIGGER_EFFECTS_BYTES))
    private val held = AtomicInteger(NOTHING_HELD)

    /** The game's color, packed as feedbackRgb packs it; true when no test holds the bar, so it goes to the pad now. */
    fun gameLightbar(rgb: Long): Boolean {
        lightbar.set(rgb)
        return isFree(StateSurface.LIGHTBAR)
    }

    fun gamePlayerLeds(ledMask: Int): Boolean {
        playerLeds.set(ledMask.toLong())
        return isFree(StateSurface.PLAYER_LEDS)
    }

    fun gameTriggerEffects(blocks: ByteArray): Boolean {
        triggerEffects.set(blocks)
        return isFree(StateSurface.TRIGGER_EFFECTS)
    }

    fun gameMicLed(state: Int): Boolean {
        micLed.set(state.toLong())
        return isFree(StateSurface.MIC_LED)
    }

    /** A bench test takes [surface] until it gives it back. */
    fun hold(surface: StateSurface) {
        held.accumulateAndGet(surface.heldBit, Int::or)
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
