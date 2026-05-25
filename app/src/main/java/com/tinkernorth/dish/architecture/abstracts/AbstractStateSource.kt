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
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    protected fun setState(reducer: (S) -> S) {
        _state.update(reducer)
    }

    protected fun setState(value: S) {
        _state.value = value
    }

    override fun onStart(owner: LifecycleOwner) = Unit

    override fun onStop(owner: LifecycleOwner) = Unit
}
