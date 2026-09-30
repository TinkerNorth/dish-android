// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.content.DialogInterface
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinkernorth.dish.R
import com.tinkernorth.dish.ui.common.DishNavigator

// The single blocking sheet for any "your input or destination is gone" failure
// across the flow: Retry re-runs the caller's recovery, Start over rewinds to
// the first screen, Exit drops to the dashboard.
internal fun showSetupError(
    activity: AppCompatActivity,
    nav: DishNavigator,
    message: String? = null,
    onRetry: () -> Unit,
) {
    MaterialAlertDialogBuilder(activity)
        .setMessage(message ?: activity.getString(R.string.setup_error_body))
        .setCancelable(false)
        .setPositiveButton(R.string.setup_error_retry) { dialog, _ -> dismissAndRetry(dialog, onRetry) }
        .setNeutralButton(R.string.setup_error_start_over) { _, _ -> nav.rewindSetupToStart() }
        .setNegativeButton(R.string.setup_error_exit) { _, _ -> nav.finishSetupToDashboard() }
        .show()
}

private fun dismissAndRetry(
    dialog: DialogInterface,
    onRetry: () -> Unit,
) {
    dialog.dismiss()
    onRetry()
}
