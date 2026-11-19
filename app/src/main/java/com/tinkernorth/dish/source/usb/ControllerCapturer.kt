// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

import android.hardware.usb.UsbDeviceConnection
import com.tinkernorth.dish.composer.BundleCapture
import com.tinkernorth.dish.composer.BundleCapturedReport
import com.tinkernorth.dish.composer.BundleHidDescriptor
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import javax.inject.Inject
import javax.inject.Singleton

// "Capture this controller" for a Direct pad: its USB descriptors and string descriptors, its HID
// report descriptor (asked of every family, for the capture only), and twenty input reports at
// rest. The device descriptors come from the claim's own connection; the reports from the pad's
// poller, which keeps them while a capture is armed.
@Singleton
class ControllerCapturer
    @Inject
    constructor(
        private val usb: UsbGamepadManager,
        private val native: PhysicalInputNative,
        private val json: Json,
    ) {
        // Null when the pad is not a Direct claim any more.
        suspend fun capture(syntheticId: Int): BundleCapture? {
            val descriptors = withContext(Dispatchers.Main) { descriptorsOf(syntheticId) } ?: return null
            if (!native.armReportCapture(syntheticId)) return null
            val reports = awaitReports(syntheticId)
            return descriptors.copy(restReports = reports)
        }

        // The claim's connection is the manager's, confined to the main thread. The raw
        // descriptors open with the device descriptor, then the configuration with every
        // interface and endpoint.
        private fun descriptorsOf(syntheticId: Int): BundleCapture? {
            val (connection, intf) = usb.claimedPadFor(syntheticId) ?: return null
            val raw = connection.rawDescriptors
            val (device, configuration) = splitRawDescriptors(raw)
            return BundleCapture(
                device = device,
                configuration = configuration,
                strings = stringDescriptorsOf(connection, raw),
                hidReportDescriptors = listOfNotNull(hidDescriptorOf(connection, intf.id)),
                restReports = emptyList(),
            )
        }

        private fun hidDescriptorOf(
            connection: UsbDeviceConnection,
            interfaceNumber: Int,
        ): BundleHidDescriptor? =
            readControl(connection, REQUEST_IN_INTERFACE, HID_REPORT_DESCRIPTOR, interfaceNumber)?.let {
                BundleHidDescriptor(interfaceNumber, it)
            }

        // The language table, then the manufacturer's and the product's strings; the serial number
        // identifies one unit and is never read.
        private fun stringDescriptorsOf(
            connection: UsbDeviceConnection,
            raw: ByteArray?,
        ): Map<String, String> {
            val indices = listOfNotNull(0, raw?.indexAt(I_MANUFACTURER), raw?.indexAt(I_PRODUCT)).filter { it >= 0 }.distinct()
            val language = readLanguage(connection)
            return indices
                .mapNotNull { index ->
                    val lang = if (index == 0) 0 else language
                    readControl(connection, REQUEST_IN_DEVICE, STRING_DESCRIPTOR or index, lang)?.let { index.toString() to it }
                }.toMap()
        }

        private fun readLanguage(connection: UsbDeviceConnection): Int {
            val buffer = ByteArray(MAX_STRING_BYTES)
            val n = connection.controlTransfer(REQUEST_IN_DEVICE, GET_DESCRIPTOR, STRING_DESCRIPTOR, 0, buffer, buffer.size, TIMEOUT_MS)
            val hasALanguage = n >= LANGUAGE_TABLE_FIRST_ID_END
            if (!hasALanguage) return 0
            return (buffer[2].toInt() and BYTE_MASK) or ((buffer[3].toInt() and BYTE_MASK) shl BITS_PER_BYTE)
        }

        private fun readControl(
            connection: UsbDeviceConnection,
            requestType: Int,
            value: Int,
            index: Int,
        ): String? {
            val buffer = ByteArray(MAX_DESCRIPTOR_BYTES)
            val n = connection.controlTransfer(requestType, GET_DESCRIPTOR, value, index, buffer, buffer.size, TIMEOUT_MS)
            if (n <= 0) return null
            return hexOf(buffer.copyOf(n))
        }

        private suspend fun awaitReports(syntheticId: Int): List<BundleCapturedReport> {
            var reports = emptyList<BundleCapturedReport>()
            repeat(POLLS) {
                reports = parseCapturedReports(json, native.capturedReportsJson(syntheticId), System.currentTimeMillis())
                if (reports.size >= REPORTS_WANTED) return reports
                delay(POLL_MS)
            }
            return reports
        }

        private companion object {
            const val REQUEST_IN_DEVICE = 0x80
            const val REQUEST_IN_INTERFACE = 0x81
            const val GET_DESCRIPTOR = 0x06
            const val STRING_DESCRIPTOR = 0x0300
            const val HID_REPORT_DESCRIPTOR = 0x2200
            const val I_MANUFACTURER = 14
            const val I_PRODUCT = 15
            const val MAX_STRING_BYTES = 255
            const val MAX_DESCRIPTOR_BYTES = 4096
            const val LANGUAGE_TABLE_FIRST_ID_END = 4
            const val TIMEOUT_MS = 250
            const val REPORTS_WANTED = 20
            const val POLLS = 20
            const val POLL_MS = 100L
            const val BYTE_MASK = 0xFF
            const val BITS_PER_BYTE = 8
        }
    }

private const val HEX_BYTE_MASK = 0xFF

// The device descriptor (its own length in its first byte) and the configuration after it, as hex.
internal fun splitRawDescriptors(raw: ByteArray?): Pair<String?, String?> {
    raw ?: return null to null
    val deviceLength = ((raw.firstOrNull()?.toInt() ?: 0) and HEX_BYTE_MASK).coerceAtMost(raw.size)
    val device = hexOf(raw.copyOfRange(0, deviceLength))
    val configuration = hexOf(raw.copyOfRange(deviceLength, raw.size))
    return device.ifEmpty { null } to configuration.ifEmpty { null }
}

internal fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and HEX_BYTE_MASK) }

private fun ByteArray.indexAt(offset: Int): Int? = getOrNull(offset)?.toInt()?.and(HEX_BYTE_MASK)?.takeIf { it > 0 }

// [{"ageMs","hex"}] from native, each age turned into a time on the wall clock at nowMs.
internal fun parseCapturedReports(
    json: Json,
    raw: String,
    nowMs: Long,
): List<BundleCapturedReport> =
    try {
        json.parseToJsonElement(raw).jsonArray.map { element ->
            val report = element.jsonObject
            BundleCapturedReport(
                atMs = nowMs - (report["ageMs"]?.jsonPrimitive?.long ?: 0L),
                hex = report["hex"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    } catch (e: SerializationException) {
        emptyList()
    } catch (e: IllegalArgumentException) {
        emptyList()
    }
