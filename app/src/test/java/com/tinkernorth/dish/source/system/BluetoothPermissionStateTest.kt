// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothPermissionStateTest {
    @Test
    fun `SATISFIED reports nothing missing`() {
        val state = BluetoothPermissionState.SATISFIED
        assertFalse(state.connectMissing)
        assertFalse(state.scanMissing)
        assertFalse(state.anyMissing)
    }

    @Test
    fun `not required reports nothing missing even when grants are absent`() {
        val state = BluetoothPermissionState(required = false, connectGranted = false, scanGranted = false)
        assertFalse(state.connectMissing)
        assertFalse(state.scanMissing)
        assertFalse(state.anyMissing)
    }

    @Test
    fun `required with both grants reports nothing missing`() {
        val state = BluetoothPermissionState(required = true, connectGranted = true, scanGranted = true)
        assertFalse(state.connectMissing)
        assertFalse(state.scanMissing)
        assertFalse(state.anyMissing)
    }

    @Test
    fun `required with connect denied reports only connect missing`() {
        val state = BluetoothPermissionState(required = true, connectGranted = false, scanGranted = true)
        assertTrue(state.connectMissing)
        assertFalse(state.scanMissing)
        assertTrue(state.anyMissing)
    }

    @Test
    fun `required with scan denied reports only scan missing`() {
        val state = BluetoothPermissionState(required = true, connectGranted = true, scanGranted = false)
        assertFalse(state.connectMissing)
        assertTrue(state.scanMissing)
        assertTrue(state.anyMissing)
    }

    @Test
    fun `required with both denied reports both missing`() {
        val state = BluetoothPermissionState(required = true, connectGranted = false, scanGranted = false)
        assertTrue(state.connectMissing)
        assertTrue(state.scanMissing)
        assertTrue(state.anyMissing)
    }
}
