// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.billing

import android.app.Activity
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TipJarSourceTest {
    private val gateway = FakeBillingGateway()
    private val memory = FakeSupporterPlanMemory()
    private val activity = mockk<Activity>()

    // advanceUntilIdle stops once only backgroundScope work is left, and the event collector must
    // be subscribed before a test emits, so every source gets one scheduler pass on creation.
    private fun TestScope.source() = TipJarSource(gateway, backgroundScope, memory).also { runCurrent() }

    @Test
    fun `an unreachable Play Store reads as unavailable`() =
        runTest {
            gateway.connectResult = false
            val source = source()
            source.open()
            runCurrent()

            assertEquals(BillingAvailability.UNAVAILABLE, source.state.value.availability)
            assertEquals(0, gateway.ownedQueries)
        }

    @Test
    fun `a failed catalog query reads as unavailable`() =
        runTest {
            gateway.catalogResult = null
            val source = source()
            source.open()
            runCurrent()

            assertEquals(BillingAvailability.UNAVAILABLE, source.state.value.availability)
        }

    @Test
    fun `a catalog query that throws reads as unavailable instead of wedging on connecting`() =
        runTest {
            gateway.catalogFailure = IllegalArgumentException("All products should be of the same product type.")
            val source = source()
            source.open()
            runCurrent()

            assertEquals(BillingAvailability.UNAVAILABLE, source.state.value.availability)
            assertEquals(0, gateway.ownedQueries)
        }

    @Test
    fun `tiers split by kind and sort by price regardless of catalog order`() =
        runTest {
            gateway.catalogResult =
                listOf(
                    plan("monthly-10", 10_000_000),
                    tip("tip_25", 25_000_000),
                    tip("tip_5", 5_000_000),
                    plan("monthly-1", 1_000_000),
                )
            val source = source()
            source.open()
            runCurrent()

            val state = source.state.value
            assertEquals(BillingAvailability.READY, state.availability)
            assertEquals(listOf("tip_5", "tip_25"), state.tips.map(Tier::productId))
            assertEquals(listOf("monthly-1", "monthly-10"), state.plans.map(Tier::basePlanId))
            assertNull(state.supporter)
        }

    @Test
    fun `opening settles leftover purchases and finds the active plan`() =
        runTest {
            gateway.owned = listOf(ownedTip("tip-token"), ownedTip("pending-tip", pending = true), ownedPlan("plan-token"))
            val source = source()
            source.open()
            runCurrent()

            assertEquals(listOf("tip-token"), gateway.consumed)
            assertEquals(listOf("plan-token"), gateway.acknowledged)
            assertEquals(
                "plan-token",
                source.state.value.supporter
                    ?.token,
            )
        }

    @Test
    fun `an already acknowledged plan is not acknowledged again`() =
        runTest {
            gateway.owned = listOf(ownedPlan("plan-token", acknowledged = true))
            val source = source()
            source.open()
            runCurrent()

            assertTrue(gateway.acknowledged.isEmpty())
            assertEquals(
                "plan-token",
                source.state.value.supporter
                    ?.token,
            )
        }

    @Test
    fun `reopening a ready source refreshes the catalog and the purchases`() =
        runTest {
            gateway.catalogResult = listOf(plan("monthly-5", 5_000_000))
            val source = source()
            source.open()
            runCurrent()
            gateway.catalogResult = listOf(plan("monthly-5", 6_000_000))
            gateway.owned = listOf(ownedPlan("plan-token", acknowledged = true))
            source.open()
            runCurrent()

            assertEquals(2, gateway.catalogQueries)
            assertEquals(2, gateway.ownedQueries)
            assertEquals(BillingAvailability.READY, source.state.value.availability)
            assertEquals(
                6_000_000,
                source.state.value.plans
                    .single()
                    .priceMicros,
            )
            assertEquals(
                "plan-token",
                source.state.value.supporter
                    ?.token,
            )
        }

    @Test
    fun `a refresh the store refuses clears the tiers`() =
        runTest {
            gateway.catalogResult = listOf(plan("monthly-5", 5_000_000))
            val source = source()
            source.open()
            runCurrent()
            gateway.catalogResult = null
            source.open()
            runCurrent()

            assertEquals(BillingAvailability.UNAVAILABLE, source.state.value.availability)
            assertTrue(
                source.state.value.plans
                    .isEmpty(),
            )
        }

    @Test
    fun `opening records whether a subscription is active`() =
        runTest {
            memory.supporterActive = true
            val source = source()
            source.open()
            runCurrent()
            assertFalse(memory.supporterActive)

            gateway.owned = listOf(ownedPlan("plan-token", acknowledged = true))
            source.open()
            runCurrent()
            assertTrue(memory.supporterActive)
        }

    @Test
    fun `verifying a supporter refreshes the cache without loading the catalog`() =
        runTest {
            memory.supporterActive = true
            val source = source()
            source.verifySupporter()
            runCurrent()

            assertFalse(memory.supporterActive)
            assertEquals(0, gateway.catalogQueries)
            assertEquals(BillingAvailability.IDLE, source.state.value.availability)
        }

    @Test
    fun `a completed tip is consumed and thanked`() =
        runTest {
            val source = source()
            gateway.events.emit(PurchaseEvent.Completed(ownedTip("tip-token")))
            runCurrent()

            assertEquals(listOf("tip-token"), gateway.consumed)
            assertEquals(TipJarNotice.ThankedForTip, source.state.value.notice)
            assertNull(source.state.value.supporter)

            source.dismissNotice()
            assertNull(source.state.value.notice)
        }

    @Test
    fun `a completed plan is acknowledged and becomes the supporter`() =
        runTest {
            val source = source()
            gateway.events.emit(PurchaseEvent.Completed(ownedPlan("plan-token")))
            runCurrent()

            assertEquals(listOf("plan-token"), gateway.acknowledged)
            assertEquals(TipJarNotice.ThankedForMonthly, source.state.value.notice)
            assertTrue(memory.supporterActive)
            assertEquals(
                "plan-token",
                source.state.value.supporter
                    ?.token,
            )
        }

    @Test
    fun `pending and failed flows surface a notice while a cancel stays quiet`() =
        runTest {
            val source = source()

            gateway.events.emit(PurchaseEvent.Pending)
            runCurrent()
            assertEquals(TipJarNotice.PaymentPending, source.state.value.notice)

            gateway.events.emit(PurchaseEvent.Failed(responseCode = 3))
            runCurrent()
            assertEquals(TipJarNotice.Failed(3), source.state.value.notice)

            source.dismissNotice()
            gateway.events.emit(PurchaseEvent.Cancelled)
            runCurrent()
            assertNull(source.state.value.notice)
        }

    @Test
    fun `a remembered plan resolves by its id on open`() =
        runTest {
            gateway.catalogResult = listOf(plan("monthly-1", 1_000_000), plan("monthly-5", 5_000_000))
            gateway.owned = listOf(ownedPlan("plan-token", acknowledged = true))
            memory.remember("plan-token", "monthly-5")
            val source = source()
            source.open()
            runCurrent()

            assertEquals("monthly-5", source.state.value.supporterPlanId)
        }

    @Test
    fun `a plan bought on another device reads as supporter without a plan`() =
        runTest {
            gateway.catalogResult = listOf(plan("monthly-1", 1_000_000))
            gateway.owned = listOf(ownedPlan("plan-token", acknowledged = true))
            val source = source()
            source.open()
            runCurrent()

            assertEquals(
                "plan-token",
                source.state.value.supporter
                    ?.token,
            )
            assertNull(source.state.value.supporterPlanId)
        }

    @Test
    fun `a completed plan purchase remembers the plan for its token`() =
        runTest {
            gateway.catalogResult = listOf(plan("monthly-1", 1_000_000), plan("monthly-25", 25_000_000))
            val source = source()
            source.open()
            runCurrent()

            source.purchase(activity, plan("monthly-25", 25_000_000))
            gateway.events.emit(PurchaseEvent.Completed(ownedPlan("new-token")))
            runCurrent()

            assertEquals(mapOf("new-token" to "monthly-25"), memory.plans)
            assertNull(memory.expectedPlan)
            assertEquals("monthly-25", source.state.value.supporterPlanId)
        }

    @Test
    fun `a cancelled plan purchase forgets the expected plan`() =
        runTest {
            gateway.catalogResult = listOf(plan("monthly-25", 25_000_000))
            val source = source()
            source.open()
            runCurrent()

            source.purchase(activity, plan("monthly-25", 25_000_000))
            gateway.events.emit(PurchaseEvent.Cancelled)
            gateway.events.emit(PurchaseEvent.Completed(ownedPlan("restored-token")))
            runCurrent()

            assertTrue(memory.plans.isEmpty())
            assertNull(source.state.value.supporterPlanId)
        }

    @Test
    fun `a plan change completed while the app was gone lands on the new token`() =
        runTest {
            gateway.catalogResult = listOf(plan("monthly-5", 5_000_000), plan("monthly-25", 25_000_000))
            gateway.owned = listOf(ownedPlan("new-token", acknowledged = true))
            memory.remember("old-token", "monthly-5")
            memory.expect("monthly-25", replacingToken = "old-token")
            val source = source()
            source.open()
            runCurrent()

            assertEquals("monthly-25", memory.plans["new-token"])
            assertNull(memory.expectedPlan)
            assertEquals("monthly-25", source.state.value.supporterPlanId)
        }

    @Test
    fun `an expected plan is dropped when the subscription did not change`() =
        runTest {
            gateway.owned = listOf(ownedPlan("plan-token", acknowledged = true))
            memory.expect("monthly-25", replacingToken = "plan-token")
            val source = source()
            source.open()
            runCurrent()

            assertTrue(memory.plans.isEmpty())
            assertNull(memory.expectedPlan)
        }

    @Test
    fun `an expected plan waits while its purchase is pending`() =
        runTest {
            gateway.owned = listOf(ownedPlan("plan-token", acknowledged = true), ownedPlan("pending-token", pending = true))
            memory.expect("monthly-25", replacingToken = "plan-token")
            val source = source()
            source.open()
            runCurrent()

            assertEquals(ExpectedPlan("monthly-25", "plan-token"), memory.expectedPlan)
        }

    @Test
    fun `changing plans replaces the active subscription but tips never do`() =
        runTest {
            gateway.owned = listOf(ownedPlan("plan-token", acknowledged = true))
            val source = source()
            source.open()
            runCurrent()

            source.purchase(activity, plan("monthly-25", 25_000_000))
            source.purchase(activity, tip("tip_5", 5_000_000))

            assertEquals("plan-token", gateway.launches[0].replacing?.token)
            assertNull(gateway.launches[1].replacing)
            assertEquals(ExpectedPlan("monthly-25", "plan-token"), memory.expectedPlan)
        }

    @Test
    fun `a launch the store refuses is reported to the caller and expects nothing`() =
        runTest {
            gateway.launchResult = false
            val source = source()

            assertEquals(false, source.purchase(activity, plan("monthly-25", 25_000_000)))
            assertNull(memory.expectedPlan)
        }
}
