// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import javax.inject.Inject

/**
 * The four per-slot user toggles. Every reader (the capability composer, the binding
 * screen) consults all four for a slot, so they travel as one.
 */
class SlotToggleStores
    @Inject
    constructor(
        val motion: MotionEnabledStore,
        val rumble: RumbleEnabledStore,
        val mic: MicEnabledStore,
        val speaker: SpeakerEnabledStore,
    )
