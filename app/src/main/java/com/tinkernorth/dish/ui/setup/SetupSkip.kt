// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.setup

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.store.OnboardingPreferenceStore
import com.tinkernorth.dish.ui.common.DishNavigator

// Every setup screen carries a top-right Skip. It is a toolbar child view (not a menu
// item) so it survives setSupportActionBar, which would otherwise route menu clicks
// past our listener. Skipping records first-run as done so the gate does not bounce
// the user straight back in, then clears the flow to the dashboard (still reachable
// from its add card and from Settings).
fun AppCompatActivity.wireSetupSkip(
    toolbar: MaterialToolbar,
    onboarding: OnboardingPreferenceStore,
    nav: DishNavigator,
) {
    val skip = layoutInflater.inflate(R.layout.view_setup_skip_button, toolbar, false)
    toolbar.addView(skip)
    skip.setOnClickListener { skipSetupToDashboard(onboarding, nav) }
}

internal fun skipSetupToDashboard(
    onboarding: OnboardingPreferenceStore,
    nav: DishNavigator,
) {
    onboarding.markWelcomeCompleted()
    nav.finishSetupToDashboard()
}
