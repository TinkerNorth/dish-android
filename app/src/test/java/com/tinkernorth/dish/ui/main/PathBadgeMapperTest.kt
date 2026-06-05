// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.usb.PathMode
import com.tinkernorth.dish.source.usb.PathReason
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PathBadgeMapperTest {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = mockk()
        every { ctx.getString(any<Int>()) } answers { "res:${firstArg<Int>()}" }
        every { ctx.getString(R.string.path_label_direct_with_rate, any<Any>()) } returns "RATE_LABEL"
    }

    private fun map(
        mode: PathMode,
        reason: PathReason,
        isOnScreen: Boolean,
        pollRateHz: Int = 0,
    ) = PathBadgeMapper.map(ctx, mode, reason, isOnScreen, pollRateHz)

    @Test
    fun `on-screen controller always reads as Standard with the on-screen reason`() {
        val badge = map(PathMode.Direct, PathReason.None, isOnScreen = true)
        assertEquals("res:${R.string.path_label_standard}", badge.label)
        assertEquals("res:${R.string.path_reason_onscreen}", badge.subtitle)
        assertFalse(badge.isDirect)
        assertFalse(badge.actionable)
    }

    @Test
    fun `direct mode without a measured rate uses the plain direct label`() {
        val badge = map(PathMode.Direct, PathReason.None, isOnScreen = false, pollRateHz = 0)
        assertEquals("res:${R.string.path_label_direct}", badge.label)
        assertEquals("res:${R.string.path_direct_explainer}", badge.subtitle)
        assertTrue(badge.isDirect)
        assertFalse(badge.actionable)
    }

    @Test
    fun `direct mode with a measured rate uses the rate label`() {
        val badge = map(PathMode.Direct, PathReason.None, isOnScreen = false, pollRateHz = 1000)
        assertEquals("RATE_LABEL", badge.label)
        assertTrue(badge.isDirect)
    }

    @Test
    fun `eligible routed controller is actionable`() {
        val badge = map(PathMode.Routed, PathReason.Eligible, isOnScreen = false)
        assertEquals("res:${R.string.path_label_standard}", badge.label)
        assertEquals("res:${R.string.path_action_try_direct}", badge.subtitle)
        assertFalse(badge.isDirect)
        assertTrue(badge.actionable)
    }

    @Test
    fun `permission-denied routed controller is actionable and offers the try action`() {
        val badge = map(PathMode.Routed, PathReason.PermissionDenied, isOnScreen = false)
        assertEquals("res:${R.string.path_action_try_direct}", badge.subtitle)
        assertTrue(badge.actionable)
    }

    @Test
    fun `bluetooth reason is informational and not actionable`() {
        val badge = map(PathMode.Routed, PathReason.Bluetooth, isOnScreen = false)
        assertEquals("res:${R.string.path_reason_bluetooth}", badge.subtitle)
        assertFalse(badge.actionable)
    }

    @Test
    fun `routed with no reason falls back to the default explanation`() {
        val badge = map(PathMode.Routed, PathReason.None, isOnScreen = false)
        assertEquals("res:${R.string.path_reason_default}", badge.subtitle)
        assertFalse(badge.actionable)
    }

    @Test
    fun `each non-actionable routed reason maps to its own explanation string`() {
        val expectations =
            mapOf(
                PathReason.UnknownModel to R.string.path_reason_unknown_model,
                PathReason.Busy to R.string.path_reason_busy,
                PathReason.InitFailed to R.string.path_reason_init_failed,
                PathReason.Detached to R.string.path_reason_detached,
                PathReason.SupportedNoFastPathYet to R.string.path_reason_not_yet,
                PathReason.OnScreen to R.string.path_reason_onscreen,
            )
        for ((reason, resId) in expectations) {
            val badge = map(PathMode.Routed, reason, isOnScreen = false)
            assertEquals("reason $reason", "res:$resId", badge.subtitle)
            assertFalse("reason $reason should not be actionable", badge.actionable)
            assertFalse(badge.isDirect)
        }
    }
}
