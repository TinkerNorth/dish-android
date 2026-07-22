// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkAccessTest {
    private val context = mockk<Context>(relaxed = true)

    @After
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `permission is the platform ACCESS_LOCAL_NETWORK string`() {
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", LocalNetworkAccess.PERMISSION)
    }

    @Test
    fun `enforcement boundary is API 37`() {
        assertFalse(LocalNetworkAccess.isEnforced(24))
        assertFalse(LocalNetworkAccess.isEnforced(36))
        assertTrue(LocalNetworkAccess.isEnforced(37))
        assertTrue(LocalNetworkAccess.isEnforced(40))
    }

    @Test
    fun `pre-enforcement OS is granted without checking the runtime permission`() {
        mockkStatic(ContextCompat::class)
        assertTrue(LocalNetworkAccess.isGranted(context, sdkInt = 36))
        verify(exactly = 0) { ContextCompat.checkSelfPermission(any(), any()) }
    }

    @Test
    fun `enforcing OS is granted when the permission is held`() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(context, LocalNetworkAccess.PERMISSION) } returns
            PackageManager.PERMISSION_GRANTED
        assertTrue(LocalNetworkAccess.isGranted(context, sdkInt = 37))
    }

    @Test
    fun `enforcing OS is not granted when the permission is denied`() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(context, LocalNetworkAccess.PERMISSION) } returns
            PackageManager.PERMISSION_DENIED
        assertFalse(LocalNetworkAccess.isGranted(context, sdkInt = 37))
    }
}
