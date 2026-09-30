// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothPermissionBannerStoreTest {
    @Test
    fun `initial state is false when never dismissed`() {
        val store = BluetoothPermissionBannerStore(contextWith(dismissed = false))
        assertFalse(store.state.value)
    }

    @Test
    fun `initial state is true when previously dismissed`() {
        val store = BluetoothPermissionBannerStore(contextWith(dismissed = true))
        assertTrue(store.state.value)
    }

    @Test
    fun `markDismissed persists the flag AND republishes state`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs =
            mockk<SharedPreferences>(relaxed = true) {
                every { getBoolean(any(), any()) } returns false
                every { edit() } returns editor
            }
        val context =
            mockk<Context> {
                every { getSharedPreferences(USER_PREFERENCES_FILE, Context.MODE_PRIVATE) } returns prefs
            }
        val store = BluetoothPermissionBannerStore(context)

        store.markDismissed()

        verify { editor.putBoolean("bt_permission_banner_dismissed", true) }
        assertTrue(store.state.value)
    }

    private fun contextWith(dismissed: Boolean): Context {
        val prefs =
            mockk<SharedPreferences>(relaxed = true) {
                every { getBoolean(any(), any()) } returns dismissed
            }
        return mockk {
            every { getSharedPreferences(any(), any()) } returns prefs
        }
    }
}
