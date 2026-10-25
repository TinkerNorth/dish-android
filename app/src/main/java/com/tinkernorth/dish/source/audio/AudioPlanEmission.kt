// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.audio

class AudioPlanEmission<Plan> internal constructor(
    internal val generation: Long,
    internal val plan: Plan,
)
