// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <stdint.h>

#include "gamepad_input.h"
#include "slot_table.h"

namespace dispatch {

void prewarmDevice(int32_t deviceId);

void applyUsbReport(int32_t deviceId, const gamepad::DeviceState& nu);

void resetAndPublish(int32_t deviceId);

void forgetDevice(int32_t deviceId);

// Everything one URB produced: the pad state, a motion sample due at its cadence and a touch frame
// the send-on-change gate let through, sent under one hold of the session's gate.
void applyUsbFrame(int32_t deviceId, const dish_slots::UsbFrame& frame);

// The pad's own mic-mute button flipped its latch. The wire bit rides the ordinary input report
// (the decoder already folded it in); this only mirrors the new state up to Kotlin, where the
// capture engine gates on it and the lamp renders it. Rare (one call per press), and never
// called from the report path when nothing changed.
void applyPadMicMute(int32_t deviceId, bool muted);

} // namespace dispatch
