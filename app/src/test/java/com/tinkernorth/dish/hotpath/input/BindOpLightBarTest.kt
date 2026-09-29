// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.source.lights.FrameworkLightGateway
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

private const val PAD = 5

// What each applied bind op asks of its pad's framework light bar. What a release, a restore and a
// forget then do to the bar is FrameworkLightGatewayTest's.
class BindOpLightBarTest {
    private val lights = mockk<FrameworkLightGateway>(relaxed = true)

    @Test
    fun `a slot bound to a satellite shows its pad's last host color again`() {
        lights.follow(BindOp.BindSatellite(deviceId = PAD, handle = 9, controllerIndex = 0))

        verify(exactly = 1) { lights.restore(PAD) }
        confirmVerified(lights)
    }

    @Test
    fun `a slot bound over Bluetooth shows its pad's last host color again`() {
        lights.follow(BindOp.BindBluetooth(deviceId = PAD, connectionId = "bt:aa"))

        verify(exactly = 1) { lights.restore(PAD) }
        confirmVerified(lights)
    }

    @Test
    fun `a slot bound to a Moonlight host shows its pad's last host color again`() {
        lights.follow(BindOp.BindMoonlight(deviceId = PAD, connectionId = "moonlight:pc", controllerNumber = 0))

        verify(exactly = 1) { lights.restore(PAD) }
        confirmVerified(lights)
    }

    @Test
    fun `an unbound slot's light bar is released`() {
        lights.follow(BindOp.Unbind(PAD))

        verify(exactly = 1) { lights.release(PAD) }
        confirmVerified(lights)
    }

    @Test
    fun `a departed pad's host color is forgotten`() {
        lights.follow(BindOp.Forget(PAD))

        verify(exactly = 1) { lights.forget(PAD) }
        confirmVerified(lights)
    }

    @Test
    fun `giving back a hub binding leaves the light bar alone`() {
        lights.follow(BindOp.ReleaseHubBinding(PAD))

        confirmVerified(lights)
    }
}
