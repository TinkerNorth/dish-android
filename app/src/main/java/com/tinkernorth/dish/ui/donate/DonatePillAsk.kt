// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.donate

internal fun donatePillAsk(
    offer: TipOffer,
    dismissed: Boolean,
    isSupporter: (SupporterCheck) -> Boolean,
): TipOffer.Ask? {
    val ask = offer as? TipOffer.Ask ?: return null
    if (dismissed) return null
    if (isSupporter(ask.supporters)) return null
    return ask
}
