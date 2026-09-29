// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.connection.ConnectionError
import com.tinkernorth.dish.source.connection.moonlight.MoonlightError
import com.tinkernorth.dish.ui.main.StringLookup

/** How a connection error reads: a title that takes the satellite's name as its one argument, and the body. */
internal data class ConnectionErrorWords(
    @StringRes val title: Int,
    val body: String,
)

internal fun connectionErrorWords(
    error: ConnectionError,
    strings: StringLookup,
): ConnectionErrorWords =
    when (error) {
        ConnectionError.ServerUnreachable ->
            ConnectionErrorWords(R.string.notif_server_unreachable_title, strings.format(R.string.conn_error_server_unreachable))
        ConnectionError.RepairNeeded ->
            ConnectionErrorWords(R.string.conn_error_title_repair_needed, strings.format(R.string.conn_error_repair_needed))
        ConnectionError.IdentityChanged ->
            ConnectionErrorWords(R.string.conn_error_title_identity_changed, strings.format(R.string.conn_error_identity_changed))
        ConnectionError.WireFailed ->
            ConnectionErrorWords(R.string.conn_error_title_wire_failed, strings.format(R.string.conn_error_wire_failed))
        ConnectionError.SatelliteUpdateRequired ->
            ConnectionErrorWords(
                R.string.conn_error_title_satellite_update_required,
                strings.format(R.string.conn_error_satellite_update_required),
            )
        ConnectionError.AppUpdateRequired ->
            ConnectionErrorWords(R.string.conn_error_title_app_update_required, strings.format(R.string.conn_error_app_update_required))
        ConnectionError.ApprovalDeclined ->
            ConnectionErrorWords(R.string.conn_error_title_approval_declined, strings.format(R.string.conn_error_approval_declined))
        ConnectionError.ApprovalTimedOut ->
            ConnectionErrorWords(R.string.conn_error_title_approval_timed_out, strings.format(R.string.conn_error_approval_timed_out))
        ConnectionError.PairingFailed ->
            ConnectionErrorWords(R.string.conn_error_title_pairing_failed, strings.format(R.string.conn_error_pairing_failed))
        is ConnectionError.PairingRefused ->
            ConnectionErrorWords(
                R.string.conn_error_title_pairing_failed,
                strings.format(R.string.conn_error_pairing_refused, error.reason),
            )
        ConnectionError.SessionFailed ->
            ConnectionErrorWords(R.string.conn_error_title_session_failed, strings.format(R.string.conn_error_session_failed))
        is ConnectionError.SessionRefused ->
            ConnectionErrorWords(
                R.string.conn_error_title_session_failed,
                strings.format(R.string.conn_error_session_refused, error.reason),
            )
        is ConnectionError.ApplyFailed ->
            ConnectionErrorWords(
                R.string.conn_error_title_apply_failed,
                strings.format(R.string.conn_error_apply_failed, error.serverName, error.failures),
            )
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
