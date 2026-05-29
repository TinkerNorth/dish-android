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
                          uint8_t endpointIn, uint16_t endpointInMaxPacket, uint8_t endpointOut);

void detachDevice(int32_t syntheticDeviceId);

uint64_t getUrbCount(int32_t deviceId);

} // namespace usbhost
