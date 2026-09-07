// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.donate

import android.app.Activity
import com.tinkernorth.dish.source.billing.BillingAvailability
import com.tinkernorth.dish.source.billing.FakeBillingGateway
import com.tinkernorth.dish.source.billing.PurchaseEvent
import com.tinkernorth.dish.source.billing.TipJarNotice
import com.tinkernorth.dish.source.billing.TipJarSource
import com.tinkernorth.dish.source.billing.ownedPlan
import com.tinkernorth.dish.source.billing.tip
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DonateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val gateway = FakeBillingGateway()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel() = DonateViewModel(TipJarSource(gateway, backgroundScope))

    @Test
    fun `creating the view model opens the tip jar`() =
        runTest(dispatcher.scheduler) {
            gateway.catalogResult = listOf(tip("tip_5", 5_000_000))
            val viewModel = viewModel()
            val job = launch { viewModel.ui.collect {} }
            advanceUntilIdle()

            assertEquals(1, gateway.connectCalls)
            assertEquals(BillingAvailability.READY, viewModel.ui.value.availability)
            assertEquals(
                listOf("tip_5"),
                viewModel.ui.value.tips
                    .map { it.productId },
            )
            job.cancel()
        }

    @Test
    fun `an active plan reads as supporter and a shown notice clears`() =
        runTest(dispatcher.scheduler) {
            val viewModel = viewModel()
            val job = launch { viewModel.ui.collect {} }
            advanceUntilIdle()
            assertFalse(viewModel.ui.value.supporterActive)

            gateway.events.emit(PurchaseEvent.Completed(ownedPlan("plan-token")))
            advanceUntilIdle()
            assertTrue(viewModel.ui.value.supporterActive)
            assertEquals(TipJarNotice.ThankedForMonthly, viewModel.ui.value.notice)

            viewModel.noticeShown()
            advanceUntilIdle()
            assertNull(viewModel.ui.value.notice)
            job.cancel()
        }

    @Test
    fun `buying forwards the tier to the store`() =
        runTest(dispatcher.scheduler) {
            val viewModel = viewModel()
            val tier = tip("tip_10", 10_000_000)

            assertTrue(viewModel.buy(mockk<Activity>(), tier))
            assertEquals(tier, gateway.launches.single().tier)
        }
}
