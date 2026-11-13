// SPDX-License-Identifier: LGPL-3.0-or-later

#include "slot_table.h"

#include <string.h>

#include <mutex>
#include <thread>
#include <utility>

#include "log_port.h"

namespace dish_slots {

using dish_send::MotionSample;
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
    s.extButtons = nu.extButtons;
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

uint64_t tagOf(const int32_t deviceId) {
    return (uint64_t{1} << 32) | static_cast<uint32_t>(deviceId);
}

// The reporting thread inside its slot: odd epoch, a full fence, then the generation. A cold write
// that is open is waited out with the bracket closed, so its own wait for the bracket returns.
class Reporting {
  public:
    explicit Reporting(HotSlot& slot) : slot_(slot) {
        for (;;) {
            step();
            std::atomic_thread_fence(std::memory_order_seq_cst);
            const uint32_t gen = slot_.gen.load(std::memory_order_acquire);
            if ((gen & 1u) == 0) return;
            step();
            while (slot_.gen.load(std::memory_order_acquire) == gen) std::this_thread::yield();
        }
    }
    ~Reporting() { step(); }
    Reporting(const Reporting&) = delete;
    Reporting& operator=(const Reporting&) = delete;

  private:
    // The reporting thread is the epoch's one writer.
    void step() {
        slot_.epoch.store(slot_.epoch.load(std::memory_order_relaxed) + 1,
                          std::memory_order_release);
    }

    HotSlot& slot_;
};

// A cold write: the generation turned odd by compare-exchange, so two cold writers of one slot
// exclude each other, then a full fence and the reporting thread's bracket waited out. Waiting for
// the epoch to change, rather than to turn even, means a stream of reports cannot starve it.
class ColdWrite {
  public:
    explicit ColdWrite(HotSlot& slot) : slot_(slot) {
        uint32_t gen = slot_.gen.load(std::memory_order_relaxed);
        for (;;) {
            if ((gen & 1u) != 0) {
                std::this_thread::yield();
                gen = slot_.gen.load(std::memory_order_relaxed);
                continue;
            }
            if (slot_.gen.compare_exchange_weak(gen, gen + 1, std::memory_order_acq_rel,
                                                std::memory_order_relaxed)) {
                break;
            }
        }
        std::atomic_thread_fence(std::memory_order_seq_cst);
        const uint64_t seen = slot_.epoch.load(std::memory_order_acquire);
        if ((seen & 1u) == 0) return;
        while (slot_.epoch.load(std::memory_order_acquire) == seen) std::this_thread::yield();
    }
    ~ColdWrite() { slot_.gen.fetch_add(1, std::memory_order_release); }
    ColdWrite(const ColdWrite&) = delete;
    ColdWrite& operator=(const ColdWrite&) = delete;

  private:
    HotSlot& slot_;
};

// The slot's holder is the mirror's one writer.
void mirrorState(HotSlot& slot) {
    uint64_t raw[STATE_WORDS] = {};
    memcpy(raw, &slot.state, sizeof(slot.state));
    const uint32_t gen = slot.mirrorGen.load(std::memory_order_relaxed);
    slot.mirrorGen.store(gen + 1, std::memory_order_relaxed);
    std::atomic_thread_fence(std::memory_order_release);
    for (size_t i = 0; i < STATE_WORDS; i++)
        slot.mirror[i].store(raw[i], std::memory_order_relaxed);
    slot.mirrorGen.store(gen + 2, std::memory_order_release);
}

DeviceState readMirror(const HotSlot& slot) {
    for (;;) {
        const uint32_t before = slot.mirrorGen.load(std::memory_order_acquire);
        if ((before & 1u) != 0) {
            std::this_thread::yield();
            continue;
        }
        uint64_t raw[STATE_WORDS];
        for (size_t i = 0; i < STATE_WORDS; i++) {
            raw[i] = slot.mirror[i].load(std::memory_order_relaxed);
        }
        std::atomic_thread_fence(std::memory_order_acquire);
        if (slot.mirrorGen.load(std::memory_order_relaxed) != before) continue;
        DeviceState s;
        memcpy(&s, raw, sizeof(s));
        return s;
    }
}

// The UI thread is the count's one writer while it reports; a cold writer zeroes it only while it
// holds the slot.
void countFrameworkEvent(HotSlot& slot) {
    slot.frameworkEvents.store(slot.frameworkEvents.load(std::memory_order_relaxed) + 1,
                               std::memory_order_relaxed);
}

// Whether the slot still belongs to the pad its reporter found it for.
bool holds(const HotSlot& slot, const int32_t deviceId) {
    return slot.held && slot.deviceId == deviceId;
}

bool consumedWithoutState(const int32_t keyCode, const int32_t action) {
    return gamepad::keyVerdict(keyCode, 0, action) != gamepad::KeyVerdict::PASS;
}

} // namespace

int SlotTable::find(const int32_t deviceId) const {
    const uint64_t tag = tagOf(deviceId);
    for (size_t i = 0; i < MAX_SLOTS; i++) {
        if (ids_[i].load(std::memory_order_acquire) == tag) return static_cast<int>(i);
    }
    return -1;
}

// Caller holds bindingsMtx_. The pad's slot, taken from the free ones when it has none; -1 with
// every slot held.
int SlotTable::findOrTake(const int32_t deviceId) {
    const int found = find(deviceId);
    if (found >= 0) return found;
    for (size_t i = 0; i < MAX_SLOTS; i++) {
        if (ids_[i].load(std::memory_order_relaxed) != 0) continue;
        HotSlot& slot = slots_[i];
        {
            const ColdWrite write(slot);
            slot.held = true;
            slot.deviceId = deviceId;
            slot.hasState = false;
            slot.binding = SlotBinding{};
            slot.state = DeviceState{};
            slot.frameworkEvents.store(0, std::memory_order_relaxed);
        }
        ids_[i].store(tagOf(deviceId), std::memory_order_release);
        return static_cast<int>(i);
    }
    dish_log::error("input: all %u slots are held, device %d is not tracked", (unsigned)MAX_SLOTS,
                    (int)deviceId);
    return -1;
}

// Caller holds bindingsMtx_ and the slot's cold write. A report already past the scan finds the
// slot no longer its own once the write closes.
void SlotTable::freeSlot(const size_t index) {
    ids_[index].store(0, std::memory_order_release);
    slots_[index].held = false;
}

// Caller holds bindingsMtx_. Never 0, which names no connection.
uint32_t SlotTable::nextBridgeConnection() {
    if (++lastBridgeConnection_ == 0) ++lastBridgeConnection_;
    return lastBridgeConnection_;
}

void SlotTable::publishGamepadToBridge(const SlotBinding& binding, const DeviceState& s) {
    if (binding.bridgeConnection == 0) return;
    BridgeReport r{};
    r.kind = binding.kind;
    r.payload = BridgeReport::GAMEPAD;
    r.connection = binding.bridgeConnection;
    r.controllerNumber = binding.controllerIndex;
    r.wButtons = s.wButtons;
    r.bLT = s.bLT;
    r.bRT = s.bRT;
    r.sLX = s.sLX;
    r.sLY = s.sLY;
    r.sRX = s.sRX;
    r.sRY = s.sRY;
    bridge_.push(r);
}

// The slot's holder. Bind-check before consume: a sample dropped for lack of a slot must not burn
// the latch, or the slot it later binds to never sees that state.
void SlotTable::publishIfChanged(HotSlot& slot) {
    const SlotBinding& binding = slot.binding;
    if (binding.kind == SLOT_NONE) return;
    if (!gamepad::consumePublishIfChanged(slot.state)) return;
    switch (binding.kind) {
    case SLOT_SATELLITE: {
        const dish_session::SessionRef session = sessions_.find(binding.sessionHandle);
        if (!session) return;
        dish_send::Framed report;
        dish_send::frameGamepadReport(
            report, session->protocolVersion.load(std::memory_order_relaxed),
            binding.controllerIndex, dish_send::xusbReportOf(slot.state), slot.state.extButtons);
        report.closesStage1 = true;
        dish_send::sendFramed(session, &report, 1);
        return;
    }
    case SLOT_BLUETOOTH:
    case SLOT_MOONLIGHT:
        publishGamepadToBridge(binding, slot.state);
        return;
    case SLOT_NONE:
        return;
    }
}

void SlotTable::mirrorIfInspecting(HotSlot& slot) {
    if (inspect_.load(std::memory_order_relaxed)) mirrorState(slot);
}

// Inside a cold write. deviceId is reused across reconnects, so the new pad would inherit stale
// held inputs; reset and re-arm so it syncs to neutral.
void SlotTable::syncSlotBaseline(HotSlot& slot) {
    if (!slot.hasState) return;
    gamepad::resetState(slot.state);
    gamepad::resetPublishLatch(slot.state);
    mirrorIfInspecting(slot);
    publishIfChanged(slot);
}

// Inside a cold write.
void SlotTable::resetToNeutral(HotSlot& slot) {
    if (!slot.hasState) return;
    gamepad::resetState(slot.state);
    mirrorIfInspecting(slot);
    publishIfChanged(slot);
}

void SlotTable::prewarmDevice(const int32_t deviceId) {
    const std::lock_guard<TrackedMutex> lock(bindingsMtx_);
    const int index = findOrTake(deviceId);
    if (index < 0) return;
    HotSlot& slot = slots_[static_cast<size_t>(index)];
    const ColdWrite write(slot);
    slot.hasState = true;
}

// The slot's reporter: the report taken into the pad's state, and whether it changed what the bound
// slot last published (bind-check before consume, as publishIfChanged).
bool SlotTable::takeUsbReport(HotSlot& slot, const DeviceState& nu) {
    DeviceState& s = slot.state;
    slot.hasState = true;
    copyPadAxes(nu, s);
    applyUsbStickDeadzone(s.sLX, s.sLY);
    applyUsbStickDeadzone(s.sRX, s.sRY);
    if (inspect_.load(std::memory_order_relaxed)) {
        mirrorMotionForInspector(nu, s);
        mirrorTouchForInspector(nu, s);
        mirrorState(slot);
    }
    return slot.binding.kind != SLOT_NONE && gamepad::consumePublishIfChanged(s);
}

// A pad's own trackpad carries no mouse buttons and no wheel.
void SlotTable::sendUsbFrame(HotSlot& slot, const UsbFrame& frame, const bool withGamepad) {
    const SlotBinding& binding = slot.binding;
    const dish_session::SessionRef session = sessions_.find(binding.sessionHandle);
    if (!session) return;
    const int32_t protocolVersion = session->protocolVersion.load(std::memory_order_relaxed);
    dish_send::Framed batch[3];
    size_t count = 0;
    if (withGamepad) {
        dish_send::frameGamepadReport(batch[count], protocolVersion, binding.controllerIndex,
                                      dish_send::xusbReportOf(slot.state), slot.state.extButtons);
        batch[count++].closesStage1 = true;
    }
    if (frame.motion != nullptr) {
        dish_send::frameMotion(batch[count++], binding.controllerIndex, *frame.motion);
    }
    if (frame.touch != nullptr) {
        dish_send::frameTouchpad(batch[count++], protocolVersion,
                                 (uint8_t)(binding.controllerIndex & 0xFF), *frame.touch, false,
                                 false, frame.touchEventTimeMs, 0);
    }
    dish_send::sendFramed(session, batch, count);
}

// The Bluetooth HID descriptor is a plain gamepad, so motion and touch have nowhere to go on that
// transport; Moonlight takes all three through the bridge.
void SlotTable::pushUsbFrame(HotSlot& slot, const UsbFrame& frame, const bool withGamepad) {
    const SlotBinding& binding = slot.binding;
    if (withGamepad) publishGamepadToBridge(binding, slot.state);
    if (binding.kind != SLOT_MOONLIGHT) return;
    if (frame.motion != nullptr) publishMotionToBridge(binding, *frame.motion);
    if (frame.touch != nullptr) publishTouchToBridge(binding, *frame.touch);
}

void SlotTable::applyUsbFrame(const int32_t deviceId, const UsbFrame& frame) {
    const int index = find(deviceId);
    if (index < 0) return;
    HotSlot& slot = slots_[static_cast<size_t>(index)];
    const Reporting reporting(slot);
    if (!holds(slot, deviceId)) return;
    const bool withGamepad = frame.report != nullptr && takeUsbReport(slot, *frame.report);
    switch (slot.binding.kind) {
    case SLOT_SATELLITE:
        sendUsbFrame(slot, frame, withGamepad);
        return;
    case SLOT_BLUETOOTH:
    case SLOT_MOONLIGHT:
        pushUsbFrame(slot, frame, withGamepad);
        return;
    case SLOT_NONE:
        return;
    }
}

void SlotTable::applyUsbReport(const int32_t deviceId, const DeviceState& nu) {
    UsbFrame frame;
    frame.report = &nu;
    applyUsbFrame(deviceId, frame);
}

void SlotTable::resetAndPublish(const int32_t deviceId) {
    std::unique_lock<TrackedMutex> lock(bindingsMtx_);
    const int index = find(deviceId);
    if (index < 0) return;
    HotSlot& slot = slots_[static_cast<size_t>(index)];
    const ColdWrite write(slot);
    lock.unlock();
    resetToNeutral(slot);
}

void SlotTable::forgetDevice(const int32_t deviceId) {
    const std::lock_guard<TrackedMutex> lock(bindingsMtx_);
    const int index = find(deviceId);
    if (index < 0) return;
    HotSlot& slot = slots_[static_cast<size_t>(index)];
    const ColdWrite write(slot);
    slot.hasState = false;
    slot.state = DeviceState{};
    slot.frameworkEvents.store(0, std::memory_order_relaxed);
    if (slot.binding.kind == SLOT_NONE) freeSlot(static_cast<size_t>(index));
}

// Kotlin translates to CONTROLLER_MOTION and drops samples the host never asked for (MOTION_EVENT
// gate), so this stays fire-and-forget.
void SlotTable::publishMotionToBridge(const SlotBinding& binding, const MotionSample& m) {
    if (binding.bridgeConnection == 0) return;
    BridgeReport r{};
    r.kind = SLOT_MOONLIGHT;
    r.payload = BridgeReport::MOTION;
    r.connection = binding.bridgeConnection;
    r.controllerNumber = binding.controllerIndex;
    memcpy(r.gyro, m.gyro, sizeof(r.gyro));
    memcpy(r.accel, m.accel, sizeof(r.accel));
    r.timestampDeltaUs = m.timestampDeltaUs;
    bridge_.push(r);
}

void SlotTable::applyUsbMotion(const int32_t deviceId, const MotionSample& sample) {
    UsbFrame frame;
    frame.motion = &sample;
    applyUsbFrame(deviceId, frame);
}

// Satellite gets the full-state touch frame; Moonlight gets it via the bridge, where Kotlin diffs
// it into CONTROLLER_TOUCH events. Routing (ds4 pad vs mouse vs off) is the satellite receiver's
// job, declared per slot in the descriptor. eventTimeMs is not carried: Moonlight re-times these
// events on the reliable control stream.
void SlotTable::publishTouchToBridge(const SlotBinding& binding, const gamepad::TouchpadState& t) {
    if (binding.bridgeConnection == 0) return;
    BridgeReport r{};
    r.kind = SLOT_MOONLIGHT;
    r.payload = BridgeReport::TOUCH;
    r.connection = binding.bridgeConnection;
    r.controllerNumber = binding.controllerIndex;
    r.touch = t;
    bridge_.push(r);
}

void SlotTable::applyUsbTouchpad(const int32_t deviceId, const gamepad::TouchpadState& t,
                                 const uint32_t eventTimeMs) {
    UsbFrame frame;
    frame.touch = &t;
    frame.touchEventTimeMs = eventTimeMs;
    applyUsbFrame(deviceId, frame);
}

// The slot's holder; action is a down or up edge, the events keyVerdict answers APPLY.
void SlotTable::applyKeyEdge(HotSlot& slot, const int32_t keyCode, const int32_t action) {
    countFrameworkEvent(slot);
    slot.hasState = true;
    if (!gamepad::applyKey(slot.state, keyCode, action == gamepad::KEY_ACTION_DOWN)) return;
    mirrorIfInspecting(slot);
    publishIfChanged(slot);
}

// A pad the table does not know has no quirks yet, which is the standard layout, and no state to
// apply the key to.
bool SlotTable::applyFrameworkKey(const int32_t deviceId, const int32_t keyCode,
                                  const int32_t action) {
    const int index = find(deviceId);
    if (index < 0) return consumedWithoutState(keyCode, action);
    HotSlot& slot = slots_[static_cast<size_t>(index)];
    const Reporting reporting(slot);
    if (!holds(slot, deviceId)) return consumedWithoutState(keyCode, action);
    const gamepad::KeyVerdict verdict = gamepad::keyVerdict(keyCode, slot.state.quirk, action);
    if (verdict == gamepad::KeyVerdict::PASS) return false;
    if (verdict == gamepad::KeyVerdict::APPLY) applyKeyEdge(slot, keyCode, action);
    return true;
}

void SlotTable::applyFrameworkMotion(const int32_t deviceId, const int32_t action,
                                     const gamepad::FrameworkAxes& sample) {
    const int index = find(deviceId);
    if (index < 0) return;
    HotSlot& slot = slots_[static_cast<size_t>(index)];
    const Reporting reporting(slot);
    if (!holds(slot, deviceId)) return;
    slot.hasState = true;
    if (action == gamepad::MOTION_ACTION_CANCEL) {
        gamepad::resetState(slot.state);
        mirrorIfInspecting(slot);
        publishIfChanged(slot);
        return;
    }
    if (action != gamepad::MOTION_ACTION_MOVE) return;
    countFrameworkEvent(slot);
    applyJoystickSample(slot.state, sample);
    mirrorIfInspecting(slot);
    publishIfChanged(slot);
}

void SlotTable::releaseAll() {
    for (size_t i = 0; i < MAX_SLOTS; i++) {
        std::unique_lock<TrackedMutex> lock(bindingsMtx_);
        if (ids_[i].load(std::memory_order_relaxed) == 0) continue;
        HotSlot& slot = slots_[i];
        const ColdWrite write(slot);
        lock.unlock();
        resetToNeutral(slot);
    }
}

void SlotTable::bindSatellite(const int32_t deviceId, const int sessionHandle,
                              const int controllerIndex) {
    std::unique_lock<TrackedMutex> lock(bindingsMtx_);
    const int index = findOrTake(deviceId);
    if (index < 0) return;
    HotSlot& slot = slots_[static_cast<size_t>(index)];
    const ColdWrite write(slot);
    slot.binding = SlotBinding{SLOT_SATELLITE, sessionHandle, controllerIndex, 0};
    connectionIds_[static_cast<size_t>(index)].reset();
    connectionNumbers_[static_cast<size_t>(index)] = 0;
    lock.unlock();
    syncSlotBaseline(slot);
}

void SlotTable::bindBridge(const int32_t deviceId, dish_bridge::BridgeConnectionId connectionId,
                           const SlotKind kind, const int controllerIndex) {
    std::unique_lock<TrackedMutex> lock(bindingsMtx_);
    const int index = findOrTake(deviceId);
    if (index < 0) return;
    HotSlot& slot = slots_[static_cast<size_t>(index)];
    const ColdWrite write(slot);
    const uint32_t connection = connectionId ? nextBridgeConnection() : 0;
    slot.binding = SlotBinding{kind, -1, controllerIndex, connection};
    connectionIds_[static_cast<size_t>(index)] = std::move(connectionId);
    connectionNumbers_[static_cast<size_t>(index)] = connection;
    lock.unlock();
    syncSlotBaseline(slot);
}

// Caller holds bindingsMtx_.
void SlotTable::unbindLocked(const size_t index) {
    HotSlot& slot = slots_[index];
    const ColdWrite write(slot);
    slot.binding = SlotBinding{};
    connectionIds_[index].reset();
    connectionNumbers_[index] = 0;
    if (!slot.hasState) freeSlot(index);
}

void SlotTable::unbind(const int32_t deviceId) {
    const std::lock_guard<TrackedMutex> lock(bindingsMtx_);
    const int index = find(deviceId);
    if (index < 0) return;
    unbindLocked(static_cast<size_t>(index));
}

void SlotTable::clearAll() {
    const std::lock_guard<TrackedMutex> lock(bindingsMtx_);
    for (size_t i = 0; i < MAX_SLOTS; i++) {
        if (ids_[i].load(std::memory_order_relaxed) != 0) unbindLocked(i);
    }
}

void SlotTable::setDeadzones(const int32_t deviceId, const float flatX, const float flatY,
                             const float flatZ, const float flatRZ) {
    const std::lock_guard<TrackedMutex> lock(bindingsMtx_);
    const int index = findOrTake(deviceId);
    if (index < 0) return;
    HotSlot& slot = slots_[static_cast<size_t>(index)];
    const ColdWrite write(slot);
    slot.hasState = true;
    slot.state.flatX = flatX;
    slot.state.flatY = flatY;
    slot.state.flatZ = flatZ;
    slot.state.flatRZ = flatRZ;
}

void SlotTable::setQuirk(const int32_t deviceId, const uint8_t quirk) {
    const std::lock_guard<TrackedMutex> lock(bindingsMtx_);
    const int index = findOrTake(deviceId);
    if (index < 0) return;
    HotSlot& slot = slots_[static_cast<size_t>(index)];
    const ColdWrite write(slot);
    slot.hasState = true;
    slot.state.quirk = quirk;
}

// Opening the inspector refreshes every slot's mirror once, so a pad that is not reporting still
// shows its current state.
void SlotTable::setInspecting(const bool on) {
    inspect_.store(on, std::memory_order_relaxed);
    if (!on) return;
    for (size_t i = 0; i < MAX_SLOTS; i++) {
        const std::lock_guard<TrackedMutex> lock(bindingsMtx_);
        if (ids_[i].load(std::memory_order_relaxed) == 0) continue;
        const ColdWrite write(slots_[i]);
        mirrorState(slots_[i]);
    }
}

size_t SlotTable::formatDeviceState(const int32_t deviceId, char* buf, const size_t cap) {
    const int index = find(deviceId);
    if (index < 0) return 0;
    const DeviceState s = readMirror(slots_[static_cast<size_t>(index)]);
    if (find(deviceId) != index) return 0;
    return gamepad::formatDeviceStateJson(s, buf, cap);
}

uint64_t SlotTable::frameworkEventCount(const int32_t deviceId) {
    const int index = find(deviceId);
    if (index < 0) return 0;
    const uint64_t count =
        slots_[static_cast<size_t>(index)].frameworkEvents.load(std::memory_order_relaxed);
    return find(deviceId) == index ? count : 0;
}

dish_bridge::BridgeConnectionId SlotTable::bridgeConnectionId(const uint32_t connection) {
    if (connection == 0) return nullptr;
    const std::lock_guard<TrackedMutex> lock(bindingsMtx_);
    for (size_t i = 0; i < MAX_SLOTS; i++) {
        if (connectionNumbers_[i] == connection) return connectionIds_[i];
    }
    return nullptr;
}

} // namespace dish_slots
