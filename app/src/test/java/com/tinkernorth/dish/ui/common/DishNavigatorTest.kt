// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tinkernorth.dish.ui.main.MainActivity
import com.tinkernorth.dish.ui.setup.SetupInputActivity
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DishNavigatorTest {
    private class RecordingActivity : Activity() {
        var starts = 0
        var finishes = 0

        override fun startActivity(intent: Intent?) {
            starts += 1
        }

        override fun finish() {
            finishes += 1
        }
    }

    private val activity = RecordingActivity()
    private val targets = mutableListOf<Class<*>>()
    private val nav = DishNavigator(activity, ::recordingIntent)

    private fun recordingIntent(
        context: Context,
        target: Class<*>,
    ): Intent {
        check(context === activity) { "the reset intent must be built from the navigating screen" }
        targets += target
        return Intent()
    }

    // The platform Intent is a stub here: addFlags has to hand back the intent itself, or the
    // navigator would start a null one.
    @Before
    fun stubIntent() {
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
    }

    @After
    fun unstubIntent() {
        unmockkConstructor(Intent::class)
    }

    @Test
    fun `the dashboard handoff starts the dashboard in a fresh task`() {
        assertEquals(MainActivity::class.java, StackReset.SETUP_TO_DASHBOARD.target)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            StackReset.SETUP_TO_DASHBOARD.flags,
        )
    }

    @Test
    fun `the setup rewind clears back to the first setup screen in the same task`() {
        assertEquals(SetupInputActivity::class.java, StackReset.SETUP_TO_START.target)
        assertEquals(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            StackReset.SETUP_TO_START.flags,
        )
    }

    @Test
    fun `finishing setup launches the dashboard handoff and finishes the screen`() {
        nav.finishSetupToDashboard()

        verify(exactly = 1) { anyConstructed<Intent>().addFlags(StackReset.SETUP_TO_DASHBOARD.flags) }
        verifyIntentTargets(MainActivity::class.java)
        assertEquals(1, activity.starts)
        assertEquals(1, activity.finishes)
    }

    @Test
    fun `rewinding setup launches the rewind and keeps the screen for the back stack to clear`() {
        nav.rewindSetupToStart()

        verify(exactly = 1) { anyConstructed<Intent>().addFlags(StackReset.SETUP_TO_START.flags) }
        verifyIntentTargets(SetupInputActivity::class.java)
        assertEquals(1, activity.starts)
        assertEquals(0, activity.finishes)
    }

    // The intent reset() builds names that screen, not only the right flags.
    private fun verifyIntentTargets(target: Class<*>) {
        assertEquals(listOf(target), targets)
    }
}
