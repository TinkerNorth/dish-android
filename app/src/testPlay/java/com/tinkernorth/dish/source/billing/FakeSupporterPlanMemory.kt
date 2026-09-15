// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.billing

class FakeSupporterPlanMemory : SupporterPlanMemory {
    val plans = mutableMapOf<String, String>()

    override fun planFor(purchaseToken: String): String? = plans[purchaseToken]

    override fun remember(
        purchaseToken: String,
        basePlanId: String,
    ) {
        plans[purchaseToken] = basePlanId
    }
}
