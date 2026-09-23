// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Collects [flow] while the owner is at least STARTED and stops when it is not.
 *
 * This is the shape every screen observer wants: a screen in the background must not keep
 * rendering into views nobody is looking at, and must pick the state back up on return.
 */
internal fun <T> LifecycleOwner.observeWhileStarted(
    flow: Flow<T>,
    onEach: suspend (T) -> Unit,
) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collect { onEach(it) } }
    }
}
