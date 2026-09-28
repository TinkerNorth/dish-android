// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import com.tinkernorth.dish.core.net.moonlight.CAP_ACCELEROMETER
import com.tinkernorth.dish.core.net.moonlight.CAP_ANALOG_TRIGGERS
import com.tinkernorth.dish.core.net.moonlight.CAP_BATTERY
import com.tinkernorth.dish.core.net.moonlight.CAP_GYRO
import com.tinkernorth.dish.core.net.moonlight.CAP_RGB_LED
import com.tinkernorth.dish.core.net.moonlight.CAP_RUMBLE
import com.tinkernorth.dish.core.net.moonlight.CAP_TOUCHPAD
import com.tinkernorth.dish.core.net.moonlight.CAP_TRIGGER_RUMBLE
import com.tinkernorth.dish.core.net.moonlight.NINTENDO
import com.tinkernorth.dish.core.net.moonlight.PLAYSTATION
import com.tinkernorth.dish.core.net.moonlight.XBOX
import org.junit.Assert.assertEquals
import org.junit.Test

// The one rule the reference count reduces to.
class MoonlightConvergeTest {
    @Test
    fun `the first pad on an idle host opens the stream`() {
        assertEquals(MoonlightConverge.OPEN, moonlightConverge(MoonlightSessionState.Idle, wantedPads = 1))
    }

    @Test
    fun `a dropped or host-ended session opens a new one rather than joining a dead one`() {
        assertEquals(MoonlightConverge.OPEN, moonlightConverge(MoonlightSessionState.Dropped, wantedPads = 1))
        assertEquals(MoonlightConverge.OPEN, moonlightConverge(MoonlightSessionState.Ended, wantedPads = 1))
    }

    @Test
    fun `later pads on a live host only announce themselves`() {
        (1..4).forEach { wanted ->
            assertEquals(MoonlightConverge.ANNOUNCE, moonlightConverge(MoonlightSessionState.Live, wanted))
        }
    }

    @Test
    fun `a launch already in flight is left alone rather than started twice`() {
        assertEquals(MoonlightConverge.WAIT, moonlightConverge(MoonlightSessionState.Launching, wantedPads = 1))
        assertEquals(MoonlightConverge.WAIT, moonlightConverge(MoonlightSessionState.Launching, wantedPads = 4))
    }

    @Test
    fun `losing the last pad on a live host closes the app it started`() {
        assertEquals(MoonlightConverge.CANCEL, moonlightConverge(MoonlightSessionState.Live, wantedPads = 0))
    }

    @Test
    fun `losing the last pad with no session up has nothing to close`() {
        listOf(
            MoonlightSessionState.Idle,
            MoonlightSessionState.Launching,
            MoonlightSessionState.Dropped,
            MoonlightSessionState.Ended,
        ).forEach { state ->
            assertEquals(state.name, MoonlightConverge.RELEASE, moonlightConverge(state, wantedPads = 0))
        }
    }

    private fun request(type: Int) = MoonlightPadRequest(slotId = "a", emulatedType = type, capabilities = CAPS, supportedButtons = BUTTONS)

    private fun held(type: Int) =
        MoonlightPad(slotId = "a", number = 0, emulatedType = type, capabilities = CAPS, supportedButtons = BUTTONS)

    @Test
    fun `a slot that holds no pad acquires one`() {
        assertEquals(PadPlacement.ACQUIRE, padPlacement(held = null, wanted = request(XBOX)))
    }

    @Test
    fun `a held pad asked for as the type it was announced as is kept`() {
        assertEquals(PadPlacement.KEEP, padPlacement(held(XBOX), request(XBOX)))
    }

    // A replug unplugs the pad in the game, so only what the host reads at arrival earns one. Wolf
    // (control/input_handler.cpp create_new_joypad) reads the accelerometer and gyro bits there
    // and nothing else: every other bit, and the supported buttons, build the same pad.
    @Test
    fun `a held pad asked for with other bits the host never reads is kept`() {
        val otherBits = request(XBOX).copy(capabilities = NON_MOTION_CAPS, supportedButtons = OTHER_BUTTONS)
        assertEquals(PadPlacement.KEEP, padPlacement(held(XBOX), otherBits))
    }

    // The host asks for motion events, and applies its motion override, only for a pad that
    // arrived with a motion bit, so a pad announced before its gyro enumerated never gets motion.
    @Test
    fun `a held pad asked for with a motion bit it was announced without is re-announced`() {
        listOf(CAP_ACCELEROMETER, CAP_GYRO, CAP_ACCELEROMETER or CAP_GYRO).forEach { motion ->
            val withMotion = request(PLAYSTATION).copy(capabilities = CAPS or motion)
            assertEquals("bits $motion", PadPlacement.REANNOUNCE, padPlacement(held(PLAYSTATION), withMotion))
        }
    }

    @Test
    fun `a held pad asked for without a motion bit it was announced with is re-announced`() {
        listOf(CAP_ACCELEROMETER, CAP_GYRO).forEach { motion ->
            val announced = held(PLAYSTATION).copy(capabilities = CAPS or CAP_ACCELEROMETER or CAP_GYRO)
            val lost = request(PLAYSTATION).copy(capabilities = (CAPS or CAP_ACCELEROMETER or CAP_GYRO) and motion.inv())
            assertEquals("bits $motion", PadPlacement.REANNOUNCE, padPlacement(announced, lost))
        }
    }

    @Test
    fun `a held pad asked for with the motion bits it was announced with and others changed is kept`() {
        val announced = held(PLAYSTATION).copy(capabilities = CAPS or CAP_ACCELEROMETER or CAP_GYRO)
        val otherBits = request(PLAYSTATION).copy(capabilities = NON_MOTION_CAPS or CAP_ACCELEROMETER or CAP_GYRO)
        assertEquals(PadPlacement.KEEP, padPlacement(announced, otherBits))
    }

    @Test
    fun `a held pad asked for as another type is re-announced`() {
        assertEquals(PadPlacement.REANNOUNCE, padPlacement(held(XBOX), request(PLAYSTATION)))
        assertEquals(PadPlacement.REANNOUNCE, padPlacement(held(PLAYSTATION), request(NINTENDO)))
    }

    @Test
    fun `every session state is answered for every pad count`() {
        MoonlightSessionState.entries.forEach { state ->
            (0..4).forEach { wanted -> moonlightConverge(state, wanted) }
        }
    }

    private companion object {
        const val CAPS = 0x03
        const val BUTTONS = 0xFFFF
        const val NON_MOTION_CAPS = CAP_ANALOG_TRIGGERS or CAP_RUMBLE or CAP_TRIGGER_RUMBLE or CAP_TOUCHPAD or CAP_BATTERY or CAP_RGB_LED
        const val OTHER_BUTTONS = 0x10FFFF
    }
}
