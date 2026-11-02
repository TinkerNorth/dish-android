// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>

#include "gamepad_input.h"
#include "session_table.h"
#include "wire_encoders.h"

// Every datagram a session sends: framed, given the session's next counter under its send turn,
// sealed and put on the wire through the session's DatagramPort.
namespace dish_send {

#pragma pack(push, 1)
struct XUSB_REPORT {
    uint16_t wButtons;
    uint8_t bLeftTrigger;
    uint8_t bRightTrigger;
    int16_t sThumbLX;
    int16_t sThumbLY;
    int16_t sThumbRX;
    int16_t sThumbRY;
};
#pragma pack(pop)
static_assert(sizeof(XUSB_REPORT) == 12, "XUSB_REPORT must be 12 bytes");

// MSG_GAMEPAD_DATA: one slot byte then the wire XUSB report.
inline constexpr size_t GAMEPAD_PAYLOAD_BYTES = 1 + sizeof(XUSB_REPORT);

// Steady-state cadence is contractual (satellite docs/contract.md: 2000 ms). Probe
// mode densifies RTT sampling only while the diagnostics latency panel is open; the
// death window stays ~10 s wall clock because the miss threshold scales with it.
inline constexpr int HEARTBEAT_INTERVAL_DEFAULT_MS = 2000;
inline constexpr int HEARTBEAT_INTERVAL_PROBE_MS = 250;
inline constexpr int HEARTBEAT_DEATH_TIMEOUT_MS = 10000;

// One datagram's plaintext and counter, waiting to be sealed and sent, holding its session's turn
// and a reference that keeps the session, and so the turn's mutex, alive until then.
struct PendingDatagram {
    std::shared_ptr<dish_session::Session> session;
    std::unique_lock<std::mutex> turn;
    uint32_t counter = 0;
    uint16_t msgType = 0;
    uint16_t innerLen = 0;
    uint8_t inner[dish_wire::INNER_HEADER_BYTES + dish_wire::MAX_INNER_PAYLOAD_BYTES];
};

// Frames one datagram's plaintext into out and takes the session's turn and its next counter, held
// until transmitDatagram has sent it; false, with nothing held, for a payload past the contract's
// ceiling, an exhausted counter or a closed socket. A thread stages at most one datagram of a
// session before sending it: a second would wait on the turn the thread already holds.
bool stageDatagram(const std::shared_ptr<dish_session::Session>& session, uint16_t msgType,
                   const uint8_t* payload, uint16_t payloadLen, PendingDatagram& out);

// Seals a staged datagram under its counter, sends it and gives the session's turn back; one never
// staged sends nothing. A send the socket refuses for lack of buffer space is a soft drop: true,
// with its counter spent.
bool transmitDatagram(PendingDatagram& datagram);

bool sendEncrypted(const std::shared_ptr<dish_session::Session>& session, uint16_t msgType,
                   const uint8_t* payload, uint16_t payloadLen);

// A physical pad's datagram, staged under the device and slot locks, is sealed and sent once the
// caller has let them go; a gamepad report also closes the stage-1 bench window.
void transmitPublished(PendingDatagram& datagram);

XUSB_REPORT xusbReportOf(const gamepad::DeviceState& s);

void stageGamepadReport(const std::shared_ptr<dish_session::Session>& session, int controllerIndex,
                        const XUSB_REPORT& report, PendingDatagram& out);

void sendGamepadReport(const std::shared_ptr<dish_session::Session>& session, int controllerIndex,
                       const XUSB_REPORT& report);

struct MotionSample {
    int16_t gyro[3];
    int16_t accel[3];
    uint32_t timestampDeltaUs;
};

// A USB-direct pad's motion: counted, then staged.
void stageMotion(const std::shared_ptr<dish_session::Session>& session, int controllerIndex,
                 const MotionSample& m, PendingDatagram& out);

// A framework pad's motion from the sensor thread: sent, then counted.
void sendMotion(const std::shared_ptr<dish_session::Session>& session, int controllerIndex,
                const MotionSample& m);

void sendBattery(const std::shared_ptr<dish_session::Session>& session, int controllerIndex,
                 int level, int status);

void stageTouchpadFrame(const std::shared_ptr<dish_session::Session>& session, uint8_t idx,
                        const gamepad::TouchpadState& t, bool rightPressed, bool middlePressed,
                        uint32_t eventTimeMs, int16_t scrollDelta, PendingDatagram& out);

void sendTouchpadFrame(const std::shared_ptr<dish_session::Session>& session, uint8_t idx,
                       const gamepad::TouchpadState& t, bool rightPressed, bool middlePressed,
                       uint32_t eventTimeMs, int16_t scrollDelta);

// One 20 ms mono window (dish_audio::AUDIO_FRAME_SAMPLES samples) encoded and sent as
// MSG_MIC_AUDIO; false when nothing left the device.
bool sendMicFrame(const std::shared_ptr<dish_session::Session>& session, uint8_t idx,
                  const int16_t* pcm);

// The heartbeat thread's body: a ping per interval until the session's heartbeat stops.
void runHeartbeat(const std::shared_ptr<dish_session::Session>& session,
                  const std::atomic<int>& intervalMs);

} // namespace dish_send
