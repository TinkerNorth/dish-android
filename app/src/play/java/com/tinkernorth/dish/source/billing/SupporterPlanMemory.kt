// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.billing

interface SupporterPlanMemory {
    fun planFor(purchaseToken: String): String?

    fun remember(
        purchaseToken: String,
        basePlanId: String,
    )
}
