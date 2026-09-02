// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speaker eligibility rule, one row at a time. Three facts have to hold together, and the
 * matrix below walks every combination of them, because each one moves independently at runtime and
 * a slot that plays while any of them is false is a track held open for a pad that is not there.
 */
class SpeakerPlayoutPolicyTest {
    private fun slot(
        slotId: String = VIRTUAL_SLOT_ID,
        handle: Int = HANDLE,
        index: Int = CTRL_IDX,
        streaming: Boolean = true,
        enabled: Boolean = true,
        playbackDeviceId: Int = NO_AUDIO_DEVICE,
    ) = SpeakerSlotInput(slotId, handle, index, streaming, enabled, playbackDeviceId)

    @Test
    fun `every combination of the three gates, and exactly one of them plays`() {
        // Eight rows as three bits, rather than nested loops: the point is that ONE of them plays.
        val rows = (0 until 8).map { Triple(it and 1 != 0, it and 2 != 0, it and 4 != 0) }
        var playing = 0
        for ((streaming, enabled, registered) in rows) {
            val plan =
                SpeakerPlayoutPolicy.plan(
                    listOf(
                        slot(
                            streaming = streaming,
                            enabled = enabled,
                            // An unregistered slot has no controller index: the emulated pad does
                            // not exist on the host yet.
                            index = if (registered) CTRL_IDX else -1,
                        ),
                    ),
                )
            val row = "streaming=$streaming enabled=$enabled registered=$registered"
            val eligible = streaming && enabled && registered
            assertEquals("$row", eligible, plan.playing)
            if (eligible) playing++ else assertEquals("$row opens no voice", emptyMap<Long, SpeakerTarget>(), plan.voices)
        }
        assertEquals("exactly one row of eight plays", 1, playing)
    }

    @Test
    fun `an eligible slot is keyed by the address its frames carry`() {
        val plan = SpeakerPlayoutPolicy.plan(listOf(slot()))
        val target = plan.voices[SpeakerPlayoutPlan.routeKey(HANDLE, CTRL_IDX)]!!
        assertEquals(VIRTUAL_SLOT_ID, target.slotId)
        assertEquals(HANDLE, target.sessionHandle)
        assertEquals(CTRL_IDX, target.controllerIndex)
        assertNull(plan.voices[SpeakerPlayoutPlan.routeKey(HANDLE, CTRL_IDX + 1)])
        assertNull(plan.voices[SpeakerPlayoutPlan.routeKey(HANDLE + 1, CTRL_IDX)])
    }

    @Test
    fun `a slot with no live session does not play`() {
        // handle is -1 until the session PUT lands, and a frame can never arrive for it.
        assertFalse(SpeakerPlayoutPolicy.plan(listOf(slot(handle = -1))).playing)
    }

    @Test
    fun `two slots on one session each get their own voice`() {
        val plan =
            SpeakerPlayoutPolicy.plan(
                listOf(slot(), slot(slotId = "-1000", index = CTRL_IDX + 1, playbackDeviceId = 11)),
            )
        assertEquals(2, plan.voices.size)
        assertEquals(NO_AUDIO_DEVICE, plan.voices[SpeakerPlayoutPlan.routeKey(HANDLE, CTRL_IDX)]!!.playbackDeviceId)
        assertEquals(11, plan.voices[SpeakerPlayoutPlan.routeKey(HANDLE, CTRL_IDX + 1)]!!.playbackDeviceId)
    }

    @Test
    fun `the same controller index on two sessions is two voices`() {
        val plan = SpeakerPlayoutPolicy.plan(listOf(slot(), slot(slotId = "-1000", handle = HANDLE + 1)))
        assertEquals(2, plan.voices.size)
    }

    @Test
    fun `the route key packs a handle and an index without collision`() {
        val seen = HashSet<Long>()
        for (handle in 0..8) {
            for (index in 0..4) {
                assertTrue(
                    "handle=$handle index=$index collided",
                    seen.add(SpeakerPlayoutPlan.routeKey(handle, index)),
                )
            }
        }
    }

    @Test
    fun `an idle plan is the empty one`() {
        assertEquals(SpeakerPlayoutPlan.IDLE, SpeakerPlayoutPolicy.plan(emptyList()))
        assertFalse(SpeakerPlayoutPlan.IDLE.playing)
    }

    private companion object {
        const val HANDLE = 7
        const val CTRL_IDX = 0
    }
}
