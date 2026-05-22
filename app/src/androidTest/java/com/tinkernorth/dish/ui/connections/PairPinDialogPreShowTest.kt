// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkernorth.dish.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the lateinit-binding crash in [PairPinDialog].
 *
 * Before the fix, assigning [PairPinDialog.dishTitle] or [PairPinDialog.dishSubtitle]
 * before the first [android.app.Dialog.show] threw
 * `UninitializedPropertyAccessException: lateinit property binding has not been
 * initialized`, because the view binding is only inflated inside `Dialog.onCreate`,
 * which the framework runs on first `show()`.
 *
 * That ordering is exactly how the dialog is used in production
 * (`ConnectionsActivity.showPairingDialog` sets the title/subtitle inside an
 * `apply { ... }` block, then calls `show()`), so the crash was reachable any
 * time the pairing flow opened the dialog.
 *
 * This test pins the contract: setting the properties pre-show must not crash,
 * and the values must end up on the visible views once the dialog has been shown.
 *
 * Run on a connected device or emulator:
 *   ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class PairPinDialogPreShowTest {
    @Test
    fun setting_title_and_subtitle_before_show_does_not_crash_and_persists_after_show() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                val dialog = PairPinDialog(activity) { /* submit not exercised */ }

                // Pre-show assignment — this is what crashed before the fix.
                dialog.dishTitle = "TITLE"
                dialog.dishSubtitle = "SUBTITLE"

                // Reads before show should return what the caller stashed,
                // not an empty default and not a crash.
                assertEquals("TITLE", dialog.dishTitle.toString())
                assertEquals("SUBTITLE", dialog.dishSubtitle.toString())

                try {
                    dialog.show()

                    // After show(), onCreate has inflated the binding and flushed
                    // the pending writes onto the TextViews.
                    assertEquals("TITLE", dialog.dishTitle.toString())
                    assertEquals("SUBTITLE", dialog.dishSubtitle.toString())
                } finally {
                    dialog.dismiss()
                }
            }
        }
    }
}
