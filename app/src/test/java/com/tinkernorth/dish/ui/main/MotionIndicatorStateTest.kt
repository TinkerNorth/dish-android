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
    fun `every non-streaming state is distinct`() {
        // The whole point of the enum: every "motion is not going out"
        // reason must read differently on the pill so the user knows the
        // right next step. A regression that collapses any two of these
        // back onto the same value would silently kill that signal.
        val nonStreaming =
            setOf(
                MotionIndicatorState.PAUSED,
                MotionIndicatorState.NOT_FORWARDED,
                MotionIndicatorState.UNAVAILABLE,
                MotionIndicatorState.USER_DISABLED,
                MotionIndicatorState.NO_HOST_SINK,
                MotionIndicatorState.STALLED,
            )
        assertEquals(6, nonStreaming.size)
        assertNotEquals(MotionIndicatorState.STREAMING, MotionIndicatorState.PAUSED)
    }

    @Test
    fun `every limit state that needs an explanation carries a detail line`() {
        // STREAMING / PAUSED are self-explanatory; every other state has a
        // one-liner so the user understands the constraint (and where
        // applicable, the actionable next step).
        assertTrue(MotionIndicatorState.UNAVAILABLE.hasDetail)
        assertTrue(MotionIndicatorState.NOT_FORWARDED.hasDetail)
        assertTrue(MotionIndicatorState.STALLED.hasDetail)
        assertTrue(MotionIndicatorState.USER_DISABLED.hasDetail)
        assertTrue(MotionIndicatorState.NO_HOST_SINK.hasDetail)
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

    // ── STALLED — synthesised when the sensor exists but isn't ticking ──

    @Test
    fun `streaming + connected + stalled maps to STALLED`() {
        // Gyro exists, source started, satellite connection up — but no
        // gyro samples in the stall window. Must demote from STREAMING so
        // the pill never claims it's live when nothing is.
        assertEquals(
            MotionIndicatorState.STALLED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = true,
                connectionConnected = true,
                isStalled = true,
            ),
        )
    }

    @Test
    fun `stalled flag is ignored when not streaming (stays PAUSED)`() {
        // Source is stopped; the stalled flag is meaningless here because no
        // sample window is in flight. PAUSED is the right reading.
        assertEquals(
            MotionIndicatorState.PAUSED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = false,
                connectionCarriesMotion = true,
                connectionConnected = true,
                isStalled = true,
            ),
        )
    }

    @Test
    fun `stalled flag is ignored over Bluetooth (stays NOT_FORWARDED)`() {
        // Bluetooth has no motion channel — stall detection is irrelevant.
        assertEquals(
            MotionIndicatorState.NOT_FORWARDED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = false,
                connectionConnected = true,
                isStalled = true,
            ),
        )
    }

    @Test
    fun `stalled flag is ignored when no gyroscope (stays UNAVAILABLE)`() {
        assertEquals(
            MotionIndicatorState.UNAVAILABLE,
            MotionIndicatorState.of(
                isAvailable = false,
                isStreaming = true,
                connectionCarriesMotion = true,
                connectionConnected = true,
                isStalled = true,
            ),
        )
    }

    @Test
    fun `STALLED carries a detail line so the limit is explained`() {
        assertTrue(MotionIndicatorState.STALLED.hasDetail)
    }

    @Test
    fun `STALLED has its own distinct label`() {
        // The "every state has a distinct label" check above already covers
        // this; pinning it explicitly so a future refactor that collapses
        // STALLED onto PAUSED's label fails loudly here.
        assertNotEquals(MotionIndicatorState.PAUSED.labelRes, MotionIndicatorState.STALLED.labelRes)
    }

    // ── USER_DISABLED — distinct from PAUSED (the user toggled motion off) ──

    @Test
    fun `userEnabled=false on a satellite connection maps to USER_DISABLED`() {
        // The user actively turned motion off on this slot. PAUSED would
        // imply the source is paused for lifecycle / link reasons, not a
        // user choice — USER_DISABLED is the actionable label.
        assertEquals(
            MotionIndicatorState.USER_DISABLED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = false,
                connectionCarriesMotion = true,
                connectionConnected = true,
                userEnabled = false,
            ),
        )
    }

    @Test
    fun `USER_DISABLED takes precedence over NOT_FORWARDED`() {
        // BT connection AND user toggled off: show the toggle as the
        // limit, not the connection kind — the toggle is the more
        // actionable fact (flip it or use a satellite connection).
        assertEquals(
            MotionIndicatorState.USER_DISABLED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = false,
                connectionCarriesMotion = false,
                connectionConnected = true,
                userEnabled = false,
            ),
        )
    }

    @Test
    fun `no gyroscope wins over USER_DISABLED`() {
        // UNAVAILABLE is the highest-precedence terminal state — a phone
        // without a gyro can't stream regardless of any toggle. Pin that
        // the hardware absence reads correctly even if the toggle is off.
        assertEquals(
            MotionIndicatorState.UNAVAILABLE,
            MotionIndicatorState.of(
                isAvailable = false,
                isStreaming = false,
                connectionCarriesMotion = true,
                connectionConnected = true,
                userEnabled = false,
            ),
        )
    }

    @Test
    fun `userEnabled=true default keeps existing call sites unchanged`() {
        // The new userEnabled parameter has a default of true so the
        // pre-PR call sites (and tests above) continue to read as
        // STREAMING / PAUSED / etc. Pin that explicitly.
        assertEquals(
            MotionIndicatorState.STREAMING,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = true,
                connectionConnected = true,
                // userEnabled defaulted (true)
            ),
        )
    }

    // ── NO_HOST_SINK — slot's controller type can't carry motion on the host ──

    @Test
    fun `hostHasSinkForType=false on a satellite slot maps to NO_HOST_SINK`() {
        // Xbox-typed virtual pad — the dish would otherwise stream, but
        // the host's XInput backend has no IMU surface so samples would
        // be silently dropped at the virtual gamepad layer. The pill
        // warns up front to switch to PlayStation for gyro to land.
        assertEquals(
            MotionIndicatorState.NO_HOST_SINK,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = true,
                connectionConnected = true,
                userEnabled = true,
                hostHasSinkForType = false,
            ),
        )
    }

    @Test
    fun `USER_DISABLED takes precedence over NO_HOST_SINK`() {
        // A user-toggled-off Xbox slot reads as USER_DISABLED — the user
        // chose to turn it off and that's the most actionable next step
        // (re-enable, or change the type to PS to make NO_HOST_SINK
        // disappear on its own).
        assertEquals(
            MotionIndicatorState.USER_DISABLED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = false,
                connectionCarriesMotion = true,
                connectionConnected = true,
                userEnabled = false,
                hostHasSinkForType = false,
            ),
        )
    }

    @Test
    fun `NOT_FORWARDED takes precedence over NO_HOST_SINK`() {
        // BT-HID connection AND an Xbox-typed slot. The BT limit is the
        // more fundamental problem — fix the connection first; the host
        // sink question only becomes meaningful on a satellite.
        assertEquals(
            MotionIndicatorState.NOT_FORWARDED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = false,
                connectionConnected = true,
                userEnabled = true,
                hostHasSinkForType = false,
            ),
        )
    }

    @Test
    fun `hostHasSinkForType=true default keeps existing call sites unchanged`() {
        // Same backwards-compat pin as for userEnabled — the new parameter
        // has a default of true so legacy call sites still compute the
        // correct state.
        assertEquals(
            MotionIndicatorState.PAUSED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = false,
                connectionCarriesMotion = true,
                connectionConnected = true,
                // userEnabled, hostHasSinkForType both default true
            ),
        )
    }
}
