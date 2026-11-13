// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.repository.TrackpadPick
import com.tinkernorth.dish.repository.TrackpadPickPreference
import com.tinkernorth.dish.repository.TrackpadPickRepository
import javax.inject.Inject
import javax.inject.Singleton

// The per-slot trackpad pick a user made for a Steam Controller; a slot with none takes its
// model's default (composer/TouchpadRouting.defaultTrackpadPick).
@Singleton
class TrackpadPickStore
    @Inject
    constructor(
        private val repo: TrackpadPickRepository,
    ) : AbstractStateSource<Map<String, TrackpadPick>>(initialState = emptyMap()) {
        init {
            setState(repo.all().mapNotNull { stored -> TrackpadPick.fromWire(stored.pick)?.let { stored.slotId to it } }.toMap())
        }

        fun setPick(
            slotId: String,
            pick: TrackpadPick,
        ) {
            repo.put(TrackpadPickPreference(slotId = slotId, pick = pick.wire))
            setState { it + (slotId to pick) }
        }

        fun pickFor(slotId: String): TrackpadPick? = state.value[slotId]

        fun forget(slotId: String) {
            repo.remove(slotId)
            setState { it - slotId }
        }
    }
