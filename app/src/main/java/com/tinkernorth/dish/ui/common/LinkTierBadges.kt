// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.content.Context
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkTier
import com.tinkernorth.dish.composer.LinkTiers
import com.tinkernorth.dish.ui.main.PillSpec
import com.tinkernorth.dish.ui.main.PillTone

@StringRes
fun tierLabelRes(tier: LinkTier): Int =
    when (tier) {
        LinkTier.FASTEST -> R.string.link_tier_fastest
        LinkTier.FAST -> R.string.link_tier_fast
        LinkTier.BASIC -> R.string.link_tier_basic
    }

@DrawableRes
fun tierIconRes(tier: LinkTier): Int =
    when (tier) {
        LinkTier.FASTEST -> R.drawable.ic_bolt
        LinkTier.FAST -> R.drawable.ic_wifi
        LinkTier.BASIC -> R.drawable.ic_bluetooth
    }

internal fun tierTone(tier: LinkTier): PillTone =
    when (tier) {
        LinkTier.FASTEST -> PillTone.ON
        LinkTier.FAST -> PillTone.FACT
        LinkTier.BASIC -> PillTone.CAP
    }

internal fun Context.tierPillSpec(tier: LinkTier): PillSpec = PillSpec(getString(tierLabelRes(tier)), tierIconRes(tier), tierTone(tier))

internal fun Context.tierPillSpec(kind: ConnectionKind): PillSpec = tierPillSpec(LinkTiers.forKind(kind))

internal fun TextView.paintTierBadge(tier: LinkTier) {
    val tone = tierTone(tier)
    setText(tierLabelRes(tier))
    setBackgroundResource(tone.background)
    setTextColor(context.getColor(tone.foreground))
}
