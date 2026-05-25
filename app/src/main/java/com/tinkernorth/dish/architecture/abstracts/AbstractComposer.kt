// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.architecture.abstracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

abstract class AbstractComposer<S>(
    scope: CoroutineScope,
    initial: S,
) {
    protected abstract fun upstream(): Flow<S>

    val state: StateFlow<S> by lazy {
        upstream().stateIn(scope, SharingStarted.Eagerly, initial)
    }
}
