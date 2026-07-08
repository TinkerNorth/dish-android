// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.architecture.abstracts

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

// Lifecycle-actuator: collects an upstream [Flow] while STARTED and drives a side effect.
// Subclasses keep their own [apply] and teardown; only the onStart/launchIn/job plumbing is shared.
//
// CONTRACT: inputs keep moving while the actuator is stopped, so [apply] must reconcile fully
// from the current upstream value and never trust memory recorded before the stop. State kept
// across emissions (dedupe caches, last-seen sets) may only ever suppress redundant work, never
// substitute for looking at the world again on the post-start emission.
abstract class AbstractController<S>(
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    private var job: Job? = null

    protected abstract fun upstream(): Flow<S>

    protected abstract fun apply(value: S)

    // Runs once per actual (re)start, after the idempotency guard and before launch, so a subclass can
    // re-arm post-stop state (e.g. a drop-late-emissions guard) under its own lock before collection.
    protected open fun onStarting() = Unit

    final override fun onStart(owner: LifecycleOwner) {
        if (job != null) return
        onStarting()
        job = upstream().onEach(::apply).launchIn(scope)
    }

    // Default teardown stops collection; override to add teardown or to keep collecting (process-scoped).
    override fun onStop(owner: LifecycleOwner) {
        cancelCollection()
    }

    protected fun cancelCollection() {
        job?.cancel()
        job = null
    }
}
