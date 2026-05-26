// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class OnboardingState(
    val welcomeCompleted: Boolean,
    val dashboardHintDismissed: Boolean,
)

@Singleton
class OnboardingPreferenceStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AbstractStateSource<OnboardingState>(
            initialState = readInitial(context),
        ) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun markWelcomeCompleted() {
            prefs.edit { putBoolean(KEY_WELCOME_COMPLETED, true) }
            setState(state.value.copy(welcomeCompleted = true))
        }

        fun dismissDashboardHint() {
            prefs.edit { putBoolean(KEY_DASHBOARD_HINT_DISMISSED, true) }
            setState(state.value.copy(dashboardHintDismissed = true))
        }

        fun resetWelcome() {
            prefs.edit {
                putBoolean(KEY_WELCOME_COMPLETED, false)
                putBoolean(KEY_DASHBOARD_HINT_DISMISSED, false)
            }
            setState(OnboardingState(welcomeCompleted = false, dashboardHintDismissed = false))
        }

        companion object {
            const val PREFS_NAME = "user_preferences"
            const val KEY_WELCOME_COMPLETED = "onboarding_welcome_completed"
            const val KEY_DASHBOARD_HINT_DISMISSED = "onboarding_dashboard_hint_dismissed"

            private fun readInitial(context: Context): OnboardingState {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                return OnboardingState(
                    welcomeCompleted = prefs.getBoolean(KEY_WELCOME_COMPLETED, false),
                    dashboardHintDismissed = prefs.getBoolean(KEY_DASHBOARD_HINT_DISMISSED, false),
                )
            }
        }
    }
