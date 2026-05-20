// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.ui.bluetooth.BtStaleReason
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Behavioural tests for [BluetoothBondMonitor]'s broadcast handling. The
 * receiver is exercised via reflection — simpler than wiring a full
 * system-context test.
 *
 * The monitor's sole responsibility is to mark hosts stale on the registry.
 * Notification rendering is the activity layer's job (it observes
 * [BluetoothGamepadRegistry.staleBtIds]) — the monitor itself never
 * touches the notification queue, so the previous test that asserted "posts
 * a WARN notification" is gone with the BondMonitor → queue coupling.
 */
class BluetoothBondMonitorTest {
    private lateinit var context: Context
    private lateinit var store: ConnectionStore
    private lateinit var registry: BluetoothGamepadRegistry
    private lateinit var monitor: BluetoothBondMonitor

    private val remembered =
        RememberedBt(
            id = "bt:AA:BB:CC",
            name = "Xbox Controller",
            mac = "AA:BB:CC",
            profileName = "Xbox",
        )

    @Before
    fun setUp() {
        // android.util.Log is unmocked by default in JVM unit tests — every
        // log call inside the receiver would throw "Method w in Log not
        // mocked". Stub it to no-ops so the receiver path itself is what's
        // under test, not the logging plumbing.
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        context = mockk(relaxed = true)
        store = mockk(relaxed = true)
        registry = mockk(relaxed = true)
        every { store.rememberedBt() } returns listOf(remembered)
        monitor = BluetoothBondMonitor(context, store, registry)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Build an `ACTION_KEY_MISSING`-shaped intent. The receiver only reads
     * the action + the EXTRA_DEVICE; we mock both since [BluetoothDevice]
     * itself isn't constructible in a JVM test.
     */
    private fun keyMissingIntent(mac: String): Intent = intentForAction("android.bluetooth.device.action.KEY_MISSING", mac)

    private fun bondStateChangedIntent(
        mac: String,
        previous: Int,
        next: Int,
    ): Intent {
        val intent = intentForAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED, mac)
        every { intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) } returns next
        every { intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR) } returns previous
        return intent
    }

    private fun intentForAction(
        action: String,
        mac: String,
    ): Intent {
        val device =
            mockk<BluetoothDevice>(relaxed = true) {
                every { address } returns mac
            }
        val intent =
            mockk<Intent>(relaxed = true) {
                every { this@mockk.action } returns action
            }
        // Both API <33 and API 33+ paths route through extractDevice; cover
        // the legacy path (extractDevice falls back to the deprecated extra).
        every {
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } returns device
        every {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } returns device
        return intent
    }

    private fun receive(intent: Intent) {
        val field = BluetoothBondMonitor::class.java.getDeclaredField("receiver")
        field.isAccessible = true
        val receiver = field.get(monitor) as android.content.BroadcastReceiver
        receiver.onReceive(context, intent)
    }

    // ── KEY_MISSING ──────────────────────────────────────────────────────

    @Test
    fun `KEY_MISSING on a remembered host marks Stale with KEY_MISSING reason`() {
        receive(keyMissingIntent("AA:BB:CC"))

        verify { registry.markStale(remembered.id, BtStaleReason.KEY_MISSING) }
    }

    @Test
    fun `KEY_MISSING for a MAC not in rememberedBt is ignored`() {
        every { store.rememberedBt() } returns emptyList()

        receive(keyMissingIntent("AA:BB:CC"))

        verify(exactly = 0) { registry.markStale(any(), any()) }
    }

    @Test
    fun `KEY_MISSING with no EXTRA_DEVICE is ignored`() {
        val intent =
            mockk<Intent>(relaxed = true) {
                every { action } returns "android.bluetooth.device.action.KEY_MISSING"
                every { getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) } returns null
                every {
                    getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } returns null
            }

        receive(intent)

        verify(exactly = 0) { registry.markStale(any(), any()) }
    }

    // ── BOND_NONE ────────────────────────────────────────────────────────

    @Test
    fun `BOND_BONDED to BOND_NONE marks Stale with BOND_REMOVED reason`() {
        receive(
            bondStateChangedIntent(
                "AA:BB:CC",
                previous = BluetoothDevice.BOND_BONDED,
                next = BluetoothDevice.BOND_NONE,
            ),
        )

        verify { registry.markStale(remembered.id, BtStaleReason.BOND_REMOVED) }
    }

    @Test
    fun `BOND_BONDING to BOND_BONDED is ignored (normal pair path)`() {
        receive(
            bondStateChangedIntent(
                "AA:BB:CC",
                previous = BluetoothDevice.BOND_BONDING,
                next = BluetoothDevice.BOND_BONDED,
            ),
        )

        verify(exactly = 0) { registry.markStale(any(), any()) }
    }

    @Test
    fun `BOND_NONE on an unrelated MAC does not mark our hosts Stale`() {
        receive(
            bondStateChangedIntent(
                "DE:AD:BE:EF:00:00",
                previous = BluetoothDevice.BOND_BONDED,
                next = BluetoothDevice.BOND_NONE,
            ),
        )

        verify(exactly = 0) { registry.markStale(any(), any()) }
    }
}
