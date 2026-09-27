// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

internal const val MAX_FS_INTERRUPT_PACKET = 64

// bInterval is an exponent on the 125 us microframe at high speed, and a count of 1 ms frames below it.
private const val MAX_INTERVAL_EXPONENT = 15
private const val MICROFRAME_US = 125L
private const val US_PER_MS = 1000L
private const val US_PER_SECOND = 1_000_000L

internal fun computeUsbPollRateHz(
    epInterval: Int,
    epMaxPacketSize: Int,
): Int {
    if (epInterval <= 0) return 0
    val isHighSpeed = epMaxPacketSize > MAX_FS_INTERRUPT_PACKET
    val periodMicros =
        if (isHighSpeed) {
            val exp = (epInterval - 1).coerceIn(0, MAX_INTERVAL_EXPONENT)
            (1L shl exp) * MICROFRAME_US
        } else {
            epInterval.toLong() * US_PER_MS
        }
    if (periodMicros <= 0L) return 0
    return (US_PER_SECOND / periodMicros).toInt()
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
