// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.bluetooth.BluetoothClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for the CoD-driven host filter. The constants on
 * [BluetoothClass.Device.Major] resolve at compile time on JVM tests so we
 * don't need Robolectric here.
 */
class BondedHostFilterTest {
    @Test
    fun `classify maps COMPUTER to Computer`() {
        assertEquals(BondedHostKind.COMPUTER, BondedHostFilter.classify(BluetoothClass.Device.Major.COMPUTER))
    }

    @Test
    fun `classify maps PHONE to Phone`() {
        assertEquals(BondedHostKind.PHONE, BondedHostFilter.classify(BluetoothClass.Device.Major.PHONE))
    }

    @Test
    fun `classify maps AUDIO_VIDEO to Console — consoles report under this major`() {
        assertEquals(BondedHostKind.CONSOLE, BondedHostFilter.classify(BluetoothClass.Device.Major.AUDIO_VIDEO))
    }

    @Test
    fun `classify falls back to Other for anything else`() {
        assertEquals(BondedHostKind.OTHER, BondedHostFilter.classify(BluetoothClass.Device.Major.PERIPHERAL))
        assertEquals(BondedHostKind.OTHER, BondedHostFilter.classify(BluetoothClass.Device.Major.WEARABLE))
        assertEquals(BondedHostKind.OTHER, BondedHostFilter.classify(BluetoothClass.Device.Major.UNCATEGORIZED))
        assertEquals(BondedHostKind.OTHER, BondedHostFilter.classify(-1))
    }

    @Test
    fun `isLikelyHost is true for the three host buckets and false for OTHER`() {
        assertTrue(BondedHostFilter.isLikelyHost(BondedHostKind.COMPUTER))
        assertTrue(BondedHostFilter.isLikelyHost(BondedHostKind.CONSOLE))
        assertTrue(BondedHostFilter.isLikelyHost(BondedHostKind.PHONE))
        assertFalse(BondedHostFilter.isLikelyHost(BondedHostKind.OTHER))
    }

    @Test
    fun `isExcludedAccessory hides peripherals, wearables, toys, health, imaging`() {
        assertTrue(BondedHostFilter.isExcludedAccessory(BluetoothClass.Device.Major.PERIPHERAL))
        assertTrue(BondedHostFilter.isExcludedAccessory(BluetoothClass.Device.Major.WEARABLE))
        assertTrue(BondedHostFilter.isExcludedAccessory(BluetoothClass.Device.Major.TOY))
        assertTrue(BondedHostFilter.isExcludedAccessory(BluetoothClass.Device.Major.HEALTH))
        assertTrue(BondedHostFilter.isExcludedAccessory(BluetoothClass.Device.Major.IMAGING))
    }

    @Test
    fun `isExcludedAccessory keeps the host-shaped majors visible`() {
        assertFalse(BondedHostFilter.isExcludedAccessory(BluetoothClass.Device.Major.COMPUTER))
        assertFalse(BondedHostFilter.isExcludedAccessory(BluetoothClass.Device.Major.PHONE))
        assertFalse(BondedHostFilter.isExcludedAccessory(BluetoothClass.Device.Major.AUDIO_VIDEO))
        // Even unknown majors should not be excluded — Show all needs to surface them.
        assertFalse(BondedHostFilter.isExcludedAccessory(BluetoothClass.Device.Major.UNCATEGORIZED))
        assertFalse(BondedHostFilter.isExcludedAccessory(-1))
    }
}
