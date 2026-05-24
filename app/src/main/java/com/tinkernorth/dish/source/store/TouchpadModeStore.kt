// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.repository.TouchpadModePreference
import com.tinkernorth.dish.repository.TouchpadModeRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped reactive mirror of the durable [TouchpadModeRepository]:
 * `satelliteId → mode` (where mode is one of `"off"`, `"ds4"`, `"mouse"`).
 *
 * The repository is the durable source of truth; this store hydrates from
 * it on construction and republishes every write as a [state] flow so the
 * dashboard's combine collector re-fires the moment the user picks a new
 * mode — no polling, no Activity reload.
 *
 * **Pattern:** [AbstractStateSource]`<Map<String, String>>` — direct
 * parallel to [MotionEnabledStore] and [ControllerTypeStore]. Per the
 * `Repository` contract, reactive reads do NOT belong on the repository
 * itself; the repository is dumb CRUD and this store is the reactive
 * wrapper.
 *
 * **Absence semantics:** [modeFor] returns null when this device has
 * never had a mode picked for that satellite. Callers (typically
 * [com.tinkernorth.dish.composer.TouchpadModeComposer.resolve]) collapse
 * null onto a default chosen at pair time. The store deliberately does
 * not bake the default in — an absent entry is semantically distinct from
 * `"off"` because the resolver may prefer `ds4` or `mouse` at pair time
 * depending on the server's advertised capabilities.
 */
@Singleton
class TouchpadModeStore
    @Inject
    constructor(
        private val repo: TouchpadModeRepository,
    ) : AbstractStateSource<Map<String, String>>(initialState = emptyMap()) {
        init {
            // Hydrate once on construction. Subsequent durable changes flow
            // through this store ([setMode] / [forget] below), so the
            // in-memory state stays in lock-step with what's on disk.
            setState(repo.all().associate { it.satelliteId to it.mode })
        }

        /** Current mode for [satelliteId], or null if the user has never picked. */
        fun modeFor(satelliteId: String): String? = state.value[satelliteId]

        /**
         * Persist the user's choice for [satelliteId] and republish. Validation
         * (a wire-string check against `TouchpadModeValue.isValid`) is the
         * caller's job — this is the storage layer, not the policy layer.
         */
        fun setMode(
            satelliteId: String,
            mode: String,
        ) {
            repo.put(TouchpadModePreference(satelliteId = satelliteId, mode = mode))
            setState { it + (satelliteId to mode) }
        }

        /**
         * Forget the user's choice for [satelliteId] (revert to whatever
         * default the resolver chooses at pair time). Used when a paired
         * satellite is forgotten — a stale entry would silently override the
         * default for a future unrelated satellite that happened to be paired
         * with the same id.
         */
        fun forget(satelliteId: String) {
            repo.remove(satelliteId)
            setState { it - satelliteId }
        }
    }
