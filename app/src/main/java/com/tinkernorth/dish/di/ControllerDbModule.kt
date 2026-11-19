// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.di

import com.tinkernorth.dish.core.controllerdb.ControllerDbGateway
import com.tinkernorth.dish.source.controllerdb.HttpsControllerDbGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Every flavour checks dish.tinkernorth.com for a newer controller database, while its switch is on.
@Module
@InstallIn(SingletonComponent::class)
abstract class ControllerDbModule {
    @Binds
    @Singleton
    abstract fun bindControllerDbGateway(gateway: HttpsControllerDbGateway): ControllerDbGateway
}
