// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayCutoutModeTest {
    @Test
    fun gamepad_overlay_window_uses_the_themed_cutout_mode() = assertCutoutMode(GamepadOverlayActivity::class.java)

    @Test
    fun touchpad_overlay_window_uses_the_themed_cutout_mode() = assertCutoutMode(TouchpadOverlayActivity::class.java)

    @Test
    fun mouse_overlay_window_uses_the_themed_cutout_mode() = assertCutoutMode(MouseOverlayActivity::class.java)

    private fun assertCutoutMode(activity: Class<out Activity>) {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        val expected =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        ActivityScenario.launch(activity).use { scenario ->
            scenario.onActivity { assertEquals(expected, it.window.attributes.layoutInDisplayCutoutMode) }
        }
    }
}
