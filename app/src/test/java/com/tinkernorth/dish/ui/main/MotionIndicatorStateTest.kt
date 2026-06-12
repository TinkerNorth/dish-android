// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.MotionCapability
import com.tinkernorth.dish.source.sensor.MotionStreamState
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
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
    fun `NO_HOST_SINK outranks BACKEND_BROKEN - the type-level reason is higher-order`() {
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
    fun `USER_DISABLED outranks BACKEND_BROKEN - user's choice is the actionable reason`() {
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
    fun `NOT_FORWARDED outranks BACKEND_BROKEN - BT-HID never carried motion in the first place`() {
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
    fun `BACKEND_BROKEN outranks STALLED - the receiver's reason beats the source's reason`() {
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

    // ---- motionIndicatorFor: the Activity-extracted input translation ----

    private fun summary(
        kind: ConnectionKind,
        live: LinkState,
    ): ConnectionSummary =
        ConnectionSummary(
            id = "c1",
            kind = kind,
            label = "label",
            detail = "detail",
            live = live,
            boundSlotIds = emptyList(),
        )

    private val fullCapability =
        MotionCapability(
            hasGyro = true,
            carriesOnConnection = true,
            userEnabled = true,
            hostHasSinkForType = true,
            satelliteBackendStatus = null,
        )

    @Test
    fun `motionIndicatorFor null summary with a streaming source maps to PAUSED`() {
        // Null summary is treated as motion-capable (carries) but not-yet-connected, so a started
        // source still reads PAUSED until liveness resolves.
        assertEquals(
            MotionIndicatorState.PAUSED,
            motionIndicatorFor(
                summary = null,
                capability = fullCapability,
                source = MotionStreamState.Streaming,
            ),
        )
    }

    @Test
    fun `motionIndicatorFor connected satellite streaming maps to STREAMING`() {
        assertEquals(
            MotionIndicatorState.STREAMING,
            motionIndicatorFor(
                summary = summary(ConnectionKind.SATELLITE, LinkState.Connected),
                capability = fullCapability,
                source = MotionStreamState.Streaming,
            ),
        )
    }

    @Test
    fun `motionIndicatorFor bluetooth summary streaming maps to NOT_FORWARDED`() {
        assertEquals(
            MotionIndicatorState.NOT_FORWARDED,
            motionIndicatorFor(
                summary = summary(ConnectionKind.BLUETOOTH, LinkState.Connected),
                capability = fullCapability,
                source = MotionStreamState.Streaming,
            ),
        )
    }

    @Test
    fun `motionIndicatorFor stalled source over a connected satellite maps to STALLED`() {
        // Stalled is folded into isStreaming, and also raises the isStalled flag.
        assertEquals(
            MotionIndicatorState.STALLED,
            motionIndicatorFor(
                summary = summary(ConnectionKind.SATELLITE, LinkState.Connected),
                capability = fullCapability,
                source = MotionStreamState.Stalled,
            ),
        )
    }

    @Test
    fun `motionIndicatorFor disabled source maps to UNAVAILABLE`() {
        // The no-gyro / off condition reaches this function as a Disabled source, not via
        // capability.hasGyro (which this translation never reads).
        assertEquals(
            MotionIndicatorState.UNAVAILABLE,
            motionIndicatorFor(
                summary = summary(ConnectionKind.SATELLITE, LinkState.Connected),
                capability = fullCapability,
                source = MotionStreamState.Disabled,
            ),
        )
    }

    @Test
    fun `motionIndicatorFor a Disabled source dominates regardless of summary and capability`() {
        // Mirrors the existing precedence test: UNAVAILABLE wins. Note the dominating input here is
        // the source being Disabled, not MotionCapability.hasGyro, which motionIndicatorFor ignores.
        val richCapability =
            MotionCapability(
                hasGyro = true,
                carriesOnConnection = true,
                userEnabled = false,
                hostHasSinkForType = false,
                satelliteBackendStatus =
                    SatelliteMotionBackendStatus(sinkSupportedForType = false, backendOk = false),
            )
        for (kind in ConnectionKind.entries) {
            for (live in LinkState.entries) {
                assertEquals(
                    "kind=$kind live=$live must stay UNAVAILABLE when source is Disabled",
                    MotionIndicatorState.UNAVAILABLE,
                    motionIndicatorFor(
                        summary = summary(kind, live),
                        capability = richCapability,
                        source = MotionStreamState.Disabled,
                    ),
                )
            }
        }
    }

    @Test
    fun `motionIndicatorFor a Stopped source over a connected satellite maps to PAUSED`() {
        // Stopped is neither Disabled (so available) nor Streaming/Stalled (so not streaming).
        assertEquals(
            MotionIndicatorState.PAUSED,
            motionIndicatorFor(
                summary = summary(ConnectionKind.SATELLITE, LinkState.Connected),
                capability = fullCapability,
                source = MotionStreamState.Stopped,
            ),
        )
    }

    @Test
    fun `motionIndicatorFor a not-yet-connected satellite reads PAUSED, not STREAMING`() {
        assertEquals(
            MotionIndicatorState.PAUSED,
            motionIndicatorFor(
                summary = summary(ConnectionKind.SATELLITE, LinkState.Connecting),
                capability = fullCapability,
                source = MotionStreamState.Streaming,
            ),
        )
    }

    @Test
    fun `motionIndicatorFor passes through capability userEnabled to USER_DISABLED`() {
        assertEquals(
            MotionIndicatorState.USER_DISABLED,
            motionIndicatorFor(
                summary = summary(ConnectionKind.SATELLITE, LinkState.Connected),
                capability = fullCapability.copy(userEnabled = false),
                source = MotionStreamState.Streaming,
            ),
        )
    }

    @Test
    fun `motionIndicatorFor passes through capability hostHasSinkForType to NO_HOST_SINK`() {
        assertEquals(
            MotionIndicatorState.NO_HOST_SINK,
            motionIndicatorFor(
                summary = summary(ConnectionKind.SATELLITE, LinkState.Connected),
                capability = fullCapability.copy(hostHasSinkForType = false),
                source = MotionStreamState.Streaming,
            ),
        )
    }

    @Test
    fun `motionIndicatorFor passes through satellite backend status to BACKEND_BROKEN`() {
        assertEquals(
            MotionIndicatorState.BACKEND_BROKEN,
            motionIndicatorFor(
                summary = summary(ConnectionKind.SATELLITE, LinkState.Connected),
                capability =
                    fullCapability.copy(
                        satelliteBackendStatus =
                            SatelliteMotionBackendStatus(
                                sinkSupportedForType = true,
                                backendOk = false,
                            ),
                    ),
                source = MotionStreamState.Streaming,
            ),
        )
    }
}
