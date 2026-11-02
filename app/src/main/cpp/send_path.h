// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

#include "gamepad_input.h"
#include "session_table.h"
#include "wire_encoders.h"

// Every datagram a session sends: framed on the sender's stack, then, holding the session's send
// gate, given the session's next counter, sealed and put on the wire through its DatagramPort.
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

// Which per-controller tally a datagram counts toward once it reaches its session.
enum class Tally : uint8_t { None, Gamepad, Motion };

// One datagram's plaintext, framed and waiting for its counter.
struct Framed {
    uint16_t msgType = 0;
    uint16_t innerLen = 0;
    Tally tally = Tally::None;
    uint8_t controller = 0;
    // A pad's published report: the stage-1 bench window closes when it leaves sendto.
    bool closesStage1 = false;
    uint8_t inner[dish_wire::INNER_HEADER_BYTES + dish_wire::MAX_INNER_PAYLOAD_BYTES];
};

// Frames one datagram's plaintext into out; false for a payload past the contract's ceiling.
bool frame(Framed& out, uint16_t msgType, const uint8_t* payload, uint16_t payloadLen);

// Seals the framed datagrams under consecutive counters and sends them back to back, holding the
// session's gate once for all of them. Answers how many the socket took: a send refused for lack
// of buffer space is a soft drop that counts as taken, with its counter spent. Nothing leaves a
// session that is closed or whose counter is exhausted.
size_t sendFramed(const dish_session::SessionRef& session, const Framed* datagrams, size_t count);

bool sendEncrypted(const dish_session::SessionRef& session, uint16_t msgType,
                   const uint8_t* payload, uint16_t payloadLen);

XUSB_REPORT xusbReportOf(const gamepad::DeviceState& s);

// Frames a MSG_GAMEPAD_DATA for one controller: its slot byte, then the wire XUSB report.
void frameGamepadReport(Framed& out, int controllerIndex, const XUSB_REPORT& report);

// True when the report left for the session.
bool sendGamepadReport(const dish_session::SessionRef& session, int controllerIndex,
                       const XUSB_REPORT& report);

struct MotionSample {
    int16_t gyro[3];
    int16_t accel[3];
    uint32_t timestampDeltaUs;
};

void frameMotion(Framed& out, int controllerIndex, const MotionSample& m);

void sendMotion(const dish_session::SessionRef& session, int controllerIndex,
                const MotionSample& m);

void sendBattery(const dish_session::SessionRef& session, int controllerIndex, int level,
                 int status);

// Protocol 2 widened the payload with the mouse buttons and the wheel; a protocol 1 satellite
// still gets the 16-byte form, which has neither.
void frameTouchpad(Framed& out, int32_t protocolVersion, uint8_t idx,
                   const gamepad::TouchpadState& t, bool rightPressed, bool middlePressed,
                   uint32_t eventTimeMs, int16_t scrollDelta);

void sendTouchpadFrame(const dish_session::SessionRef& session, uint8_t idx,
                       const gamepad::TouchpadState& t, bool rightPressed, bool middlePressed,
                       uint32_t eventTimeMs, int16_t scrollDelta);

// One 20 ms mono window (dish_audio::AUDIO_FRAME_SAMPLES samples) encoded and sent as
// MSG_MIC_AUDIO; false when nothing left the device.
bool sendMicFrame(const dish_session::SessionRef& session, uint8_t idx, const int16_t* pcm);

// The heartbeat thread's body: a ping per interval until the session's heartbeat stops.
void runHeartbeat(const dish_session::SessionRef& session, const std::atomic<int>& intervalMs);

} // namespace dish_send
