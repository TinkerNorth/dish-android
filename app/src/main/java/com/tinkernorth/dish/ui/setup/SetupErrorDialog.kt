// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tinkernorth.dish.R
import com.tinkernorth.dish.ui.main.MainActivity

// The single blocking sheet for any "your input or destination is gone" failure
// across the flow: Retry re-runs the caller's recovery, Start over rewinds to
// the first screen, Exit drops to the dashboard.
object SetupErrorDialog {
    fun show(
        activity: AppCompatActivity,
        message: String? = null,
        onRetry: () -> Unit,
    ) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(message ?: activity.getString(R.string.setup_error_body))
            .setCancelable(false)
            .setPositiveButton(R.string.setup_error_retry) { dialog, _ ->
                dialog.dismiss()
                onRetry()
            }.setNeutralButton(R.string.setup_error_start_over) { _, _ -> startOver(activity) }
            .setNegativeButton(R.string.setup_error_exit) { _, _ -> exitToDashboard(activity) }
            .show()
    }

    // SetupInputActivity sits at the root of the flow's task, so CLEAR_TOP rewinds
    // to it and drops every screen stacked above.
    fun startOver(activity: Activity) {
        activity.startActivity(
            Intent(activity, SetupInputActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    fun exitToDashboard(activity: Activity) {
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        activity.finish()
    }
}
