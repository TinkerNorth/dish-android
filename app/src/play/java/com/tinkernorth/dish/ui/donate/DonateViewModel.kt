// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.donate

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tinkernorth.dish.source.billing.BillingAvailability
import com.tinkernorth.dish.source.billing.Tier
import com.tinkernorth.dish.source.billing.TipJarNotice
import com.tinkernorth.dish.source.billing.TipJarSource
import com.tinkernorth.dish.source.billing.TipJarState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DonateUiState(
    val availability: BillingAvailability,
    val tips: List<Tier>,
    val plans: List<Tier>,
    val supporterActive: Boolean,
    val notice: TipJarNotice?,
)

@HiltViewModel
class DonateViewModel
    @Inject
    constructor(
        private val tipJar: TipJarSource,
    ) : ViewModel() {
        val ui: StateFlow<DonateUiState> =
            tipJar.state
                .map(TipJarState::toUi)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), tipJar.state.value.toUi())

        init {
            tipJar.open()
        }

        fun buy(
            activity: Activity,
            tier: Tier,
        ): Boolean = tipJar.purchase(activity, tier)

        fun noticeShown() = tipJar.dismissNotice()
    }

private fun TipJarState.toUi() =
    DonateUiState(
        availability = availability,
        tips = tips,
        plans = plans,
        supporterActive = supporter != null,
        notice = notice,
    )
