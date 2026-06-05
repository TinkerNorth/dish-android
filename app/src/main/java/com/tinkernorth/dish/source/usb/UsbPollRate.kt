// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

internal const val MAX_FS_INTERRUPT_PACKET = 64

internal fun computeUsbPollRateHz(
    epInterval: Int,
    epMaxPacketSize: Int,
): Int {
    if (epInterval <= 0) return 0
    val isHighSpeed = epMaxPacketSize > MAX_FS_INTERRUPT_PACKET
    val periodMicros =
        if (isHighSpeed) {
            val exp = (epInterval - 1).coerceIn(0, 15)
            (1L shl exp) * 125L
        } else {
            epInterval.toLong() * 1000L
        }
    if (periodMicros <= 0L) return 0
    return (1_000_000L / periodMicros).toInt()
}

internal fun measuredPollRateHz(
    deltaCount: Long,
    deltaMs: Long,
): Int {
    if (deltaMs <= 0L) return 0
    if (deltaCount <= 0L) return 0
    return ((deltaCount * MILLIS_PER_SEC) / deltaMs).toInt()
}

private const val MILLIS_PER_SEC = 1000L
