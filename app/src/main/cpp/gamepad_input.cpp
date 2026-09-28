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
constexpr float INVERTED_AXIS_MAX = -AXIS_MAX;
constexpr float TRIGGER_MAX = 255.f;
constexpr float TRIGGER_MIN = 0.f;
constexpr float HAT_THRESHOLD = 0.5f;
constexpr int64_t NS_PER_MS = 1000000;

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

bool isStandardLeftTriggerKey(const int32_t androidKeycode) {
    return androidKeycode == KC_BUTTON_L2 || androidKeycode == KC_BUTTON_7;
}

bool isStandardRightTriggerKey(const int32_t androidKeycode) {
    return androidKeycode == KC_BUTTON_R2 || androidKeycode == KC_BUTTON_8;
}

bool applyStandardKey(DeviceState& s, const int32_t androidKeycode, const bool down) {
    if (isStandardLeftTriggerKey(androidKeycode)) {
        setLeftTriggerFromKey(s, down);
        return true;
    }
    if (isStandardRightTriggerKey(androidKeycode)) {
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

bool standardLayoutConsumesKey(const int32_t androidKeycode) {
    const bool isATriggerKey =
        isStandardLeftTriggerKey(androidKeycode) || isStandardRightTriggerKey(androidKeycode);
    const bool isAMappedButton = keycodeToXusb(androidKeycode) != NO_XUSB_BIT;
    return isATriggerKey || isAMappedButton;
}

uint16_t hatAxesToDpadBits(const float hatX, const float hatY) {
    uint16_t bits = 0;
    if (hatX < -HAT_THRESHOLD) bits |= XUSB_DPAD_LEFT;
    if (hatX > HAT_THRESHOLD) bits |= XUSB_DPAD_RIGHT;
    if (hatY < -HAT_THRESHOLD) bits |= XUSB_DPAD_UP;
    if (hatY > HAT_THRESHOLD) bits |= XUSB_DPAD_DOWN;
    return bits;
}

bool isTouchpadEdge(const TouchpadState& last, const TouchpadState& cur) {
    const bool contactChanged = cur.f0Active != last.f0Active || cur.f1Active != last.f1Active;
    const bool clickChanged = cur.clickDown != last.clickDown;
    const bool trackingIdChanged = cur.f0Id != last.f0Id || cur.f1Id != last.f1Id;
    return contactChanged || clickChanged || trackingIdChanged;
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

bool consumesKey(const int32_t androidKeycode, const uint8_t quirk) {
    const bool usesSwitchLayout = (quirk & QUIRK_SWITCH_LAYOUT) != 0;
    if (usesSwitchLayout) return switchLayoutConsumesKey(androidKeycode);
    return standardLayoutConsumesKey(androidKeycode);
}

KeyVerdict keyVerdict(const int32_t androidKeycode, const uint8_t quirk, const int32_t action) {
    if (!consumesKey(androidKeycode, quirk)) return KeyVerdict::PASS;
    const bool isAnEdge = action == KEY_ACTION_DOWN || action == KEY_ACTION_UP;
    if (!isAnEdge) return KeyVerdict::SWALLOW;
    return KeyVerdict::APPLY;
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

void applyAxes(DeviceState& s, const float x, const float y, const float z, const float rz,
               const float leftTrigger, const float rightTrigger, const float hatX,
               const float hatY) {
    s.sLX = scaleAxis(deadzone(x, s.flatX), AXIS_MAX);
    s.sLY = scaleAxis(deadzone(y, s.flatY), INVERTED_AXIS_MAX);
    s.sRX = scaleAxis(deadzone(z, s.flatZ), AXIS_MAX);
    s.sRY = scaleAxis(deadzone(rz, s.flatRZ), INVERTED_AXIS_MAX);

    if (!s.ltFromKey) s.bLT = scaleTrigger(leftTrigger, TRIGGER_MAX);
    if (!s.rtFromKey) s.bRT = scaleTrigger(rightTrigger, TRIGGER_MAX);

    const uint16_t withoutDpad = static_cast<uint16_t>(s.wButtons & ~XUSB_DPAD_MASK);
    s.wButtons = static_cast<uint16_t>(withoutDpad | hatAxesToDpadBits(hatX, hatY));
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
    const bool sameButtons = s.lastWButtons == s.wButtons;
    const bool sameTriggers = s.lastBLT == s.bLT && s.lastBRT == s.bRT;
    const bool sameLeftStick = s.lastSLX == s.sLX && s.lastSLY == s.sLY;
    const bool sameRightStick = s.lastSRX == s.sRX && s.lastSRY == s.sRY;
    const bool sameReport = sameButtons && sameTriggers && sameLeftStick && sameRightStick;
    const bool isUnchanged = s.everPublished && sameReport;
    if (isUnchanged) return false;
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

size_t formatDeviceStateJson(const DeviceState& s, char* buf, const size_t cap) {
    const int n = snprintf(
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
    const bool fits = n >= 0 && (size_t)n < cap;
    return fits ? (size_t)n : 0;
}

bool operator==(const TouchpadState& a, const TouchpadState& b) {
    const bool sameContacts = a.f0Active == b.f0Active && a.f1Active == b.f1Active;
    const bool sameClick = a.clickDown == b.clickDown;
    const bool sameIds = a.f0Id == b.f0Id && a.f1Id == b.f1Id;
    const bool sameFinger0 = a.f0X == b.f0X && a.f0Y == b.f0Y;
    const bool sameFinger1 = a.f1X == b.f1X && a.f1Y == b.f1Y;
    const bool sameTouches = sameContacts && sameIds && sameFinger0 && sameFinger1;
    return sameTouches && sameClick;
}

TouchpadSend TouchpadGate::decide(const TouchpadState& cur, const int64_t nowNs) {
    const bool changed = cur != last_;
    if (changed) return decideOnChange(cur, nowNs);
    return decideHeal(nowNs);
}

TouchpadSend TouchpadGate::decideOnChange(const TouchpadState& cur, const int64_t nowNs) {
    const bool isAnEdge = isTouchpadEdge(last_, cur);
    const bool isInsideTheMoveInterval = nowNs - lastSentNs_ < kTouchpadMoveIntervalNs;
    const bool coalesces = !isAnEdge && isInsideTheMoveInterval;
    if (coalesces) return TouchpadSend::SKIP;
    last_ = cur;
    lastSentNs_ = nowNs;
    lastEventMs_ = nowNs / NS_PER_MS;
    resendsLeft_ = kTouchpadHealResends;
    return TouchpadSend::FRESH;
}

TouchpadSend TouchpadGate::decideHeal(const int64_t nowNs) {
    const bool hasResendsLeft = resendsLeft_ > 0;
    const bool theIntervalElapsed = nowNs - lastSentNs_ >= kTouchpadMoveIntervalNs;
    const bool heals = hasResendsLeft && theIntervalElapsed;
    if (!heals) return TouchpadSend::SKIP;
    resendsLeft_--;
    lastSentNs_ = nowNs;
    return TouchpadSend::HEAL;
}

} // namespace gamepad
