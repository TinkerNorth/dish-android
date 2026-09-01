// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string>

#include "gamepad_input.h"
#include "usb_hid_descriptor.h"

namespace usbparsers {

enum class Parser : uint8_t {
    NONE = 0,
    XINPUT_360 = 1,
    XBOX_ONE_GIP = 2,
    DUALSHOCK4 = 3,
    DUALSENSE = 4,
    SWITCH_PRO_USB = 5,
    STADIA = 6,
    GENERIC_HID_GAMEPAD = 7,
    // Same input report as XINPUT_360, but rumble needs the wrapped wireless-receiver frame.
    XINPUT_360_WIRELESS = 8,
    STEAM_CONTROLLER = 9,
};

enum class InitKind : uint8_t {
    NONE = 0,
    XBOX_ONE_POWERON = 1,
    SWITCH_PRO_HANDSHAKE = 2,
    // Xbox One S / Elite Series 2 want the GIP set-mode packet on top of the universal sequence.
    XBOX_ONE_S = 3,
    // Steam Controller: stop the firmware's stand-alone keyboard/mouse emulation and enable the
    // IMU.
    STEAM_QUIET = 4,
};

// Which direction a Steam Controller config sequence runs. QUIET is sent at attach; RESTORE must
// run on every exit path or the pad stays mute as a desktop mouse after we hand it back.
enum class SteamConfig : uint8_t {
    QUIET = 0,
    RESTORE = 1,
};

// Wireless dongle connect/disconnect events that arrive on the same endpoint as input reports.
// They never decode as state, so without them a departed pad would keep its last published input
// latched and a returning pad (fresh boot, settings gone) would stream without its quiet-mode init.
enum class WirelessEvent : uint8_t {
    NONE = 0,
    CONNECT = 1,
    DISCONNECT = 2,
};

enum class ButtonOrder : uint8_t {
    WESTERN = 0,
    SWITCH = 1,
};

struct KnownDevice {
    uint16_t vid;
    uint16_t pid;
    const char* name;
    Parser parser;
    InitKind init;
    ButtonOrder order = ButtonOrder::WESTERN;
};

struct Classification {
    Parser parser = Parser::NONE;
    InitKind init = InitKind::NONE;
    const char* name = nullptr;
    ButtonOrder order = ButtonOrder::WESTERN;
};

// Per-device, expand-only auto-range for sticks that report raw ADC values. We don't read the
// controller's factory calibration from SPI flash, and the usable deflection varies per unit and
// per direction, so each axis tracks the largest deflection seen on each side of center
// independently and stretches each side to full scale. A full sweep into every corner teaches it
// the true range; the seed sits below any healthy stick's throw so each direction can still reach
// the rail before it is fully learned.
struct AxisAutoRange {
    int32_t posReach = 1000;
    int32_t negReach = 1000;
};

// DualShock 4 / DualSense per-device IMU calibration (read from the calibration feature report).
// raw -> calibrated math from Linux hid-playstation; calibrated gyro is 1024 units/deg-s, accel
// 8192 units/g. Without it the raw sensor scale and bias are unknown, so motion stays off.
struct PsImuCalib {
    bool valid = false;
    int32_t gyroNumer[3] = {0, 0, 0};
    int32_t gyroDenom[3] = {1, 1, 1};
    int32_t accelBias[3] = {0, 0, 0};
    int32_t accelNumer[3] = {0, 0, 0};
    int32_t accelDenom[3] = {1, 1, 1};
};

struct ParserState {
    AxisAutoRange lx;
    AxisAutoRange ly;
    AxisAutoRange rx;
    AxisAutoRange ry;
    // Xbox One guide button (GIP report 0x07) is sticky state merged into the main 0x20 reports.
    bool xboxGuideHeld = false;
    gamepad::DeviceState xboxLastMain;
    // Filled at attach for GENERIC_HID_GAMEPAD when the report descriptor parses; empty otherwise.
    usbhid::HidLayout hidLayout;
    // Filled at attach for DUALSHOCK4 / DUALSENSE when the calibration report reads; invalid
    // otherwise.
    PsImuCalib psImu;
    // Steam Controller stick, held across the frames where the shared left axes carry pad data.
    int16_t steamStickX = 0;
    int16_t steamStickY = 0;
    // The DualSense mic-mute button is momentary, but the wire's WBUTTON_MIC_MUTE carries the
    // mute STATE it toggles (contract §Controller audio), so the latch that state lives in is
    // ours to keep. It sits here rather than in DeviceState because DeviceState is rebuilt from
    // scratch by every decode; this survives across reports for as long as the pad is claimed,
    // which is the same lifetime the pad's own firmware gives its mute setting.
    bool micMuteHeld = false;
    bool micMuted = false;
};

const KnownDevice* lookupKnown(uint16_t vid, uint16_t pid);

Classification classifyDevice(uint16_t vid, uint16_t pid, uint8_t ifClass, uint8_t ifSubclass,
                              uint8_t ifProtocol);

bool isVerifiedFastLane(uint16_t vid, uint16_t pid);

enum class ProbeOutcome : uint8_t {
    DECODED = 0,
    SILENT = 1,
    UNDECODED = 2,
};

bool probePermitsClaim(ProbeOutcome outcome, bool verifiedFastLane);

// Whether releasing this model back to Standard produces a framework gamepad InputDevice. False
// for the Steam Controller, whose stand-alone identity is a keyboard and mouse: a release that
// waited for a framework gamepad would always time out into a false "restore stuck".
bool modelExpectsFrameworkGamepad(uint16_t vid, uint16_t pid);

const char* parserName(Parser p);

bool parserHasImu(Parser p);

bool parserHasRumble(Parser p);

bool parserHasTouchpad(Parser p);

// True for families whose rumble the Android framework can't drive (the Switch Pro's proprietary
// HD-rumble); a framework vibrator they expose is a false positive, so only the Direct path
// actuates.
bool parserFrameworkRumbleUnreliable(Parser p);

// Feedback surfaces beyond the two rumble motors, all Direct-path only: the framework exposes no
// controller LED, trigger-motor, or trigger-effect API at all.
bool parserHasLightbar(Parser p);       // DS4 / DualSense RGB lightbar
bool parserHasPlayerLeds(Parser p);     // DualSense 5-LED bar, Switch Pro 4 player lights
bool parserHasTriggerEffects(Parser p); // DualSense adaptive-trigger effect blocks
bool parserHasTriggerRumble(Parser p);  // Xbox One GIP impulse-trigger motors

inline constexpr int TRIGGER_EFFECT_BLOCK_LEN = 11; // DS5 mode byte + 10 params

// Merged per-device feedback state. GIP packs all four motors into every rumble report (SDL keeps
// the same merged state), and the DS5 lightbar wants a one-time LIGHTBAR_SETUP handoff, so the
// writer owns this across calls; everything else is carried per call.
struct FeedbackState {
    uint16_t strong = 0;
    uint16_t weak = 0;
    uint16_t leftTrigger = 0;
    uint16_t rightTrigger = 0;
    bool ds5LightbarSetupSent = false;
};

// Pure report builders over the merged state; each returns bytes written (0 = unsupported family
// or out too small). Like buildRumbleReport the buffers are the full OUT transfer including the
// leading report id where the family has one.
size_t buildMergedRumbleReport(Parser p, FeedbackState& st, uint8_t seq, uint8_t* out,
                               size_t outCap);
size_t buildLightbarReport(Parser p, FeedbackState& st, uint8_t r, uint8_t g, uint8_t b,
                           uint8_t* out, size_t outCap);
size_t buildPlayerLedsReport(Parser p, uint8_t ledMask, uint8_t seq, uint8_t* out, size_t outCap);
size_t buildTriggerEffectsReport(Parser p, const uint8_t left[TRIGGER_EFFECT_BLOCK_LEN],
                                 const uint8_t right[TRIGGER_EFFECT_BLOCK_LEN], uint8_t* out,
                                 size_t outCap);

// Pure: writes the index-th GIP init packet for an Xbox One InitKind into out (with the sequence
// number at byte 2), returns its length or 0 when there are no more. runInit sends them in order.
size_t buildGipInitPacket(InitKind init, int index, uint8_t seq, uint8_t* out, size_t outCap);

// Pure: writes the index-th Steam Controller feature report for a config direction into out,
// returns its length or 0 when there are no more. Payload only; the caller frames it as a
// SET_REPORT.
size_t buildSteamConfigPacket(SteamConfig stage, int index, uint8_t* out, size_t outCap);

bool runInit(int fd, int interfaceNumber, uint8_t epOut, Parser p, InitKind init);

// Undoes runInit's device-side changes. No-op for families that never changed the device.
void runTeardown(int fd, int interfaceNumber, Parser p);

bool runRumble(int fd, uint8_t epOut, Parser p, uint16_t strong, uint16_t weak, uint8_t seq);

// Android write wrappers over the feedback builders, mirroring runRumble.
bool runMergedRumble(int fd, uint8_t epOut, Parser p, FeedbackState& st, uint8_t seq);
bool runLightbar(int fd, uint8_t epOut, Parser p, FeedbackState& st, uint8_t r, uint8_t g,
                 uint8_t b);
bool runPlayerLeds(int fd, uint8_t epOut, Parser p, uint8_t ledMask, uint8_t seq);
bool runTriggerEffects(int fd, uint8_t epOut, Parser p,
                       const uint8_t left[TRIGGER_EFFECT_BLOCK_LEN],
                       const uint8_t right[TRIGGER_EFFECT_BLOCK_LEN]);

// Pure: writes the device's rumble report into out, returns its length (0 if unsupported or out is
// too small). Host-tested in usb_parsers_test.cpp; runRumble is the Android write wrapper.
size_t buildRumbleReport(Parser p, uint16_t strong, uint16_t weak, uint8_t seq, uint8_t* out,
                         size_t outCap);

bool decodeReport(Parser p, const uint8_t* buf, size_t len, gamepad::DeviceState& s,
                  ParserState* sticks);

// Pure: classifies a report as a wireless connect/disconnect event for families that interleave
// them with input (the Steam Controller dongle). NONE for every other parser and packet.
WirelessEvent checkWirelessEvent(Parser p, const uint8_t* buf, size_t len);

bool decodeGenericHidGamepad(const uint8_t* buf, size_t len, gamepad::DeviceState& s);

// Parses a DualShock 4 / DualSense calibration feature report into per-axis gyro/accel factors.
bool parsePsCalibration(const uint8_t* buf, size_t len, PsImuCalib& out);

} // namespace usbparsers
