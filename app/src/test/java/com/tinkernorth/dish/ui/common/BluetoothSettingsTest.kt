// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class BluetoothSettingsTest {
    private val activity = mockk<Activity>(relaxed = true)

    @Test
    fun `the handoff starts the system Bluetooth settings`() {
        activity.openBluetoothSettings()

        verify(exactly = 1) { activity.startActivity(any()) }
    }

    @Test
    fun `a device without a Bluetooth settings screen leaves the caller standing`() {
        every { activity.startActivity(any()) } throws ActivityNotFoundException("no Bluetooth settings")

        activity.openBluetoothSettings()
    }

    @Test
    fun `a settings app that refuses the caller leaves the caller standing`() {
        every { activity.startActivity(any()) } throws SecurityException("refused")

        activity.openBluetoothSettings()
    }
}
