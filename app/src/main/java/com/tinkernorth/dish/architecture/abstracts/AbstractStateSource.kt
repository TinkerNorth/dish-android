// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.architecture.abstracts

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

abstract class AbstractStateSource<S>(
    initialState: S,
) : DefaultLifecycleObserver {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<S> = mutableState.asStateFlow()

    protected fun setState(reducer: (S) -> S) {
        mutableState.update(reducer)
    }

    protected fun setState(value: S) {
        mutableState.value = value
    }

    // For a write whose caller needs what it replaced, read and written as one atomic step.
    protected fun getAndSetState(reducer: (S) -> S): S = mutableState.getAndUpdate(reducer)

    override fun onStart(owner: LifecycleOwner) = Unit

    override fun onStop(owner: LifecycleOwner) = Unit
}
