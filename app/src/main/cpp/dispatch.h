// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <stdint.h>

#include "gamepad_input.h"

namespace dispatch {

void prewarmDevice(int32_t deviceId);

void applyUsbReport(int32_t deviceId, const gamepad::DeviceState& nu);

void resetAndPublish(int32_t deviceId);

void forgetDevice(int32_t deviceId);

void applyUsbMotion(int32_t deviceId, int16_t gyroX, int16_t gyroY, int16_t gyroZ, int16_t accelX,
                    int16_t accelY, int16_t accelZ, uint32_t timestampDeltaUs);

void applyUsbTouchpad(int32_t deviceId, const gamepad::TouchpadState& t, uint32_t eventTimeMs);

} // namespace dispatch
