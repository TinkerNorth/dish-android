// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.connections

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.source.connection.moonlight.MoonlightTrustState
import com.tinkernorth.dish.ui.main.holdsPairing

// Known hosts first (from the composer summaries), then discovered hosts not already known.
// The trust word is derived from what we already hold: a session that is up or a mutual-TLS
// call that went through proves the pairing stands, a stored record means it is remembered but
// unverified this visit, and anything else has never been paired. Nothing here probes; the
// binding flow does that, and hands the result back through [verifiedIds].
fun moonlightRows(
    conns: List<ConnectionSummary>,
    discovered: List<MoonlightHost>,
    pairedIds: Set<String> = emptySet(),
    verifiedIds: Set<String> = emptySet(),
): List<MoonlightRow> {
    val known = conns.filter { it.kind == ConnectionKind.MOONLIGHT }
    val knownIds = known.mapTo(mutableSetOf()) { it.id }
    return buildList {
        known.forEach { summary ->
            add(
                MoonlightRow.Known(
                    summary = summary,
                    trust = moonlightTrustFor(summary, summary.id in pairedIds, summary.id in verifiedIds),
                    controllerCount = summary.boundSlotIds.size,
                ),
            )
        }
        discovered.forEach { host ->
            if (host.id !in knownIds) add(MoonlightRow.Discovered(host))
        }
    }
}

@StringRes
internal fun moonlightPairActionRes(trust: MoonlightTrustState): Int =
    if (trust.holdsPairing()) R.string.action_repair_short else R.string.ml_action_pair

@ColorRes
internal fun moonlightTrustColorRes(trust: MoonlightTrustState): Int =
    if (trust.holdsPairing()) R.color.colorSuccess else R.color.colorMuted

// PAIRED is proven this visit (live session or an authorised mutual-TLS call); REMEMBERED
// holds a stored record without fresh proof. Both wear the "Paired" chip; the split still
// decides nothing user-visible here beyond being available to callers that probe.
internal fun moonlightTrustFor(
    summary: ConnectionSummary,
    paired: Boolean,
    verified: Boolean = false,
): MoonlightTrustState =
    when {
        summary.live == LinkState.Connected || summary.live == LinkState.Unstable -> MoonlightTrustState.PAIRED
        verified -> MoonlightTrustState.PAIRED
        paired -> MoonlightTrustState.REMEMBERED
        else -> MoonlightTrustState.NOT_PAIRED
    }
