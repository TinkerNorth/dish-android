// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <stdint.h>

namespace gamepad {

constexpr uint16_t XUSB_DPAD_UP = 0x0001;
constexpr uint16_t XUSB_DPAD_DOWN = 0x0002;
constexpr uint16_t XUSB_DPAD_LEFT = 0x0004;
constexpr uint16_t XUSB_DPAD_RIGHT = 0x0008;
constexpr uint16_t XUSB_START = 0x0010;
constexpr uint16_t XUSB_BACK = 0x0020;
constexpr uint16_t XUSB_THUMB_L = 0x0040;
constexpr uint16_t XUSB_THUMB_R = 0x0080;
constexpr uint16_t XUSB_LB = 0x0100;
constexpr uint16_t XUSB_RB = 0x0200;
constexpr uint16_t XUSB_A = 0x1000;
constexpr uint16_t XUSB_B = 0x2000;
constexpr uint16_t XUSB_X = 0x4000;
constexpr uint16_t XUSB_Y = 0x8000;
constexpr uint16_t XUSB_DPAD_MASK = 0x000F;

// Mirrored from <android/keycodes.h> so this header builds host-side for tests.
constexpr int32_t KC_DPAD_UP = 19;
constexpr int32_t KC_DPAD_DOWN = 20;
constexpr int32_t KC_DPAD_LEFT = 21;
constexpr int32_t KC_DPAD_RIGHT = 22;
constexpr int32_t KC_BUTTON_A = 96;
constexpr int32_t KC_BUTTON_B = 97;
constexpr int32_t KC_BUTTON_X = 99;
constexpr int32_t KC_BUTTON_Y = 100;
constexpr int32_t KC_BUTTON_L1 = 102;
constexpr int32_t KC_BUTTON_R1 = 103;
constexpr int32_t KC_BUTTON_L2 = 104;
constexpr int32_t KC_BUTTON_R2 = 105;
constexpr int32_t KC_BUTTON_THUMBL = 106;
constexpr int32_t KC_BUTTON_THUMBR = 107;
constexpr int32_t KC_BUTTON_START = 108;
constexpr int32_t KC_BUTTON_SELECT = 109;

constexpr int32_t KC_BUTTON_1 = 188;
constexpr int32_t KC_BUTTON_2 = 189;
constexpr int32_t KC_BUTTON_3 = 190;
constexpr int32_t KC_BUTTON_4 = 191;
constexpr int32_t KC_BUTTON_5 = 192;
constexpr int32_t KC_BUTTON_6 = 193;
constexpr int32_t KC_BUTTON_7 = 194;
constexpr int32_t KC_BUTTON_8 = 195;
constexpr int32_t KC_BUTTON_9 = 196;
constexpr int32_t KC_BUTTON_10 = 197;
constexpr int32_t KC_BUTTON_11 = 198;
constexpr int32_t KC_BUTTON_12 = 199;
constexpr int32_t KC_BUTTON_13 = 200;
constexpr int32_t KC_BUTTON_14 = 201;
constexpr int32_t KC_BUTTON_15 = 202;
constexpr int32_t KC_BUTTON_16 = 203;

struct DeviceState {
    uint16_t wButtons = 0;
    uint8_t bLT = 0, bRT = 0;
    int16_t sLX = 0, sLY = 0, sRX = 0, sRY = 0;

    // While true, ignore axis-side trigger reads so a 0 sample doesn't clobber a held key.
    bool ltFromKey = false, rtFromKey = false;

    bool everPublished = false;
    uint16_t lastWButtons = 0;
    uint8_t lastBLT = 0, lastBRT = 0;
    int16_t lastSLX = 0, lastSLY = 0, lastSRX = 0, lastSRY = 0;

    float flatX = 0.f, flatY = 0.f, flatZ = 0.f, flatRZ = 0.f;

    // Motion in wire units, filled by parsers that decode an on-controller IMU. Not part of
    // consumePublishIfChanged (gamepad data); the USB poll loop ships these separately as MSG_MOTION.
    bool motionValid = false;
    int16_t gyroX = 0, gyroY = 0, gyroZ = 0;
    int16_t accelX = 0, accelY = 0, accelZ = 0;
};

int16_t scaleAxis(float v, float max);

uint8_t scaleTrigger(float v, float max);

float deadzone(float v, float flat);

uint16_t keycodeToXusb(int32_t androidKeycode);

bool applyKey(DeviceState& s, int32_t androidKeycode, bool down);

// Caller pre-resolves trigger pairs: lt=max(LTRIGGER,BRAKE), rt=max(RTRIGGER,GAS).
void applyAxes(DeviceState& s, float x, float y, float z, float rz, float leftTrigger,
               float rightTrigger, float hatX, float hatY);

void resetState(DeviceState& s);

bool consumePublishIfChanged(DeviceState& s);

} // namespace gamepad
