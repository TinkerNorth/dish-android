// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.repository.MotionPreference
import com.tinkernorth.dish.repository.MotionPreferenceRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MotionEnabledStore
    @Inject
    constructor(
        private val repo: MotionPreferenceRepository,
    ) : AbstractStateSource<Map<String, Boolean>>(initialState = emptyMap()) {
        init {
            setState(repo.all().associate { it.slotId to it.enabled })
        }

        fun setEnabled(
            slotId: String,
            enabled: Boolean,
        ) {
            repo.put(MotionPreference(slotId = slotId, enabled = enabled))
            setState { it + (slotId to enabled) }
        }

        // Absent entry collapses to DEFAULT_ENABLED; raw map keeps absence distinct from explicit true.
        fun isEnabled(slotId: String): Boolean = state.value[slotId] ?: DEFAULT_ENABLED

        fun forget(slotId: String) {
            repo.remove(slotId)
            setState { it - slotId }
        }

        companion object {
            const val DEFAULT_ENABLED: Boolean = true
        }
    }
