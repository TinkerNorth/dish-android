// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkernorth.dish.ui.main.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: prove the process can start MainActivity and reach RESUMED
 * without crashing. This is intentionally minimal — it catches catastrophic
 * regressions (Hilt graph misconfiguration, missing AndroidManifest entries,
 * native-load failures on an unsupported emulator ABI) that unit tests with
 * mocked Android framework cannot.
 *
 * Run on a connected device or emulator:
 *   ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {
    @Test
    fun activity_reaches_resumed_without_crashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                check(!activity.isFinishing) {
                    "MainActivity finished itself during launch — likely native fallback or crash."
                }
            }
        }
    }
}
