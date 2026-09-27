// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.system

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothAdapterStateObserverTest {
    private val adapter = mockk<BluetoothAdapter>()
    private val manager = mockk<BluetoothManager> { every { this@mockk.adapter } returns this@BluetoothAdapterStateObserverTest.adapter }
    private val context = mockk<Context>(relaxed = true)
    private val owner = mockk<LifecycleOwner>()

    private fun adapterEnabled(enabled: Boolean) {
        every { context.getSystemService(BluetoothManager::class.java) } returns manager
        every { adapter.isEnabled } returns enabled
    }

    @Test
    fun `no adapter reads unsupported`() {
        every { context.getSystemService(BluetoothManager::class.java) } returns null

        assertEquals(BluetoothAdapterState.UNSUPPORTED, BluetoothAdapterStateObserver(context).state.value)
    }

    @Test
    fun `an adapter that is off reads off`() {
        adapterEnabled(false)

        assertEquals(BluetoothAdapterState.OFF, BluetoothAdapterStateObserver(context).state.value)
    }

    @Test
    fun `an enabled adapter reads on`() {
        adapterEnabled(true)

        assertEquals(BluetoothAdapterState.ON, BluetoothAdapterStateObserver(context).state.value)
    }

    private fun startAndCaptureReceiver(observer: BluetoothAdapterStateObserver): BroadcastReceiver {
        val receiver = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(receiver), any(), any<Int>()) } returns null
        observer.onStart(owner)
        return receiver.captured
    }

    private fun intentWithAction(action: String?): Intent {
        val intent = mockk<Intent>()
        every { intent.action } returns action
        return intent
    }

    @Test
    fun `onStart re-reads the adapter before registering`() {
        adapterEnabled(false)
        val observer = BluetoothAdapterStateObserver(context)
        every { adapter.isEnabled } returns true

        startAndCaptureReceiver(observer)

        assertEquals(BluetoothAdapterState.ON, observer.state.value)
    }

    @Test
    fun `a state-changed broadcast refreshes the reading`() {
        adapterEnabled(true)
        val observer = BluetoothAdapterStateObserver(context)
        val receiver = startAndCaptureReceiver(observer)

        every { adapter.isEnabled } returns false
        receiver.onReceive(context, intentWithAction(BluetoothAdapter.ACTION_STATE_CHANGED))

        assertEquals(BluetoothAdapterState.OFF, observer.state.value)
    }

    @Test
    fun `a broadcast with another action is ignored`() {
        adapterEnabled(true)
        val observer = BluetoothAdapterStateObserver(context)
        val receiver = startAndCaptureReceiver(observer)

        every { adapter.isEnabled } returns false
        receiver.onReceive(context, intentWithAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))

        assertEquals(BluetoothAdapterState.ON, observer.state.value)
    }

    @Test
    fun `onStart twice registers once`() {
        adapterEnabled(true)
        val observer = BluetoothAdapterStateObserver(context)

        observer.onStart(owner)
        observer.onStart(owner)

        verify(exactly = 1) { context.registerReceiver(any(), any(), any<Int>()) }
    }

    @Test
    fun `onStop unregisters once and a second onStop is a no-op`() {
        adapterEnabled(true)
        val observer = BluetoothAdapterStateObserver(context)
        observer.onStart(owner)

        observer.onStop(owner)
        observer.onStop(owner)

        verify(exactly = 1) { context.unregisterReceiver(any()) }
    }

    @Test
    fun `onStop before any onStart unregisters nothing`() {
        adapterEnabled(true)

        BluetoothAdapterStateObserver(context).onStop(owner)

        verify(exactly = 0) { context.unregisterReceiver(any()) }
    }
}
