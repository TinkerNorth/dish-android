// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.ui.bluetooth.BtStaleReason
import com.tinkernorth.dish.ui.common.DishNotification
import com.tinkernorth.dish.ui.common.DishNotificationQueue
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Behavioural tests for [BluetoothBondMonitor]'s broadcast handling. The
 * receiver runs against the live [DishNotificationQueue] + [BluetoothGamepadRegistry]
 * so the full "broadcast → markStale + notification.post" chain is exercised
 * without instantiating an Android `BroadcastReceiver` (we invoke the
 * inner `onReceive` callback directly via reflection — simpler than wiring
 * a full system-context test).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothBondMonitorTest {
    private lateinit var context: Context
    private lateinit var store: ConnectionStore
    private lateinit var registry: BluetoothGamepadRegistry
    private lateinit var queue: DishNotificationQueue
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
        queue = DishNotificationQueue()
        every { store.rememberedBt() } returns listOf(remembered)
        every { context.getString(any(), any<String>()) } answers {
            val args = secondArg<Array<Any>>()
            "msg(${firstArg<Int>()}:${args.joinToString(",")})"
        }
        every { context.getString(any()) } answers { "msg(${firstArg<Int>()})" }
        monitor = BluetoothBondMonitor(context, store, registry, queue)
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
    fun `KEY_MISSING on a remembered host posts a WARN notification with action`() =
        runTest(TestScope(StandardTestDispatcher()).testScheduler) {
            receive(keyMissingIntent("AA:BB:CC"))

            val n = queue.posts.first()
            assertEquals(DishNotification.Severity.WARN, n.severity)
            assertEquals(DishNotification.DURATION_PERSISTENT, n.durationMs)
            // Same-key dedup so multiple KEY_MISSING for the same host don't
            // stack — the row chip carries the persistent narrative.
            assertEquals("bt-stale:${remembered.id}", n.key)
            // Action label is the localised "SETTINGS" string the receiver
            // looks up. The mock returns a deterministic stub.
            assertEquals(
                "Action must offer a recovery path",
                true,
                n.action != null,
            )
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
