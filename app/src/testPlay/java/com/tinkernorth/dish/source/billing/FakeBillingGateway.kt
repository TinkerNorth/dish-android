// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeBillingGateway : BillingGateway {
    data class Launch(
        val tier: Tier,
        val replacing: OwnedPurchase?,
    )

    val events = MutableSharedFlow<PurchaseEvent>()
    override val purchaseEvents: Flow<PurchaseEvent> = events

    var connectResult = true
    var catalogResult: List<Tier>? = emptyList()
    var owned: List<OwnedPurchase>? = emptyList()
    var launchResult = true

    var connectCalls = 0
    var ownedQueries = 0
    val consumed = mutableListOf<String>()
    val acknowledged = mutableListOf<String>()
    val launches = mutableListOf<Launch>()

    override suspend fun connect(): Boolean {
        connectCalls++
        return connectResult
    }

    override suspend fun catalog(
        tipProductIds: List<String>,
        subscriptionProductId: String,
    ): List<Tier>? = catalogResult

    override suspend fun ownedPurchases(): List<OwnedPurchase>? {
        ownedQueries++
        return owned
    }

    override fun launchPurchase(
        activity: Activity,
        tier: Tier,
        replacing: OwnedPurchase?,
    ): Boolean {
        launches += Launch(tier, replacing)
        return launchResult
    }

    override suspend fun consume(purchaseToken: String): Boolean {
        consumed += purchaseToken
        return true
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        acknowledged += purchaseToken
        return true
    }
}

fun tip(
    id: String,
    micros: Long,
) = Tier(id, null, TierKind.TIP, micros, "CA$${micros / 1_000_000}.00", null)

fun plan(
    basePlanId: String,
    micros: Long,
) = Tier(TipCatalog.SUBSCRIPTION_PRODUCT_ID, basePlanId, TierKind.MONTHLY, micros, "CA$${micros / 1_000_000}.00", "token-$basePlanId")

fun ownedTip(
    token: String,
    pending: Boolean = false,
) = OwnedPurchase(token, listOf("tip_5"), acknowledged = false, pending = pending)

fun ownedPlan(
    token: String,
    acknowledged: Boolean = false,
    pending: Boolean = false,
) = OwnedPurchase(token, listOf(TipCatalog.SUBSCRIPTION_PRODUCT_ID), acknowledged = acknowledged, pending = pending)
