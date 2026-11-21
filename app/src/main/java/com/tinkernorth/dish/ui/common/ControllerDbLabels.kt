// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.content.Context
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.controllerdb.ControllerDbCheckResult
import com.tinkernorth.dish.core.controllerdb.ControllerDbSource
import com.tinkernorth.dish.source.controllerdb.ControllerDbStatus
import com.tinkernorth.dish.ui.diagnostics.agoLabel

@StringRes
internal fun controllerDbSourceLabel(source: ControllerDbSource): Int =
    when (source) {
        ControllerDbSource.BUNDLED -> R.string.controller_db_source_bundled
        ControllerDbSource.CACHE -> R.string.controller_db_source_cache
    }

// Null before the first check.
@StringRes
internal fun controllerDbResultLabel(result: ControllerDbCheckResult?): Int =
    when (result) {
        null -> R.string.controller_db_result_never
        ControllerDbCheckResult.UP_TO_DATE -> R.string.controller_db_result_up_to_date
        ControllerDbCheckResult.UPDATED -> R.string.controller_db_result_updated
        ControllerDbCheckResult.OFFLINE -> R.string.controller_db_result_offline
        ControllerDbCheckResult.INVALID -> R.string.controller_db_result_invalid
        ControllerDbCheckResult.OFF -> R.string.controller_db_result_off
    }

// The result the status line shows: a check under way, then the last result.
@StringRes
internal fun controllerDbStatusResultLabel(status: ControllerDbStatus): Int =
    if (status.checking) R.string.controller_db_result_checking else controllerDbResultLabel(status.lastCheckResult)

// "Check now" does nothing with the switch off, and nothing twice at once.
internal fun controllerDbCheckNowEnabled(status: ControllerDbStatus): Boolean = status.checksEnabled && !status.checking

// "Version 2, downloaded · Up to date, 3 minutes ago": the active copy, then the last check.
internal fun Context.controllerDbStatusText(
    status: ControllerDbStatus,
    nowMs: Long,
): String {
    val copy = getString(R.string.controller_db_version, status.activeVersion, getString(controllerDbSourceLabel(status.source)))
    val result = getString(controllerDbStatusResultLabel(status))
    val checked = status.lastCheckAtMs > 0L && status.checksEnabled && !status.checking
    val check = if (checked) getString(R.string.controller_db_checked_ago, result, agoLabel(nowMs, status.lastCheckAtMs)) else result
    return getString(R.string.controller_db_status, copy, check)
}
