// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// API 31 (S), where BLUETOOTH_CONNECT became a runtime permission, and the release before it.
private const val SDK_RUNTIME_GRANT = 31
private const val SDK_INSTALL_GRANT = 30

class BluetoothPermissionsTest {
    private val context = mockk<Context>()

    @Before
    fun setUp() {
        mockkStatic(ContextCompat::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun runtimeGrantIs(result: Int) {
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) } returns result
    }

    @Test
    fun `below API 31 the install-time grant answers without asking`() {
        runtimeGrantIs(PackageManager.PERMISSION_DENIED)

        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkBluetoothConnectPermission(SDK_INSTALL_GRANT))
        verify(exactly = 0) { ContextCompat.checkSelfPermission(any(), any()) }
    }

    @Test
    fun `from API 31 a denied runtime grant reads denied`() {
        runtimeGrantIs(PackageManager.PERMISSION_DENIED)

        assertEquals(PackageManager.PERMISSION_DENIED, context.checkBluetoothConnectPermission(SDK_RUNTIME_GRANT))
    }

    @Test
    fun `from API 31 a given runtime grant reads granted`() {
        runtimeGrantIs(PackageManager.PERMISSION_GRANTED)

        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkBluetoothConnectPermission(SDK_RUNTIME_GRANT))
    }
}
