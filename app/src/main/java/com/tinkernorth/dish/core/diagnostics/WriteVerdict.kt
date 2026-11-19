// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.diagnostics

// usb_parsers.h OutputRoute, by value, and its name in the bundle.
enum class WriteRoute(
    val native: Int,
    val wire: String,
) {
    NONE(0, "none"),
    INTERRUPT_OUT(1, "interruptOut"),
    SET_REPORT(2, "setReport"),
}

// A Direct pad's last output write, as UsbDirectNative.lastWriteVerdict reports it.
data class WriteVerdict(
    val route: WriteRoute,
    val endpoint: Int,
    val reportId: Int,
    val ok: Boolean,
    val err: Int,
    val ageMs: Long,
)

private const val FIELD_RECORDED = 0
private const val FIELD_ROUTE = 1
private const val FIELD_ENDPOINT = 2
private const val FIELD_REPORT_ID = 3
private const val FIELD_OK = 4
private const val FIELD_ERR = 5
private const val FIELD_AGE = 6
private const val FIELD_COUNT = 7
private const val TRUE = 1L
private const val SHORT_WRITE = -1

// {recorded, route, endpoint, reportId, ok, err, ageMs}; null when nothing was written yet.
fun writeVerdictOf(fields: LongArray?): WriteVerdict? {
    fields ?: return null
    if (fields.size < FIELD_COUNT) return null
    if (fields[FIELD_RECORDED] != TRUE) return null
    val route = WriteRoute.entries.firstOrNull { it.native.toLong() == fields[FIELD_ROUTE] } ?: return null
    return WriteVerdict(
        route = route,
        endpoint = fields[FIELD_ENDPOINT].toInt(),
        reportId = fields[FIELD_REPORT_ID].toInt(),
        ok = fields[FIELD_OK] == TRUE,
        err = fields[FIELD_ERR].toInt(),
        ageMs = fields[FIELD_AGE],
    )
}

// The Linux errno names a USB write fails with; the contract's rows spell them this way.
private val ERRNO_NAMES =
    mapOf(
        1 to "EPERM",
        2 to "ENOENT",
        5 to "EIO",
        11 to "EAGAIN",
        16 to "EBUSY",
        19 to "ENODEV",
        22 to "EINVAL",
        32 to "EPIPE",
        62 to "ETIME",
        71 to "EPROTO",
        75 to "EOVERFLOW",
        108 to "ESHUTDOWN",
        110 to "ETIMEDOUT",
    )

fun errName(err: Int): String {
    if (err == SHORT_WRITE) return "short write"
    return ERRNO_NAMES[err] ?: "errno $err"
}

fun reportIdHex(reportId: Int): String = "%02x".format(reportId and BYTE_MASK)

private const val BYTE_MASK = 0xFF
