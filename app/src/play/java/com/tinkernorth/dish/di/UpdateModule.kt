// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.di

import com.tinkernorth.dish.source.update.NoUpdateNotices
import com.tinkernorth.dish.source.update.UpdateNotices
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Play updates the app; see NoUpdateNotices.
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds
    @Singleton
    abstract fun bindUpdateNotices(notices: NoUpdateNotices): UpdateNotices
}
