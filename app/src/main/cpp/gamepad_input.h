// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <stddef.h>
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
constexpr uint16_t XUSB_GUIDE = 0x0400;
constexpr uint16_t XUSB_A = 0x1000;
constexpr uint16_t XUSB_B = 0x2000;
constexpr uint16_t XUSB_X = 0x4000;
constexpr uint16_t XUSB_Y = 0x8000;
constexpr uint16_t XUSB_DPAD_MASK = 0x000F;

// A HID hat's eight directions, clockwise from up.
constexpr uint16_t HAT_DIRECTION_BITS[] = {
    XUSB_DPAD_UP,    XUSB_DPAD_UP | XUSB_DPAD_RIGHT,
    XUSB_DPAD_RIGHT, XUSB_DPAD_DOWN | XUSB_DPAD_RIGHT,
    XUSB_DPAD_DOWN,  XUSB_DPAD_DOWN | XUSB_DPAD_LEFT,
    XUSB_DPAD_LEFT,  XUSB_DPAD_UP | XUSB_DPAD_LEFT,
};

// Zero for any direction outside the eight, which is how every HID hat spells centred.
constexpr uint16_t hatDirectionBits(const int64_t direction) {
    const bool isADirection = direction >= 0 && direction < 8;
    if (!isADirection) return 0;
    return HAT_DIRECTION_BITS[direction];
}

// Not an XINPUT bit: 0x0800 is the one value the XINPUT-shaped word leaves
// unassigned, and protocol 2 spends it on the DualSense mic-mute button
// (satellite core/types.h WBUTTON_MIC_MUTE, docs/contract.md §Controller
// audio). Only the DualSense identity maps it into the emulated pad's input
// report; every other identity ignores it. It is state, not an edge, like every
// other button in the word, and it rides the ordinary MSG_GAMEPAD_DATA frame:
// the whole 16-bit word is copied through, so nothing on this path has to know
// about it beyond leaving it alone.
constexpr uint16_t WBUTTON_MIC_MUTE = 0x0800;

// Mirrored from <android/keycodes.h> so this header builds host-side for tests.
constexpr int32_t KC_DPAD_UP = 19;
constexpr int32_t KC_DPAD_DOWN = 20;
constexpr int32_t KC_DPAD_LEFT = 21;
constexpr int32_t KC_DPAD_RIGHT = 22;
constexpr int32_t KC_BUTTON_A = 96;
constexpr int32_t KC_BUTTON_B = 97;
constexpr int32_t KC_BUTTON_C = 98;
constexpr int32_t KC_BUTTON_X = 99;
constexpr int32_t KC_BUTTON_Y = 100;
constexpr int32_t KC_BUTTON_Z = 101;
constexpr int32_t KC_BUTTON_L1 = 102;
constexpr int32_t KC_BUTTON_R1 = 103;
constexpr int32_t KC_BUTTON_L2 = 104;
constexpr int32_t KC_BUTTON_R2 = 105;
constexpr int32_t KC_BUTTON_THUMBL = 106;
constexpr int32_t KC_BUTTON_THUMBR = 107;
constexpr int32_t KC_BUTTON_START = 108;
constexpr int32_t KC_BUTTON_SELECT = 109;
constexpr int32_t KC_BUTTON_MODE = 110;

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

// Per-device framework-path button quirks (Nintendo and friends sit A/B and X/Y opposite to Xbox).
constexpr uint8_t QUIRK_SWAP_AB = 0x01;
constexpr uint8_t QUIRK_SWAP_XY = 0x02;
constexpr uint8_t QUIRK_SWITCH_LAYOUT = 0x04;
constexpr uint8_t QUIRK_N64_HID_LAYOUT = 0x08;
constexpr uint8_t QUIRK_N64_DRIVER_LAYOUT = 0x10;
// 8BitDo pads in D-input mode: Heart arrives as BTN_C and is Guide, and the analog Accelerator
// and Brake only echo the digital L and R (swapped over USB), so the keys alone drive the triggers.
constexpr uint8_t QUIRK_EIGHTBITDO_DINPUT = 0x20;
// An Xbox pad on the kernel's xpad driver with no key layout file: Generic.kl leaves the triggers
// on Z and RZ, centred axes that rest at -1, and the right stick on RX and RY.
constexpr uint8_t QUIRK_XPAD_TRIGGER_AXES = 0x40;

constexpr uint8_t N64_C_UP = 0x01;
constexpr uint8_t N64_C_DOWN = 0x02;
constexpr uint8_t N64_C_LEFT = 0x04;
constexpr uint8_t N64_C_RIGHT = 0x08;

struct DeviceState {
    uint16_t wButtons = 0;
    uint8_t bLT = 0, bRT = 0;
    int16_t sLX = 0, sLY = 0, sRX = 0, sRY = 0;

    bool ltFromKey = false, rtFromKey = false;

    bool everPublished = false;
    uint16_t lastWButtons = 0;
    uint8_t lastBLT = 0, lastBRT = 0;
    int16_t lastSLX = 0, lastSLY = 0, lastSRX = 0, lastSRY = 0;

    float flatX = 0.f, flatY = 0.f, flatZ = 0.f, flatRZ = 0.f;

    uint8_t quirk = 0;
    uint8_t cButtons = 0;

    bool motionValid = false;
    int16_t gyroX = 0, gyroY = 0, gyroZ = 0;
    int16_t accelX = 0, accelY = 0, accelZ = 0;

    bool touchValid = false;
    bool touch0Active = false, touch1Active = false;
    bool touchClick = false;
    uint8_t touch0Id = 0, touch1Id = 0;
    int16_t touch0X = 0, touch0Y = 0, touch1X = 0, touch1Y = 0;

    // The pad's own charge, for the families whose report carries it (the two Sony pads and
    // the Switch Pro): a percent or usbparsers::PAD_BATTERY_LEVEL_UNKNOWN, and MSG_BATTERY's own
    // status value.
    bool batteryValid = false;
    uint8_t batteryLevel = 0xFF;
    uint8_t batteryStatus = 0;
};

// Wire-normalized (full-range int16) two-finger touchpad snapshot, the MSG_TOUCHPAD payload
// minus ctrlIdx and timestamp.
struct TouchpadState {
    bool f0Active = false, f1Active = false;
    bool clickDown = false;
    uint8_t f0Id = 0, f1Id = 0;
    int16_t f0X = 0, f0Y = 0, f1X = 0, f1Y = 0;
};

bool operator==(const TouchpadState& a, const TouchpadState& b);
inline bool operator!=(const TouchpadState& a, const TouchpadState& b) { return !(a == b); }

enum class TouchpadSend : uint8_t {
    SKIP = 0,
    FRESH = 1, // state changed: send with a new event time
    HEAL = 2,  // unchanged duplicate: same event time so the receiver can drop it if the
               // original arrived, or adopt it if that frame was lost on the wire
};

// Send-on-change gate for the USB-direct touchpad stream. The heal resends exist because plain
// UDP can drop a lift frame, which would leave the receiver holding a phantom finger.
constexpr int64_t kTouchpadMoveIntervalNs = 8000000;
constexpr int kTouchpadHealResends = 2;

class TouchpadGate {
  public:
    TouchpadSend decide(const TouchpadState& cur, int64_t nowNs);

    const TouchpadState& lastSent() const { return last_; }

    int64_t lastEventTimeMs() const { return lastEventMs_; }

  private:
    TouchpadSend decideOnChange(const TouchpadState& cur, int64_t nowNs);
    TouchpadSend decideHeal(int64_t nowNs);

    TouchpadState last_{};
    int64_t lastSentNs_ = 0;
    int64_t lastEventMs_ = 0;
    int resendsLeft_ = 0;
};

int16_t scaleAxis(float v, float max);

uint8_t scaleTrigger(float v, float max);

float deadzone(float v, float flat);

uint16_t keycodeToXusb(int32_t androidKeycode);

uint16_t switchLayoutKeycodeToXusb(int32_t androidKeycode);

bool switchLayoutConsumesKey(int32_t androidKeycode);

bool usesN64Layout(uint8_t quirk);

constexpr int16_t STICK_RAIL = 32767;

constexpr int16_t stickRail(const bool positive, const bool negative) {
    if (positive == negative) return 0;
    return positive ? STICK_RAIL : static_cast<int16_t>(-STICK_RAIL);
}

inline void applyCButtonStick(DeviceState& s, const uint8_t cButtons) {
    const bool up = (cButtons & N64_C_UP) != 0;
    const bool down = (cButtons & N64_C_DOWN) != 0;
    const bool left = (cButtons & N64_C_LEFT) != 0;
    const bool right = (cButtons & N64_C_RIGHT) != 0;
    s.sRX = stickRail(right, left);
    s.sRY = stickRail(up, down);
}

// The key filter every JNI entry point shares through keyVerdict: true exactly when applyKey would
// consume the key under this quirk. Allocation-free; the standard layout counts L2/R2 and
// BUTTON_7/8 as the trigger keys, the Switch layout answers for its own key set.
bool consumesKey(int32_t androidKeycode, uint8_t quirk);

// Mirrored from <android/input.h> (AKEY_EVENT_ACTION_DOWN / _UP); satellite_jni.cpp asserts that
// they agree.
constexpr int32_t KEY_ACTION_DOWN = 0;
constexpr int32_t KEY_ACTION_UP = 1;

// What a JNI key entry point does with one framework key event: hand it back to the framework,
// consume it without touching the pad, or consume it and apply it with applyKey.
enum class KeyVerdict : uint8_t { PASS, SWALLOW, APPLY };

// A key consumesKey rejects passes; a key it accepts is always consumed, so it cannot move View
// focus, and only its down and up edges change the pad.
KeyVerdict keyVerdict(int32_t androidKeycode, uint8_t quirk, int32_t action);

uint16_t applyButtonQuirk(uint16_t xusbBit, uint8_t quirk);

// The standard layout's bit for a key under a quirk: keycodeToXusb, plus the 8BitDo Heart.
uint16_t standardKeycodeToXusb(int32_t androidKeycode, uint8_t quirk);

bool applyKey(DeviceState& s, int32_t androidKeycode, bool down);

// One joystick sample as the framework reports it, before the right-stick and trigger axes are
// reduced to the pair the pad is actually driving.
struct FrameworkAxes {
    float x = 0.f, y = 0.f, z = 0.f, rz = 0.f, rx = 0.f, ry = 0.f;
    float hatX = 0.f, hatY = 0.f;
    float lTrigger = 0.f, rTrigger = 0.f, brake = 0.f, gas = 0.f;
};

struct ReducedAxes {
    float rightX = 0.f, rightY = 0.f, lt = 0.f, rt = 0.f;
};

// Which framework axes carry the right stick and the triggers, by quirk.
ReducedAxes reduceFrameworkAxes(const FrameworkAxes& axes, uint8_t quirk);

// z/rz and the triggers are the reduced pair (reduceFrameworkAxes).
void applyAxes(DeviceState& s, float x, float y, float z, float rz, float leftTrigger,
               float rightTrigger, float hatX, float hatY);

void resetState(DeviceState& s);

bool consumePublishIfChanged(DeviceState& s);

void resetPublishLatch(DeviceState& s);

// Serializes the wire-facing view of a DeviceState for the diagnostics inspector. Pure and
// bounded (host-testable); returns bytes written excluding the NUL, or 0 if cap is too small.
size_t formatDeviceStateJson(const DeviceState& s, char* buf, size_t cap);

} // namespace gamepad
