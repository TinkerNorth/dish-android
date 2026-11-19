// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.source.audio.PadAudioRoutes
import javax.inject.Inject

/**
 * The two pad-audio facts the capability model reads together: the OS route table, which says
 * where a pad's audio function is, and the outputs the phone refused at those routes.
 */
class PadAudioStores
    @Inject
    constructor(
        val routes: PadAudioRoutes,
        val refusals: PadAudioRefusalStore,
    )
