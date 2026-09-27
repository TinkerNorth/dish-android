// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BluetoothPadLinkReaderTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `no bonded device of that name stays unknown`() {
        assertEquals(BluetoothLinkType.UNKNOWN, linkTypeOf(emptyList()))
    }

    @Test
    fun `an ambiguous bonded name stays unknown`() {
        assertEquals(BluetoothLinkType.UNKNOWN, linkTypeOf(listOf(BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_LE)))
    }

    @Test
    fun `one classic match is classic`() {
        assertEquals(BluetoothLinkType.CLASSIC, linkTypeOf(listOf(BluetoothDevice.DEVICE_TYPE_CLASSIC)))
    }

    @Test
    fun `one low energy match is low energy`() {
        assertEquals(BluetoothLinkType.LOW_ENERGY, linkTypeOf(listOf(BluetoothDevice.DEVICE_TYPE_LE)))
    }

    @Test
    fun `one dual match is dual`() {
        assertEquals(BluetoothLinkType.DUAL, linkTypeOf(listOf(BluetoothDevice.DEVICE_TYPE_DUAL)))
    }

    @Test
    fun `a device type the platform does not classify stays unknown`() {
        assertEquals(BluetoothLinkType.UNKNOWN, linkTypeOf(listOf(BluetoothDevice.DEVICE_TYPE_UNKNOWN)))
    }

    private val adapter = mockk<BluetoothAdapter>()
    private val manager = mockk<BluetoothManager> { every { this@mockk.adapter } returns this@BluetoothPadLinkReaderTest.adapter }
    private val context = mockk<Context>(relaxed = true)

    private fun bonded(
        name: String,
        type: Int,
    ): BluetoothDevice =
        mockk {
            every { this@mockk.name } returns name
            every { this@mockk.type } returns type
        }

    @Test
    fun `the bonded list is matched by the pad's name`() {
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        every { adapter.bondedDevices } returns
            setOf(bonded("Pixel Buds", BluetoothDevice.DEVICE_TYPE_LE), bonded("DualSense", BluetoothDevice.DEVICE_TYPE_CLASSIC))

        assertEquals(BluetoothLinkType.CLASSIC, BluetoothPadLinkReader(context).linkType("DualSense"))
    }

    @Test
    fun `no adapter reads unknown`() {
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns null

        assertEquals(BluetoothLinkType.UNKNOWN, BluetoothPadLinkReader(context).linkType("DualSense"))
    }

    @Test
    fun `a permission revoked mid-read leaves the link unknown`() {
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        every { adapter.bondedDevices } throws SecurityException("revoked")

        assertEquals(BluetoothLinkType.UNKNOWN, BluetoothPadLinkReader(context).linkType("DualSense"))
    }
}
