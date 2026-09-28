// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.di

import android.content.Context
import com.tinkernorth.dish.core.input.GamepadProfile
import com.tinkernorth.dish.source.bluetooth.HidProxyClient
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Under the JVM stub SDK_INT is 0, so the HID proxy factory takes its below-Android-9 branch: the
 * client that stands in where the Bluetooth HID Device profile does not exist.
 */
class AppModuleTest {
    private val factory = AppModule.provideHidProxyFactory(mockk<Context>())

    @Test
    fun `below Android 9 the adapter reads as off`() {
        assertFalse(factory().isAdapterEnabled())
    }

    @Test
    fun `below Android 9 acquiring reports that the profile needs Android 9`() {
        val events = mockk<HidProxyClient.Events>(relaxed = true)

        factory().acquire(events)

        verify(exactly = 1) { events.onError(ANDROID_9_REQUIRED) }
        verify(exactly = 0) { events.onAcquired() }
    }

    @Test
    fun `below Android 9 nothing is sent and no host is found`() {
        val client = factory()
        client.registerApp(mockk<GamepadProfile>())
        client.connectToHost(HOST_MAC)

        assertFalse(client.sendReport(ByteArray(REPORT_BYTES)))
        assertNull(client.findOsConnectedHost(HOST_MAC))
    }

    private companion object {
        const val ANDROID_9_REQUIRED = "Bluetooth HID Device requires Android 9+"
        const val HOST_MAC = "00:11:22:33:44:55"
        const val REPORT_BYTES = 8
    }
}
