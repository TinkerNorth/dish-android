// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class PlayBillingGateway
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : BillingGateway {
        private val events = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
        override val purchaseEvents: Flow<PurchaseEvent> = events.asSharedFlow()

        private val client: BillingClient =
            BillingClient
                .newBuilder(context)
                .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build()

        private val connectLock = Mutex()

        @Volatile
        private var detailsByProductId: Map<String, ProductDetails> = emptyMap()

        override suspend fun connect(): Boolean {
            if (client.isReady) return true
            return connectLock.withLock {
                if (client.isReady) return true
                suspendCancellableCoroutine { continuation ->
                    client.startConnection(
                        object : BillingClientStateListener {
                            // Auto-reconnection re-invokes this on every recovery; only the first call has a waiter.
                            override fun onBillingSetupFinished(result: BillingResult) {
                                if (continuation.isActive) continuation.resume(result.responseCode == BillingResponseCode.OK)
                            }

                            override fun onBillingServiceDisconnected() = Unit
                        },
                    )
                }
            }
        }

        override suspend fun catalog(
            tipProductIds: List<String>,
            subscriptionProductId: String,
        ): List<Tier>? {
            val products =
                tipProductIds.map { product(it, ProductType.INAPP) } + product(subscriptionProductId, ProductType.SUBS)
            val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
            val (result, details) =
                suspendCancellableCoroutine<Pair<BillingResult, QueryProductDetailsResult?>> { continuation ->
                    client.queryProductDetailsAsync(params) { billingResult, queryResult ->
                        if (continuation.isActive) continuation.resume(billingResult to queryResult)
                    }
                }
            if (result.responseCode != BillingResponseCode.OK || details == null) return null
            detailsByProductId = details.productDetailsList.associateBy { it.productId }
            return details.productDetailsList.flatMap(::tiersOf)
        }

        private fun product(
            id: String,
            type: String,
        ): QueryProductDetailsParams.Product =
            QueryProductDetailsParams.Product
                .newBuilder()
                .setProductId(id)
                .setProductType(type)
                .build()

        private fun tiersOf(details: ProductDetails): List<Tier> =
            if (details.productType == ProductType.INAPP) {
                listOfNotNull(
                    details.oneTimePurchaseOfferDetails?.let { offer ->
                        Tier(details.productId, null, TierKind.TIP, offer.priceAmountMicros, offer.formattedPrice, null)
                    },
                )
            } else {
                details.subscriptionOfferDetails.orEmpty().mapNotNull { offer ->
                    val phase = offer.pricingPhases.pricingPhaseList.firstOrNull() ?: return@mapNotNull null
                    Tier(
                        details.productId,
                        offer.basePlanId,
                        TierKind.MONTHLY,
                        phase.priceAmountMicros,
                        phase.formattedPrice,
                        offer.offerToken,
                    )
                }
            }

        override suspend fun ownedPurchases(): List<OwnedPurchase>? {
            val tips = queryPurchases(ProductType.INAPP) ?: return null
            val plans = queryPurchases(ProductType.SUBS) ?: return null
            return tips + plans
        }

        private suspend fun queryPurchases(type: String): List<OwnedPurchase>? {
            val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
            val (result, purchases) =
                suspendCancellableCoroutine<Pair<BillingResult, List<Purchase>>> { continuation ->
                    client.queryPurchasesAsync(params) { billingResult, list ->
                        if (continuation.isActive) continuation.resume(billingResult to list)
                    }
                }
            if (result.responseCode != BillingResponseCode.OK) return null
            return purchases.map(::owned)
        }

        override fun launchPurchase(
            activity: Activity,
            tier: Tier,
            replacing: OwnedPurchase?,
        ): Boolean {
            val details = detailsByProductId[tier.productId] ?: return false
            val product =
                BillingFlowParams.ProductDetailsParams
                    .newBuilder()
                    .setProductDetails(details)
                    .apply { tier.offerToken?.let(::setOfferToken) }
                    .build()
            val params =
                BillingFlowParams
                    .newBuilder()
                    .setProductDetailsParamsList(listOf(product))
                    .apply {
                        if (replacing != null && tier.kind == TierKind.MONTHLY) {
                            setSubscriptionUpdateParams(
                                BillingFlowParams.SubscriptionUpdateParams
                                    .newBuilder()
                                    .setOldPurchaseToken(replacing.token)
                                    .setSubscriptionReplacementMode(
                                        BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION,
                                    ).build(),
                            )
                        }
                    }.build()
            return client.launchBillingFlow(activity, params).responseCode == BillingResponseCode.OK
        }

        override suspend fun consume(purchaseToken: String): Boolean =
            suspendCancellableCoroutine { continuation ->
                val params = ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build()
                client.consumeAsync(params) { result, _ ->
                    if (continuation.isActive) continuation.resume(result.responseCode == BillingResponseCode.OK)
                }
            }

        override suspend fun acknowledge(purchaseToken: String): Boolean =
            suspendCancellableCoroutine { continuation ->
                val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
                client.acknowledgePurchase(params) { result ->
                    if (continuation.isActive) continuation.resume(result.responseCode == BillingResponseCode.OK)
                }
            }

        private fun onPurchasesUpdated(
            result: BillingResult,
            purchases: List<Purchase>?,
        ) {
            when (result.responseCode) {
                BillingResponseCode.OK ->
                    purchases.orEmpty().forEach { purchase ->
                        val event =
                            if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                                PurchaseEvent.Pending
                            } else {
                                PurchaseEvent.Completed(owned(purchase))
                            }
                        events.tryEmit(event)
                    }
                BillingResponseCode.USER_CANCELED -> events.tryEmit(PurchaseEvent.Cancelled)
                else -> events.tryEmit(PurchaseEvent.Failed(result.responseCode))
            }
        }

        private fun owned(purchase: Purchase): OwnedPurchase =
            OwnedPurchase(
                token = purchase.purchaseToken,
                productIds = purchase.products,
                acknowledged = purchase.isAcknowledged,
                pending = purchase.purchaseState == Purchase.PurchaseState.PENDING,
            )
    }
