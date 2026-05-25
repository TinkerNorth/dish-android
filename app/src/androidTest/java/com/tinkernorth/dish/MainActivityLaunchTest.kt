// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkernorth.dish.ui.main.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

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
