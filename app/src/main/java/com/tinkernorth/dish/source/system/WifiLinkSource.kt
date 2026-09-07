// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

data class WifiLink(
    val rssiDbm: Int,
    val linkSpeedMbps: Int,
    val frequencyMhz: Int,
    val txLinkSpeedMbps: Int = UNKNOWN_SPEED,
    val rxLinkSpeedMbps: Int = UNKNOWN_SPEED,
    val generation: WifiGeneration = WifiGeneration.UNKNOWN,
    val ipv4: String? = null,
    val prefixLength: Int = 0,
) {
    companion object {
        const val UNKNOWN_SPEED = -1
    }
}

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

enum class WifiGeneration {
    UNKNOWN,
    LEGACY,
    WIFI_4,
    WIFI_5,
    WIFI_6,
    WIFI_7,
    ;

    companion object {
        private const val STANDARD_LEGACY = 1
        private const val STANDARD_11N = 4
        private const val STANDARD_11AC = 5
        private const val STANDARD_11AX = 6
        private const val STANDARD_11BE = 8

        fun fromWifiStandard(standard: Int): WifiGeneration =
            when (standard) {
                STANDARD_LEGACY -> LEGACY
                STANDARD_11N -> WIFI_4
                STANDARD_11AC -> WIFI_5
                STANDARD_11AX -> WIFI_6
                STANDARD_11BE -> WIFI_7
                else -> UNKNOWN
            }
    }
}

object WifiSubnet {
    private const val IPV4_BITS = 32
    private const val OCTET_BITS = 8
    private const val OCTET_MASK = 0xFF

    // Null when either side is not a dotted IPv4 literal (a hostname, an IPv6 address, no link).
    fun sameSubnet(
        phoneIpv4: String?,
        prefixLength: Int,
        hostIp: String,
    ): Boolean? {
        val phone = parseIpv4(phoneIpv4 ?: return null) ?: return null
        val host = parseIpv4(hostIp) ?: return null
        if (prefixLength <= 0 || prefixLength > IPV4_BITS) return null
        val mask = if (prefixLength == IPV4_BITS) -1 else (-1 shl (IPV4_BITS - prefixLength))
        return (phone and mask) == (host and mask)
    }

    private fun parseIpv4(text: String): Int? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet < 0 || octet > OCTET_MASK) return null
            value = (value shl OCTET_BITS) or octet
        }
        return value
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
            val address = phoneAddress()
            return WifiLink(
                rssiDbm = info.rssi,
                linkSpeedMbps = info.linkSpeed,
                frequencyMhz = info.frequency,
                txLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.txLinkSpeedMbps else WifiLink.UNKNOWN_SPEED,
                rxLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.rxLinkSpeedMbps else WifiLink.UNKNOWN_SPEED,
                generation = generationOf(info),
                ipv4 = address?.first,
                prefixLength = address?.second ?: 0,
            )
        }

        private fun generationOf(info: WifiInfo): WifiGeneration =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WifiGeneration.fromWifiStandard(info.wifiStandard)
            } else {
                WifiGeneration.UNKNOWN
            }

        private fun phoneAddress(): Pair<String, Int>? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val link = cm?.activeNetwork?.let { network -> runCatching { cm.getLinkProperties(network) }.getOrNull() }
            val v4 = link?.linkAddresses?.firstOrNull { it.address is Inet4Address }
            return v4?.address?.hostAddress?.let { it to v4.prefixLength }
        }
    }
