// SPDX-License-Identifier: LGPL-3.0-or-later

#include "gamepad_input.h"

#include <algorithm>
#include <cmath>

namespace gamepad {

int16_t scaleAxis(float v, float max) {
    float s = v * max;
    if (s > 32767.f) s = 32767.f;
    if (s < -32768.f) s = -32768.f;
    return static_cast<int16_t>(s);
}

uint8_t scaleTrigger(float v, float max) {
    float s = v * max;
    if (s > 255.f) s = 255.f;
    if (s < 0.f) s = 0.f;
    return static_cast<uint8_t>(s);
}

float deadzone(float v, float flat) { return std::fabs(v) > flat ? v : 0.f; }

uint16_t keycodeToXusb(int32_t kc) {
    switch (kc) {
    case KC_BUTTON_A:
        return XUSB_A;
    case KC_BUTTON_B:
        return XUSB_B;
    case KC_BUTTON_X:
        return XUSB_X;
    case KC_BUTTON_Y:
        return XUSB_Y;
    case KC_BUTTON_L1:
        return XUSB_LB;
    case KC_BUTTON_R1:
        return XUSB_RB;
    case KC_BUTTON_THUMBL:
        return XUSB_THUMB_L;
    case KC_BUTTON_THUMBR:
        return XUSB_THUMB_R;
    case KC_BUTTON_START:
        return XUSB_START;
    case KC_BUTTON_SELECT:
        return XUSB_BACK;
    case KC_DPAD_UP:
        return XUSB_DPAD_UP;
    case KC_DPAD_DOWN:
        return XUSB_DPAD_DOWN;
    case KC_DPAD_LEFT:
        return XUSB_DPAD_LEFT;
    case KC_DPAD_RIGHT:
        return XUSB_DPAD_RIGHT;
    case KC_BUTTON_1:
        return XUSB_A;
    case KC_BUTTON_2:
        return XUSB_B;
    case KC_BUTTON_3:
        return XUSB_X;
    case KC_BUTTON_4:
        return XUSB_Y;
    case KC_BUTTON_5:
        return XUSB_LB;
    case KC_BUTTON_6:
        return XUSB_RB;
    case KC_BUTTON_9:
        return XUSB_BACK;
    case KC_BUTTON_10:
        return XUSB_START;
    case KC_BUTTON_11:
        return XUSB_THUMB_L;
    case KC_BUTTON_12:
        return XUSB_THUMB_R;
    default:
        return 0;
    }
}

bool applyKey(DeviceState& s, int32_t kc, bool down) {
    if (kc == KC_BUTTON_L2 || kc == KC_BUTTON_7) {
        s.ltFromKey = down;
        s.bLT = down ? 255 : 0;
        return true;
    }
    if (kc == KC_BUTTON_R2 || kc == KC_BUTTON_8) {
        s.rtFromKey = down;
        s.bRT = down ? 255 : 0;
        return true;
    }
    uint16_t bit = keycodeToXusb(kc);
    if (bit == 0) return false;
    if (down) {
        s.wButtons = static_cast<uint16_t>(s.wButtons | bit);
    } else {
        s.wButtons = static_cast<uint16_t>(s.wButtons & ~bit);
    }
    return true;
}

void applyAxes(DeviceState& s, float x, float y, float z, float rz, float leftTrigger,
               float rightTrigger, float hatX, float hatY) {
    s.sLX = scaleAxis(deadzone(x, s.flatX), 32767.f);
    s.sLY = scaleAxis(deadzone(y, s.flatY), -32767.f);
    s.sRX = scaleAxis(deadzone(z, s.flatZ), 32767.f);
    s.sRY = scaleAxis(deadzone(rz, s.flatRZ), -32767.f);

    if (!s.ltFromKey) s.bLT = scaleTrigger(leftTrigger, 255.f);
    if (!s.rtFromKey) s.bRT = scaleTrigger(rightTrigger, 255.f);

    s.wButtons = static_cast<uint16_t>(s.wButtons & ~XUSB_DPAD_MASK);
    if (hatX < -0.5f) s.wButtons = static_cast<uint16_t>(s.wButtons | XUSB_DPAD_LEFT);
    if (hatX > 0.5f) s.wButtons = static_cast<uint16_t>(s.wButtons | XUSB_DPAD_RIGHT);
    if (hatY < -0.5f) s.wButtons = static_cast<uint16_t>(s.wButtons | XUSB_DPAD_UP);
    if (hatY > 0.5f) s.wButtons = static_cast<uint16_t>(s.wButtons | XUSB_DPAD_DOWN);
}

void resetState(DeviceState& s) {
    s.wButtons = 0;
    s.bLT = 0;
    s.bRT = 0;
    s.sLX = s.sLY = s.sRX = s.sRY = 0;
    s.ltFromKey = false;
    s.rtFromKey = false;
}

bool consumePublishIfChanged(DeviceState& s) {
    if (s.everPublished && s.lastWButtons == s.wButtons && s.lastBLT == s.bLT &&
        s.lastBRT == s.bRT && s.lastSLX == s.sLX && s.lastSLY == s.sLY && s.lastSRX == s.sRX &&
        s.lastSRY == s.sRY) {
        return false;
    }
    s.lastWButtons = s.wButtons;
    s.lastBLT = s.bLT;
    s.lastBRT = s.bRT;
    s.lastSLX = s.sLX;
    s.lastSLY = s.sLY;
    s.lastSRX = s.sRX;
    s.lastSRY = s.sRY;
    s.everPublished = true;
    return true;
}

} // namespace gamepad
