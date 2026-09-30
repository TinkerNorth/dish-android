// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.hotpath.input.Transport
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The formatters take a Context only for getString, so a mock that echoes the resource id
// pins which string each branch picks.

private val NO_HOST_FEATURES =
    HostFeatureSet(
        hasCatalog = false,
        mouseControl = false,
        keyboardControl = false,
        rumbleReturn = false,
    )

class DiagnosticsLabelsTest {
    private val context =
        mockk<Context> {
            every { getString(any()) } answers { "s${firstArg<Int>()}" }
            every { getString(R.string.diagnostics_hz, *anyVararg()) } answers { "${secondArg<Array<Any?>>().first()} Hz" }
        }

    private fun pad(
        transport: Transport?,
        synthetic: Boolean,
    ) = ControllerDiag(
        slotId = "3",
        name = "Pad",
        isVirtual = false,
        transport = transport,
        isUsbSynthetic = synthetic,
        hasGyro = false,
        pollRateHz = 0,
        gyroHz = 0,
        state = ControllerDiagState.CONNECTED,
        battery = null,
        host = null,
        functions = emptyList(),
    )

    @Test
    fun `a direct USB pad is labelled direct, a framework one standard, anything else Bluetooth`() {
        assertEquals("s${R.string.diagnostics_transport_usb_direct}", context.transportLabel(pad(Transport.Usb, synthetic = true)))
        assertEquals("s${R.string.diagnostics_transport_usb_standard}", context.transportLabel(pad(Transport.Usb, synthetic = false)))
        assertEquals("s${R.string.diagnostics_transport_bluetooth}", context.transportLabel(pad(Transport.Bluetooth, synthetic = false)))
    }

    @Test
    fun `each controller state has its own label`() {
        val labels = ControllerDiagState.entries.map { context.controllerStateLabel(it) }
        assertEquals(ControllerDiagState.entries.size, labels.toSet().size)
        assertEquals("s${R.string.diagnostics_state_needs_replug}", context.controllerStateLabel(ControllerDiagState.NEEDS_REPLUG))
    }

    @Test
    fun `a rate of zero or less reads unknown and a positive one reads in hertz`() {
        assertEquals("s${R.string.diagnostics_unknown}", context.hzLabel(0))
        assertEquals("s${R.string.diagnostics_unknown}", context.hzLabel(-1))
        assertEquals("250 Hz", context.hzLabel(250))
    }

    @Test
    fun `a Bluetooth host's emulated type is its profile and a typeless satellite has none`() {
        assertEquals("Xbox", context.emulatedTypeLabel(ConnectionKind.BLUETOOTH, typeId = null, btProfile = "Xbox"))
        assertNull(context.emulatedTypeLabel(ConnectionKind.SATELLITE, typeId = null, btProfile = "Xbox"))
        assertNull(context.emulatedTypeLabel(ConnectionKind.MOONLIGHT, typeId = null, btProfile = null))
    }

    @Test
    fun `the host feature list reads none when nothing is on`() {
        assertEquals("s${R.string.diagnostics_none}", context.hostFeatureList(NO_HOST_FEATURES))
    }

    @Test
    fun `the host feature list joins each feature that is on`() {
        val features = NO_HOST_FEATURES.copy(rumbleReturn = true, mouseControl = true)
        assertEquals("s${R.string.setup_cap_rumble} · s${R.string.binding_func_mouse}", context.hostFeatureList(features))
    }

    @Test
    fun `an empty function list is an empty string`() {
        assertEquals("", context.featureList(emptyList()))
        assertEquals("s${R.string.setup_cap_motion}", context.featureList(listOf(Feature.MOTION)))
    }
}
