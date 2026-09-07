// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.billing

import android.app.Activity
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class BillingAvailability { IDLE, CONNECTING, READY, UNAVAILABLE }

sealed interface TipJarNotice {
    data object PaymentPending : TipJarNotice

    data object ThankedForTip : TipJarNotice

    data object ThankedForMonthly : TipJarNotice

    data object Failed : TipJarNotice
}

data class TipJarState(
    val availability: BillingAvailability = BillingAvailability.IDLE,
    val tips: List<Tier> = emptyList(),
    val plans: List<Tier> = emptyList(),
    val supporter: OwnedPurchase? = null,
    val notice: TipJarNotice? = null,
)

// Nothing here talks to Play until the donate screen calls [open]; the stream path never pays for it.
@Singleton
class TipJarSource
    @Inject
    constructor(
        private val gateway: BillingGateway,
        private val scope: CoroutineScope,
    ) : AbstractStateSource<TipJarState>(TipJarState()) {
        private var opening: Job? = null

        init {
            gateway.purchaseEvents.onEach(::onPurchaseEvent).launchIn(scope)
        }

        fun open() {
            if (opening?.isActive == true) return
            if (state.value.availability == BillingAvailability.READY) {
                opening = scope.launch { reconcile() }
                return
            }
            setState { it.copy(availability = BillingAvailability.CONNECTING) }
            opening =
                scope.launch {
                    val tiers =
                        if (gateway.connect()) {
                            gateway.catalog(TipCatalog.tipProductIds, TipCatalog.SUBSCRIPTION_PRODUCT_ID)
                        } else {
                            null
                        }
                    if (tiers == null) {
                        setState { it.copy(availability = BillingAvailability.UNAVAILABLE) }
                        return@launch
                    }
                    setState {
                        it.copy(
                            availability = BillingAvailability.READY,
                            tips = tiers.filter { tier -> tier.kind == TierKind.TIP }.sortedBy(Tier::priceMicros),
                            plans = tiers.filter { tier -> tier.kind == TierKind.MONTHLY }.sortedBy(Tier::priceMicros),
                        )
                    }
                    reconcile()
                }
        }

        fun purchase(
            activity: Activity,
            tier: Tier,
        ): Boolean {
            val replacing = state.value.supporter?.takeIf { tier.kind == TierKind.MONTHLY }
            return gateway.launchPurchase(activity, tier, replacing)
        }

        fun dismissNotice() {
            setState { it.copy(notice = null) }
        }

        // Play refunds anything left unacknowledged for three days, so every visit settles what an
        // interrupted flow may have left behind before the user sees the buttons.
        private suspend fun reconcile() {
            val owned = gateway.ownedPurchases() ?: return
            owned.filterNot(OwnedPurchase::pending).forEach { settle(it) }
            val supporter = owned.firstOrNull { kindOf(it) == TierKind.MONTHLY && !it.pending }
            setState { it.copy(supporter = supporter?.copy(acknowledged = true)) }
        }

        private suspend fun settle(purchase: OwnedPurchase) {
            when (kindOf(purchase)) {
                TierKind.TIP -> gateway.consume(purchase.token)
                TierKind.MONTHLY -> if (!purchase.acknowledged) gateway.acknowledge(purchase.token)
            }
        }

        private suspend fun onPurchaseEvent(event: PurchaseEvent) {
            when (event) {
                PurchaseEvent.Pending -> setState { it.copy(notice = TipJarNotice.PaymentPending) }
                PurchaseEvent.Cancelled -> Unit
                is PurchaseEvent.Failed -> setState { it.copy(notice = TipJarNotice.Failed) }
                is PurchaseEvent.Completed -> {
                    settle(event.purchase)
                    if (kindOf(event.purchase) == TierKind.MONTHLY) {
                        setState {
                            it.copy(supporter = event.purchase.copy(acknowledged = true), notice = TipJarNotice.ThankedForMonthly)
                        }
                    } else {
                        setState { it.copy(notice = TipJarNotice.ThankedForTip) }
                    }
                }
            }
        }

        private fun kindOf(purchase: OwnedPurchase): TierKind =
            if (TipCatalog.SUBSCRIPTION_PRODUCT_ID in purchase.productIds) TierKind.MONTHLY else TierKind.TIP
    }
