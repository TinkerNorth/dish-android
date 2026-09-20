// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamingServiceLiveness
    @Inject
    constructor() : AbstractStateSource<Boolean>(false) {
        fun markLive() = setState(true)

        fun markGone() = setState(false)
    }
