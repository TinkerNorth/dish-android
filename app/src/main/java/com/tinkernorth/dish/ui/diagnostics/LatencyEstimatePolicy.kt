// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

object LatencyEstimatePolicy {
    private const val MS_PER_SECOND = 1000.0

    // Half the report interval is the average wait for the next report; the phone path and the
    // one-way network time then add on top. Unknown parts stay null rather than reading as zero.
    fun estimate(
        pollRateHz: Int,
        phonePathMs: Double?,
        rttMs: Double?,
    ): LatencyEstimate =
        LatencyEstimate(
            pollHalfMs = if (pollRateHz > 0) MS_PER_SECOND / pollRateHz / 2 else null,
            phonePathMs = phonePathMs,
            networkOneWayMs = rttMs?.let { it / 2 },
        )
}
