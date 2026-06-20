// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionRateUserFacingOnTest {
    // gyro -> controller layer, userMotion -> userEnabled, hostSink -> type layer (the emulated
    // type's motion sink), backendDown -> MOTION in runtimeDown. Other layers are filled full.
    private fun caps(
        gyro: Boolean = true,
        userMotion: Boolean = true,
        hostSink: Boolean = true,
        backendDown: Boolean = false,
    ): SlotCapabilities {
        fun motionSet(present: Boolean) = if (present) CapabilitySet.of(Feature.MOTION) else CapabilitySet.EMPTY
        val all = CapabilitySet(Feature.entries.toSet())
        return SlotCapabilities(
            controller = motionSet(gyro),
            transport = all,
            type = motionSet(hostSink),
            host = all,
            userEnabled = motionSet(userMotion),
            runtimeDown = motionSet(backendDown),
        )
    }

    // Link-liveness rides the bound summary now, not the capability model: Connected carries motion.
    private fun summary(
        live: LinkState = LinkState.Connected,
        kind: ConnectionKind = ConnectionKind.SATELLITE,
    ): ConnectionSummary =
        ConnectionSummary(
            id = "c1",
            kind = kind,
            label = "label",
            detail = "detail",
            live = live,
            boundSlotIds = emptyList(),
        )

    @Test
    fun `shown when motion is fully on`() {
        assertTrue(motionRateUserFacingOn(caps(), summary()))
        assertTrue(motionRateUserFacingOn(caps(backendDown = false), summary()))
    }

    @Test
    fun `hidden for an emulated type with no host sink`() {
        assertFalse(motionRateUserFacingOn(caps(hostSink = false), summary()))
    }

    @Test
    fun `hidden when the user disabled motion`() {
        assertFalse(motionRateUserFacingOn(caps(userMotion = false), summary()))
    }

    @Test
    fun `hidden when the connection does not carry motion`() {
        // carriesOnConnection used to live in the capability model; it is now the bound link's liveness.
        assertFalse(motionRateUserFacingOn(caps(), summary(live = LinkState.Connecting)))
        assertFalse(motionRateUserFacingOn(caps(), boundStatus = null))
    }

    @Test
    fun `hidden over a connected bluetooth host`() {
        // Motion never carries over a Bluetooth host even when fully on; only a Satellite does.
        assertFalse(motionRateUserFacingOn(caps(), summary(kind = ConnectionKind.BLUETOOTH)))
    }

    @Test
    fun `hidden when the host backend is broken`() {
        assertFalse(motionRateUserFacingOn(caps(backendDown = true), summary()))
    }

    @Test
    fun `hidden without a gyro`() {
        assertFalse(motionRateUserFacingOn(caps(gyro = false), summary()))
    }
}
