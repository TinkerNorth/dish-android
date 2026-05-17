// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MotionIndicatorState.of] — the pure (isAvailable, isStreaming,
 * connectionCarriesMotion, connectionConnected) → indicator-state mapping
 * behind the touch-overlay's phone-motion pill.
 *
 * The contract that matters: the user must be able to tell apart the
 * "motion is not going out" situations —
 *
 *  - no gyroscope at all                 → [MotionIndicatorState.UNAVAILABLE]
 *  - gyroscope, source paused, or the
 *    satellite connection is not up      → [MotionIndicatorState.PAUSED]
 *  - gyroscope, but a Bluetooth connection with no motion channel
 *                                        → [MotionIndicatorState.NOT_FORWARDED]
 *
 * — and never see a false [MotionIndicatorState.STREAMING]. Two cases must
 * not promote to STREAMING despite a "started" source: a Bluetooth connection
 * (drops every sample), and a satellite connection that is not CONNECTED
 * (`sendMotion` drops every packet).
 */
class MotionIndicatorStateTest {
    @Test
    fun `gyro present, streaming, connected satellite maps to STREAMING`() {
        assertEquals(
            MotionIndicatorState.STREAMING,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = true,
                connectionConnected = true,
            ),
        )
    }

    @Test
    fun `gyro present but source not started maps to PAUSED`() {
        // The overlay-backgrounded case: hardware is there, source is stopped
        // to release the sensor listeners. Must NOT collapse onto UNAVAILABLE.
        assertEquals(
            MotionIndicatorState.PAUSED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = false,
                connectionCarriesMotion = true,
                connectionConnected = true,
            ),
        )
    }

    @Test
    fun `a streaming source over a disconnected satellite reads as PAUSED, not STREAMING`() {
        // The honesty case this test was added for: PhoneMotionSource.start()
        // ran and isStreaming is true, but the satellite connection is
        // reconnecting / idle, so sendMotion drops every packet. The pill must
        // say PAUSED — claiming "streaming" while nothing reaches the wire is
        // the bug.
        assertEquals(
            MotionIndicatorState.PAUSED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = true,
                connectionConnected = false,
            ),
        )
    }

    @Test
    fun `STREAMING requires both a started source and a connected connection`() {
        // gyro present, a satellite connection that carries motion: STREAMING
        // only when the source is started AND the connection is actually up.
        for (streaming in listOf(true, false)) {
            for (connected in listOf(true, false)) {
                val expected =
                    if (streaming && connected) {
                        MotionIndicatorState.STREAMING
                    } else {
                        MotionIndicatorState.PAUSED
                    }
                assertEquals(
                    "streaming=$streaming connected=$connected",
                    expected,
                    MotionIndicatorState.of(
                        isAvailable = true,
                        isStreaming = streaming,
                        connectionCarriesMotion = true,
                        connectionConnected = connected,
                    ),
                )
            }
        }
    }

    @Test
    fun `bluetooth connection never reads as STREAMING even while source is started`() {
        // The honesty case: PhoneMotionSource.start() runs and isStreaming is
        // true, but the satellite send is a no-op for Bluetooth, so the
        // samples are dropped. The pill must say NOT_FORWARDED, not STREAMING —
        // and that holds even though a Bluetooth connection reads "connected".
        assertEquals(
            MotionIndicatorState.NOT_FORWARDED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = false,
                connectionConnected = true,
            ),
        )
        // ...and the same when the source happens to be paused too.
        assertEquals(
            MotionIndicatorState.NOT_FORWARDED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = false,
                connectionCarriesMotion = false,
                connectionConnected = true,
            ),
        )
    }

    @Test
    fun `no gyro maps to UNAVAILABLE regardless of the other flags`() {
        // No gyroscope wins outright: start() is a no-op, so neither a stale
        // streaming flag, the connection kind, nor its liveness change it.
        for (streaming in listOf(true, false)) {
            for (carries in listOf(true, false)) {
                for (connected in listOf(true, false)) {
                    assertEquals(
                        "isAvailable=false must always be UNAVAILABLE",
                        MotionIndicatorState.UNAVAILABLE,
                        MotionIndicatorState.of(
                            isAvailable = false,
                            isStreaming = streaming,
                            connectionCarriesMotion = carries,
                            connectionConnected = connected,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `the three non-streaming states are all distinct`() {
        // The whole point of the enum: "no hardware", "paused", and "this
        // connection can't carry motion" must not be the same value.
        val nonStreaming =
            setOf(
                MotionIndicatorState.PAUSED,
                MotionIndicatorState.NOT_FORWARDED,
                MotionIndicatorState.UNAVAILABLE,
            )
        assertEquals(3, nonStreaming.size)
        assertNotEquals(MotionIndicatorState.STREAMING, MotionIndicatorState.PAUSED)
    }

    @Test
    fun `only UNAVAILABLE and NOT_FORWARDED carry an explanatory detail line`() {
        // STREAMING / PAUSED are self-explanatory; the two "motion won't
        // leave the phone" states get a one-liner so the limit is clear.
        assertTrue(MotionIndicatorState.UNAVAILABLE.hasDetail)
        assertTrue(MotionIndicatorState.NOT_FORWARDED.hasDetail)
        assertFalse(MotionIndicatorState.STREAMING.hasDetail)
        assertFalse(MotionIndicatorState.PAUSED.hasDetail)
    }

    @Test
    fun `every state carries a distinct, non-zero label and a dot colour`() {
        val states = MotionIndicatorState.entries
        val labels = states.map { it.labelRes }
        val colors = states.map { it.dotColorRes }
        // No "0" (missing) resource ids, and each state has its own label so
        // the four cases read differently in the pill.
        assertTrue("a label resource is unset", labels.all { it != 0 })
        assertTrue("a dot-colour resource is unset", colors.all { it != 0 })
        assertEquals("labels must be unique per state", labels.size, labels.toSet().size)
    }
}
