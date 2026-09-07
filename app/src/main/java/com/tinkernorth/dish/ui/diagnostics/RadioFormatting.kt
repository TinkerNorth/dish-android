// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import android.content.Context
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.bluetooth.BluetoothLinkType
import com.tinkernorth.dish.source.system.BluetoothAdapterState
import com.tinkernorth.dish.source.system.WifiBand
import com.tinkernorth.dish.source.system.WifiGeneration
import com.tinkernorth.dish.source.system.WifiLink
import com.tinkernorth.dish.source.usb.UsbEndpointFacts

internal fun Context.wifiBandLabel(band: WifiBand): String =
    when (band) {
        // 2.4 GHz is the one worth calling out: it is the band that makes streaming laggy.
        WifiBand.GHZ_2_4 -> getString(R.string.diagnostics_wifi_band_warn)
        WifiBand.GHZ_5 -> "5 GHz"
        WifiBand.GHZ_6 -> "6 GHz"
        WifiBand.UNKNOWN -> getString(R.string.diagnostics_unknown)
    }

internal fun Context.wifiGenerationLabel(generation: WifiGeneration): String? =
    when (generation) {
        WifiGeneration.UNKNOWN -> null
        WifiGeneration.LEGACY -> getString(R.string.diagnostics_wifi_gen_legacy)
        WifiGeneration.WIFI_4 -> getString(R.string.diagnostics_wifi_gen, 4)
        WifiGeneration.WIFI_5 -> getString(R.string.diagnostics_wifi_gen, 5)
        WifiGeneration.WIFI_6 -> getString(R.string.diagnostics_wifi_gen, 6)
        WifiGeneration.WIFI_7 -> getString(R.string.diagnostics_wifi_gen, 7)
    }

internal fun Context.wifiLines(radios: RadioFacts): List<String> {
    val link = radios.wifi ?: return listOf(getString(R.string.diagnostics_wifi_none))
    val lines = mutableListOf<String>()
    val band = wifiBandLabel(WifiBand.fromFrequencyMhz(link.frequencyMhz))
    lines += diagKv(R.string.diagnostics_signal, getString(R.string.diagnostics_signal_value, link.rssiDbm, band))
    lines += diagKv(R.string.diagnostics_link_speed, linkSpeedValue(link))
    wifiGenerationLabel(link.generation)?.let { lines += diagKv(R.string.diagnostics_wifi_generation, it) }
    link.ipv4?.let { lines += diagKv(R.string.diagnostics_phone_ip, getString(R.string.diagnostics_ip_value, it, link.prefixLength)) }
    val lock = getString(if (radios.lowLatencyLockHeld) R.string.diagnostics_lock_held else R.string.diagnostics_lock_released)
    lines += diagKv(R.string.diagnostics_low_latency_lock, lock)
    lines += diagKv(R.string.diagnostics_wifi_drops, radios.wifiDrops.toString())
    return lines
}

private fun Context.linkSpeedValue(link: WifiLink): String =
    if (link.txLinkSpeedMbps > 0 && link.rxLinkSpeedMbps > 0) {
        getString(R.string.diagnostics_link_speed_txrx, link.txLinkSpeedMbps, link.rxLinkSpeedMbps)
    } else {
        getString(R.string.diagnostics_mbps, link.linkSpeedMbps)
    }

internal fun Context.bluetoothLinkTypeLabel(type: BluetoothLinkType): String? =
    when (type) {
        BluetoothLinkType.UNKNOWN -> null
        BluetoothLinkType.CLASSIC -> getString(R.string.diagnostics_bt_classic)
        BluetoothLinkType.LOW_ENERGY -> getString(R.string.diagnostics_bt_le)
        BluetoothLinkType.DUAL -> getString(R.string.diagnostics_bt_dual)
    }

internal fun Context.bluetoothLines(
    radios: RadioFacts,
    controllers: List<ControllerDiag>,
    hosts: List<HostDiag>,
): List<String> {
    val lines = mutableListOf<String>()
    val adapter =
        when (radios.bluetoothAdapter) {
            BluetoothAdapterState.ON -> R.string.diagnostics_bt_on
            BluetoothAdapterState.OFF -> R.string.diagnostics_bt_off
            BluetoothAdapterState.UNSUPPORTED -> R.string.diagnostics_bt_unsupported
        }
    lines += diagKv(R.string.diagnostics_bt_adapter, getString(adapter))
    val permission = if (radios.bluetoothPermission.connectMissing) R.string.diagnostics_missing else R.string.diagnostics_granted
    lines += diagKv(R.string.diagnostics_bt_permission, getString(permission))
    val pads = controllers.filter { it.transport == Transport.Bluetooth }
    lines += diagKv(R.string.diagnostics_bt_pads, pads.size.toString())
    pads.forEach { pad ->
        val type = pad.facts?.let { bluetoothLinkTypeLabel(it.linkType) }
        lines += type?.let { getString(R.string.diagnostics_joined, pad.name, it) } ?: pad.name
    }
    val btHosts = hosts.filter { it.kind == ConnectionKind.BLUETOOTH }
    if (btHosts.isEmpty()) {
        lines += diagKv(R.string.diagnostics_bt_host_role, getString(R.string.diagnostics_bt_host_none))
    }
    btHosts.forEach { lines += diagKv(R.string.diagnostics_bt_host_role, bluetoothHostValue(it)) }
    return lines
}

internal fun Context.bluetoothHostValue(host: HostDiag): String {
    val state = host.bluetooth?.state
    val profile = state?.profileName ?: host.btProfile ?: host.label
    val peer = state?.connectedName
    return when {
        state?.connected == true && peer != null -> getString(R.string.diagnostics_joined, profile, peer)
        state?.acquiring == true -> getString(R.string.diagnostics_joined, profile, getString(R.string.diagnostics_bt_acquiring))
        state?.autoReconnecting == true -> getString(R.string.diagnostics_joined, profile, getString(R.string.diagnostics_bt_reconnecting))
        state?.registered == true -> getString(R.string.diagnostics_joined, profile, getString(R.string.diagnostics_bt_waiting))
        else -> getString(R.string.diagnostics_joined, profile, getString(R.string.chip_status_offline))
    }
}

internal fun Context.endpointValue(facts: UsbEndpointFacts): String {
    val base = getString(R.string.diagnostics_endpoint_value, facts.pollRateHz, facts.maxPacketSize, facts.intervalRaw)
    return if (facts.highSpeed) getString(R.string.diagnostics_joined, base, getString(R.string.diagnostics_high_speed)) else base
}

internal fun Context.usbLines(controllers: List<ControllerDiag>): List<String> {
    val usb = controllers.filter { it.transport == Transport.Usb }
    val direct = usb.count { it.isUsbSynthetic }
    val lines =
        mutableListOf(diagKv(R.string.diagnostics_usb_pads, getString(R.string.diagnostics_usb_pads_value, usb.size - direct, direct)))
    usb.forEach { pad ->
        val endpoint = pad.facts?.endpoint?.let { endpointValue(it) }
        lines += endpoint?.let { getString(R.string.diagnostics_joined, pad.name, it) } ?: pad.name
    }
    return lines
}
