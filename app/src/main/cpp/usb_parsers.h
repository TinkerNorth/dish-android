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
    SWITCH_N64_USB = 10,
    // The Steam Controller (2026, "Triton"): its own report ids, haptic rumble, and lizard mode
    // under a firmware watchdog.
    STEAM_TRITON = 11,
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
    // Steam Controller (2026): lizard mode off and the raw IMU on, one settings report each.
    STEAM_TRITON_QUIET = 5,
};

// Which direction a Steam Controller config sequence runs. QUIET is sent at attach; RESTORE must
// run on every exit path or the pad stays mute as a desktop mouse after we hand it back.
enum class SteamConfig : uint8_t {
    QUIET = 0,
    RESTORE = 1,
};

// One Steam Controller (2026) settings write, as SDL sends each: the lizard-mode switch its
// firmware's watchdog turns back on unless it hears it again, or the IMU mode that streams raw
// motion.
enum class TritonSetting : uint8_t {
    LIZARD_OFF = 0,
    RAW_IMU = 1,
};

// Wireless dongle connect/disconnect events that arrive on the same endpoint as input reports.
// They never decode as state, so without them a departed pad would keep its last published input
// latched and a returning pad (fresh boot, settings gone) would stream without its quiet-mode init.
enum class WirelessEvent : uint8_t {
    NONE = 0,
    CONNECT = 1,
    DISCONNECT = 2,
};

// What one report does to a wireless receiver's slot: its controller arrives (rebooted, its
// settings gone), leaves, or nothing changes.
enum class SlotLinkStep : uint8_t {
    STAYS = 0,
    ARRIVES = 1,
    LEAVES = 2,
};

using usbhid::ButtonOrder;

// The pad battery a decoder reads (gamepad::DeviceState::battery*). The status is MSG_BATTERY's
// own value, restated here like the wire's button bits so this header stays free of the wire
// headers: 0 unknown, 1 discharging, 2 charging, 3 full. The readings are hid-playstation's and
// hid-nintendo's byte for byte, so a Direct pad shows the number the kernel driver would.
constexpr uint8_t PAD_BATTERY_LEVEL_UNKNOWN = 0xFF;
constexpr uint8_t PAD_BATTERY_STATUS_UNKNOWN = 0;
constexpr uint8_t PAD_BATTERY_STATUS_DISCHARGING = 1;
constexpr uint8_t PAD_BATTERY_STATUS_CHARGING = 2;
constexpr uint8_t PAD_BATTERY_STATUS_FULL = 3;

// What FeedbackState holds for a write that has not happened, and what nextRefreshInMs answers for
// a pad that never needs one repeated.
inline constexpr int64_t NEVER_SENT_MS = INT64_MIN;
inline constexpr uint32_t NO_REFRESH = UINT32_MAX;

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
    // Filled at attach: the model's product id, for a decoder that tells the variants of one
    // report family apart (the DualSense Edge's back buttons).
    uint16_t productId = 0;
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
    // The Steam Controller (2026)'s IMU tick last published as motion: SDL sends a sensor sample
    // only for a tick it has not seen, starting from 0.
    uint32_t tritonImuTick = 0;
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

// Whether the family reports whether or not anything moved: a Sony pad or a Switch pad streams at
// a fixed rate, so its silence through the probe is a fault rather than idleness.
bool parserStreamsUnprompted(Parser p);

bool probePermitsClaim(ProbeOutcome outcome, bool verifiedFastLane, Parser parser);

// Whether releasing this model back to Standard produces a framework gamepad InputDevice (both
// Steam Controllers' stand-alone identity is a keyboard and mouse).
bool modelExpectsFrameworkGamepad(uint16_t vid, uint16_t pid);

// Whether the model sources the INPUT extended-buttons word (contract §Extended buttons): both
// Steam Controllers, the DualSense Edge and the Switch pads with a Capture button.
bool modelHasExtendedButtons(uint16_t vid, uint16_t pid);

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
bool parserHasHapticLanes(Parser p);    // DualSense HD-haptics lanes on its USB audio OUT
bool parserHasTriggerRumble(Parser p);  // Xbox One GIP impulse-trigger motors
bool parserHasMicMuteLed(Parser p);     // DualSense mic-mute lamp (and its mic amp)

inline constexpr int TRIGGER_EFFECT_BLOCK_LEN = 11; // DS5 mode byte + 10 params

// MSG_MIC_LED's states, which are also the DualSense's own mute-lamp values.
inline constexpr uint8_t MIC_MUTE_LED_OFF = 0;
inline constexpr uint8_t MIC_MUTE_LED_ON = 1;
inline constexpr uint8_t MIC_MUTE_LED_PULSE = 2;

// Merged per-device feedback state. GIP packs all four motors into every rumble report (SDL keeps
// the same merged state), the DS5 lightbar wants a one-time LIGHTBAR_SETUP handoff, and the DS5
// mute lamp has to survive every later write, so the writer owns this across calls; everything
// else is carried per call.
struct FeedbackState {
    uint16_t strong = 0;
    uint16_t weak = 0;
    uint16_t leftTrigger = 0;
    uint16_t rightTrigger = 0;
    bool ds5LightbarSetupSent = false;
    // hid-playstation's use_vibration_v2, set at attach from the firmware-info report.
    bool ds5VibrationV2 = false;
    // Last lamp state the host asked for, and whether it ever asked. Every DS5 report we build
    // starts from a fresh memset, so the lamp is re-asserted from here on all of them: a report
    // that flags a field the firmware then finds zeroed is exactly how a lamp gets stomped by an
    // unrelated colour change. Unset means the host never drove the lamp, and a pad whose lamp we
    // were never asked about must keep whatever it had.
    uint8_t ds5MicMuteLed = MIC_MUTE_LED_OFF;
    bool ds5MicMuteLedSet = false;
    // When the Steam Controller (2026) last heard its settings and its rumble, on the output
    // worker's clock (dish_usb::outputClockMs); refreshDue reads them.
    int64_t settingsSentMs = NEVER_SENT_MS;
    int64_t rumbleSentMs = NEVER_SENT_MS;
    // A receiver slot with no controller on it: nothing is refreshed until one connects.
    bool controllerAway = false;
};

// The writes a pad loses unless the host repeats them. The Steam Controller (2026) turns lizard
// mode back on from a firmware watchdog and stops its motors after a ~50 ms safety timeout, so SDL
// re-sends the lizard switch every 3 s and a running rumble every 40 ms.
struct RefreshDue {
    bool settings = false;
    bool rumble = false;
};

// Pure: what the pad is owed at nowMs, from when FeedbackState says each was last written.
RefreshDue refreshDue(Parser p, const FeedbackState& st, int64_t nowMs);

// Pure: milliseconds from nowMs until refreshDue next owes anything; NO_REFRESH when it never will.
uint32_t nextRefreshInMs(Parser p, const FeedbackState& st, int64_t nowMs);

// How a family's output reports reach the pad: the interrupt OUT endpoint, or, for a HID family
// whose interface has none, a SET_REPORT(Output) on the control pipe, which is where hidapi writes
// an output report then. NONE refuses the write.
enum class OutputRoute : uint8_t {
    NONE = 0,
    INTERRUPT_OUT = 1,
    CONTROL_SET_REPORT = 2,
};

OutputRoute outputRouteFor(Parser p, uint8_t epOut);

// Pure report builders over the merged state; each returns bytes written (0 = unsupported family
// or out too small). Like buildRumbleReport the buffers are the full OUT transfer including the
// leading report id where the family has one.
size_t buildMergedRumbleReport(Parser p, FeedbackState& st, uint8_t seq, uint8_t* out,
                               size_t outCap);
size_t buildLightbarReport(Parser p, FeedbackState& st, uint8_t r, uint8_t g, uint8_t b,
                           uint8_t* out, size_t outCap);
size_t buildPlayerLedsReport(Parser p, const FeedbackState& st, uint8_t ledMask, uint8_t seq,
                             uint8_t* out, size_t outCap);
size_t buildTriggerEffectsReport(Parser p, const FeedbackState& st,
                                 const uint8_t left[TRIGGER_EFFECT_BLOCK_LEN],
                                 const uint8_t right[TRIGGER_EFFECT_BLOCK_LEN], uint8_t* out,
                                 size_t outCap);
// state is MSG_MIC_LED's own value; it is recorded in [st] so the other builders keep re-asserting
// it.
size_t buildMicMuteLedReport(Parser p, FeedbackState& st, uint8_t state, uint8_t* out,
                             size_t outCap);

// Pure: writes the index-th GIP init packet for an Xbox One InitKind into out (with the sequence
// number at byte 2), returns its length or 0 when there are no more. runInit sends them in order.
size_t buildGipInitPacket(InitKind init, int index, uint8_t seq, uint8_t* out, size_t outCap);

// The Switch Pro USB init sequence, packet by packet: the four 0x80 commands as full 64-byte
// packets, then the three subcommands (report mode, vibration, IMU). Zero once the sequence ends
// or the packet does not fit.
size_t buildSwitchInitPacket(int index, uint8_t* out, size_t outCap);

// Pure: whether a failed write of the index-th Switch init packet ends the sequence.
bool switchInitStepIsFatal(int index);

// A GIP report whose flags ask for an acknowledgement (the mode-button report does) is
// retransmitted until one arrives; this builds the ack xpad sends. Zero when none is owed.
constexpr size_t kGipAckBytes = 13;
size_t buildGipAck(const uint8_t* report, size_t len, uint8_t* out, size_t outCap);

// One OUT report on the interrupt endpoint; nothing to write is a refusal, not a no-op.
bool writeOutReport(int fd, uint8_t epOut, const uint8_t* buf, size_t n);

// Pure: writes the index-th Steam Controller feature report for a config direction into out,
// returns its length or 0 when there are no more. Payload only; the caller frames it as a
// SET_REPORT.
size_t buildSteamConfigPacket(SteamConfig stage, int index, uint8_t* out, size_t outCap);

// The Steam Controller (2026) settings feature report: report id 1, 64 bytes with the id.
inline constexpr uint8_t TRITON_SETTINGS_REPORT_ID = 0x01;
inline constexpr size_t TRITON_SETTINGS_REPORT_BYTES = 64;

// Pure: one setting's whole SET_REPORT data stage, report id first; 0 when out is too small.
size_t buildTritonSettingsReport(TritonSetting setting, uint8_t* out, size_t outCap);

// Sends the attach-time init sequence InitKind names over the claimed interface.
bool runInit(int fd, int interfaceNumber, uint8_t epOut, InitKind init);

// Undoes runInit's device-side changes. No-op for families that never changed the device, and for
// the Steam Controller (2026), whose own watchdog restores lizard mode once the settings stop.
void runTeardown(int fd, int interfaceNumber, Parser p);

// Asks a receiver slot what it holds (buildReceiverStateRequest); its answer arrives as a report.
// False when nothing was asked.
bool runReceiverStateRequest(int fd, int interfaceNumber, Parser p);

// Re-sends the settings refreshDue owes: the Steam Controller (2026)'s lizard switch, once, so a
// refused write costs the output worker a single transfer. False for every other family.
bool runSettingsRefresh(int fd, int interfaceNumber, Parser p);

// Android write wrappers over the feedback builders. Each refuses a device it has no route to (no
// OUT endpoint, and for rumble no control-pipe route either, outputRouteFor) before it builds, so a
// builder never records in FeedbackState a write that cannot happen.
bool runMergedRumble(int fd, int interfaceNumber, uint8_t epOut, Parser p, FeedbackState& st,
                     uint8_t seq);
bool runLightbar(int fd, uint8_t epOut, Parser p, FeedbackState& st, uint8_t r, uint8_t g,
                 uint8_t b);
bool runPlayerLeds(int fd, uint8_t epOut, Parser p, const FeedbackState& st, uint8_t ledMask,
                   uint8_t seq);
bool runTriggerEffects(int fd, uint8_t epOut, Parser p, const FeedbackState& st,
                       const uint8_t left[TRIGGER_EFFECT_BLOCK_LEN],
                       const uint8_t right[TRIGGER_EFFECT_BLOCK_LEN]);
bool runMicMuteLed(int fd, uint8_t epOut, Parser p, FeedbackState& st, uint8_t state);

// Pure: writes the device's rumble report into out, returns its length (0 if unsupported or out is
// too small). Host-tested in usb_parsers_test.cpp; the Android writer goes through
// buildMergedRumbleReport, of which this is the no-state form.
size_t buildRumbleReport(Parser p, uint16_t strong, uint16_t weak, uint8_t seq, uint8_t* out,
                         size_t outCap);

bool decodeReport(Parser p, const uint8_t* buf, size_t len, gamepad::DeviceState& s,
                  ParserState* sticks);

// Pure: classifies a report as a wireless connect/disconnect event for families that interleave
// them with input (both Steam Controllers' dongles). NONE for every other parser and packet.
WirelessEvent checkWirelessEvent(Parser p, const uint8_t* buf, size_t len);

// Pure: the pad's own charge out of a report that carries nothing else, the Steam Controller
// (2026)'s battery status. False, with s untouched, for every other report and parser.
bool decodePadBatteryReport(Parser p, const uint8_t* buf, size_t len, gamepad::DeviceState& s);

// Pure: a receiver slot's link across one report, from its wireless event or, for a slot that held
// no controller, a report that decoded as state (SDL: "Maybe we missed a wireless status packet?").
SlotLinkStep slotLinkStep(bool linked, WirelessEvent event, bool decodedState);

// Pure: whether a pad's controller is there to configure or restore. Always for a pad of its own;
// for a receiver slot, only while its controller is connected.
bool controllerIsThere(bool receiverSlot, bool linked);

// Pure: the feature report a receiver slot answers with a wireless event saying whether a
// controller is on it, into out; 0 for a family whose slots are not asked.
size_t buildReceiverStateRequest(Parser p, uint8_t* out, size_t outCap);

bool decodeGenericHidGamepad(const uint8_t* buf, size_t len, gamepad::DeviceState& s);

// A DualShock 4 / DualSense calibration feature report: gyro at bytes 1..21, accel at 23..34.
inline constexpr size_t PS_CALIBRATION_REPORT_BYTES = 35;

// Parses a DualShock 4 / DualSense calibration feature report into per-axis gyro/accel factors.
bool parsePsCalibration(const uint8_t* buf, size_t len, PsImuCalib& out);

// The DualSense firmware-info feature report (0x20): 64 bytes, byte 0 the report id, the feature
// ("update") version little-endian at 44..45, as hid-playstation reads it.
inline constexpr uint8_t DS5_FIRMWARE_INFO_REPORT_ID = 0x20;
inline constexpr size_t DS5_FIRMWARE_INFO_REPORT_BYTES = 64;

struct Ds5FirmwareInfo {
    bool valid = false;
    uint16_t featureVersion = 0;
};

// Pure: the feature version out of a firmware-info report; false, and an invalid info, for a
// report of another length or id.
bool parseDs5FirmwareInfo(const uint8_t* buf, size_t len, Ds5FirmwareInfo& out);

// Pure: hid-playstation's rule for the revised classic-rumble mode. Every DualSense Edge, and a
// DualSense from feature version 2.21; a pad whose report did not read keeps the original mode.
bool ds5UsesVibrationV2(uint16_t productId, const Ds5FirmwareInfo& info);

// USB control-transfer fields the class-driver requests use (USB 2.0 §9.3, HID 1.11 §7.2), shared
// with the descriptor and report fetches in usb_host.cpp.
inline constexpr uint8_t USB_REQUEST_TYPE_OUT_CLASS_INTERFACE = 0x21;
inline constexpr uint8_t USB_REQUEST_TYPE_IN_STANDARD_INTERFACE = 0x81;
inline constexpr uint8_t USB_REQUEST_TYPE_IN_CLASS_INTERFACE = 0xA1;
inline constexpr uint8_t USB_REQUEST_GET_REPORT = 0x01;
inline constexpr uint8_t USB_REQUEST_GET_DESCRIPTOR = 0x06;
inline constexpr uint8_t USB_REQUEST_SET_REPORT = 0x09;
inline constexpr uint8_t HID_REPORT_TYPE_OUTPUT = 0x02;
inline constexpr uint8_t HID_REPORT_TYPE_FEATURE = 0x03;
inline constexpr uint8_t HID_DESCRIPTOR_TYPE_REPORT = 0x22;
inline constexpr unsigned USB_CONTROL_TIMEOUT_MS = 250;

} // namespace usbparsers
