// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

#include "bridge_connection_ids.h"
#include "gamepad_input.h"
#include "send_path.h"
#include "session_table.h"
#include "tracked_mutex.h"

// Every physical pad's state and the slot it is bound to: a satellite session's controller, or a
// Bluetooth or Moonlight connection the bridge thread serves.
namespace dish_slots {

// The pads the app tracks at once.
inline constexpr size_t MAX_SLOTS = 16;

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
    // Names the bind's connection id in its bridge reports; 0 when there is none.
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

// What one URB of a USB pad carries: its pad state, when it decoded to one; a motion sample due at
// the motion cadence; a touch frame the send-on-change gate let through.
struct UsbFrame {
    const gamepad::DeviceState* report = nullptr;
    const dish_send::MotionSample* motion = nullptr;
    const gamepad::TouchpadState* touch = nullptr;
    uint32_t touchEventTimeMs = 0;
};

inline constexpr size_t STATE_WORDS =
    (sizeof(gamepad::DeviceState) + sizeof(uint64_t) - 1) / sizeof(uint64_t);

// One pad's slot. Its plain fields belong to the pad's one reporting thread (its USB poller, or
// the UI thread for a framework pad) while that thread is inside its bracket (epoch odd), and to a
// cold writer while the generation is odd. Each side stores its own word, fences, then loads the
// other's, so the two never hold the slot at once.
struct HotSlot {
    alignas(64) std::atomic<uint32_t> gen{0};
    alignas(64) std::atomic<uint64_t> epoch{0};

    // The slot is taken, by the pad deviceId names.
    alignas(64) bool held = false;
    int32_t deviceId = 0;
    // The pad's state exists: prewarmed, configured or reported since its last forget.
    bool hasState = false;
    SlotBinding binding;
    gamepad::DeviceState state;

    // Read without either side: the UI thread's count of the framework events it applied, and,
    // while the inspector is open, the state under a seqlock of its own.
    std::atomic<uint64_t> frameworkEvents{0};
    std::atomic<uint32_t> mirrorGen{0};
    std::atomic<uint64_t> mirror[STATE_WORDS] = {};
};

class SlotTable {
  public:
    SlotTable(dish_session::SessionTable& sessions, BridgeSink& bridge)
        : sessions_(sessions), bridge_(bridge) {}
    SlotTable(const SlotTable&) = delete;
    SlotTable& operator=(const SlotTable&) = delete;

    // The reporting thread's entry points: the USB poller's (dispatch.h) and the UI thread's. No
    // lock and no heap; a report that meets a cold write waits for it to close.
    // Everything one URB produced, sent back to back under one hold of the session's gate, its
    // counters consecutive: the gamepad report when it changed, then motion, then touch.
    void applyUsbFrame(int32_t deviceId, const UsbFrame& frame);
    void applyUsbReport(int32_t deviceId, const gamepad::DeviceState& nu);
    void applyUsbMotion(int32_t deviceId, const dish_send::MotionSample& sample);
    void applyUsbTouchpad(int32_t deviceId, const gamepad::TouchpadState& t, uint32_t eventTimeMs);
    // A framework key event; true when the key is the pad's to consume.
    bool applyFrameworkKey(int32_t deviceId, int32_t keyCode, int32_t action);
    // A framework joystick event; action is already masked.
    void applyFrameworkMotion(int32_t deviceId, int32_t action,
                              const gamepad::FrameworkAxes& sample);

    // The cold path. A pad is known from its prewarm (USB attach), its configuration or its bind;
    // reports for a pad the table does not know are dropped.
    void prewarmDevice(int32_t deviceId);
    void resetAndPublish(int32_t deviceId);
    void forgetDevice(int32_t deviceId);
    // Each device is reset and sent on its own.
    void releaseAll();

    void bindSatellite(int32_t deviceId, int sessionHandle, int controllerIndex);
    void bindBridge(int32_t deviceId, dish_bridge::BridgeConnectionId connectionId, SlotKind kind,
                    int controllerIndex);
    void unbind(int32_t deviceId);
    void clearAll();

    void setDeadzones(int32_t deviceId, float flatX, float flatY, float flatZ, float flatRZ);
    void setQuirk(int32_t deviceId, uint8_t quirk);
    // Diagnostics-only mirror gate: motion/touch land in the device state, and the state in the
    // inspector's mirror, solely while an inspector screen is open.
    void setInspecting(bool on);
    // formatDeviceStateJson of the device's state as the inspector last saw it; 0 for an unknown
    // device.
    size_t formatDeviceState(int32_t deviceId, char* buf, size_t cap);
    uint64_t frameworkEventCount(int32_t deviceId);

    // The id a bridge report's connection number names while a slot is bound with it; null once
    // the bind it came from is gone.
    dish_bridge::BridgeConnectionId bridgeConnectionId(uint32_t connection);

  private:
    int find(int32_t deviceId) const;
    int findOrTake(int32_t deviceId);
    void unbindLocked(size_t index);
    void freeSlot(size_t index);
    uint32_t nextBridgeConnection();

    void publishIfChanged(HotSlot& slot);
    bool takeUsbReport(HotSlot& slot, const gamepad::DeviceState& nu);
    void sendUsbFrame(HotSlot& slot, const UsbFrame& frame, bool withGamepad);
    void pushUsbFrame(HotSlot& slot, const UsbFrame& frame, bool withGamepad);
    void publishGamepadToBridge(const SlotBinding& binding, const gamepad::DeviceState& s);
    void publishMotionToBridge(const SlotBinding& binding, const dish_send::MotionSample& m);
    void publishTouchToBridge(const SlotBinding& binding, const gamepad::TouchpadState& t);
    void syncSlotBaseline(HotSlot& slot);
    void resetToNeutral(HotSlot& slot);
    void applyKeyEdge(HotSlot& slot, int32_t keyCode, int32_t action);
    void mirrorIfInspecting(HotSlot& slot);

    dish_session::SessionTable& sessions_;
    BridgeSink& bridge_;

    // The cold path's: which pad holds which slot, and each bind's connection id and number.
    TrackedMutex bindingsMtx_{"input.bindings"};
    std::array<dish_bridge::BridgeConnectionId, MAX_SLOTS> connectionIds_ = {};
    std::array<uint32_t, MAX_SLOTS> connectionNumbers_ = {};
    uint32_t lastBridgeConnection_ = 0;

    // The packed line the reporting threads scan: a held slot's deviceId, tagged; 0 when free.
    std::array<std::atomic<uint64_t>, MAX_SLOTS> ids_ = {};
    std::array<HotSlot, MAX_SLOTS> slots_;

    // Off costs one relaxed load per report, the same budget as the latency bench markers.
    std::atomic<bool> inspect_{false};
};

} // namespace dish_slots
