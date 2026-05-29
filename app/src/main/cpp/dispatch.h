// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <stdint.h>

#include "gamepad_input.h"

namespace dispatch {

// Default-constructs the device entry now, so the first hot-path applyUsbReport call doesn't
// trigger an unordered_map node allocation under the devices mutex. Called from attach.
void prewarmDevice(int32_t deviceId);

// Copies the input/axis fields from `nu` into the device's tracked state under the existing
// devices mutex, then runs the standard publishIfChanged path so a USB-host fast-lane report
// flows out through the same encrypted-UDP send queue as a framework-routed event.
void applyUsbReport(int32_t deviceId, const gamepad::DeviceState& nu);

// Resets the device's tracked state to neutral and runs publishIfChanged so an unplug or
// fast-lane teardown clears any held buttons on the satellite side.
void resetAndPublish(int32_t deviceId);

// Drops the device entry entirely. Use on USB detach so a future re-plug starts clean.
void forgetDevice(int32_t deviceId);

// Routes a decoded IMU sample (wire units) to the device's bound satellite session as MSG_MOTION.
// No-ops if the device isn't bound to a satellite. The server only consumes it when the slot's
// advertised caps include motion (i.e. the user's motion toggle is on), so this stays gated by the
// existing caps/sink mechanism.
void applyUsbMotion(int32_t deviceId, int16_t gyroX, int16_t gyroY, int16_t gyroZ, int16_t accelX,
                    int16_t accelY, int16_t accelZ, uint32_t timestampDeltaUs);

} // namespace dispatch
