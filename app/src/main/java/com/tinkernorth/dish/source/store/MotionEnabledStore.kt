// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.repository.MotionPreference
import com.tinkernorth.dish.repository.MotionPreferenceRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped reactive mirror of the durable
 * [MotionPreferenceRepository]: `slotId → enabled`.
 *
 * The repository is the durable source of truth; this store hydrates from
 * it on construction and republishes every write as a `StateFlow` so the
 * [com.tinkernorth.dish.composer.MotionCapabilityComposer] (and the UI) can
 * react without re-reading SharedPreferences on every frame.
 *
 * **Pattern:** [AbstractStateSource]`<Map<String, Boolean>>` — same shape
 * as [ControllerTypeStore]. The two stores are parallel "reactive
 * in-memory cache over a persisted choice" types; if you grow another one
 * (per-slot rumble strength etc.), copy this file.
 *
 * **Default policy:** [isEnabled] collapses an absent entry onto
 * [DEFAULT_ENABLED] (true). The product decision is "motion on unless the
 * user turned it off" — discoverable via the indicator pill, recoverable
 * via the toggle. The repository deliberately does not bake the default in
 * (an absent entry is semantically different from `enabled=true`), so
 * downstream consumers consult [isEnabled] rather than the raw map.
 */
@Singleton
class MotionEnabledStore
    @Inject
    constructor(
        private val repo: MotionPreferenceRepository,
    ) : AbstractStateSource<Map<String, Boolean>>(initialState = emptyMap()) {
        init {
            // Hydrate once on construction. Subsequent durable changes flow
            // through this store (setEnabled below), so it stays in sync.
            setState(repo.all().associate { it.slotId to it.enabled })
        }

        /**
         * Persist the user's choice for [slotId] and republish. Writes are
         * synchronous to disk via SharedPreferences' edit().apply() (which
         * commits the in-memory map immediately and persists asynchronously).
         */
        fun setEnabled(
            slotId: String,
            enabled: Boolean,
        ) {
            repo.put(MotionPreference(slotId = slotId, enabled = enabled))
            setState { it + (slotId to enabled) }
        }

        /**
         * Resolve the effective enabled boolean for [slotId], collapsing an
         * absent entry onto [DEFAULT_ENABLED]. Use this — not the raw map
         * — wherever the consumer cares about effective behaviour.
         */
        fun isEnabled(slotId: String): Boolean = state.value[slotId] ?: DEFAULT_ENABLED

        /**
         * Forget the user's choice for [slotId] (revert to default). Used
         * when a physical pad is unbound permanently — keeping a stale
         * entry forever would silently override the default for a future
         * unrelated device that happened to get the same `InputDevice` id.
         */
        fun forget(slotId: String) {
            repo.remove(slotId)
            setState { it - slotId }
        }

        companion object {
            /**
             * Product default: motion is on for any slot that has not been
             * explicitly toggled off. Surfaced as a constant so the composer
             * and tests can pin the same value.
             */
            const val DEFAULT_ENABLED: Boolean = true
        }
    }
