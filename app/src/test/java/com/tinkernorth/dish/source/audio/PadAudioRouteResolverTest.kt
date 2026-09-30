// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.audio

import android.media.AudioDeviceInfo
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadAudioRouteResolverTest {
    @Test
    fun `a usb device is a pad endpoint on every release`() {
        assertTrue(isPluggedUsbType(AudioDeviceInfo.TYPE_USB_DEVICE, sdkInt = Build.VERSION_CODES.N))
        assertTrue(isPluggedUsbType(AudioDeviceInfo.TYPE_USB_DEVICE, sdkInt = Build.VERSION_CODES.O))
    }

    @Test
    fun `a usb headset is a pad endpoint only from 26`() {
        assertFalse(isPluggedUsbType(AudioDeviceInfo.TYPE_USB_HEADSET, sdkInt = Build.VERSION_CODES.N_MR1))
        assertTrue(isPluggedUsbType(AudioDeviceInfo.TYPE_USB_HEADSET, sdkInt = Build.VERSION_CODES.O))
    }

    @Test
    fun `a usb accessory is not a pad endpoint`() {
        assertFalse(isPluggedUsbType(AudioDeviceInfo.TYPE_USB_ACCESSORY, sdkInt = Build.VERSION_CODES.O))
    }

    @Test
    fun `a built-in speaker is not a pad endpoint`() {
        assertFalse(isPluggedUsbType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, sdkInt = Build.VERSION_CODES.O))
    }
}
