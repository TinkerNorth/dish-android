// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eligibility rule behind the privacy invariant, exhaustively.
 *
 * Four independent facts decide whether a microphone runs (streaming, toggle, permission, mute)
 * and the rule is that ALL of them must hold. The full matrix is walked below rather than sampled:
 * every row where the answer is "no capture" is a row where a regression would put a live
 * microphone on somebody's LAN.
 */
class MicCapturePolicyTest {
    private fun slot(
        streaming: Boolean = true,
        micEnabled: Boolean = true,
        muted: Boolean = false,
        slotId: String = SLOT,
        connectionId: String? = CONN,
    ) = MicSlotInput(
        slotId = slotId,
        connectionId = connectionId,
        streaming = streaming,
        micEnabled = micEnabled,
        muted = muted,
    )

    private data class Row(
        val streaming: Boolean,
        val micEnabled: Boolean,
        val granted: Boolean,
        val muted: Boolean,
    )

    /** All 2^4 combinations, packed off the bits of 0..15 so no row can be quietly omitted. */
    private fun matrix(): List<Row> =
        (0 until MATRIX_ROWS).map { bits ->
            Row(
                streaming = bits and 0b0001 != 0,
                micEnabled = bits and 0b0010 != 0,
                granted = bits and 0b0100 != 0,
                muted = bits and 0b1000 != 0,
            )
        }

    @Test
    fun `the whole streaming x toggle x permission x mute matrix, every row`() {
        val rows = matrix()
        assertEquals("the matrix is 2^4 rows", MATRIX_ROWS, rows.size)
        for (row in rows) {
            val plan =
                MicCapturePolicy.plan(
                    listOf(slot(streaming = row.streaming, micEnabled = row.micEnabled, muted = row.muted)),
                    permissionGranted = row.granted,
                )
            val shouldArm = row.streaming && row.micEnabled && row.granted
            val shouldDeliver = shouldArm && !row.muted
            assertEquals("armed for $row", shouldArm, plan.arming)
            assertEquals("delivering for $row", shouldDeliver, plan.delivering.isNotEmpty())
            assertEquals("capturing for $row", shouldDeliver, plan.capturing)
        }
    }

    @Test
    fun `three quarters of the matrix send nothing, and that is the point`() {
        val capturing = matrix().count { it.streaming && it.micEnabled && it.granted && !it.muted }
        assertEquals("exactly one row of sixteen captures", 1, capturing)
    }

    @Test
    fun `a muted slot is armed but never delivering`() {
        // The distinction is the whole reason there are two sets: the foreground service keeps
        // its microphone type across a mute (it can only be re-taken from the foreground), while
        // not one packet goes out.
        val plan = MicCapturePolicy.plan(listOf(slot(muted = true)), permissionGranted = true)
        assertEquals(setOf(MicCaptureTarget(SLOT, CONN)), plan.armed)
        assertTrue(plan.delivering.isEmpty())
        assertFalse(plan.capturing)
    }

    @Test
    fun `no permission means nothing is even armed, whatever else is true`() {
        // Not just a delivery gate: a service claiming a microphone type it cannot use would be a
        // microphone the user sees in the status bar and cannot account for.
        val plan =
            MicCapturePolicy.plan(
                listOf(slot(slotId = "a"), slot(slotId = "b", connectionId = "other")),
                permissionGranted = false,
            )
        assertEquals(MicCapturePlan.IDLE, plan)
    }

    @Test
    fun `an unbound slot contributes nothing`() {
        val plan = MicCapturePolicy.plan(listOf(slot(connectionId = null)), permissionGranted = true)
        assertEquals(MicCapturePlan.IDLE, plan)
    }

    @Test
    fun `slots are independent, so one muted slot does not silence another`() {
        val plan =
            MicCapturePolicy.plan(
                listOf(
                    slot(slotId = "virtual", muted = true),
                    slot(slotId = "-1000", connectionId = "conn-b"),
                ),
                permissionGranted = true,
            )
        assertEquals(
            setOf(MicCaptureTarget("virtual", CONN), MicCaptureTarget("-1000", "conn-b")),
            plan.armed,
        )
        assertEquals(setOf(MicCaptureTarget("-1000", "conn-b")), plan.delivering)
    }

    @Test
    fun `a slot whose capability path does not carry a microphone is excluded on its own`() {
        val plan =
            MicCapturePolicy.plan(
                listOf(slot(slotId = "off", micEnabled = false), slot(slotId = "on")),
                permissionGranted = true,
            )
        assertEquals(setOf(MicCaptureTarget("on", CONN)), plan.armed)
        assertEquals(setOf(MicCaptureTarget("on", CONN)), plan.delivering)
    }

    @Test
    fun `a slot that stopped streaming drops out even while its toggle stays on`() {
        val plan = MicCapturePolicy.plan(listOf(slot(streaming = false)), permissionGranted = true)
        assertEquals(MicCapturePlan.IDLE, plan)
    }

    @Test
    fun `no slots at all is idle, not an empty capture`() {
        assertEquals(MicCapturePlan.IDLE, MicCapturePolicy.plan(emptyList(), permissionGranted = true))
        assertFalse(MicCapturePlan.IDLE.capturing)
    }

    private companion object {
        const val SLOT = "virtual"
        const val CONN = "satellite:abc"
        const val MATRIX_ROWS = 16
    }
}
