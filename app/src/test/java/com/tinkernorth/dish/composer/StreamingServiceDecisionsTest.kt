// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import android.content.pm.ServiceInfo
import android.os.Build
import com.tinkernorth.dish.source.audio.MicIndicatorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

// The streaming service's decisions, pure: what one snapshot asks of the service, what the
// notification says, which sessions stop-all reaches, and which foreground types each API holds.
class StreamingServiceDecisionsTest {
    private fun summary(
        id: String,
        kind: ConnectionKind,
        live: LinkState,
    ) = ConnectionSummary(
        id = id,
        kind = kind,
        label = id,
        detail = "",
        live = live,
        boundSlotIds = emptyList(),
    )

    private fun snapshot(
        streamingSlots: Int = 1,
        directClaims: Int = 0,
        micArmed: Boolean = false,
    ) = StreamingSnapshot(
        streamingSlots = streamingSlots,
        connections = emptyList(),
        directClaims = directClaims,
        micArmed = micArmed,
        micState = MicIndicatorState.HIDDEN,
    )

    @Test
    fun `no streaming slot and no held claim stops the service`() {
        assertEquals(RefreshAction.STOP, refreshActionFor(snapshot(streamingSlots = 0, directClaims = 0), micTypeHeld = false))
    }

    @Test
    fun `a held direct claim keeps the service up with nothing streaming`() {
        assertEquals(RefreshAction.NOTIFY, refreshActionFor(snapshot(streamingSlots = 0, directClaims = 1), micTypeHeld = false))
    }

    @Test
    fun `arming the microphone re-declares the foreground types`() {
        assertEquals(RefreshAction.REDECLARE, refreshActionFor(snapshot(micArmed = true), micTypeHeld = false))
    }

    @Test
    fun `disarming the microphone re-declares them too`() {
        assertEquals(RefreshAction.REDECLARE, refreshActionFor(snapshot(micArmed = false), micTypeHeld = true))
    }

    @Test
    fun `an unchanged microphone type only repaints the notification`() {
        assertEquals(RefreshAction.NOTIFY, refreshActionFor(snapshot(micArmed = true), micTypeHeld = true))
    }

    @Test
    fun `stopping wins over a microphone flip when there is nothing to hold`() {
        val idleWithMicFlip = snapshot(streamingSlots = 0, directClaims = 0, micArmed = true)
        assertEquals(RefreshAction.STOP, refreshActionFor(idleWithMicFlip, micTypeHeld = false))
    }

    @Test
    fun `the primary label is the first connected link of any kind`() {
        val connections =
            listOf(
                summary("sat-a", ConnectionKind.SATELLITE, LinkState.Connecting),
                summary("bt-b", ConnectionKind.BLUETOOTH, LinkState.Connected),
                summary("sat-c", ConnectionKind.SATELLITE, LinkState.Connected),
            )
        assertEquals("bt-b", primaryLabelFor(connections))
    }

    @Test
    fun `no connected link means no primary label`() {
        assertNull(primaryLabelFor(listOf(summary("sat-a", ConnectionKind.SATELLITE, LinkState.Connecting))))
    }

    @Test
    fun `zero streaming slots reads as the usb hold body`() {
        assertEquals(StreamingBodyKind.USB_HOLD, streamingBodyKind(streamingSlots = 0))
    }

    @Test
    fun `one streaming slot reads as the streaming body`() {
        assertEquals(StreamingBodyKind.STREAMING, streamingBodyKind(streamingSlots = 1))
    }

    @Test
    fun `stop-all picks only the connected satellite and bluetooth links`() {
        val summaries =
            listOf(
                summary("sat-live", ConnectionKind.SATELLITE, LinkState.Connected),
                summary("sat-linking", ConnectionKind.SATELLITE, LinkState.Connecting),
                summary("bt-live", ConnectionKind.BLUETOOTH, LinkState.Connected),
                summary("bt-saved", ConnectionKind.BLUETOOTH, LinkState.Saved),
            )
        assertEquals(
            SessionsToStop(satelliteIds = listOf("sat-live"), bluetoothIds = listOf("bt-live")),
            sessionsToStop(summaries),
        )
    }

    @Test
    fun `stop-all leaves a moonlight session alone`() {
        val summaries = listOf(summary("ml", ConnectionKind.MOONLIGHT, LinkState.Connected))
        assertEquals(SessionsToStop(satelliteIds = emptyList(), bluetoothIds = emptyList()), sessionsToStop(summaries))
    }

    @Test
    fun `an armed microphone is declared first and falls back to connected device only`() {
        assertEquals(
            listOf(ForegroundDeclaration.CONNECTED_DEVICE_AND_MICROPHONE, ForegroundDeclaration.CONNECTED_DEVICE_ONLY),
            foregroundDeclarationsFor(micArmed = true),
        )
    }

    @Test
    fun `an unarmed microphone never asks for the mic type`() {
        assertEquals(listOf(ForegroundDeclaration.CONNECTED_DEVICE_ONLY), foregroundDeclarationsFor(micArmed = false))
    }

    @Test
    fun `the fallback declaration carries no microphone`() {
        assertFalse(foregroundDeclarationsFor(micArmed = true).last().micArmed)
    }

    @Test
    fun `the named type bits are the platform's`() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, FOREGROUND_TYPE_CONNECTED_DEVICE)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, FOREGROUND_TYPE_MICROPHONE)
    }

    @Test
    fun `no type at all is the platform's zero`() {
        assertEquals(0, FOREGROUND_TYPE_NONE)
    }

    @Test
    fun `from R an armed microphone adds its type`() {
        assertEquals(foregroundServiceTypes(micArmed = true), foregroundServiceTypesForThisApi(Build.VERSION_CODES.R, micArmed = true))
    }

    @Test
    fun `from R an unarmed session is connected device only`() {
        assertEquals(FOREGROUND_TYPE_CONNECTED_DEVICE, foregroundServiceTypesForThisApi(Build.VERSION_CODES.R, micArmed = false))
    }

    @Test
    fun `Q holds the connected-device type even with a microphone armed`() {
        assertEquals(FOREGROUND_TYPE_CONNECTED_DEVICE, foregroundServiceTypesForThisApi(Build.VERSION_CODES.Q, micArmed = true))
    }

    @Test
    fun `below Q there is no type to declare`() {
        assertEquals(FOREGROUND_TYPE_NONE, foregroundServiceTypesForThisApi(Build.VERSION_CODES.P, micArmed = true))
    }
}
