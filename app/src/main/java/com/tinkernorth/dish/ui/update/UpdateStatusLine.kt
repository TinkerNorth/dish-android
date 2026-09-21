// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.update

import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.update.UpdateNoticePhase
import com.tinkernorth.dish.source.update.UpdateNoticeStatus

// The Settings row for the update check, as resource ids plus the one argument
// the body may take, so the mapping is testable without a Context.
data class UpdateStatusLine(
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    val versionArg: String? = null,
    // The row is a button: it checks, or opens the download when one is on
    // offer. With checks off it does nothing, and the body says so.
    val actionable: Boolean = true,
)

fun updateStatusLine(status: UpdateNoticeStatus): UpdateStatusLine =
    when (status.phase) {
        UpdateNoticePhase.Disabled ->
            UpdateStatusLine(R.string.settings_update_check_now_title, R.string.update_status_off, actionable = false)
        UpdateNoticePhase.Idle -> UpdateStatusLine(R.string.settings_update_check_now_title, R.string.update_status_idle)
        UpdateNoticePhase.Checking -> UpdateStatusLine(R.string.settings_update_check_now_title, R.string.update_status_checking)
        UpdateNoticePhase.UpToDate -> UpdateStatusLine(R.string.settings_update_check_now_title, R.string.update_status_up_to_date)
        UpdateNoticePhase.Failed -> UpdateStatusLine(R.string.settings_update_check_now_title, R.string.update_status_failed)
        UpdateNoticePhase.Available ->
            if (status.required) {
                UpdateStatusLine(R.string.settings_update_download_title, R.string.update_status_required, status.availableVersion)
            } else {
                UpdateStatusLine(R.string.settings_update_download_title, R.string.update_status_available, status.availableVersion)
            }
    }
