// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkernorth.dish.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairPinDialogPreShowTest {
    @Test
    fun setting_title_and_subtitle_before_show_does_not_crash_and_persists_after_show() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                val dialog = PairPinDialog(activity) { }

                dialog.dishTitle = "TITLE"
                dialog.dishSubtitle = "SUBTITLE"

                assertEquals("TITLE", dialog.dishTitle.toString())
                assertEquals("SUBTITLE", dialog.dishSubtitle.toString())

                try {
                    dialog.show()

                    assertEquals("TITLE", dialog.dishTitle.toString())
                    assertEquals("SUBTITLE", dialog.dishSubtitle.toString())
                } finally {
                    dialog.dismiss()
                }
            }
        }
    }
}
