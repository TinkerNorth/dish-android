// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.bluetooth.BtStaleReason
import com.tinkernorth.dish.source.system.BluetoothAdapterState
import com.tinkernorth.dish.source.system.BluetoothPermissionBannerVariant
import com.tinkernorth.dish.source.system.NetworkState
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val MS_PER_SECOND = 1000L
private const val CLOCK_PATTERN = "%02d:%02d"

internal enum class BtAdapterBanner { UNSUPPORTED, OFF }

internal fun btAdapterBannerFor(state: BluetoothAdapterState): BtAdapterBanner? =
    when (state) {
        BluetoothAdapterState.ON -> null
        BluetoothAdapterState.UNSUPPORTED -> BtAdapterBanner.UNSUPPORTED
        BluetoothAdapterState.OFF -> BtAdapterBanner.OFF
    }

internal enum class NetworkBanner { NO_NETWORK, CELLULAR_ONLY }

internal fun networkBannerFor(state: NetworkState): NetworkBanner? =
    when (state) {
        NetworkState.WIFI -> null
        NetworkState.NONE -> NetworkBanner.NO_NETWORK
        NetworkState.CELLULAR -> NetworkBanner.CELLULAR_ONLY
    }

internal data class StaleBtCopy(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)

internal fun staleBtCopy(reason: BtStaleReason): StaleBtCopy =
    when (reason) {
        BtStaleReason.KEY_MISSING -> StaleBtCopy(R.string.notif_bt_key_missing_title, R.string.notif_bt_key_missing_body)
        BtStaleReason.BOND_REMOVED -> StaleBtCopy(R.string.notif_bt_bond_removed_title, R.string.notif_bt_bond_removed_body)
    }

internal data class BtPermissionBannerCopy(
    val severity: DishNotification.Severity,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)

internal fun btPermissionBannerCopy(variant: BluetoothPermissionBannerVariant): BtPermissionBannerCopy =
    when (variant) {
        BluetoothPermissionBannerVariant.CONNECT ->
            BtPermissionBannerCopy(
                DishNotification.Severity.WARN,
                R.string.notif_bt_permission_title,
                R.string.notif_bt_permission_body,
            )
        BluetoothPermissionBannerVariant.SCAN ->
            BtPermissionBannerCopy(
                DishNotification.Severity.INFO,
                R.string.notif_bt_scan_permission_title,
                R.string.notif_bt_scan_permission_body,
            )
    }

@StringRes
internal fun btConnectingLabelRes(state: BluetoothGamepadRegistry.SlotState): Int =
    when {
        state.registered -> R.string.bt_row_pair_from_host
        state.acquiring -> R.string.bt_row_acquiring
        else -> R.string.bt_row_waiting
    }

internal fun secondsLeft(
    untilMs: Long?,
    nowMs: Long,
): Int {
    val until = untilMs ?: return 0
    val remainingMs = until - nowMs
    return (remainingMs / MS_PER_SECOND).toInt().coerceAtLeast(0)
}

@StringRes
internal fun pairSubtitleRes(serverName: String): Int =
    if (serverName.isNotEmpty()) R.string.pair_dialog_subtitle_named else R.string.pair_dialog_subtitle

internal sealed interface DiscoveryEmptyCopy {
    data object NeverScanned : DiscoveryEmptyCopy

    data class NoResultsAt(
        val clock: String,
    ) : DiscoveryEmptyCopy
}

internal fun discoveryEmptyCopy(
    lastScanAtMs: Long?,
    zone: TimeZone = TimeZone.getDefault(),
): DiscoveryEmptyCopy {
    val lastScan = lastScanAtMs ?: return DiscoveryEmptyCopy.NeverScanned
    return DiscoveryEmptyCopy.NoResultsAt(formatClock(lastScan, zone))
}

internal fun formatClock(
    epochMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
): String {
    val cal = Calendar.getInstance(zone)
    cal.timeInMillis = epochMs
    return String.format(Locale.ROOT, CLOCK_PATTERN, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}
