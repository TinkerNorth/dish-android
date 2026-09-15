// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tinkernorth.dish.source.billing.SupporterPlanMemory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupporterPlanStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : SupporterPlanMemory {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun planFor(purchaseToken: String): String? =
            prefs.getString(KEY_PLAN, null)?.takeIf { prefs.getString(KEY_TOKEN, null) == purchaseToken }

        override fun remember(
            purchaseToken: String,
            basePlanId: String,
        ) {
            prefs.edit {
                putString(KEY_TOKEN, purchaseToken)
                putString(KEY_PLAN, basePlanId)
            }
        }

        private companion object {
            const val PREFS_NAME = "user_preferences"
            const val KEY_TOKEN = "supporter_plan_token"
            const val KEY_PLAN = "supporter_plan_id"
        }
    }
