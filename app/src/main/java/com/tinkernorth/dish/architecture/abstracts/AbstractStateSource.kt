// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.architecture.abstracts

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

abstract class AbstractStateSource<S>(
    initialState: S,
) : DefaultLifecycleObserver {
    @PublishedApi
    internal val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<S> = mutableState.asStateFlow()

    // Inline, so a reducer passed here is a body the compiler inlines at the call site rather than a
    // stored callback object, and the shape rules read it as such.
    protected inline fun setState(reducer: (S) -> S) {
        mutableState.update(reducer)
    }

    protected fun setState(value: S) {
        mutableState.value = value
    }

    override fun onStart(owner: LifecycleOwner) = Unit

    override fun onStop(owner: LifecycleOwner) = Unit
}
