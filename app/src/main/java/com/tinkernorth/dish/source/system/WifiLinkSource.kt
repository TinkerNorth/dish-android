// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class WifiLink(
    val rssiDbm: Int,
    val linkSpeedMbps: Int,
    val frequencyMhz: Int,
)

// Frequency to marketing band. Pure so the 2.4 GHz warning threshold is testable.
enum class WifiBand {
    GHZ_2_4,
    GHZ_5,
    GHZ_6,
    UNKNOWN,
    ;

    companion object {
        fun fromFrequencyMhz(frequencyMhz: Int): WifiBand =
            when (frequencyMhz) {
                in 2400..2500 -> GHZ_2_4
                in 4900..5899 -> GHZ_5
                in 5925..7125 -> GHZ_6
                else -> UNKNOWN
            }
    }
}

/**
 * On-demand Wi-Fi link probe for the diagnostics screen. RSSI, link speed, and frequency
 * are populated without a location grant (only SSID/BSSID are redacted on API 29+), so no
 * new permission flow is needed. Deprecated API accepted: the NetworkCallback replacement
 * demands a live callback registration for what is here a 2 s pull on one screen.
 */
@Singleton
class WifiLinkSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        @Suppress("DEPRECATION")
        fun read(): WifiLink? {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val info = runCatching { wifi.connectionInfo }.getOrNull() ?: return null
            if (info.networkId == -1 && info.rssi >= 0) return null
            return WifiLink(
                rssiDbm = info.rssi,
                linkSpeedMbps = info.linkSpeed,
                frequencyMhz = info.frequency,
            )
        }
    }
