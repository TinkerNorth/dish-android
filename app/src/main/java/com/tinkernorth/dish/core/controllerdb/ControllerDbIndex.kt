// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// The families this build's Direct paths compile, by their docs/controller-db.md section 4.1 ids,
// and the value of each in usb_parsers.h's Parser.
val NATIVE_PARSERS: Map<String, Int> =
    mapOf(
        "xinput360" to 1,
        "xboxOneGip" to 2,
        "dualshock4" to 3,
        "dualsense" to 4,
        "switchProUsb" to 5,
        "stadia" to 6,
        FAMILY_HID to 7,
        "xinput360Wireless" to 8,
        "steamController" to 9,
        "switchN64Usb" to 10,
        "steamTriton" to 11,
        "steamControllerBle" to 12,
    )

// output.init.kind onto usb_parsers.h's InitKind.
val NATIVE_INIT_KINDS: Map<String, Int> =
    mapOf(
        INIT_NONE to 0,
        "xboxOnePowerOn" to 1,
        "switchProHandshake" to 2,
        "xboxOneS" to 3,
        "steamQuiet" to 4,
        "steamTritonQuiet" to 5,
    )

// input.mapping onto usb_hid_descriptor.h's ButtonOrder.
val NATIVE_BUTTON_ORDERS: Map<String, Int> =
    mapOf(
        MAPPING_WESTERN to 0,
        "switch" to 1,
        "eightbitdo" to 2,
        "playstationClassic" to 3,
    )

// No op is compiled in schema 1: a record that needs one is inert here.
val COMPILED_OPS: Set<String> = emptySet()

private const val OS_ANDROID = "android"
private const val ISSUE_OPEN = "open"
private const val HEX_RADIX = 16
private const val USB_ID_DIGITS = 4
private const val IDENTITY_PARTS = 2

// One immutable view of a controller database: every record by the vid:pid it lists, and the
// strings that name them. Built once per database; a newer database replaces the whole index.
class ControllerDbIndex(
    val db: ControllerDbFile,
    val strings: ControllerStrings,
) {
    val dbVersion: Int get() = db.dbVersion

    private val byUsb: Map<String, List<ControllerRecord>> = byIdentity(db.records) { it.identity.usb }
    private val byBluetooth: Map<String, List<ControllerRecord>> = byIdentity(db.records) { it.identity.bluetooth }

    fun usbRecords(identity: String): List<ControllerRecord> = byUsb[identity].orEmpty()

    fun recordForUsb(
        vendorId: Int,
        productId: Int,
    ): ControllerRecord? = byUsb[identityKey(vendorId, productId)]?.firstOrNull()

    fun recordForBluetooth(
        vendorId: Int,
        productId: Int,
    ): ControllerRecord? = byBluetooth[identityKey(vendorId, productId)]?.firstOrNull()
}

private inline fun byIdentity(
    records: List<ControllerRecord>,
    identities: (ControllerRecord) -> List<String>,
): Map<String, List<ControllerRecord>> {
    val out = LinkedHashMap<String, MutableList<ControllerRecord>>()
    for (record in records) {
        for (identity in identities(record)) out.getOrPut(identity.lowercase()) { mutableListOf() }.add(record)
    }
    return out
}

fun identityKey(
    vendorId: Int,
    productId: Int,
): String = "%04x:%04x".format(vendorId and USB_ID_MASK, productId and USB_ID_MASK)

private const val USB_ID_MASK = 0xFFFF

fun parseIdentity(identity: String): Pair<Int, Int>? {
    val parts = identity.split(":")
    if (parts.size != IDENTITY_PARTS) return null
    val isFourDigits = parts.all { it.length == USB_ID_DIGITS }
    if (!isFourDigits) return null
    val vid = parts[0].toIntOrNull(HEX_RADIX) ?: return null
    val pid = parts[1].toIntOrNull(HEX_RADIX) ?: return null
    return vid to pid
}

fun mappingName(record: ControllerRecord): String {
    val mapping = record.input.mapping as? JsonPrimitive ?: return MAPPING_WESTERN
    return if (mapping.isString) mapping.content else MAPPING_WESTERN
}

// Section 6.6: a record that needs a family, op, init kind or button order this build does not
// have is kept for its name and issues, and its input and output are never applied.
fun isInert(record: ControllerRecord): Boolean {
    val family = record.input.protocol.ref
    val needsAMissingFamily = record.requires.families.any { it !in NATIVE_PARSERS }
    val needsAMissingOp = record.requires.ops.any { it !in COMPILED_OPS }
    val speaksAMissingFamily = family !in NATIVE_PARSERS
    val initsAMissingWay = record.output.init.kind !in NATIVE_INIT_KINDS
    val ordersAMissingWay = family == FAMILY_HID && mappingName(record) !in NATIVE_BUTTON_ORDERS
    return needsAMissingFamily || needsAMissingOp || speaksAMissingFamily || initsAMissingWay || ordersAMissingWay
}

// Section 4.1: an issue shows while it is open, when its transport is this one and its device and
// os are this host's or absent; os.android is the Android release, as a number or a version string.
fun issuesFor(
    record: ControllerRecord,
    transport: String,
    deviceModel: String,
    androidMajor: Int,
): List<RecordIssue> = record.support.issues.filter { issueMatches(it, transport, deviceModel, androidMajor) }

private fun issueMatches(
    issue: RecordIssue,
    transport: String,
    deviceModel: String,
    androidMajor: Int,
): Boolean {
    val isOpen = issue.status == ISSUE_OPEN
    val isThisTransport = issue.transport == null || issue.transport == transport
    val isThisDevice = issue.device == null || issue.device == deviceModel
    val isThisOs = osMatches(issue, androidMajor)
    return isOpen && isThisTransport && isThisDevice && isThisOs
}

private fun osMatches(
    issue: RecordIssue,
    androidMajor: Int,
): Boolean {
    val os = issue.os ?: return true
    val android = os[OS_ANDROID] as? JsonPrimitive ?: return false
    val major = android.intOrNull ?: android.content.takeWhile { it.isDigit() }.toIntOrNull()
    return major == androidMajor
}
