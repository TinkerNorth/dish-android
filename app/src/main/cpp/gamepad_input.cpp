// SPDX-License-Identifier: LGPL-3.0-or-later

#include "gamepad_input.h"

#include <algorithm>
#include <cmath>
#include <cstdio>

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

uint16_t applyButtonQuirk(uint16_t bit, uint8_t quirk) {
    if (quirk & QUIRK_SWAP_AB) {
        if (bit == XUSB_A) return XUSB_B;
        if (bit == XUSB_B) return XUSB_A;
    }
    if (quirk & QUIRK_SWAP_XY) {
        if (bit == XUSB_X) return XUSB_Y;
        if (bit == XUSB_Y) return XUSB_X;
    }
    return bit;
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
    uint16_t bit = applyButtonQuirk(keycodeToXusb(kc), s.quirk);
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

void resetPublishLatch(DeviceState& s) {
    s.everPublished = false;
    s.lastWButtons = 0;
    s.lastBLT = 0;
    s.lastBRT = 0;
    s.lastSLX = 0;
    s.lastSLY = 0;
    s.lastSRX = 0;
    s.lastSRY = 0;
}

size_t formatDeviceStateJson(const DeviceState& s, char* buf, size_t cap) {
    int n = snprintf(
        buf, cap,
        "{\"buttons\":%u,\"lt\":%u,\"rt\":%u,\"lx\":%d,\"ly\":%d,\"rx\":%d,\"ry\":%d,"
        "\"motionValid\":%s,\"gx\":%d,\"gy\":%d,\"gz\":%d,\"ax\":%d,\"ay\":%d,\"az\":%d,"
        "\"touchValid\":%s,\"f0Active\":%s,\"f0Id\":%u,\"f0X\":%d,\"f0Y\":%d,"
        "\"f1Active\":%s,\"f1Id\":%u,\"f1X\":%d,\"f1Y\":%d,\"click\":%s}",
        (unsigned)s.wButtons, (unsigned)s.bLT, (unsigned)s.bRT, (int)s.sLX, (int)s.sLY, (int)s.sRX,
        (int)s.sRY, s.motionValid ? "true" : "false", (int)s.gyroX, (int)s.gyroY, (int)s.gyroZ,
        (int)s.accelX, (int)s.accelY, (int)s.accelZ, s.touchValid ? "true" : "false",
        s.touch0Active ? "true" : "false", (unsigned)s.touch0Id, (int)s.touch0X, (int)s.touch0Y,
        s.touch1Active ? "true" : "false", (unsigned)s.touch1Id, (int)s.touch1X, (int)s.touch1Y,
        s.touchClick ? "true" : "false");
    if (n < 0 || (size_t)n >= cap) return 0;
    return (size_t)n;
}

bool operator==(const TouchpadState& a, const TouchpadState& b) {
    return a.f0Active == b.f0Active && a.f1Active == b.f1Active && a.clickDown == b.clickDown &&
           a.f0Id == b.f0Id && a.f1Id == b.f1Id && a.f0X == b.f0X && a.f0Y == b.f0Y &&
           a.f1X == b.f1X && a.f1Y == b.f1Y;
}

TouchpadSend TouchpadGate::decide(const TouchpadState& cur, int64_t nowNs) {
    if (cur != last_) {
        const bool edge = cur.f0Active != last_.f0Active || cur.f1Active != last_.f1Active ||
                          cur.clickDown != last_.clickDown || cur.f0Id != last_.f0Id ||
                          cur.f1Id != last_.f1Id;
        // A skipped move is not lost data: the next report carries fresher coordinates.
        if (!edge && nowNs - lastSentNs_ < kTouchpadMoveIntervalNs) return TouchpadSend::SKIP;
        last_ = cur;
        lastSentNs_ = nowNs;
        lastEventMs_ = nowNs / 1000000;
        resendsLeft_ = kTouchpadHealResends;
        return TouchpadSend::FRESH;
    }
    if (resendsLeft_ > 0 && nowNs - lastSentNs_ >= kTouchpadMoveIntervalNs) {
        resendsLeft_--;
        lastSentNs_ = nowNs;
        return TouchpadSend::HEAL;
    }
    return TouchpadSend::SKIP;
}

} // namespace gamepad
