// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

/** Why each attached USB pad has the audio route it has; the Diagnostics page reads it. */
@Singleton
class PadAudioFactsStore
    @Inject
    constructor() : AbstractStateSource<Map<Int, PadAudioFacts>>(emptyMap()) {
        fun publish(facts: Map<Int, PadAudioFacts>) {
            setState(facts)
        }
    }
