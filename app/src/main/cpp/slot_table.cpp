// SPDX-License-Identifier: LGPL-3.0-or-later

#include "slot_table.h"

#include <string.h>

#include <utility>
#include <vector>

namespace dish_slots {

using dish_send::MotionSample;
using dish_send::PendingDatagram;
using gamepad::DeviceState;

namespace {

// USB-direct reports skip gamepad::applyAxes, so the per-device flat (deadzone) is never applied; a
// resting stick would otherwise stream jitter and drift. Radial hard cutoff, ~8% of int16 range.
constexpr int32_t kUsbStickDeadzone = 2600;
inline void applyUsbStickDeadzone(int16_t& x, int16_t& y) {
    const int64_t mag2 = static_cast<int64_t>(x) * x + static_cast<int64_t>(y) * y;
    if (mag2 < static_cast<int64_t>(kUsbStickDeadzone) * kUsbStickDeadzone) {
        x = 0;
        y = 0;
    }
}

void copyPadAxes(const DeviceState& nu, DeviceState& s) {
    s.wButtons = nu.wButtons;
    s.bLT = nu.bLT;
    s.bRT = nu.bRT;
    s.sLX = nu.sLX;
    s.sLY = nu.sLY;
    s.sRX = nu.sRX;
    s.sRY = nu.sRY;
}

// Copy-on-valid keeps the last known sample visible: a report that carries no motion must not
// read as a stopped pad in the inspector.
void mirrorMotionForInspector(const DeviceState& nu, DeviceState& s) {
    if (!nu.motionValid) return;
    s.motionValid = true;
    s.gyroX = nu.gyroX;
    s.gyroY = nu.gyroY;
    s.gyroZ = nu.gyroZ;
    s.accelX = nu.accelX;
    s.accelY = nu.accelY;
    s.accelZ = nu.accelZ;
}

// Same copy-on-valid rule: a report without a touch update must not read as a lift.
void mirrorTouchForInspector(const DeviceState& nu, DeviceState& s) {
    if (!nu.touchValid) return;
    s.touchValid = true;
    s.touch0Active = nu.touch0Active;
    s.touch0Id = nu.touch0Id;
    s.touch0X = nu.touch0X;
    s.touch0Y = nu.touch0Y;
    s.touch1Active = nu.touch1Active;
    s.touch1Id = nu.touch1Id;
    s.touch1X = nu.touch1X;
    s.touch1Y = nu.touch1Y;
    s.touchClick = nu.touchClick;
}

void applyJoystickSample(DeviceState& state, const gamepad::FrameworkAxes& sample) {
    const gamepad::ReducedAxes reduced = gamepad::reduceFrameworkAxes(sample, state.quirk);
    gamepad::applyAxes(state, sample.x, sample.y, reduced.rightX, reduced.rightY, reduced.lt,
                       reduced.rt, sample.hatX, sample.hatY);
}

} // namespace

void SlotTable::publishGamepadToSatellite(const SlotBinding& binding, const DeviceState& s,
                                          PendingDatagram& out) {
    auto session = sessions_.get(binding.sessionHandle);
    if (!session) return;
    dish_send::stageGamepadReport(session, binding.controllerIndex, dish_send::xusbReportOf(s),
                                  out);
}

// Bluetooth and Moonlight both leave through Kotlin, so the state is queued for the bridge thread
// rather than written here: this call holds the slot lock and must not make a JVM upcall under it.
void SlotTable::publishGamepadToBridge(const SlotBinding& binding, const DeviceState& s) {
    if (!binding.bridgeConnectionId) return;
    BridgeReport r{};
    r.kind = binding.kind;
    r.payload = BridgeReport::GAMEPAD;
    r.connectionId = binding.bridgeConnectionId;
    r.controllerNumber = binding.controllerIndex;
    r.wButtons = s.wButtons;
    r.bLT = s.bLT;
    r.bRT = s.bRT;
    r.sLX = s.sLX;
    r.sLY = s.sLY;
    r.sRX = s.sRX;
    r.sRY = s.sRY;
    bridge_.push(std::move(r));
}

// Lock order: devices < slots < (sessions | btQueue | a session's send turn). The datagram is
// staged here, under both locks, so a slot's reports take their counters in the order its latch
// consumed them; the caller seals and sends it with transmitPublished after letting the locks go.
void SlotTable::publishIfChanged(const int32_t deviceId, DeviceState& s, PendingDatagram& out) {
    std::lock_guard<TrackedMutex> lock(slotsMtx_);
    auto it = slots_.find(deviceId);
    if (it == slots_.end()) return;
    // Bind-check before consume: a sample dropped for lack of a slot must not burn the latch, or
    // the slot it later binds to never sees that state.
    if (!gamepad::consumePublishIfChanged(s)) return;

    const SlotBinding& binding = it->second;
    switch (binding.kind) {
    case SLOT_SATELLITE:
        publishGamepadToSatellite(binding, s, out);
        return;
    case SLOT_BLUETOOTH:
    case SLOT_MOONLIGHT:
        publishGamepadToBridge(binding, s);
        return;
    case SLOT_NONE:
        return;
    }
}

// deviceId is reused across reconnects, so the new pad would inherit stale held inputs; reset and
// re-arm so it syncs to neutral. Call without the slot lock held: publishIfChanged retakes it.
void SlotTable::syncSlotBaseline(const int32_t deviceId) {
    PendingDatagram datagram;
    {
        std::lock_guard<TrackedMutex> lock(devicesMtx_);
        auto it = devices_.find(deviceId);
        if (it == devices_.end()) return;
        gamepad::resetState(it->second);
        gamepad::resetPublishLatch(it->second);
        publishIfChanged(deviceId, it->second, datagram);
    }
    dish_send::transmitPublished(datagram);
}

void SlotTable::prewarmDevice(const int32_t deviceId) {
    std::lock_guard<TrackedMutex> lock(devicesMtx_);
    devices_[deviceId];
}

void SlotTable::applyUsbReport(const int32_t deviceId, const DeviceState& nu) {
    PendingDatagram datagram;
    {
        std::lock_guard<TrackedMutex> lock(devicesMtx_);
        auto& s = devices_[deviceId];
        copyPadAxes(nu, s);
        applyUsbStickDeadzone(s.sLX, s.sLY);
        applyUsbStickDeadzone(s.sRX, s.sRY);
        const bool inspecting = inspect_.load(std::memory_order_relaxed);
        if (inspecting) {
            mirrorMotionForInspector(nu, s);
            mirrorTouchForInspector(nu, s);
        }
        publishIfChanged(deviceId, s, datagram);
    }
    dish_send::transmitPublished(datagram);
}

void SlotTable::resetAndPublish(const int32_t deviceId) {
    PendingDatagram datagram;
    {
        std::lock_guard<TrackedMutex> lock(devicesMtx_);
        auto it = devices_.find(deviceId);
        if (it == devices_.end()) return;
        gamepad::resetState(it->second);
        publishIfChanged(deviceId, it->second, datagram);
    }
    dish_send::transmitPublished(datagram);
}

void SlotTable::forgetDevice(const int32_t deviceId) {
    std::lock_guard<TrackedMutex> lock(devicesMtx_);
    devices_.erase(deviceId);
    frameworkEventCounts_.erase(deviceId);
}

// Kotlin translates to CONTROLLER_MOTION and drops samples the host never asked for (MOTION_EVENT
// gate), so this stays fire-and-forget.
void SlotTable::publishMotionToBridge(const SlotBinding& binding, const MotionSample& m) {
    if (!binding.bridgeConnectionId) return;
    BridgeReport r{};
    r.kind = SLOT_MOONLIGHT;
    r.payload = BridgeReport::MOTION;
    r.connectionId = binding.bridgeConnectionId;
    r.controllerNumber = binding.controllerIndex;
    memcpy(r.gyro, m.gyro, sizeof(r.gyro));
    memcpy(r.accel, m.accel, sizeof(r.accel));
    r.timestampDeltaUs = m.timestampDeltaUs;
    bridge_.push(std::move(r));
}

void SlotTable::publishMotionToSatellite(const SlotBinding& binding, const MotionSample& m,
                                         PendingDatagram& out) {
    auto session = sessions_.get(binding.sessionHandle);
    if (!session) return;
    dish_send::stageMotion(session, binding.controllerIndex, m, out);
}

// Stages under the slot lock, so the caller sends out once the lock is let go.
void SlotTable::routeUsbMotion(const int32_t deviceId, const MotionSample& sample,
                               PendingDatagram& out) {
    std::lock_guard<TrackedMutex> lock(slotsMtx_);
    auto it = slots_.find(deviceId);
    if (it == slots_.end()) return;
    const SlotBinding& binding = it->second;
    switch (binding.kind) {
    case SLOT_MOONLIGHT:
        publishMotionToBridge(binding, sample);
        return;
    case SLOT_SATELLITE:
        publishMotionToSatellite(binding, sample, out);
        return;
    case SLOT_BLUETOOTH:
    case SLOT_NONE:
        return;
    }
}

void SlotTable::applyUsbMotion(const int32_t deviceId, const MotionSample& sample) {
    PendingDatagram datagram;
    routeUsbMotion(deviceId, sample, datagram);
    dish_send::transmitDatagram(datagram);
}

// The Bluetooth HID descriptor is a plain gamepad, so touch has nowhere to go on that
// transport. Satellite gets the full-state frame; Moonlight gets it via the bridge, where
// Kotlin diffs it into CONTROLLER_TOUCH events. Routing (ds4 pad vs mouse vs off) is the
// satellite receiver's job, declared per slot in the descriptor.
// eventTimeMs is not carried: Moonlight re-times these events on the reliable control stream.
void SlotTable::publishTouchToBridge(const SlotBinding& binding, const gamepad::TouchpadState& t) {
    if (!binding.bridgeConnectionId) return;
    BridgeReport r{};
    r.kind = SLOT_MOONLIGHT;
    r.payload = BridgeReport::TOUCH;
    r.connectionId = binding.bridgeConnectionId;
    r.controllerNumber = binding.controllerIndex;
    r.touch = t;
    bridge_.push(std::move(r));
}

// A pad's own trackpad carries no mouse buttons and no wheel.
void SlotTable::publishTouchToSatellite(const SlotBinding& binding, const gamepad::TouchpadState& t,
                                        const uint32_t eventTimeMs, PendingDatagram& out) {
    auto session = sessions_.get(binding.sessionHandle);
    if (!session) return;
    const uint8_t idx = (uint8_t)(binding.controllerIndex & 0xFF);
    dish_send::stageTouchpadFrame(session, idx, t, false, false, eventTimeMs, 0, out);
}

// Stages under the slot lock, so the caller sends out once the lock is let go.
void SlotTable::routeUsbTouchpad(const int32_t deviceId, const gamepad::TouchpadState& t,
                                 const uint32_t eventTimeMs, PendingDatagram& out) {
    std::lock_guard<TrackedMutex> lock(slotsMtx_);
    auto it = slots_.find(deviceId);
    if (it == slots_.end()) return;

    const SlotBinding& binding = it->second;
    switch (binding.kind) {
    case SLOT_MOONLIGHT:
        publishTouchToBridge(binding, t);
        return;
    case SLOT_SATELLITE:
        publishTouchToSatellite(binding, t, eventTimeMs, out);
        return;
    case SLOT_BLUETOOTH:
    case SLOT_NONE:
        return;
    }
}

void SlotTable::applyUsbTouchpad(const int32_t deviceId, const gamepad::TouchpadState& t,
                                 const uint32_t eventTimeMs) {
    PendingDatagram datagram;
    routeUsbTouchpad(deviceId, t, eventTimeMs, datagram);
    dish_send::transmitDatagram(datagram);
}

// Caller holds the device lock. An unknown device has no quirks yet, which is the standard layout.
uint8_t SlotTable::quirkFor(const int32_t deviceId) {
    auto it = devices_.find(deviceId);
    if (it == devices_.end()) return 0;
    return it->second.quirk;
}

// Caller holds the device lock; action is a down or up edge, the events keyVerdict answers APPLY.
void SlotTable::stageFrameworkKey(const int32_t deviceId, const int32_t keyCode,
                                  const int32_t action, PendingDatagram& out) {
    frameworkEventCounts_[deviceId]++;
    auto& state = devices_[deviceId];
    if (gamepad::applyKey(state, keyCode, action == gamepad::KEY_ACTION_DOWN)) {
        publishIfChanged(deviceId, state, out);
    }
}

// True when the key is the pad's to consume. Stages under the device lock, so the caller sends out
// once the lock is let go.
bool SlotTable::filterFrameworkKey(const int32_t deviceId, const int32_t keyCode,
                                   const int32_t action, PendingDatagram& out) {
    std::lock_guard<TrackedMutex> lock(devicesMtx_);
    const gamepad::KeyVerdict verdict = gamepad::keyVerdict(keyCode, quirkFor(deviceId), action);
    if (verdict == gamepad::KeyVerdict::PASS) return false;
    if (verdict == gamepad::KeyVerdict::APPLY) stageFrameworkKey(deviceId, keyCode, action, out);
    return true;
}

bool SlotTable::applyFrameworkKey(const int32_t deviceId, const int32_t keyCode,
                                  const int32_t action) {
    PendingDatagram datagram;
    const bool consumed = filterFrameworkKey(deviceId, keyCode, action, datagram);
    dish_send::transmitPublished(datagram);
    return consumed;
}

// Stages under the device lock, so the caller sends out once the lock is let go.
void SlotTable::stageFrameworkMotion(const int32_t deviceId, const int32_t action,
                                     const gamepad::FrameworkAxes& sample, PendingDatagram& out) {
    std::lock_guard<TrackedMutex> lock(devicesMtx_);
    auto& state = devices_[deviceId];

    if (action == gamepad::MOTION_ACTION_CANCEL) {
        gamepad::resetState(state);
        publishIfChanged(deviceId, state, out);
        return;
    }
    if (action != gamepad::MOTION_ACTION_MOVE) return;

    frameworkEventCounts_[deviceId]++;
    applyJoystickSample(state, sample);
    publishIfChanged(deviceId, state, out);
}

void SlotTable::applyFrameworkMotion(const int32_t deviceId, const int32_t action,
                                     const gamepad::FrameworkAxes& sample) {
    PendingDatagram datagram;
    stageFrameworkMotion(deviceId, action, sample, datagram);
    dish_send::transmitPublished(datagram);
}

void SlotTable::releaseAll() {
    std::vector<int32_t> deviceIds;
    {
        std::lock_guard<TrackedMutex> lock(devicesMtx_);
        deviceIds.reserve(devices_.size());
        for (const auto& kv : devices_) deviceIds.push_back(kv.first);
    }
    for (const int32_t deviceId : deviceIds) resetAndPublish(deviceId);
}

void SlotTable::bindSatellite(const int32_t deviceId, const int sessionHandle,
                              const int controllerIndex) {
    {
        std::lock_guard<TrackedMutex> lock(slotsMtx_);
        auto& b = slots_[deviceId];
        b.kind = SLOT_SATELLITE;
        b.sessionHandle = sessionHandle;
        b.controllerIndex = controllerIndex;
        b.bridgeConnectionId.reset();
    }
    syncSlotBaseline(deviceId);
}

void SlotTable::bindBridge(const int32_t deviceId, dish_bridge::BridgeConnectionId connectionId,
                           const SlotKind kind, const int controllerIndex) {
    {
        std::lock_guard<TrackedMutex> lock(slotsMtx_);
        auto& b = slots_[deviceId];
        b.kind = kind;
        b.sessionHandle = -1;
        b.controllerIndex = controllerIndex;
        b.bridgeConnectionId = std::move(connectionId);
    }
    syncSlotBaseline(deviceId);
}

void SlotTable::unbind(const int32_t deviceId) {
    std::lock_guard<TrackedMutex> lock(slotsMtx_);
    slots_.erase(deviceId);
}

void SlotTable::clearAll() {
    std::lock_guard<TrackedMutex> lock(slotsMtx_);
    slots_.clear();
}

void SlotTable::setDeadzones(const int32_t deviceId, const float flatX, const float flatY,
                             const float flatZ, const float flatRZ) {
    std::lock_guard<TrackedMutex> lock(devicesMtx_);
    auto& s = devices_[deviceId];
    s.flatX = flatX;
    s.flatY = flatY;
    s.flatZ = flatZ;
    s.flatRZ = flatRZ;
}

void SlotTable::setQuirk(const int32_t deviceId, const uint8_t quirk) {
    std::lock_guard<TrackedMutex> lock(devicesMtx_);
    devices_[deviceId].quirk = quirk;
}

void SlotTable::setInspecting(const bool on) { inspect_.store(on, std::memory_order_relaxed); }

size_t SlotTable::formatDeviceState(const int32_t deviceId, char* buf, const size_t cap) {
    std::lock_guard<TrackedMutex> lock(devicesMtx_);
    auto it = devices_.find(deviceId);
    if (it == devices_.end()) return 0;
    return gamepad::formatDeviceStateJson(it->second, buf, cap);
}

uint64_t SlotTable::frameworkEventCount(const int32_t deviceId) {
    std::lock_guard<TrackedMutex> lock(devicesMtx_);
    auto it = frameworkEventCounts_.find(deviceId);
    return it == frameworkEventCounts_.end() ? 0 : it->second;
}

} // namespace dish_slots
