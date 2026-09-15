// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tinkernorth.dish.source.billing.ExpectedPlan
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

        override var supporterActive: Boolean
            get() = prefs.getBoolean(KEY_ACTIVE, false)
            set(value) = prefs.edit { putBoolean(KEY_ACTIVE, value) }

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

        override fun expect(
            basePlanId: String,
            replacingToken: String?,
        ) {
            prefs.edit {
                putString(KEY_EXPECTED_PLAN, basePlanId)
                putString(KEY_EXPECTED_FROM, replacingToken)
            }
        }

        override fun expected(): ExpectedPlan? =
            prefs.getString(KEY_EXPECTED_PLAN, null)?.let { ExpectedPlan(it, prefs.getString(KEY_EXPECTED_FROM, null)) }

        override fun forgetExpected() {
            prefs.edit {
                remove(KEY_EXPECTED_PLAN)
                remove(KEY_EXPECTED_FROM)
            }
        }

        companion object {
            private const val PREFS_NAME = "user_preferences"
            private const val KEY_TOKEN = "supporter_plan_token"
            private const val KEY_PLAN = "supporter_plan_id"
            private const val KEY_ACTIVE = "supporter_active"
            private const val KEY_EXPECTED_PLAN = "supporter_plan_expected"
            private const val KEY_EXPECTED_FROM = "supporter_plan_expected_from"

            fun isSupporter(context: Context): Boolean =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)
        }
    }
