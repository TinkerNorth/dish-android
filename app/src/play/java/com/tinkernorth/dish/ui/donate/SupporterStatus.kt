// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.donate

import android.content.Context
import com.tinkernorth.dish.source.billing.TipJarSource
import com.tinkernorth.dish.source.store.SupporterPlanStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TipJarEntryPoint {
    fun tipJar(): TipJarSource
}

internal fun Context.isSupporter(): Boolean {
    if (!SupporterPlanStore.isSupporter(this)) return false
    EntryPointAccessors.fromApplication(applicationContext, TipJarEntryPoint::class.java).tipJar().verifySupporter()
    return true
}
