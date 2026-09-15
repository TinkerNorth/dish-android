// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.billing

data class ExpectedPlan(
    val basePlanId: String,
    val replacingToken: String?,
)

interface SupporterPlanMemory {
    var supporterActive: Boolean

    fun planFor(purchaseToken: String): String?

    fun remember(
        purchaseToken: String,
        basePlanId: String,
    )

    fun expect(
        basePlanId: String,
        replacingToken: String?,
    )

    fun expected(): ExpectedPlan?

    fun forgetExpected()
}
