// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.content.Context
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.connection.ConnectionError
import com.tinkernorth.dish.source.connection.moonlight.MoonlightError
import com.tinkernorth.dish.ui.main.StringLookup

internal fun connectionErrorText(
    error: ConnectionError,
    strings: StringLookup,
): String =
    when (error) {
        ConnectionError.ServerUnreachable -> strings.format(R.string.conn_error_server_unreachable)
        ConnectionError.RepairNeeded -> strings.format(R.string.conn_error_repair_needed)
        ConnectionError.IdentityChanged -> strings.format(R.string.conn_error_identity_changed)
        ConnectionError.WireFailed -> strings.format(R.string.conn_error_wire_failed)
        ConnectionError.SatelliteUpdateRequired -> strings.format(R.string.conn_error_satellite_update_required)
        ConnectionError.AppUpdateRequired -> strings.format(R.string.conn_error_app_update_required)
        ConnectionError.ApprovalDeclined -> strings.format(R.string.conn_error_approval_declined)
        ConnectionError.ApprovalTimedOut -> strings.format(R.string.conn_error_approval_timed_out)
        ConnectionError.PairingFailed -> strings.format(R.string.conn_error_pairing_failed)
        is ConnectionError.PairingRefused -> strings.format(R.string.conn_error_pairing_refused, error.reason)
        ConnectionError.SessionFailed -> strings.format(R.string.conn_error_session_failed)
        is ConnectionError.SessionRefused -> strings.format(R.string.conn_error_session_refused, error.reason)
        is ConnectionError.ApplyFailed -> strings.format(R.string.conn_error_apply_failed, error.serverName, error.failures)
    }

internal fun moonlightErrorText(
    error: MoonlightError,
    strings: StringLookup,
): String =
    when (error) {
        is MoonlightError.NoHostAnswered -> strings.format(R.string.ml_error_no_host_answered, error.address)
        is MoonlightError.NoAppsAvailable -> strings.format(R.string.ml_error_no_apps, error.hostName)
    }

internal fun appCloseRequestedText(
    hostName: String,
    strings: StringLookup,
): String = strings.format(R.string.ml_notice_app_close_requested, hostName)

internal class ContextStringLookup(
    private val context: Context,
) : StringLookup {
    override fun format(
        @StringRes res: Int,
        vararg args: Any,
    ): String = context.getString(res, *args)
}
