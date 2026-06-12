// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.repository.TouchpadModePreference
import com.tinkernorth.dish.repository.TouchpadModeRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TouchpadModeStore
    @Inject
    constructor(
        private val repo: TouchpadModeRepository,
    ) : AbstractStateSource<Map<String, String>>(initialState = emptyMap()) {
        init {
            setState(repo.all().associate { it.satelliteId to it.mode })
        }

        // Null means never picked: resolver collapses to pair-time default (distinct from "off").
        fun modeFor(satelliteId: String): String? = state.value[satelliteId]

        fun setMode(
            satelliteId: String,
            mode: String,
        ) {
            repo.put(TouchpadModePreference(satelliteId = satelliteId, mode = mode))
            setState { it + (satelliteId to mode) }
        }

        fun forget(satelliteId: String) {
            repo.remove(satelliteId)
            setState { it - satelliteId }
        }
    }
