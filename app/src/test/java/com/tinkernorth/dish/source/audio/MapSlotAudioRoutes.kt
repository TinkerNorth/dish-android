// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * A route table driven from a plain map, so a test can say which slots have their own endpoints
 * without standing up the real table's device registry.
 *
 * [changes] is passed in rather than owned: the tests that move a route mid-run hold the flow
 * themselves and push into it between assertions.
 */
internal class MapSlotAudioRoutes(
    override val changes: StateFlow<Map<Int, PadAudioRoute>>,
    private val bySlot: Map<String, PadAudioRoute>,
) : SlotAudioRoutes {
    override fun forSlot(slotId: String) = bySlot[slotId] ?: PadAudioRoute.NONE
}
