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
constexpr uint16_t XUSB_GUIDE = 0x0400;
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

// Per-device framework-path button quirks (Nintendo and friends sit A/B and X/Y opposite to Xbox).
constexpr uint8_t QUIRK_SWAP_AB = 0x01;
constexpr uint8_t QUIRK_SWAP_XY = 0x02;

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

    uint8_t quirk = 0;

    bool motionValid = false;
    int16_t gyroX = 0, gyroY = 0, gyroZ = 0;
    int16_t accelX = 0, accelY = 0, accelZ = 0;

    // False when the report carried no touch update (short report, or a DS4 frame
    // with zero bundled touch packets): the last sent state must persist, not lift.
    bool touchValid = false;
    bool touch0Active = false, touch1Active = false;
    bool touchClick = false;
    uint8_t touch0Id = 0, touch1Id = 0;
    int16_t touch0X = 0, touch0Y = 0, touch1X = 0, touch1Y = 0;
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

// Send-on-change gate for the USB-direct touchpad stream. Edges (contact, lift, click, new
// tracking id) go out immediately because a lost or delayed edge is user-visible; coordinate
// moves coalesce to the motion cadence since the next report supersedes them anyway. After
// every change the final state is re-sent kTouchpadHealResends more times: like the overlay's
// resend burst, this heals a lift frame dropped by plain UDP, which would otherwise leave the
// receiver holding a phantom finger.
constexpr int64_t kTouchpadMoveIntervalNs = 8000000;
constexpr int kTouchpadHealResends = 2;

class TouchpadGate {
  public:
    TouchpadSend decide(const TouchpadState& cur, int64_t nowNs);

    const TouchpadState& lastSent() const { return last_; }

    int64_t lastEventTimeMs() const { return lastEventMs_; }

  private:
    TouchpadState last_{};
    int64_t lastSentNs_ = 0;
    int64_t lastEventMs_ = 0;
    int resendsLeft_ = 0;
};

int16_t scaleAxis(float v, float max);

uint8_t scaleTrigger(float v, float max);

float deadzone(float v, float flat);

uint16_t keycodeToXusb(int32_t androidKeycode);

uint16_t applyButtonQuirk(uint16_t xusbBit, uint8_t quirk);

bool applyKey(DeviceState& s, int32_t androidKeycode, bool down);

// Caller pre-resolves trigger pairs: lt=max(LTRIGGER,BRAKE), rt=max(RTRIGGER,GAS).
void applyAxes(DeviceState& s, float x, float y, float z, float rz, float leftTrigger,
               float rightTrigger, float hatX, float hatY);

void resetState(DeviceState& s);

bool consumePublishIfChanged(DeviceState& s);

// A (re)bound target pad is neutral; without re-arming, the on-change latch would never resend.
void resetPublishLatch(DeviceState& s);

// Serializes the wire-facing view of a DeviceState for the diagnostics inspector. Pure and
// bounded (host-testable); returns bytes written excluding the NUL, or 0 if cap is too small.
size_t formatDeviceStateJson(const DeviceState& s, char* buf, size_t cap);

} // namespace gamepad
