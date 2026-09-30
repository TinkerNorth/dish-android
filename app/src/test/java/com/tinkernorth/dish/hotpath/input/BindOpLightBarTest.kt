// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.input

import com.tinkernorth.dish.source.lights.FrameworkLightGateway
import com.tinkernorth.dish.source.lights.LightSource
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

private const val PAD = 5

// What each applied bind op asks of its pad's framework light bar. What a release, a bind to a
// host and a forget then do to the bar is FrameworkLightGatewayTest's.
class BindOpLightBarTest {
    private val lights = mockk<FrameworkLightGateway>(relaxed = true)

    @Test
    fun `a slot bound to a satellite is keyed to that satellite's controller`() {
        lights.follow(BindOp.BindSatellite(deviceId = PAD, connectionId = "sat:a", handle = 9, controllerIndex = 2))

        verify(exactly = 1) { lights.boundTo(PAD, LightSource("sat:a", 2)) }
        confirmVerified(lights)
    }

    @Test
    fun `a slot bound over Bluetooth is keyed to its Bluetooth connection, whose host sends no color`() {
        lights.follow(BindOp.BindBluetooth(deviceId = PAD, connectionId = "bt:aa"))

        verify(exactly = 1) { lights.boundTo(PAD, LightSource("bt:aa", 0)) }
        confirmVerified(lights)
    }

    @Test
    fun `a slot bound to a Moonlight host is keyed to that host's pad number`() {
        lights.follow(BindOp.BindMoonlight(deviceId = PAD, connectionId = "moonlight:pc", controllerNumber = 1))

        verify(exactly = 1) { lights.boundTo(PAD, LightSource("moonlight:pc", 1)) }
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
