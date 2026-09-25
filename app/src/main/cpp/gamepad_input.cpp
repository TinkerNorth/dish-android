// SPDX-License-Identifier: LGPL-3.0-or-later

#include "gamepad_input.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdio>

namespace gamepad {

namespace {

constexpr float AXIS_MAX = 32767.f;
constexpr float AXIS_MIN = -32768.f;
constexpr float TRIGGER_MAX = 255.f;
constexpr float TRIGGER_MIN = 0.f;

} // namespace

int16_t scaleAxis(const float v, const float max) {
    const float scaled = v * max;
    const float clamped = std::clamp(scaled, AXIS_MIN, AXIS_MAX);
    return static_cast<int16_t>(clamped);
}

uint8_t scaleTrigger(const float v, const float max) {
    const float scaled = v * max;
    const float clamped = std::clamp(scaled, TRIGGER_MIN, TRIGGER_MAX);
    return static_cast<uint8_t>(clamped);
}

float deadzone(const float v, const float flat) {
    const bool isOutsideTheFlatZone = std::fabs(v) > flat;
    return isOutsideTheFlatZone ? v : 0.f;
}

namespace {

struct KeycodeMapping {
    int32_t androidKeycode;
    uint16_t xusbBit;
};

constexpr KeycodeMapping BASE_KEYCODE_MAP[] = {
    {KC_BUTTON_A, XUSB_A},
    {KC_BUTTON_B, XUSB_B},
    {KC_BUTTON_X, XUSB_X},
    {KC_BUTTON_Y, XUSB_Y},
    {KC_BUTTON_L1, XUSB_LB},
    {KC_BUTTON_R1, XUSB_RB},
    {KC_BUTTON_THUMBL, XUSB_THUMB_L},
    {KC_BUTTON_THUMBR, XUSB_THUMB_R},
    {KC_BUTTON_START, XUSB_START},
    {KC_BUTTON_SELECT, XUSB_BACK},
    {KC_DPAD_UP, XUSB_DPAD_UP},
    {KC_DPAD_DOWN, XUSB_DPAD_DOWN},
    {KC_DPAD_LEFT, XUSB_DPAD_LEFT},
    {KC_DPAD_RIGHT, XUSB_DPAD_RIGHT},
    {KC_BUTTON_1, XUSB_A},
    {KC_BUTTON_2, XUSB_B},
    {KC_BUTTON_3, XUSB_X},
    {KC_BUTTON_4, XUSB_Y},
    {KC_BUTTON_5, XUSB_LB},
    {KC_BUTTON_6, XUSB_RB},
    {KC_BUTTON_9, XUSB_BACK},
    {KC_BUTTON_10, XUSB_START},
    {KC_BUTTON_11, XUSB_THUMB_L},
    {KC_BUTTON_12, XUSB_THUMB_R},
};

constexpr KeycodeMapping SWITCH_LAYOUT_KEYCODE_MAP[] = {
    {KC_BUTTON_A, XUSB_X},
    {KC_BUTTON_B, XUSB_A},
    {KC_BUTTON_C, XUSB_B},
    {KC_BUTTON_X, XUSB_Y},
    {KC_BUTTON_Y, XUSB_LB},
    {KC_BUTTON_Z, XUSB_RB},
    {KC_BUTTON_L2, XUSB_BACK},
    {KC_BUTTON_R2, XUSB_START},
    {KC_BUTTON_SELECT, XUSB_THUMB_L},
    {KC_BUTTON_START, XUSB_THUMB_R},
    {KC_BUTTON_MODE, XUSB_GUIDE},
    {KC_DPAD_UP, XUSB_DPAD_UP},
    {KC_DPAD_DOWN, XUSB_DPAD_DOWN},
    {KC_DPAD_LEFT, XUSB_DPAD_LEFT},
    {KC_DPAD_RIGHT, XUSB_DPAD_RIGHT},
};

constexpr uint16_t NO_XUSB_BIT = 0;
constexpr uint8_t TRIGGER_FULL = 255;
constexpr uint8_t TRIGGER_RELEASED = 0;

template <size_t N>
uint16_t lookUpXusbBit(const KeycodeMapping (&mappings)[N], const int32_t androidKeycode) {
    for (const KeycodeMapping& mapping : mappings) {
        const bool isTheKeycode = mapping.androidKeycode == androidKeycode;
        if (isTheKeycode) return mapping.xusbBit;
    }
    return NO_XUSB_BIT;
}

void setButtonBit(DeviceState& s, const uint16_t xusbBit, const bool down) {
    const uint16_t withBitSet = static_cast<uint16_t>(s.wButtons | xusbBit);
    const uint16_t withBitCleared = static_cast<uint16_t>(s.wButtons & ~xusbBit);
    s.wButtons = down ? withBitSet : withBitCleared;
}

void setLeftTriggerFromKey(DeviceState& s, const bool down) {
    s.ltFromKey = down;
    s.bLT = down ? TRIGGER_FULL : TRIGGER_RELEASED;
}

void setRightTriggerFromKey(DeviceState& s, const bool down) {
    s.rtFromKey = down;
    s.bRT = down ? TRIGGER_FULL : TRIGGER_RELEASED;
}

bool applySwitchLayoutKey(DeviceState& s, const int32_t androidKeycode, const bool down) {
    const bool isZl = androidKeycode == KC_BUTTON_L1;
    if (isZl) {
        setLeftTriggerFromKey(s, down);
        return true;
    }
    const bool isZr = androidKeycode == KC_BUTTON_R1;
    if (isZr) {
        setRightTriggerFromKey(s, down);
        return true;
    }
    const uint16_t xusbBit = switchLayoutKeycodeToXusb(androidKeycode);
    const bool isMapped = xusbBit != NO_XUSB_BIT;
    if (!isMapped) return false;

    setButtonBit(s, xusbBit, down);
    return true;
}

bool applyStandardKey(DeviceState& s, const int32_t androidKeycode, const bool down) {
    const bool isLeftTrigger = androidKeycode == KC_BUTTON_L2 || androidKeycode == KC_BUTTON_7;
    if (isLeftTrigger) {
        setLeftTriggerFromKey(s, down);
        return true;
    }
    const bool isRightTrigger = androidKeycode == KC_BUTTON_R2 || androidKeycode == KC_BUTTON_8;
    if (isRightTrigger) {
        setRightTriggerFromKey(s, down);
        return true;
    }
    const uint16_t mappedBit = keycodeToXusb(androidKeycode);
    const uint16_t xusbBit = applyButtonQuirk(mappedBit, s.quirk);
    const bool isMapped = xusbBit != NO_XUSB_BIT;
    if (!isMapped) return false;

    setButtonBit(s, xusbBit, down);
    return true;
}

} // namespace

uint16_t keycodeToXusb(const int32_t androidKeycode) {
    return lookUpXusbBit(BASE_KEYCODE_MAP, androidKeycode);
}

uint16_t switchLayoutKeycodeToXusb(const int32_t androidKeycode) {
    return lookUpXusbBit(SWITCH_LAYOUT_KEYCODE_MAP, androidKeycode);
}

bool switchLayoutConsumesKey(const int32_t androidKeycode) {
    const bool isZl = androidKeycode == KC_BUTTON_L1;
    const bool isZr = androidKeycode == KC_BUTTON_R1;
    const bool isMappedButton = switchLayoutKeycodeToXusb(androidKeycode) != NO_XUSB_BIT;
    return isZl || isZr || isMappedButton;
}

uint16_t applyButtonQuirk(const uint16_t xusbBit, const uint8_t quirk) {
    const bool swapsAb = (quirk & QUIRK_SWAP_AB) != 0;
    if (swapsAb) {
        if (xusbBit == XUSB_A) return XUSB_B;
        if (xusbBit == XUSB_B) return XUSB_A;
    }
    const bool swapsXy = (quirk & QUIRK_SWAP_XY) != 0;
    if (swapsXy) {
        if (xusbBit == XUSB_X) return XUSB_Y;
        if (xusbBit == XUSB_Y) return XUSB_X;
    }
    return xusbBit;
}

bool applyKey(DeviceState& s, const int32_t androidKeycode, const bool down) {
    const bool usesSwitchLayout = (s.quirk & QUIRK_SWITCH_LAYOUT) != 0;
    if (usesSwitchLayout) return applySwitchLayoutKey(s, androidKeycode, down);
    return applyStandardKey(s, androidKeycode, down);
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
