// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <stdint.h>
#include <string>

namespace usbhost {

struct AttachResult {
    int32_t syntheticDeviceId = 0;
    bool ok = false;
};

AttachResult attachDevice(int fd, uint16_t vid, uint16_t pid, int interfaceNumber,
                          uint8_t endpointIn, uint16_t endpointInMaxPacket, uint8_t endpointOut,
                          uint8_t interfaceClass, uint8_t interfaceSubclass,
                          uint8_t interfaceProtocol);

void detachDevice(int32_t syntheticDeviceId);

uint64_t getUrbCount(int32_t deviceId);

uint64_t getMotionCount(int32_t deviceId);

void sendRumble(int32_t syntheticDeviceId, uint16_t strong, uint16_t weak);

void sendTriggerRumble(int32_t syntheticDeviceId, uint16_t left, uint16_t right);

void sendLightbar(int32_t syntheticDeviceId, uint8_t r, uint8_t g, uint8_t b);

void sendPlayerLeds(int32_t syntheticDeviceId, uint8_t ledMask);

// left/right are the raw 11-byte DualSense trigger-effect blocks.
void sendTriggerEffects(int32_t syntheticDeviceId, const uint8_t* left, const uint8_t* right);

} // namespace usbhost
