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
    {KC_BUTTON_MODE, XUSB_GUIDE},
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

enum class N64Input : uint8_t {
    A,
    B,
    Z,
    L,
    R,
    ZR,
    START,
    C_UP,
    C_DOWN,
    C_LEFT,
    C_RIGHT,
    HOME,
    CAPTURE,
    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
};

struct N64KeyMapping {
    int32_t androidKeycode;
    N64Input input;
};

constexpr N64KeyMapping N64_DRIVER_KEYCODE_MAP[] = {
    {KC_BUTTON_A, N64Input::A},
    {KC_BUTTON_B, N64Input::B},
    {KC_BUTTON_L2, N64Input::Z},
    {KC_BUTTON_L1, N64Input::L},
    {KC_BUTTON_R1, N64Input::R},
    {KC_BUTTON_R2, N64Input::ZR},
    {KC_BUTTON_START, N64Input::START},
    {KC_BUTTON_SELECT, N64Input::C_UP},
    {KC_BUTTON_X, N64Input::C_DOWN},
    {KC_BUTTON_Y, N64Input::C_LEFT},
    {KC_BUTTON_C, N64Input::C_RIGHT},
    {KC_BUTTON_MODE, N64Input::HOME},
    {KC_BUTTON_Z, N64Input::CAPTURE},
    {KC_DPAD_UP, N64Input::DPAD_UP},
    {KC_DPAD_DOWN, N64Input::DPAD_DOWN},
    {KC_DPAD_LEFT, N64Input::DPAD_LEFT},
    {KC_DPAD_RIGHT, N64Input::DPAD_RIGHT},
};

constexpr N64KeyMapping N64_HID_KEYCODE_MAP[] = {
    {KC_BUTTON_A, N64Input::B},
    {KC_BUTTON_B, N64Input::A},
    {KC_BUTTON_C, N64Input::C_UP},
    {KC_BUTTON_X, N64Input::C_LEFT},
    {KC_BUTTON_Y, N64Input::L},
    {KC_BUTTON_Z, N64Input::R},
    {KC_BUTTON_L1, N64Input::Z},
    {KC_BUTTON_R1, N64Input::C_DOWN},
    {KC_BUTTON_L2, N64Input::C_RIGHT},
    {KC_BUTTON_R2, N64Input::START},
    {KC_BUTTON_SELECT, N64Input::ZR},
    {KC_BUTTON_MODE, N64Input::HOME},
    {KC_BUTTON_THUMBL, N64Input::CAPTURE},
    {KC_DPAD_UP, N64Input::DPAD_UP},
    {KC_DPAD_DOWN, N64Input::DPAD_DOWN},
    {KC_DPAD_LEFT, N64Input::DPAD_LEFT},
    {KC_DPAD_RIGHT, N64Input::DPAD_RIGHT},
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

template <size_t N>
bool lookUpN64Input(const N64KeyMapping (&mappings)[N], const int32_t androidKeycode,
                    N64Input& out) {
    for (const N64KeyMapping& mapping : mappings) {
        const bool isTheKeycode = mapping.androidKeycode == androidKeycode;
        if (!isTheKeycode) continue;
        out = mapping.input;
        return true;
    }
    return false;
}

bool n64InputFor(const uint8_t quirk, const int32_t androidKeycode, N64Input& out) {
    const bool usesHidOrder = (quirk & QUIRK_N64_HID_LAYOUT) != 0;
    if (usesHidOrder) return lookUpN64Input(N64_HID_KEYCODE_MAP, androidKeycode, out);
    return lookUpN64Input(N64_DRIVER_KEYCODE_MAP, androidKeycode, out);
}

uint16_t n64ButtonBit(const N64Input input) {
    switch (input) {
    case N64Input::A:
        return XUSB_A;
    case N64Input::B:
        return XUSB_B;
    case N64Input::L:
        return XUSB_LB;
    case N64Input::R:
        return XUSB_RB;
    case N64Input::START:
        return XUSB_START;
    case N64Input::HOME:
        return XUSB_GUIDE;
    case N64Input::CAPTURE:
        return XUSB_BACK;
    case N64Input::DPAD_UP:
        return XUSB_DPAD_UP;
    case N64Input::DPAD_DOWN:
        return XUSB_DPAD_DOWN;
    case N64Input::DPAD_LEFT:
        return XUSB_DPAD_LEFT;
    case N64Input::DPAD_RIGHT:
        return XUSB_DPAD_RIGHT;
    case N64Input::Z:
    case N64Input::ZR:
    case N64Input::C_UP:
    case N64Input::C_DOWN:
    case N64Input::C_LEFT:
    case N64Input::C_RIGHT:
        return NO_XUSB_BIT;
    }
    return NO_XUSB_BIT;
}

uint8_t n64CBit(const N64Input input) {
    switch (input) {
    case N64Input::C_UP:
        return N64_C_UP;
    case N64Input::C_DOWN:
        return N64_C_DOWN;
    case N64Input::C_LEFT:
        return N64_C_LEFT;
    case N64Input::C_RIGHT:
        return N64_C_RIGHT;
    default:
        return 0;
    }
}

void setCButton(DeviceState& s, const uint8_t cBit, const bool down) {
    const uint8_t withBitSet = static_cast<uint8_t>(s.cButtons | cBit);
    const uint8_t withBitCleared = static_cast<uint8_t>(s.cButtons & ~cBit);
    s.cButtons = down ? withBitSet : withBitCleared;
    applyCButtonStick(s, s.cButtons);
}

bool applyN64Key(DeviceState& s, const int32_t androidKeycode, const bool down) {
    N64Input input = N64Input::A;
    const bool isMapped = n64InputFor(s.quirk, androidKeycode, input);
    if (!isMapped) return false;
    if (input == N64Input::Z) {
        setLeftTriggerFromKey(s, down);
        return true;
    }
    if (input == N64Input::ZR) {
        setRightTriggerFromKey(s, down);
        return true;
    }
    const uint8_t cBit = n64CBit(input);
    if (cBit != 0) {
        setCButton(s, cBit, down);
        return true;
    }
    setButtonBit(s, n64ButtonBit(input), down);
    return true;
}

bool n64LayoutConsumesKey(const uint8_t quirk, const int32_t androidKeycode) {
    N64Input input = N64Input::A;
    return n64InputFor(quirk, androidKeycode, input);
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
    const uint16_t mappedBit = standardKeycodeToXusb(androidKeycode, s.quirk);
    const uint16_t xusbBit = applyButtonQuirk(mappedBit, s.quirk);
    const bool isMapped = xusbBit != NO_XUSB_BIT;
    if (!isMapped) return false;

    setButtonBit(s, xusbBit, down);
    return true;
}

bool standardLayoutConsumesKey(const int32_t androidKeycode, const uint8_t quirk) {
    const bool isATriggerKey =
        isStandardLeftTriggerKey(androidKeycode) || isStandardRightTriggerKey(androidKeycode);
    const bool isAMappedButton = standardKeycodeToXusb(androidKeycode, quirk) != NO_XUSB_BIT;
    return isATriggerKey || isAMappedButton;
}

float pickLargerMagnitude(const float a, const float b) {
    return std::fabs(a) >= std::fabs(b) ? a : b;
}

float centredToTrigger(const float v) { return std::clamp((v + 1.f) / 2.f, 0.f, 1.f); }

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

uint16_t standardKeycodeToXusb(const int32_t androidKeycode, const uint8_t quirk) {
    const bool heartIsGuide =
        (quirk & QUIRK_EIGHTBITDO_DINPUT) != 0 && androidKeycode == KC_BUTTON_C;
    if (heartIsGuide) return XUSB_GUIDE;
    return keycodeToXusb(androidKeycode);
}

ReducedAxes reduceFrameworkAxes(const FrameworkAxes& a, const uint8_t quirk) {
    const bool zAndRzAreTheTriggers = (quirk & QUIRK_XPAD_TRIGGER_AXES) != 0;
    if (zAndRzAreTheTriggers) return {a.rx, a.ry, centredToTrigger(a.z), centredToTrigger(a.rz)};
    const float rightX = pickLargerMagnitude(a.z, a.rx);
    const float rightY = pickLargerMagnitude(a.rz, a.ry);
    const bool analogTriggersOnlyEchoTheKeys = (quirk & QUIRK_EIGHTBITDO_DINPUT) != 0;
    if (analogTriggersOnlyEchoTheKeys) return {rightX, rightY, a.lTrigger, a.rTrigger};
    return {rightX, rightY, std::max(a.lTrigger, a.brake), std::max(a.rTrigger, a.gas)};
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

bool usesN64Layout(const uint8_t quirk) {
    return (quirk & (QUIRK_N64_HID_LAYOUT | QUIRK_N64_DRIVER_LAYOUT)) != 0;
}

bool consumesKey(const int32_t androidKeycode, const uint8_t quirk) {
    if (usesN64Layout(quirk)) return n64LayoutConsumesKey(quirk, androidKeycode);
    const bool usesSwitchLayout = (quirk & QUIRK_SWITCH_LAYOUT) != 0;
    if (usesSwitchLayout) return switchLayoutConsumesKey(androidKeycode);
    return standardLayoutConsumesKey(androidKeycode, quirk);
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
    if (usesN64Layout(s.quirk)) return applyN64Key(s, androidKeycode, down);
    const bool usesSwitchLayout = (s.quirk & QUIRK_SWITCH_LAYOUT) != 0;
    if (usesSwitchLayout) return applySwitchLayoutKey(s, androidKeycode, down);
    return applyStandardKey(s, androidKeycode, down);
}

void applyAxes(DeviceState& s, const float x, const float y, const float z, const float rz,
               const float leftTrigger, const float rightTrigger, const float hatX,
               const float hatY) {
    s.sLX = scaleAxis(deadzone(x, s.flatX), AXIS_MAX);
    s.sLY = scaleAxis(deadzone(y, s.flatY), INVERTED_AXIS_MAX);
    const bool rightStickIsTheCButtons = usesN64Layout(s.quirk);
    if (!rightStickIsTheCButtons) {
        s.sRX = scaleAxis(deadzone(z, s.flatZ), AXIS_MAX);
        s.sRY = scaleAxis(deadzone(rz, s.flatRZ), INVERTED_AXIS_MAX);
    }

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
    s.cButtons = 0;
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
