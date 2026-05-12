// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

/*
 * gamepad_input.h — pure gamepad-input processing.
 *
 * Owns per-device button/axis state, the keycode → XUSB bit mapping, and
 * the send-on-change gate. Deliberately free of Android, JNI, and sodium
 * dependencies so it can be exercised from a host-build googletest target
 * (see app/src/test/cpp/).
 *
 * The JNI glue in satellite_jni.cpp converts GameActivity events into the
 * primitive arguments these functions take and handles the locking + the
 * actual report dispatch.
 */
#pragma once

#include <stdint.h>

namespace gamepad {

// ── XUSB wButtons bits (XInput-compatible layout) ──────────────────────────
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

// ── Android keycode mirrors ────────────────────────────────────────────────
// Mirrored as integer constants rather than included from <android/keycodes.h>
// so this header builds on host for unit tests. Values match Android's stable
// input ABI; if Android ever renumbers these the satellite path is broken
// regardless.
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

// Generic HID joystick keycodes. Cheap USB adapters don't ship with an
// Android key-layout file that relabels their buttons to the named A/B/X/Y/
// L1/R1/etc. set above, so their buttons surface as BUTTON_1..16 instead.
// [keycodeToXusb] gives BUTTON_1..6, 9..12 a default XUSB mapping that
// matches the DirectInput numbering most of these adapters use; BUTTON_7/8
// are routed through the trigger-via-key path in [applyKey] (same as L2/R2).
// BUTTON_13..16 are left unmapped because their physical assignment varies
// too much between devices to guess at.
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

// ── Per-device input state ─────────────────────────────────────────────────
// One DeviceState per physical InputDevice id. The JNI layer keys these by
// device id in an unordered_map so two physical controllers don't bleed
// state into each other.
struct DeviceState {
    // Live values updated by applyKey / applyAxes.
    uint16_t wButtons = 0;
    uint8_t bLT = 0, bRT = 0;
    int16_t sLX = 0, sLY = 0, sRX = 0, sRY = 0;

    // L2/R2 are reported as both keys and trigger axes on different pads.
    // While a key-down is held we ignore the axis read so a 0-valued axis
    // sample doesn't drop the trigger back to 0.
    bool ltFromKey = false, rtFromKey = false;

    // Send-on-change gate: lastXxx mirrors the most recently published
    // values. everPublished is false until the first publish so the first
    // event always emits, even if the device starts at all-zero.
    bool everPublished = false;
    uint16_t lastWButtons = 0;
    uint8_t lastBLT = 0, lastBRT = 0;
    int16_t lastSLX = 0, lastSLY = 0, lastSRX = 0, lastSRY = 0;

    // Per-axis flat (deadzone) values, pushed once at device-add from
    // InputDevice.getMotionRange. Without this, axes near rest stream
    // tiny non-zero values to the server.
    float flatX = 0.f, flatY = 0.f, flatZ = 0.f, flatRZ = 0.f;
};

// ── Pure helpers ───────────────────────────────────────────────────────────

// Scale a -1..1 axis float to a clamped int16 ([-32768, 32767]).
int16_t scaleAxis(float v, float max);

// Scale a 0..1 trigger float to a clamped uint8 ([0, 255]).
uint8_t scaleTrigger(float v, float max);

// Zero values inside the (symmetric) deadzone, pass through otherwise.
float deadzone(float v, float flat);

// Map an Android keycode (KC_* / AKEYCODE_*) to its XUSB wButtons bit.
// Returns 0 for keycodes that aren't gamepad buttons.
uint16_t keycodeToXusb(int32_t androidKeycode);

// ── State transitions ──────────────────────────────────────────────────────

// Apply a key down/up to [s]. Handles the L2/R2 trigger-via-key path. Returns
// true if the keycode is a gamepad input we recognised (i.e. caller should
// consider publishing).
bool applyKey(DeviceState& s, int32_t androidKeycode, bool down);

// Apply a motion-event update. Caller resolves the trigger pairs:
//   leftTrigger  = max(AXIS_LTRIGGER, AXIS_BRAKE)
//   rightTrigger = max(AXIS_RTRIGGER, AXIS_GAS)
// to keep this function free of Android axis-index symbols. Deadzones are
// read from [s] and applied per-axis.
void applyAxes(DeviceState& s, float x, float y, float z, float rz, float leftTrigger,
               float rightTrigger, float hatX, float hatY);

// Zero buttons/axes/triggers for ACTION_CANCEL or focus-loss release-all.
void resetState(DeviceState& s);

// Send-on-change gate. Returns true if the live state has diverged from
// lastXxx (or this is the first publish), and atomically copies live →
// lastXxx so the next call short-circuits unless something else changed.
// Caller does the actual report dispatch.
bool consumePublishIfChanged(DeviceState& s);

} // namespace gamepad
