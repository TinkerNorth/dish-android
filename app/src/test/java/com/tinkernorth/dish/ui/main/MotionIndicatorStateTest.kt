// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(
            MotionIndicatorState.NOT_FORWARDED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = false,
                connectionConnected = true,
            ),
        )
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
        assertTrue("a label resource is unset", labels.all { it != 0 })
        assertTrue("a dot-colour resource is unset", colors.all { it != 0 })
        assertEquals("labels must be unique per state", labels.size, labels.toSet().size)
    }

    @Test
    fun `streaming + connected + stalled maps to STALLED`() {
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
        assertNotEquals(MotionIndicatorState.PAUSED.labelRes, MotionIndicatorState.STALLED.labelRes)
    }

    @Test
    fun `userEnabled=false on a satellite connection maps to USER_DISABLED`() {
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
    fun `hostHasSinkForType=false on a satellite slot maps to NO_HOST_SINK`() {
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
    fun `BACKEND_BROKEN fires when satellite reports its sink failed`() {
        assertEquals(
            MotionIndicatorState.BACKEND_BROKEN,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = true,
                connectionConnected = true,
                userEnabled = true,
                hostHasSinkForType = true,
                satelliteBackendOk = false,
            ),
        )
    }

    @Test
    fun `satelliteBackendOk=null stays out of the way`() {
        assertEquals(
            MotionIndicatorState.STREAMING,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = true,
                connectionConnected = true,
                userEnabled = true,
                hostHasSinkForType = true,
                satelliteBackendOk = null,
            ),
        )
    }

    @Test
    fun `satelliteBackendOk=true is the normal happy-path branch`() {
        assertEquals(
            MotionIndicatorState.STREAMING,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = true,
                connectionConnected = true,
                userEnabled = true,
                hostHasSinkForType = true,
                satelliteBackendOk = true,
            ),
        )
    }

    @Test
    fun `NO_HOST_SINK outranks BACKEND_BROKEN — the type-level reason is higher-order`() {
        assertEquals(
            MotionIndicatorState.NO_HOST_SINK,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = true,
                connectionConnected = true,
                userEnabled = true,
                hostHasSinkForType = false,
                satelliteBackendOk = false,
            ),
        )
    }

    @Test
    fun `USER_DISABLED outranks BACKEND_BROKEN — user's choice is the actionable reason`() {
        assertEquals(
            MotionIndicatorState.USER_DISABLED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = false,
                connectionCarriesMotion = true,
                connectionConnected = true,
                userEnabled = false,
                hostHasSinkForType = true,
                satelliteBackendOk = false,
            ),
        )
    }

    @Test
    fun `NOT_FORWARDED outranks BACKEND_BROKEN — BT-HID never carried motion in the first place`() {
        assertEquals(
            MotionIndicatorState.NOT_FORWARDED,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = false,
                connectionConnected = true,
                userEnabled = true,
                hostHasSinkForType = true,
                satelliteBackendOk = false,
            ),
        )
    }

    @Test
    fun `BACKEND_BROKEN outranks STALLED — the receiver's reason beats the source's reason`() {
        assertEquals(
            MotionIndicatorState.BACKEND_BROKEN,
            MotionIndicatorState.of(
                isAvailable = true,
                isStreaming = true,
                connectionCarriesMotion = true,
                connectionConnected = true,
                userEnabled = true,
                hostHasSinkForType = true,
                satelliteBackendOk = false,
                isStalled = true,
            ),
        )
    }
}
