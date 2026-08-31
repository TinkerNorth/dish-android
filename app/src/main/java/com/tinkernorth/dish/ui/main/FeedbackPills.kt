// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.SlotCapabilities

// Pure pill reducers for the dashboard cards, siblings of pointerFuncFacts:
// what a bound slot's path carries beyond the classic three rows.

// The Moonlight pointer story is simpler than the satellite's mode machinery:
// pad touch streams whenever the layers carry it, and the mouse rides the
// control stream natively.
internal fun moonlightPointerFacts(row: ControllerAdapter.Row): List<PointerPillFact> =
    buildList {
        if (Feature.TOUCHPAD in row.motionCap.available) add(PointerPillFact.PAD_ON)
        if (Feature.MOUSE in row.motionCap.available) add(PointerPillFact.MOUSE_READY)
    }

// The feedback surfaces the bound path can land, shown as capability pills so
// the card lists everything active on the slot, not just the classic three.
internal enum class FeedbackPillFact { TRIGGER_RUMBLE, LIGHTBAR, TRIGGER_EFFECTS, PLAYER_LEDS }

internal fun feedbackFuncFacts(caps: SlotCapabilities): List<FeedbackPillFact> =
    buildList {
        if (Feature.TRIGGER_RUMBLE in caps.available) add(FeedbackPillFact.TRIGGER_RUMBLE)
        if (Feature.LIGHTBAR in caps.available) add(FeedbackPillFact.LIGHTBAR)
        if (Feature.TRIGGER_EFFECTS in caps.available) add(FeedbackPillFact.TRIGGER_EFFECTS)
        if (Feature.PLAYER_LEDS in caps.available) add(FeedbackPillFact.PLAYER_LEDS)
    }
