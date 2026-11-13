// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <stdint.h>
#include <string>

#include "gamepad_input.h"

namespace usbhost {

struct AttachResult {
    int32_t syntheticDeviceId = 0;
    bool ok = false;
};

// A receiver slot is claimed to listen: no init and no probe, its controller set up once one is
// there.
AttachResult attachDevice(int fd, uint16_t vid, uint16_t pid, int interfaceNumber,
                          uint8_t endpointIn, uint16_t endpointInMaxPacket, uint8_t endpointOut,
                          uint8_t interfaceClass, uint8_t interfaceSubclass,
                          uint8_t interfaceProtocol, bool receiverSlot);

void detachDevice(int32_t syntheticDeviceId);

// A Steam Controller on Bluetooth LE as a Direct pad, its reports handed in from the JVM's GATT
// client rather than read by a poller. Its id comes from the allocator the USB pads draw from, so
// the two never collide. Nothing is written to the pad yet; not ok for a model that is no Bluetooth
// LE Steam Controller, or with every entry taken.
AttachResult attachBlePad(uint16_t vid, uint16_t pid, uint8_t inputReportId);

// The writes that take the pad out of lizard mode, then its output worker; false when one was
// refused.
bool startBlePad(int32_t padId);

// One notification of the pad's input characteristic, as the JVM received it.
void deliverBleReport(int32_t padId, const uint8_t* value, size_t len);

// restore gives the 2015 pad its stand-alone settings back over the link before the claim ends.
void detachBlePad(int32_t padId, bool restore);

uint64_t getUrbCount(int32_t deviceId);

uint64_t getMotionCount(int32_t deviceId);

// URBs that reaped with an error status, failed to submit, or failed to reap.
uint64_t getUrbErrorCount(int32_t deviceId);

// Whether the claimed pad's controller is there: a receiver slot's while one is connected to it,
// any other pad's always. False for a device that is gone.
bool isPadLinked(int32_t deviceId);

// The claimed pad's own charge, as its last report carried it: level << 8 | status in
// usbparsers::PAD_BATTERY_* terms, or -1 when the device is gone or no report has carried a
// reading yet. Kotlin's battery source polls this on its own 30 s cadence: the slot card shows it,
// and the wire carries the lower of it and the phone's.
int32_t getPadBattery(int32_t deviceId);

// {"model","parser","init","reportBytes","endpointOut","lastUrbStatus"} for a claimed device.
std::string deviceInfoJson(int32_t deviceId);

void sendRumble(int32_t syntheticDeviceId, uint16_t strong, uint16_t weak);

void sendTriggerRumble(int32_t syntheticDeviceId, uint16_t left, uint16_t right);

void sendLightbar(int32_t syntheticDeviceId, uint8_t r, uint8_t g, uint8_t b);

void sendPlayerLeds(int32_t syntheticDeviceId, uint8_t ledMask);

// left/right are the raw 11-byte DualSense trigger-effect blocks.
void sendTriggerEffects(int32_t syntheticDeviceId, const uint8_t* left, const uint8_t* right);

// MSG_MIC_LED's own state byte: 0 off, 1 on, 2 pulse. Only the DualSense has the lamp; every other
// family drops the write in the builder.
void sendMicMuteLed(int32_t syntheticDeviceId, uint8_t state);

// The slot's trackpad pick for a Steam Controller (gamepad::TrackpadPick), read by its poller
// before each decode.
void setTrackpadPick(int32_t syntheticDeviceId, gamepad::TrackpadPick pick);

} // namespace usbhost
