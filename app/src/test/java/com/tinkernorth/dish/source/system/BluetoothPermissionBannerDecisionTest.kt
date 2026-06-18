// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothPermissionBannerDecisionTest {
    @Test
    fun `dismissed suppresses the banner even when both permissions are missing`() {
        val state = BluetoothPermissionState(required = true, connectGranted = false, scanGranted = false)
        assertNull(BluetoothPermissionBannerDecision.evaluate(state, dismissed = true))
    }

    @Test
    fun `connect missing yields the CONNECT variant`() {
        val state = BluetoothPermissionState(required = true, connectGranted = false, scanGranted = true)
        assertEquals(
            BluetoothPermissionBannerVariant.CONNECT,
            BluetoothPermissionBannerDecision.evaluate(state, dismissed = false),
        )
    }

    @Test
    fun `scan missing alone yields the SCAN variant`() {
        val state = BluetoothPermissionState(required = true, connectGranted = true, scanGranted = false)
        assertEquals(
            BluetoothPermissionBannerVariant.SCAN,
            BluetoothPermissionBannerDecision.evaluate(state, dismissed = false),
        )
    }

    @Test
    fun `both missing prioritises CONNECT`() {
        val state = BluetoothPermissionState(required = true, connectGranted = false, scanGranted = false)
        assertEquals(
            BluetoothPermissionBannerVariant.CONNECT,
            BluetoothPermissionBannerDecision.evaluate(state, dismissed = false),
        )
    }

    @Test
    fun `nothing missing yields no banner`() {
        val state = BluetoothPermissionState(required = true, connectGranted = true, scanGranted = true)
        assertNull(BluetoothPermissionBannerDecision.evaluate(state, dismissed = false))
    }

    @Test
    fun `pre-S satisfied state yields no banner`() {
        assertNull(BluetoothPermissionBannerDecision.evaluate(BluetoothPermissionState.SATISFIED, dismissed = false))
    }
}
