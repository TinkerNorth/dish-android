// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

// Which slots have the phone's mouse surface open right now. Deliberately in-memory:
// the flag mirrors a foreground screen, so process death is exactly when it must reset.
@Singleton
class MouseSurfaceStore
    @Inject
    constructor() : AbstractStateSource<Set<String>>(initialState = emptySet()) {
        fun setOpen(
            slotId: String,
            open: Boolean,
        ) {
            setState { current -> if (open) current + slotId else current - slotId }
        }

        fun isOpen(slotId: String): Boolean = slotId in state.value
    }
