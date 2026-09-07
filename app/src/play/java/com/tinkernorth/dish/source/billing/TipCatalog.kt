// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.billing

// Product ids are permanent in Play Console, so they name a tier rather than an amount; prices live there.
object TipCatalog {
    val tipProductIds: List<String> = listOf("tip_5", "tip_10", "tip_25", "tip_50", "tip_100", "tip_max")
    const val SUBSCRIPTION_PRODUCT_ID = "supporter_monthly"
}
