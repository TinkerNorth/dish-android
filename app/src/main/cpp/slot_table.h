// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <unordered_map>

#include "bridge_connection_ids.h"
#include "gamepad_input.h"
#include "send_path.h"
#include "session_table.h"
#include "tracked_mutex.h"

// Every physical pad's state and the slot it is bound to: a satellite session's controller, or a
// Bluetooth or Moonlight connection the JNI layer's bridge thread serves.
namespace dish_slots {

enum SlotKind : uint8_t {
    SLOT_NONE = 0,
    SLOT_SATELLITE = 1,
    SLOT_BLUETOOTH = 2,
    SLOT_MOONLIGHT = 3
};

struct SlotBinding {
    SlotKind kind = SLOT_NONE;
    int sessionHandle = -1;
    int controllerIndex = -1;
    dish_bridge::BridgeConnectionId bridgeConnectionId;
    // Names bridgeConnectionId in the reports; 0 when there is none.
    uint32_t bridgeConnection = 0;
};

// Bridge kinds (Bluetooth, Moonlight) run off the UI thread: BluetoothHidDevice.sendReport is
// Binder IPC, and the Moonlight path encrypts + frames in Kotlin. One queue + thread serves both;
// the report's kind picks the Kotlin bridge to upcall.
struct BridgeReport {
    SlotKind kind = SLOT_NONE;
    // What the report carries: the Moonlight lane also ferries motion and
    // touch samples off the USB reader thread (Bluetooth stays gamepad-only).
    enum Payload : uint8_t { GAMEPAD = 0, MOTION = 1, TOUCH = 2 };
    Payload payload = GAMEPAD;
    // The bind's connection number (SlotTable::bridgeConnectionId resolves it to the id): a number
    // rather than the id itself, so a report copies whole through a lock-free ring.
    uint32_t connection = 0;
    int32_t controllerNumber = 0;
    uint16_t wButtons = 0;
    uint8_t bLT = 0, bRT = 0;
    int16_t sLX = 0, sLY = 0, sRX = 0, sRY = 0;
    int16_t gyro[3] = {};
    int16_t accel[3] = {};
    uint32_t timestampDeltaUs = 0;
    gamepad::TouchpadState touch;
};

// Where a bridge slot's reports go. Called on the input path, so it hands the report on and must
// not make a JVM upcall itself.
class BridgeSink {
  public:
    virtual void push(const BridgeReport& report) = 0;

  protected:
    ~BridgeSink() = default;
};

class SlotTable {
  public:
    SlotTable(dish_session::SessionTable& sessions, BridgeSink& bridge)
        : sessions_(sessions), bridge_(bridge) {}
    SlotTable(const SlotTable&) = delete;
    SlotTable& operator=(const SlotTable&) = delete;

    // The USB reader thread's entry points (dispatch.h).
    void prewarmDevice(int32_t deviceId);
    void applyUsbReport(int32_t deviceId, const gamepad::DeviceState& nu);
    void resetAndPublish(int32_t deviceId);
    void forgetDevice(int32_t deviceId);
    void applyUsbMotion(int32_t deviceId, const dish_send::MotionSample& sample);
    void applyUsbTouchpad(int32_t deviceId, const gamepad::TouchpadState& t, uint32_t eventTimeMs);

    // A framework key event; true when the key is the pad's to consume.
    bool applyFrameworkKey(int32_t deviceId, int32_t keyCode, int32_t action);
    // A framework joystick event; action is already masked.
    void applyFrameworkMotion(int32_t deviceId, int32_t action,
                              const gamepad::FrameworkAxes& sample);
    // Each device is reset and sent on its own, so no send happens under the device lock.
    void releaseAll();

    void bindSatellite(int32_t deviceId, int sessionHandle, int controllerIndex);
    void bindBridge(int32_t deviceId, dish_bridge::BridgeConnectionId connectionId, SlotKind kind,
                    int controllerIndex);
    void unbind(int32_t deviceId);
    void clearAll();

    void setDeadzones(int32_t deviceId, float flatX, float flatY, float flatZ, float flatRZ);
    void setQuirk(int32_t deviceId, uint8_t quirk);
    // Diagnostics-only mirror gate: motion/touch land in the device state solely for the inspector
    // snapshot, and only while an inspector screen is open.
    void setInspecting(bool on);
    // formatDeviceStateJson of the device's state; 0 for an unknown device.
    size_t formatDeviceState(int32_t deviceId, char* buf, size_t cap);
    uint64_t frameworkEventCount(int32_t deviceId);

    // The id a bridge report's connection number names while a slot is bound with it; null once
    // the bind it came from is gone.
    dish_bridge::BridgeConnectionId bridgeConnectionId(uint32_t connection);

  private:
    void publishIfChanged(int32_t deviceId, gamepad::DeviceState& s,
                          dish_send::PendingDatagram& out);
    void publishGamepadToSatellite(const SlotBinding& binding, const gamepad::DeviceState& s,
                                   dish_send::PendingDatagram& out);
    void publishGamepadToBridge(const SlotBinding& binding, const gamepad::DeviceState& s);
    void syncSlotBaseline(int32_t deviceId);
    void routeUsbMotion(int32_t deviceId, const dish_send::MotionSample& sample,
                        dish_send::PendingDatagram& out);
    void publishMotionToBridge(const SlotBinding& binding, const dish_send::MotionSample& m);
    void publishMotionToSatellite(const SlotBinding& binding, const dish_send::MotionSample& m,
                                  dish_send::PendingDatagram& out);
    void routeUsbTouchpad(int32_t deviceId, const gamepad::TouchpadState& t, uint32_t eventTimeMs,
                          dish_send::PendingDatagram& out);
    void publishTouchToBridge(const SlotBinding& binding, const gamepad::TouchpadState& t);
    void publishTouchToSatellite(const SlotBinding& binding, const gamepad::TouchpadState& t,
                                 uint32_t eventTimeMs, dish_send::PendingDatagram& out);
    uint8_t quirkFor(int32_t deviceId);
    uint32_t nextBridgeConnection();
    void stageFrameworkKey(int32_t deviceId, int32_t keyCode, int32_t action,
                           dish_send::PendingDatagram& out);
    bool filterFrameworkKey(int32_t deviceId, int32_t keyCode, int32_t action,
                            dish_send::PendingDatagram& out);
    void stageFrameworkMotion(int32_t deviceId, int32_t action,
                              const gamepad::FrameworkAxes& sample,
                              dish_send::PendingDatagram& out);

    dish_session::SessionTable& sessions_;
    BridgeSink& bridge_;

    TrackedMutex devicesMtx_{"input.devices"};
    std::unordered_map<int32_t, gamepad::DeviceState> devices_;
    std::unordered_map<int32_t, uint64_t> frameworkEventCounts_;

    TrackedMutex slotsMtx_{"input.slots"};
    std::unordered_map<int32_t, SlotBinding> slots_;
    uint32_t lastBridgeConnection_ = 0;

    // Off costs one relaxed load per report, the same budget as the latency bench markers.
    std::atomic<bool> inspect_{false};
};

} // namespace dish_slots
