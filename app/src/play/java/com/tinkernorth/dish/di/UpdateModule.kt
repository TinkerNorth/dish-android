// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.di

import com.tinkernorth.dish.source.update.NoUpdateNotices
import com.tinkernorth.dish.source.update.UpdateNotices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Play updates the app; see NoUpdateNotices.
@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {
    @Provides
    @Singleton
    fun provideUpdateNotices(): UpdateNotices = NoUpdateNotices
}
