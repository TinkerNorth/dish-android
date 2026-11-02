// SPDX-License-Identifier: LGPL-3.0-or-later

#include "slot_table.h"

#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "fake_satellite.h"
#include "hotpath_latency.h"
#include "recording_sinks.h"
#include "vector_session.h"
#include "wire_vectors.h"

using vector_session::Bytes;
using vector_session::bytesOf;

namespace {

constexpr int32_t kAndroidKeycodeButtonA = 96;
constexpr int32_t kAndroidKeycodeVolumeUp = 24;
constexpr uint16_t kXusbA = 0x1000;
constexpr auto kStillWaiting = std::chrono::milliseconds(200);
constexpr int kStreamReports = 4000;
constexpr int kRebinds = 200;
constexpr int kRoleReports = 1500;
constexpr int kFastHeartbeatMs = 50;
constexpr int kMicFrames = 20;
constexpr int kBatteryReports = 50;

gamepad::DeviceState pinnedUsbReport() {
    gamepad::DeviceState nu{};
    nu.wButtons = 0x1234;
    nu.bLT = 0x56;
    nu.bRT = 0xFE;
    nu.sLX = -12345;
    nu.sLY = 23456;
    nu.sRX = 1000;
    nu.sRY = -2000;
    return nu;
}

gamepad::DeviceState buttons(const uint16_t wButtons) {
    gamepad::DeviceState nu{};
    nu.wButtons = wButtons;
    return nu;
}

gamepad::TouchpadState pinnedTouch() {
    gamepad::TouchpadState t{};
    t.f0Active = true;
    t.f1Active = true;
    t.clickDown = true;
    t.f0Id = 9;
    t.f1Id = 10;
    t.f0X = -32000;
    t.f0Y = 31000;
    t.f1X = 1234;
    t.f1Y = -4321;
    return t;
}

class Slots : public vector_session::VectorSession {
  protected:
    void SetUp() override {
        vector_session::VectorSession::SetUp();
        slots = std::make_unique<dish_slots::SlotTable>(table, bridge);
    }

    // The XUSB buttons each MSG_GAMEPAD_DATA datagram carried, in the order they were sent.
    std::vector<uint16_t> sentButtons() {
        const vector_session::Key key = vector_session::vectorKey();
        std::vector<uint16_t> out;
        for (const Bytes& sent : sentBytes()) {
            const auto inner = fake_satellite::openFromClient(wire_vectors::kToken, key.bytes,
                                                              sent.data(), sent.size());
            EXPECT_TRUE(inner.has_value() && inner->msgType == dish_wire::MSG_GAMEPAD_DATA);
            if (!inner || inner->payload.size() < 3) continue;
            out.push_back((uint16_t)(inner->payload[1] | inner->payload[2] << 8));
        }
        return out;
    }

    recording::Bridge bridge;
    std::unique_ptr<dish_slots::SlotTable> slots;
};

} // namespace

TEST_F(Slots, TheUsbAndFrameworkPathsPutThePinnedBytesOnTheWire) {
    slots->bindSatellite(wire_vectors::kUsbDevice, handle, wire_vectors::kUsbController);
    slots->bindSatellite(wire_vectors::kFrameworkDevice, handle,
                         wire_vectors::kFrameworkController);
    EXPECT_TRUE(sentBytes().empty());

    slots->applyUsbReport(wire_vectors::kUsbDevice, pinnedUsbReport());
    EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kUsbGamepad)});
    slots->applyUsbReport(wire_vectors::kUsbDevice, pinnedUsbReport());
    EXPECT_TRUE(sentBytes().empty());

    slots->applyUsbMotion(wire_vectors::kUsbDevice,
                          dish_send::MotionSample{{100, -200, 300}, {-400, 500, -600}, 7777});
    EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kUsbMotion)});

    slots->applyUsbTouchpad(wire_vectors::kUsbDevice, pinnedTouch(), 0x89ABCDEFu);
    EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kUsbTouchpadV1)});
    session->protocolVersion.store(2);
    slots->applyUsbTouchpad(wire_vectors::kUsbDevice, pinnedTouch(), 0x89ABCDEFu);
    EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kUsbTouchpadV2)});

    const gamepad::FrameworkAxes sample{0.5f, -0.25f, 0.75f, -1.0f, 0.f, 0.f,
                                        1.0f, 0.f,    0.3f,  0.9f,  0.f, 0.f};
    slots->applyFrameworkMotion(wire_vectors::kFrameworkDevice, gamepad::MOTION_ACTION_MOVE,
                                sample);
    EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kFrameworkGamepad)});
    EXPECT_EQ(session->sentByCtrl[wire_vectors::kUsbController].load(), 1u);
    EXPECT_EQ(session->motionByCtrl[wire_vectors::kUsbController].load(), 1u);
}

TEST_F(Slots, AReportIsSentOnlyWhenItChangesWhatTheSlotLastSent) {
    slots->bindSatellite(1, handle, 0);
    slots->applyUsbReport(1, buttons(0x0001));
    slots->applyUsbReport(1, buttons(0x0001));
    slots->applyUsbReport(1, buttons(0x0002));
    slots->applyUsbReport(1, buttons(0x0002));
    EXPECT_EQ(sentButtons(), (std::vector<uint16_t>{0x0001, 0x0002}));
}

TEST_F(Slots, AnUnboundDeviceSendsNothingUntilItsBindStartsItFromNeutral) {
    slots->prewarmDevice(1);
    slots->applyUsbReport(1, buttons(0x0004));
    EXPECT_TRUE(sentBytes().empty());
    EXPECT_TRUE(bridge.taken().empty());

    slots->bindSatellite(1, handle, 0);
    slots->applyUsbReport(1, buttons(0x0004));
    EXPECT_EQ(sentButtons(), (std::vector<uint16_t>{0x0000, 0x0004}));
}

TEST_F(Slots, ABindSyncsTheSlotToNeutralSoAReportAfterARebindIsSentAgain) {
    slots->bindSatellite(1, handle, 0);
    slots->applyUsbReport(1, buttons(0x0008));
    EXPECT_EQ(sentButtons(), (std::vector<uint16_t>{0x0008}));

    slots->unbind(1);
    slots->applyUsbReport(1, buttons(0x0010));
    EXPECT_TRUE(sentBytes().empty());

    slots->bindSatellite(1, handle, 0);
    EXPECT_EQ(sentButtons(), (std::vector<uint16_t>{0x0000}));
    slots->applyUsbReport(1, buttons(0x0010));
    slots->applyUsbReport(1, buttons(0x0010));
    EXPECT_EQ(sentButtons(), (std::vector<uint16_t>{0x0010}));
}

TEST_F(Slots, ClearingEverySlotStopsEveryDevicesReports) {
    slots->bindSatellite(1, handle, 0);
    slots->bindSatellite(2, handle, 1);
    slots->clearAll();
    slots->applyUsbReport(1, buttons(0x0001));
    slots->applyUsbReport(2, buttons(0x0002));
    EXPECT_TRUE(sentBytes().empty());
}

TEST_F(Slots, AMoonlightSlotHandsEveryReportToTheBridgeAndNothingToTheWire) {
    const dish_bridge::BridgeConnectionId id = std::make_shared<const std::string>("moonlight-1");
    slots->bindBridge(1, id, dish_slots::SLOT_MOONLIGHT, 2);
    slots->applyUsbReport(1, pinnedUsbReport());
    slots->applyUsbMotion(1, dish_send::MotionSample{{1, 2, 3}, {4, 5, 6}, 7});
    slots->applyUsbTouchpad(1, pinnedTouch(), 99);
    EXPECT_TRUE(sentBytes().empty());

    const std::vector<dish_slots::BridgeReport> reports = bridge.taken();
    ASSERT_EQ(reports.size(), 3u);
    EXPECT_EQ(reports[0].payload, dish_slots::BridgeReport::GAMEPAD);
    EXPECT_EQ(reports[0].kind, dish_slots::SLOT_MOONLIGHT);
    EXPECT_EQ(slots->bridgeConnectionId(reports[0].connection), id);
    EXPECT_EQ(reports[1].connection, reports[0].connection);
    EXPECT_EQ(reports[2].connection, reports[0].connection);
    EXPECT_EQ(reports[0].controllerNumber, 2);
    EXPECT_EQ(reports[0].wButtons, 0x1234);
    EXPECT_EQ(reports[0].sLX, -12345);
    EXPECT_EQ(reports[0].sRX, 0);
    EXPECT_EQ(reports[1].payload, dish_slots::BridgeReport::MOTION);
    EXPECT_EQ(reports[1].gyro[2], 3);
    EXPECT_EQ(reports[1].accel[0], 4);
    EXPECT_EQ(reports[1].timestampDeltaUs, 7u);
    EXPECT_EQ(reports[2].payload, dish_slots::BridgeReport::TOUCH);
    EXPECT_TRUE(reports[2].touch == pinnedTouch());
}

TEST_F(Slots, ABluetoothSlotCarriesTheGamepadAlone) {
    const dish_bridge::BridgeConnectionId id = std::make_shared<const std::string>("bt-1");
    slots->bindBridge(1, id, dish_slots::SLOT_BLUETOOTH, -1);
    slots->applyUsbReport(1, buttons(0x0020));
    slots->applyUsbMotion(1, dish_send::MotionSample{{1, 2, 3}, {4, 5, 6}, 7});
    slots->applyUsbTouchpad(1, pinnedTouch(), 99);
    const std::vector<dish_slots::BridgeReport> reports = bridge.taken();
    ASSERT_EQ(reports.size(), 1u);
    EXPECT_EQ(reports[0].kind, dish_slots::SLOT_BLUETOOTH);
    EXPECT_EQ(reports[0].wButtons, 0x0020);
}

TEST_F(Slots, ARebindNamesANewConnectionAndTheOldOneResolvesToNothing) {
    const dish_bridge::BridgeConnectionId id = std::make_shared<const std::string>("moonlight-1");
    slots->bindBridge(1, id, dish_slots::SLOT_MOONLIGHT, 0);
    slots->applyUsbReport(1, buttons(0x0001));
    slots->bindBridge(1, id, dish_slots::SLOT_MOONLIGHT, 0);
    slots->applyUsbReport(1, buttons(0x0002));
    const std::vector<dish_slots::BridgeReport> reports = bridge.taken();
    ASSERT_EQ(reports.size(), 3u);
    EXPECT_NE(reports[2].connection, reports[0].connection);
    EXPECT_EQ(slots->bridgeConnectionId(reports[0].connection), nullptr);
    EXPECT_EQ(slots->bridgeConnectionId(reports[2].connection), id);
    EXPECT_EQ(slots->bridgeConnectionId(0), nullptr);
}

TEST_F(Slots, ABridgeSlotWithoutAConnectionPublishesNothing) {
    slots->bindBridge(1, nullptr, dish_slots::SLOT_MOONLIGHT, 0);
    slots->applyUsbReport(1, buttons(0x0040));
    EXPECT_TRUE(bridge.taken().empty());
    EXPECT_TRUE(sentBytes().empty());
}

TEST_F(Slots, ResettingADevicePublishesNeutral) {
    slots->bindSatellite(1, handle, 0);
    slots->applyUsbReport(1, buttons(0x0080));
    slots->resetAndPublish(1);
    EXPECT_EQ(sentButtons(), (std::vector<uint16_t>{0x0080, 0x0000}));
}

TEST_F(Slots, ReleasingEveryReportPublishesNeutralForEachBoundDevice) {
    slots->bindSatellite(1, handle, 0);
    slots->bindSatellite(2, handle, 1);
    slots->applyUsbReport(1, buttons(0x0100));
    slots->applyUsbReport(2, buttons(0x0200));
    EXPECT_EQ(sentBytes().size(), 2u);
    slots->releaseAll();
    EXPECT_EQ(sentButtons(), (std::vector<uint16_t>{0x0000, 0x0000}));
}

TEST_F(Slots, AForgottenDeviceHasNothingLeftToReset) {
    slots->bindSatellite(1, handle, 0);
    slots->applyUsbReport(1, buttons(0x0400));
    slots->forgetDevice(1);
    slots->resetAndPublish(1);
    EXPECT_EQ(sentButtons(), (std::vector<uint16_t>{0x0400}));
}

TEST_F(Slots, AGamepadKeyIsConsumedAndSentAndAnyOtherKeyPassesUntouched) {
    slots->bindSatellite(1, handle, 0);
    EXPECT_TRUE(slots->applyFrameworkKey(1, kAndroidKeycodeButtonA, gamepad::KEY_ACTION_DOWN));
    EXPECT_FALSE(slots->applyFrameworkKey(1, kAndroidKeycodeVolumeUp, gamepad::KEY_ACTION_DOWN));
    EXPECT_TRUE(slots->applyFrameworkKey(1, kAndroidKeycodeButtonA, gamepad::KEY_ACTION_UP));
    EXPECT_EQ(sentButtons(), (std::vector<uint16_t>{kXusbA, 0x0000}));
    EXPECT_EQ(slots->frameworkEventCount(1), 2u);
}

TEST_F(Slots, AFrameworkCancelPublishesNeutralAndOnlyAMoveIsCounted) {
    slots->bindSatellite(1, handle, 0);
    const gamepad::FrameworkAxes leftTrigger{0.f, 0.f, 0.f, 0.f, 0.f, 0.f,
                                             0.f, 0.f, 1.f, 0.f, 0.f, 0.f};
    slots->applyFrameworkMotion(1, gamepad::MOTION_ACTION_MOVE, leftTrigger);
    slots->applyFrameworkMotion(1, 0, gamepad::FrameworkAxes{});
    slots->applyFrameworkMotion(1, gamepad::MOTION_ACTION_CANCEL, leftTrigger);
    EXPECT_EQ(sentButtons(), (std::vector<uint16_t>{0x0000, 0x0000}));
    EXPECT_EQ(slots->frameworkEventCount(1), 1u);
}

TEST_F(Slots, TheInspectorSeesMotionAndTouchOnlyWhileItIsOpen) {
    gamepad::DeviceState nu = buttons(0x0001);
    nu.motionValid = true;
    nu.gyroX = 1234;
    nu.touchValid = true;
    nu.touch0Active = true;
    char closedView[512];
    char openView[512];
    slots->prewarmDevice(1);
    slots->applyUsbReport(1, nu);
    ASSERT_GT(slots->formatDeviceState(1, closedView, sizeof(closedView)), 0u);
    slots->setInspecting(true);
    slots->applyUsbReport(1, nu);
    ASSERT_GT(slots->formatDeviceState(1, openView, sizeof(openView)), 0u);
    EXPECT_NE(std::string(closedView).find("\"gx\":0,"), std::string::npos) << closedView;
    EXPECT_NE(std::string(openView).find("\"gx\":1234,"), std::string::npos) << openView;
    EXPECT_EQ(slots->formatDeviceState(99, openView, sizeof(openView)), 0u);
}

TEST_F(Slots, OnlyAGamepadReportClosesTheStage1BenchWindow) {
    slots->bindSatellite(1, handle, 0);
    hotpath::setEnabled(true);
    hotpath::statsJson(true);
    hotpath::markInputRead(1);
    slots->applyUsbMotion(1, dish_send::MotionSample{{1, 2, 3}, {4, 5, 6}, 7});
    const std::string afterMotion = hotpath::statsJson(false);
    slots->applyUsbReport(1, buttons(0x0001));
    const std::string afterGamepad = hotpath::statsJson(true);
    hotpath::setEnabled(false);
    EXPECT_NE(afterMotion.find("\"stage1_hotpath_us\":{\"n\":0}"), std::string::npos)
        << afterMotion;
    EXPECT_NE(afterGamepad.find("\"stage1_hotpath_us\":{\"n\":1,"), std::string::npos)
        << afterGamepad;
}

TEST_F(Slots, AReportForADeviceTheTableDoesNotKnowIsDropped) {
    slots->applyUsbReport(7, buttons(0x0001));
    slots->applyFrameworkMotion(7, gamepad::MOTION_ACTION_MOVE, gamepad::FrameworkAxes{});
    EXPECT_TRUE(slots->applyFrameworkKey(7, kAndroidKeycodeButtonA, gamepad::KEY_ACTION_DOWN));
    EXPECT_FALSE(slots->applyFrameworkKey(7, kAndroidKeycodeVolumeUp, gamepad::KEY_ACTION_DOWN));
    EXPECT_EQ(slots->frameworkEventCount(7), 0u);
    char view[512];
    EXPECT_EQ(slots->formatDeviceState(7, view, sizeof(view)), 0u);
    EXPECT_TRUE(sentBytes().empty());
}

TEST_F(Slots, AForgottenUnboundDeviceGivesItsSlotBack) {
    for (int32_t device = 0; device < (int32_t)dish_slots::MAX_SLOTS; device++) {
        slots->prewarmDevice(device);
    }
    slots->prewarmDevice(100);
    slots->bindSatellite(100, handle, 0);
    slots->applyUsbReport(100, buttons(0x0001));
    EXPECT_TRUE(sentBytes().empty());
    slots->forgetDevice(3);
    slots->bindSatellite(100, handle, 0);
    slots->applyUsbReport(100, buttons(0x0001));
    EXPECT_EQ(sentButtons(), (std::vector<uint16_t>{0x0001}));
}

// A rebind's neutral goes out inside its cold write, so a report that arrives meanwhile waits for
// the write to close and reaches the wire after the neutral, never before it.
TEST_F(Slots, AReportThatMeetsARebindGoesOutAfterItsNeutral) {
    slots->bindSatellite(1, handle, 0);
    slots->applyUsbReport(1, buttons(0x0001));
    ASSERT_EQ(sentBytes().size(), 1u);
    recording::HeldPort held;
    held.attachTo(*session);
    held.holdNext();
    std::thread binder([this] { slots->bindSatellite(1, handle, 0); });
    ASSERT_TRUE(held.waitUntilHolding());
    std::atomic<bool> reported{false};
    std::thread poller([this, &reported] {
        slots->applyUsbReport(1, buttons(0x0002));
        reported = true;
    });
    std::this_thread::sleep_for(kStillWaiting);
    EXPECT_FALSE(reported.load());
    held.letGo();
    binder.join();
    poller.join();
    const vector_session::Key key = vector_session::vectorKey();
    std::vector<uint16_t> order;
    for (const Bytes& sent : held.taken()) {
        const auto inner = fake_satellite::openFromClient(wire_vectors::kToken, key.bytes,
                                                          sent.data(), sent.size());
        ASSERT_TRUE(inner.has_value());
        order.push_back((uint16_t)(inner->payload[1] | inner->payload[2] << 8));
    }
    EXPECT_EQ(order, (std::vector<uint16_t>{0x0000, 0x0002}));
}

// An unbind waits out the report in flight, so once it returns nothing more of the slot's leaves.
TEST_F(Slots, AnUnbindReturnsOnlyOnceTheReportInFlightHasLeft) {
    slots->bindSatellite(1, handle, 0);
    recording::HeldPort held;
    held.attachTo(*session);
    held.holdNext();
    std::thread poller([this] { slots->applyUsbReport(1, buttons(0x0004)); });
    ASSERT_TRUE(held.waitUntilHolding());
    std::atomic<bool> unbound{false};
    std::thread binder([this, &unbound] {
        slots->unbind(1);
        unbound = true;
    });
    std::this_thread::sleep_for(kStillWaiting);
    EXPECT_FALSE(unbound.load());
    held.letGo();
    poller.join();
    binder.join();
    EXPECT_EQ(held.taken().size(), 1u);
    slots->applyUsbReport(1, buttons(0x0008));
    EXPECT_TRUE(held.taken().empty());
}

// A poller streams reports while the binding thread unbinds and rebinds its slot: every datagram
// opens, carries the slot's controller and either a neutral or a state the poller reported, and
// the counters rise.
TEST_F(Slots, BindsRacingAStreamOfReportsNeverSendGarbage) {
    slots->prewarmDevice(1);
    slots->bindSatellite(1, handle, 3);
    std::atomic<bool> stop{false};
    std::thread poller([this, &stop] {
        for (int k = 0; k < kStreamReports && !stop.load(); k++) {
            slots->applyUsbReport(1, buttons((uint16_t)(1 + k % 0x7FFF)));
        }
    });
    std::thread binder([this] {
        for (int i = 0; i < kRebinds; i++) {
            if (i % 2 == 0) {
                slots->unbind(1);
            } else {
                slots->bindSatellite(1, handle, 3);
            }
        }
        slots->bindSatellite(1, handle, 3);
    });
    binder.join();
    stop = true;
    poller.join();
    const vector_session::Key key = vector_session::vectorKey();
    uint32_t last = 0;
    for (const Bytes& sent : sentBytes()) {
        const auto inner = fake_satellite::openFromClient(wire_vectors::kToken, key.bytes,
                                                          sent.data(), sent.size());
        ASSERT_TRUE(inner.has_value());
        ASSERT_EQ(inner->msgType, dish_wire::MSG_GAMEPAD_DATA);
        ASSERT_EQ(inner->payload[0], 3);
        const uint16_t pressed = (uint16_t)(inner->payload[1] | inner->payload[2] << 8);
        ASSERT_LE(pressed, 0x7FFF);
        ASSERT_GT(vector_session::counterOf(sent), last);
        last = vector_session::counterOf(sent);
    }
}

// Two USB pollers, the UI thread, the heartbeat, the mic and the battery on one session: every
// datagram reaches the wire under the next counter, so the satellite drops none for order.
TEST_F(Slots, EveryRoleSendingOnOneSessionReachesTheWireInCounterOrder) {
    slots->bindSatellite(1, handle, 0);
    slots->bindSatellite(2, handle, 1);
    slots->setQuirk(3, 0);
    slots->bindSatellite(3, handle, 2);
    const std::atomic<int> heartbeatMs{kFastHeartbeatMs};
    const dish_session::SessionRef s = session;
    s->heartbeat.start([s, &heartbeatMs] { dish_send::runHeartbeat(s, heartbeatMs); });
    std::vector<std::thread> roles;
    for (const int32_t device : {1, 2}) {
        roles.emplace_back([this, device] {
            for (int k = 0; k < kRoleReports; k++) {
                slots->applyUsbReport(device, buttons((uint16_t)(1 + k % 2)));
                if (k % 8 == 0) {
                    slots->applyUsbMotion(device, dish_send::MotionSample{{1, 2, 3}, {4, 5, 6}, 8});
                }
            }
        });
    }
    roles.emplace_back([this] {
        for (int k = 0; k < kRoleReports; k++) {
            gamepad::FrameworkAxes axes{};
            axes.x = (k % 2 == 0) ? 0.5f : -0.5f;
            slots->applyFrameworkMotion(3, gamepad::MOTION_ACTION_MOVE, axes);
        }
    });
    roles.emplace_back([this] {
        const int16_t silence[dish_audio::AUDIO_FRAME_SAMPLES] = {};
        for (int k = 0; k < kMicFrames; k++) dish_send::sendMicFrame(session, 0, silence);
    });
    roles.emplace_back([this] {
        for (int k = 0; k < kBatteryReports; k++) dish_send::sendBattery(session, 1, 50, 1);
    });
    for (std::thread& role : roles) role.join();
    s->heartbeat.stop();
    const std::vector<recording::Sent> sent = port.taken();
    std::set<std::thread::id> threads;
    for (size_t i = 0; i < sent.size(); i++) {
        threads.insert(sent[i].thread);
        ASSERT_EQ(vector_session::counterOf(sent[i].bytes), wire_vectors::kFirstCounter + i)
            << "at " << i;
    }
    EXPECT_GE(threads.size(), 5u);
}
