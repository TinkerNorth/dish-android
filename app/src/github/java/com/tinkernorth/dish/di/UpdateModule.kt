// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.di

import com.tinkernorth.dish.source.update.GitHubManifestGateway
import com.tinkernorth.dish.source.update.UpdateCoordinator
import com.tinkernorth.dish.source.update.UpdateManifestGateway
import com.tinkernorth.dish.source.update.UpdateNotices
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// The directly-distributed build learns about new releases from GitHub; the
// play flavor binds a no-op in its own UpdateModule.
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds
    @Singleton
    abstract fun bindUpdateNotices(coordinator: UpdateCoordinator): UpdateNotices

    @Binds
    @Singleton
    abstract fun bindUpdateManifestGateway(gateway: GitHubManifestGateway): UpdateManifestGateway
}
