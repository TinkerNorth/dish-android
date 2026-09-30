// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.setup

import com.tinkernorth.dish.source.store.OnboardingPreferenceStore
import com.tinkernorth.dish.ui.common.DishNavigator
import io.mockk.mockk
import io.mockk.verifySequence
import org.junit.Test

class SetupSkipTest {
    private val onboarding = mockk<OnboardingPreferenceStore>(relaxed = true)
    private val nav = mockk<DishNavigator>(relaxed = true)

    // First-run is recorded before the dashboard opens, or the dashboard's gate bounces the user
    // straight back into setup.
    @Test
    fun `skip records first run as done and then hands the flow to the dashboard`() {
        skipSetupToDashboard(onboarding, nav)

        verifySequence {
            onboarding.markWelcomeCompleted()
            nav.finishSetupToDashboard()
        }
    }
}
