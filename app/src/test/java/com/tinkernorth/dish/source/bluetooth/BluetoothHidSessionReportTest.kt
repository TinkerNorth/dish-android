// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import com.tinkernorth.dish.core.input.BluetoothGamepad.GamepadProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BluetoothHidSessionReportTest {
    private lateinit var fake: FakeHidProxyClient
    private lateinit var session: BluetoothHidSession

    @Before
    fun setUp() {
        fake = FakeHidProxyClient()
        session = BluetoothHidSession { fake }
    }

    @Test
    fun `sendReport in Idle returns false and does not touch the proxy`() {
        val ok = session.sendReport(ByteArray(14))
        assertFalse(ok)
        assertTrue(fake.calls.none { it is FakeHidProxyClient.Call.SendReport })
    }

    @Test
    fun `sendReport in Acquiring returns false`() {
        session.start(GamepadProfile.XBOX, null)
        assertFalse(session.sendReport(ByteArray(14)))
    }

    @Test
    fun `sendReport in Registered returns false`() {
        session.start(GamepadProfile.XBOX, null)
        fake.fireAcquired()
        fake.fireAppRegistered()
        assertFalse(session.sendReport(ByteArray(14)))
    }

    @Test
    fun `sendReport in Connected delegates to the proxy and returns its result`() {
        session.start(GamepadProfile.XBOX, null)
        fake.fireAcquired()
        fake.fireAppRegistered()
        fake.fireHostConnected("AA", "X")
        fake.sendReportReturns = true

        val report = ByteArray(14).also { it[0] = 1 }
        assertTrue(session.sendReport(report))

        val call = fake.calls.filterIsInstance<FakeHidProxyClient.Call.SendReport>().single()
        assertEquals(14, call.report.size)
        assertEquals(1.toByte(), call.report[0])
    }

    @Test
    fun `sendReport falls through when proxy returns false`() {
        session.start(GamepadProfile.XBOX, null)
        fake.fireAcquired()
        fake.fireAppRegistered()
        fake.fireHostConnected("AA", "X")
        fake.sendReportReturns = false

        assertFalse(session.sendReport(ByteArray(14)))
    }
}
