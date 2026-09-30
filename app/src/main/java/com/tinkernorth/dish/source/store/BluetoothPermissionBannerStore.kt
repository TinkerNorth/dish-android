// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothPermissionBannerStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AbstractStateSource<Boolean>(
            initialState = readBannerDismissed(context),
        ) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun markDismissed() {
            prefs.edit { putBoolean(KEY_BANNER_DISMISSED, true) }
            setState(true)
        }

        companion object {
            const val PREFS_NAME = "user_preferences"
            const val KEY_BANNER_DISMISSED = "bt_permission_banner_dismissed"
        }
    }

private fun readBannerDismissed(context: Context): Boolean =
    context
        .getSharedPreferences(BluetoothPermissionBannerStore.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(BluetoothPermissionBannerStore.KEY_BANNER_DISMISSED, false)
