// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow

enum class TierKind { TIP, MONTHLY }

data class Tier(
    val productId: String,
    val basePlanId: String?,
    val kind: TierKind,
    val priceMicros: Long,
    val formattedPrice: String,
    val offerToken: String?,
)

data class OwnedPurchase(
    val token: String,
    val productIds: List<String>,
    val acknowledged: Boolean,
    val pending: Boolean,
)

sealed interface PurchaseEvent {
    data class Completed(
        val purchase: OwnedPurchase,
    ) : PurchaseEvent

    data object Pending : PurchaseEvent

    data object Cancelled : PurchaseEvent

    data class Failed(
        val responseCode: Int,
    ) : PurchaseEvent
}

interface BillingGateway {
    val purchaseEvents: Flow<PurchaseEvent>

    suspend fun connect(): Boolean

    suspend fun catalog(
        tipProductIds: List<String>,
        subscriptionProductId: String,
    ): List<Tier>?

    suspend fun ownedPurchases(): List<OwnedPurchase>?

    fun launchPurchase(
        activity: Activity,
        tier: Tier,
        replacing: OwnedPurchase?,
    ): Boolean

    suspend fun consume(purchaseToken: String): Boolean

    suspend fun acknowledge(purchaseToken: String): Boolean
}
