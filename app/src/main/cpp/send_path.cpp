// SPDX-License-Identifier: LGPL-3.0-or-later

#include "send_path.h"

#include <errno.h>
#include <string.h>
#include <unistd.h>

#include <sodium.h>

#include "audio_codec.h"
#include "hotpath_latency.h"
#include "log_port.h"
#include "send_counter.h"
#include "tracked_mutex.h"

namespace dish_send {

using dish_session::CTRL_INDEX_MASK;
using dish_session::Material;
using dish_session::Session;
using dish_session::SessionRef;

namespace {

void putBE16(uint8_t* dst, uint16_t v) {
    dst[0] = (uint8_t)(v >> 8);
    dst[1] = (uint8_t)(v);
}

void putBE32(uint8_t* dst, uint32_t v) {
    dst[0] = (uint8_t)(v >> 24);
    dst[1] = (uint8_t)(v >> 16);
    dst[2] = (uint8_t)(v >> 8);
    dst[3] = (uint8_t)(v);
}

// stage-2: the round-trip clock starts here, on this session's own clock.
void armLatencyPing(Session& s) {
    if (!hotpath::enabled()) return;
    const int64_t now = hotpath::nowMonotonicNs();
    if (!hotpath::shouldArmPing(s.lastPingNs.load(std::memory_order_relaxed), now)) return;
    s.lastPingNs.store(now, std::memory_order_relaxed);
}

// Every ping counts as missed until its ACK clears the counter; enough in a row is a dead link.
void countMissedAck(Session& s, const int intervalMs) {
    const int missMax = HEARTBEAT_DEATH_TIMEOUT_MS / intervalMs;
    const bool isDead = s.missedAcks.fetch_add(1, std::memory_order_relaxed) + 1 >= missMax;
    if (!isDead) return;
    dish_log::error("Missed %d heartbeat ACKs, connection dead", missMax);
    s.connectionAlive.store(false, std::memory_order_relaxed);
}

// Slept in slices so a stop is observed well before the next interval is up.
constexpr int kHeartbeatSliceMs = 50;

void sleepUntilNextHeartbeat(Session& s, const int intervalMs) {
    for (int i = 0; i < intervalMs / kHeartbeatSliceMs; i++) {
        if (!s.heartbeat.running()) break;
        usleep(kHeartbeatSliceMs * 1000);
    }
}

// The gate's holder is the tallies' only writer, so a load and a store count without a
// read-modify-write.
void count(std::atomic<uint32_t>& tally) {
    tally.store(tally.load(std::memory_order_relaxed) + 1, std::memory_order_relaxed);
}

void countFor(Session& s, const Framed& d) {
    const size_t controller = d.controller & CTRL_INDEX_MASK;
    if (d.tally == Tally::Gamepad) count(s.sentByCtrl[controller]);
    if (d.tally == Tally::Motion) count(s.motionByCtrl[controller]);
}

// Under the gate: the counter's place on the wire is kept from its draw to the return of sendto.
// A send refused for lack of buffer space is a soft drop: UDP semantics absorb it and the next
// tick refreshes the state.
bool sealAndSend(Session& s, const Material& m, const uint32_t counter, const Framed& d) {
    // Nonce: dir(1) | 0×7 | counter(4 BE). The direction byte keeps this
    // direction's nonces disjoint from the server's under the shared key.
    uint8_t nonce[12] = {};
    nonce[0] = dish_wire::CRYPTO_DIR_CLIENT_TO_SERVER;
    putBE32(nonce + 8, counter);

    uint8_t packet[dish_wire::MAX_DATAGRAM_BYTES];
    // The ceiling made structural: a full-size inner payload plus header and
    // tag is exactly one Ethernet MTU, so nothing this path emits can fragment.
    static_assert(8 + sizeof(d.inner) + crypto_aead_chacha20poly1305_ietf_ABYTES == sizeof(packet),
                  "send buffer must be exactly the contract's datagram ceiling");
    memcpy(packet, m.token, 4);
    putBE32(packet + 4, counter);
    unsigned long long cipherLen = 0;
    crypto_aead_chacha20poly1305_ietf_encrypt(packet + 8, &cipherLen, d.inner, d.innerLen, m.token,
                                              4, nullptr, nonce, m.key);

    const size_t totalLen = 8 + (size_t)cipherLen;
    blockingRegion("net.sendto");
    const ssize_t sent = s.port.send(s.port.context, m.sock, packet, totalLen, m.dest);
    const int sendErrno = errno;
    if (sent < 0 && (sendErrno == EAGAIN || sendErrno == EWOULDBLOCK)) return true;
    return sent == (ssize_t)totalLen;
}

} // namespace

bool frame(Framed& out, const uint16_t msgType, const uint8_t* payload, const uint16_t payloadLen) {
    // Sized from the contract's datagram ceiling, not from the message set:
    // MSG_MIC_AUDIO carries a whole Opus packet, and the bound has to hold
    // structurally rather than by what today's senders happen to emit.
    if (!dish_wire::innerPayloadFits(payloadLen)) return false;
    putBE16(out.inner, msgType);
    putBE16(out.inner + 2, payloadLen);
    if (payloadLen > 0) memcpy(out.inner + 4, payload, payloadLen);
    out.msgType = msgType;
    out.innerLen = static_cast<uint16_t>(dish_wire::INNER_HEADER_BYTES + payloadLen);
    return true;
}

size_t sendFramed(const SessionRef& ref, const Framed* datagrams, const size_t count) {
    if (!ref || count == 0) return 0;
    Session& s = *ref.entry;
    size_t taken = 0;
    s.gate.hold();
    const Material m = dish_session::materialUnderGate(s);
    const bool open = m.handle == ref.handle && m.sock >= 0;
    for (size_t i = 0; open && i < count; i++) {
        uint32_t counter = 0;
        if (!dish_counter::acquireSendCounter(s.counter, &counter)) break;
        countFor(s, datagrams[i]);
        if (sealAndSend(s, m, counter, datagrams[i])) taken++;
    }
    s.gate.release();
    return taken;
}

bool sendEncrypted(const SessionRef& session, const uint16_t msgType, const uint8_t* payload,
                   const uint16_t payloadLen) {
    Framed d;
    if (!frame(d, msgType, payload, payloadLen)) return false;
    return sendFramed(session, &d, 1) == 1;
}

XUSB_REPORT xusbReportOf(const gamepad::DeviceState& s) {
    XUSB_REPORT r{};
    r.wButtons = s.wButtons;
    r.bLeftTrigger = s.bLT;
    r.bRightTrigger = s.bRT;
    r.sThumbLX = s.sLX;
    r.sThumbLY = s.sLY;
    r.sThumbRX = s.sRX;
    r.sThumbRY = s.sRY;
    return r;
}

// The satellite takes the pad state as a wire XUSB report, straight out of the stack: one slot
// byte then the report, no allocation on the per-packet path.
void frameGamepadReport(Framed& out, const int controllerIndex, const XUSB_REPORT& report) {
    uint8_t payload[GAMEPAD_PAYLOAD_BYTES];
    payload[0] = (uint8_t)(controllerIndex & 0xFF);
    memcpy(payload + 1, &report, sizeof(report));
    frame(out, dish_wire::MSG_GAMEPAD_DATA, payload, sizeof(payload));
    out.tally = Tally::Gamepad;
    out.controller = (uint8_t)(controllerIndex & CTRL_INDEX_MASK);
}

bool sendGamepadReport(const SessionRef& session, const int controllerIndex,
                       const XUSB_REPORT& report) {
    Framed d;
    frameGamepadReport(d, controllerIndex, report);
    return sendFramed(session, &d, 1) == 1;
}

void frameMotion(Framed& out, const int controllerIndex, const MotionSample& m) {
    uint8_t payload[dish_wire::MOTION_PAYLOAD_BYTES];
    dish_wire::encodeMotionPayload(payload, (uint8_t)(controllerIndex & 0xFF), m.gyro[0], m.gyro[1],
                                   m.gyro[2], m.accel[0], m.accel[1], m.accel[2],
                                   m.timestampDeltaUs);
    frame(out, dish_wire::MSG_MOTION, payload, sizeof(payload));
    out.tally = Tally::Motion;
    out.controller = (uint8_t)(controllerIndex & CTRL_INDEX_MASK);
}

void sendMotion(const SessionRef& session, const int controllerIndex, const MotionSample& m) {
    Framed d;
    frameMotion(d, controllerIndex, m);
    sendFramed(session, &d, 1);
}

void sendBattery(const SessionRef& session, const int controllerIndex, const int level,
                 const int status) {
    uint8_t payload[dish_wire::BATTERY_PAYLOAD_BYTES];
    dish_wire::encodeBatteryPayload(payload, (uint8_t)(controllerIndex & 0xFF),
                                    (uint8_t)(level & 0xFF), (uint8_t)(status & 0xFF));
    sendEncrypted(session, dish_wire::MSG_BATTERY, payload, sizeof(payload));
}

void frameTouchpad(Framed& out, const int32_t protocolVersion, const uint8_t idx,
                   const gamepad::TouchpadState& t, const bool rightPressed,
                   const bool middlePressed, const uint32_t eventTimeMs,
                   const int16_t scrollDelta) {
    uint8_t payload[dish_wire::TOUCHPAD_V2_PAYLOAD_BYTES];
    if (protocolVersion >= dish_wire::PROTOCOL_VERSION_TOUCHPAD_V2) {
        dish_wire::encodeTouchpadPayloadV2(payload, idx, t.f0Active, t.f1Active, t.clickDown,
                                           rightPressed, middlePressed, t.f0Id, t.f0X, t.f0Y,
                                           t.f1Id, t.f1X, t.f1Y, eventTimeMs, scrollDelta);
        frame(out, dish_wire::MSG_TOUCHPAD, payload, dish_wire::TOUCHPAD_V2_PAYLOAD_BYTES);
        return;
    }
    dish_wire::encodeTouchpadPayloadV1(payload, idx, t.f0Active, t.f1Active, t.clickDown, t.f0Id,
                                       t.f0X, t.f0Y, t.f1Id, t.f1X, t.f1Y, eventTimeMs);
    frame(out, dish_wire::MSG_TOUCHPAD, payload, dish_wire::TOUCHPAD_V1_PAYLOAD_BYTES);
}

void sendTouchpadFrame(const SessionRef& session, const uint8_t idx,
                       const gamepad::TouchpadState& t, const bool rightPressed,
                       const bool middlePressed, const uint32_t eventTimeMs,
                       const int16_t scrollDelta) {
    if (!session) return;
    Framed d;
    frameTouchpad(d, session->protocolVersion.load(std::memory_order_relaxed), idx, t, rightPressed,
                  middlePressed, eventTimeMs, scrollDelta);
    sendFramed(session, &d, 1);
}

// Refusing a window that is not exactly AUDIO_FRAME_SAMPLES is the encoder's
// job, not a check duplicated here: a mis-framed buffer must not become a
// packet the satellite cannot place in its timeline.
bool sendMicFrame(const SessionRef& session, const uint8_t idx, const int16_t* pcm) {
    if (!session) return false;
    Session& s = *session;
    // The wire ceiling, not a guess at the bitrate: libopus treats the output
    // size as a hard limit it encodes down to, so the only refusal on this path
    // should be one the contract actually imposes.
    uint8_t payload[dish_wire::AUDIO_WIRE_HEADER_BYTES + dish_wire::AUDIO_WIRE_MAX_OPUS_BYTES];
    size_t opusBytes = 0;
    uint16_t seq = 0;
    {
        std::lock_guard<TrackedMutex> lock(s.audioMtx);
        if (s.audioHandle != session.handle) return false;
        dish_session::ControllerAudio& ca = s.audio[idx];
        if (!ca.micEncoder) {
            ca.micEncoder = dish_audio::OpusStreamEncoder::create(dish_audio::Stream::Mic);
            if (!ca.micEncoder) {
                dish_log::error("mic audio: no Opus encoder for ctrl %u", (unsigned)idx);
                return false;
            }
        }
        opusBytes = ca.micEncoder->encode(pcm, dish_audio::AUDIO_FRAME_SAMPLES,
                                          payload + dish_wire::AUDIO_WIRE_HEADER_BYTES,
                                          dish_wire::AUDIO_WIRE_MAX_OPUS_BYTES);
        if (opusBytes == 0) return false;
        seq = ca.micSeq++; // u16, wraps by design (contract §Controller audio)
    }
    dish_wire::encodeAudioFrameHeader(payload, idx, seq);
    const uint16_t payloadLen =
        static_cast<uint16_t>(dish_wire::AUDIO_WIRE_HEADER_BYTES + opusBytes);
    return sendEncrypted(session, dish_wire::MSG_MIC_AUDIO, payload, payloadLen);
}

// A heartbeat started for a session that closed before it began ends at its next ping instead of
// pinging for whichever session takes the entry next.
void runHeartbeat(const SessionRef& session, const std::atomic<int>& intervalMs) {
    Session& s = *session;
    while (s.heartbeat.running() && dish_session::readMaterial(s).handle == session.handle) {
        sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0);
        s.rtt.pings.fetch_add(1, std::memory_order_relaxed);
        armLatencyPing(s);

        const int interval = intervalMs.load(std::memory_order_relaxed);
        countMissedAck(s, interval);
        sleepUntilNextHeartbeat(s, interval);
    }
}

} // namespace dish_send
