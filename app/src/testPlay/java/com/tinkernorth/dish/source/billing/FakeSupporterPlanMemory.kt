// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.billing

class FakeSupporterPlanMemory : SupporterPlanMemory {
    val plans = mutableMapOf<String, String>()
    var expectedPlan: ExpectedPlan? = null
    override var supporterActive = false

    override fun planFor(purchaseToken: String): String? = plans[purchaseToken]

    override fun remember(
        purchaseToken: String,
        basePlanId: String,
    ) {
        plans[purchaseToken] = basePlanId
    }

    override fun expect(
        basePlanId: String,
        replacingToken: String?,
    ) {
        expectedPlan = ExpectedPlan(basePlanId, replacingToken)
    }

    override fun expected(): ExpectedPlan? = expectedPlan

    override fun forgetExpected() {
        expectedPlan = null
    }
}
