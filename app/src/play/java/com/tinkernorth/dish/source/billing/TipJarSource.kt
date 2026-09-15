// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.billing

import android.app.Activity
import android.util.Log
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import kotlinx.coroutines.CancellationException
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

    data class Failed(
        val responseCode: Int,
    ) : TipJarNotice
}

data class TipJarState(
    val availability: BillingAvailability = BillingAvailability.IDLE,
    val tips: List<Tier> = emptyList(),
    val plans: List<Tier> = emptyList(),
    val supporter: OwnedPurchase? = null,
    val supporterPlanId: String? = null,
    val notice: TipJarNotice? = null,
)

// Nothing here talks to Play until the donate screen calls [open] or a cached supporter is
// re-verified once per launch; the stream path never pays for it.
@Singleton
class TipJarSource
    @Inject
    constructor(
        private val gateway: BillingGateway,
        private val scope: CoroutineScope,
        private val memory: SupporterPlanMemory,
    ) : AbstractStateSource<TipJarState>(TipJarState()) {
        private var opening: Job? = null
        private var verifying: Job? = null

        init {
            gateway.purchaseEvents.onEach(::onPurchaseEvent).launchIn(scope)
        }

        fun open() {
            if (opening?.isActive == true) return
            if (state.value.availability != BillingAvailability.READY) {
                setState { it.copy(availability = BillingAvailability.CONNECTING) }
            }
            opening =
                scope.launch {
                    val tiers =
                        runCatching {
                            if (gateway.connect()) {
                                gateway.catalog(TipCatalog.tipProductIds, TipCatalog.SUBSCRIPTION_PRODUCT_ID)
                            } else {
                                null
                            }
                        }.getOrElse { failure ->
                            if (failure is CancellationException) throw failure
                            Log.w(TAG, "catalog query failed: ${failure.message}", failure)
                            null
                        }
                    if (tiers == null) {
                        setState { it.copy(availability = BillingAvailability.UNAVAILABLE, tips = emptyList(), plans = emptyList()) }
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

        fun verifySupporter() {
            if (verifying != null || opening != null) return
            verifying =
                scope.launch {
                    val owned =
                        runCatching { if (gateway.connect()) gateway.ownedPurchases() else null }
                            .getOrElse { failure ->
                                if (failure is CancellationException) throw failure
                                Log.w(TAG, "supporter check failed: ${failure.message}", failure)
                                null
                            } ?: return@launch
                    memory.supporterActive = owned.any { kindOf(it) == TierKind.MONTHLY && !it.pending }
                }
        }

        fun purchase(
            activity: Activity,
            tier: Tier,
        ): Boolean {
            val replacing = state.value.supporter?.takeIf { tier.kind == TierKind.MONTHLY }
            val launched = gateway.launchPurchase(activity, tier, replacing)
            if (launched && tier.kind == TierKind.MONTHLY) tier.basePlanId?.let { memory.expect(it, replacing?.token) }
            return launched
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
            settleExpectation(supporter, awaiting = owned.any { kindOf(it) == TierKind.MONTHLY && it.pending })
            memory.supporterActive = supporter != null
            setState {
                it.copy(supporter = supporter?.copy(acknowledged = true), supporterPlanId = supporter?.let { s -> memory.planFor(s.token) })
            }
        }

        // A plan change that completed while the process was gone still lands on the token it produced.
        private fun settleExpectation(
            supporter: OwnedPurchase?,
            awaiting: Boolean,
        ) {
            val expected = memory.expected() ?: return
            if (awaiting) return
            if (supporter != null && supporter.token != expected.replacingToken) memory.remember(supporter.token, expected.basePlanId)
            memory.forgetExpected()
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
                PurchaseEvent.Cancelled -> memory.forgetExpected()
                is PurchaseEvent.Failed -> {
                    memory.forgetExpected()
                    setState { it.copy(notice = TipJarNotice.Failed(event.responseCode)) }
                }
                is PurchaseEvent.Completed -> {
                    settle(event.purchase)
                    if (kindOf(event.purchase) == TierKind.MONTHLY) {
                        memory.expected()?.let { memory.remember(event.purchase.token, it.basePlanId) }
                        memory.forgetExpected()
                        memory.supporterActive = true
                        val purchase = event.purchase.copy(acknowledged = true)
                        setState {
                            it.copy(
                                supporter = purchase,
                                supporterPlanId = memory.planFor(purchase.token),
                                notice = TipJarNotice.ThankedForMonthly,
                            )
                        }
                    } else {
                        setState { it.copy(notice = TipJarNotice.ThankedForTip) }
                    }
                }
            }
        }

        private fun kindOf(purchase: OwnedPurchase): TierKind =
            if (TipCatalog.SUBSCRIPTION_PRODUCT_ID in purchase.productIds) TierKind.MONTHLY else TierKind.TIP

        private companion object {
            private const val TAG = "TipJarSource"
        }
    }
