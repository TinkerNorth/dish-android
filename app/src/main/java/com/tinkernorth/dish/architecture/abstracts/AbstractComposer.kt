// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.abstracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Pattern: **pure derivation** from upstream flows into a single [StateFlow]. The
 * `Abstract` prefix + the `abstracts/` package signal that this is a base class.
 *
 * Use this when the class combines existing flows (typically from
 * [AbstractStateSource]s or `Repository`s) and produces no new external input
 * of its own. Examples in this codebase:
 *
 *   - `ConnectionsComposer` — combines satellite + Bluetooth + bindings + stale
 *     markers into a list of `ConnectionSummary`.
 *   - `WakeStateComposer` — combines bindings + connections into `WakeState`.
 *   - `PhysicalReachabilityComposer` — combines physical devices + bindings +
 *     connections + slot tables into the reachable-pad map.
 *
 * The base provides:
 *
 *   - a single `StateFlow<S>` named [state], started **eagerly** on the supplied
 *     scope so the downstream value is always available without a one-frame
 *     flicker on Activity resume;
 *   - exactly one extension point ([upstream]) so subclasses cannot smuggle in
 *     extra surface;
 *   - explicit `initial` so the StateFlow has a sane value before the first
 *     upstream emission.
 *
 * **Why no events?** A pure derivation has no events. If you find yourself reaching
 * for an event channel, you are not writing an `AbstractComposer` — you are writing
 * an [AbstractStateSource] that happens to combine inputs. Switch base classes
 * rather than add the field.
 *
 * **Why no lifecycle?** The upstream flows already carry their own lifecycles. A
 * composer's own state is just the latest combination of its upstreams. If you
 * need lifecycle-bound side effects (e.g. acquire a wake lock when the derived
 * count > 0), expose the `StateFlow<S>` from the composer and let a separate
 * [AbstractStateSource] subscribe to it. See `WakeStateComposer` vs
 * `WakeStateController` for the canonical example.
 *
 * **Threading:** the combine runs on whichever dispatcher [upstream] is collected
 * on (default: the [scope]'s dispatcher). UI consumers should `.collect` on `Main`.
 *
 * **Testing:** see `ComposerProbe` in `com.tinkernorth.dish.architecture.testing`.
 */
abstract class AbstractComposer<S>(
    scope: CoroutineScope,
    initial: S,
) {
    /** The upstream flow whose latest value is the composer's state. */
    protected abstract fun upstream(): Flow<S>

    val state: StateFlow<S> by lazy {
        upstream().stateIn(scope, SharingStarted.Eagerly, initial)
    }
}
