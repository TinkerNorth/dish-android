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

namespace dish_send {

using dish_session::CTRL_INDEX_MASK;
using dish_session::Session;

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

} // namespace

bool stageDatagram(const std::shared_ptr<Session>& session, const uint16_t msgType,
                   const uint8_t* payload, const uint16_t payloadLen, PendingDatagram& out) {
    if (!session) return false;
    // Sized from the contract's datagram ceiling, not from the message set:
    // MSG_MIC_AUDIO carries a whole Opus packet, and the bound has to hold
    // structurally rather than by what today's senders happen to emit.
    if (!dish_wire::innerPayloadFits(payloadLen)) return false;
    putBE16(out.inner, msgType);
    putBE16(out.inner + 2, payloadLen);
    if (payloadLen > 0) memcpy(out.inner + 4, payload, payloadLen);

    Session& s = *session;
    if (!dish_counter::takeTurnAndCounter(s.sendTurnMtx, s.counter, out.turn, &out.counter)) {
        return false;
    }
    if (s.udpSock < 0) {
        out.turn.unlock();
        return false;
    }
    out.session = session;
    out.msgType = msgType;
    out.innerLen = static_cast<uint16_t>(dish_wire::INNER_HEADER_BYTES + payloadLen);
    return true;
}

// The turn has been held since the counter was drawn, so the counter's place on the wire is kept
// while the encryption runs outside the device and slot locks.
bool transmitDatagram(PendingDatagram& datagram) {
    if (!datagram.turn.owns_lock()) return false;
    const Session& s = *datagram.session;

    // Nonce: dir(1) | 0×7 | counter(4 BE). The direction byte keeps this
    // direction's nonces disjoint from the server's under the shared key.
    uint8_t nonce[12] = {};
    nonce[0] = dish_wire::CRYPTO_DIR_CLIENT_TO_SERVER;
    putBE32(nonce + 8, datagram.counter);

    uint8_t packet[dish_wire::MAX_DATAGRAM_BYTES];
    // The ceiling made structural: a full-size inner payload plus header and
    // tag is exactly one Ethernet MTU, so nothing this path emits can fragment.
    static_assert(8 + sizeof(datagram.inner) + crypto_aead_chacha20poly1305_ietf_ABYTES ==
                      sizeof(packet),
                  "send buffer must be exactly the contract's datagram ceiling");
    memcpy(packet, s.token, 4);
    putBE32(packet + 4, datagram.counter);
    unsigned long long cipherLen = 0;
    crypto_aead_chacha20poly1305_ietf_encrypt(packet + 8, &cipherLen, datagram.inner,
                                              datagram.innerLen, s.token, 4, nullptr, nonce, s.key);

    const size_t totalLen = 8 + (size_t)cipherLen;
    const ssize_t sent = s.port.send(s.port.context, s.udpSock, packet, totalLen, s.dest);
    const int sendErrno = errno;
    datagram.turn.unlock();
    // Soft-drop on buffer-full: UDP semantics absorb it and the next tick refreshes state.
    if (sent < 0 && (sendErrno == EAGAIN || sendErrno == EWOULDBLOCK)) { return true; }
    return sent == (ssize_t)totalLen;
}

bool sendEncrypted(const std::shared_ptr<Session>& session, const uint16_t msgType,
                   const uint8_t* payload, const uint16_t payloadLen) {
    PendingDatagram datagram;
    if (!stageDatagram(session, msgType, payload, payloadLen, datagram)) return false;
    return transmitDatagram(datagram);
}

void transmitPublished(PendingDatagram& datagram) {
    const bool isAGamepadReport = datagram.msgType == dish_wire::MSG_GAMEPAD_DATA;
    transmitDatagram(datagram);
    if (isAGamepadReport) hotpath::markGamepadSent(); // stage-1 end: the packet has left sendto()
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
void stageGamepadReport(const std::shared_ptr<Session>& session, const int controllerIndex,
                        const XUSB_REPORT& report, PendingDatagram& out) {
    uint8_t payload[GAMEPAD_PAYLOAD_BYTES];
    payload[0] = (uint8_t)(controllerIndex & 0xFF);
    memcpy(payload + 1, &report, sizeof(report));
    stageDatagram(session, dish_wire::MSG_GAMEPAD_DATA, payload, sizeof(payload), out);
    session->sentByCtrl[controllerIndex & CTRL_INDEX_MASK].fetch_add(1, std::memory_order_relaxed);
}

void sendGamepadReport(const std::shared_ptr<Session>& session, const int controllerIndex,
                       const XUSB_REPORT& report) {
    PendingDatagram datagram;
    stageGamepadReport(session, controllerIndex, report, datagram);
    transmitDatagram(datagram);
}

void stageMotion(const std::shared_ptr<Session>& session, const int controllerIndex,
                 const MotionSample& m, PendingDatagram& out) {
    uint8_t payload[dish_wire::MOTION_PAYLOAD_BYTES];
    session->motionByCtrl[controllerIndex & CTRL_INDEX_MASK].fetch_add(1,
                                                                       std::memory_order_relaxed);
    dish_wire::encodeMotionPayload(payload, (uint8_t)(controllerIndex & 0xFF), m.gyro[0], m.gyro[1],
                                   m.gyro[2], m.accel[0], m.accel[1], m.accel[2],
                                   m.timestampDeltaUs);
    stageDatagram(session, dish_wire::MSG_MOTION, payload, sizeof(payload), out);
}

void sendMotion(const std::shared_ptr<Session>& session, const int controllerIndex,
                const MotionSample& m) {
    uint8_t payload[dish_wire::MOTION_PAYLOAD_BYTES];
    dish_wire::encodeMotionPayload(payload, (uint8_t)(controllerIndex & 0xFF), m.gyro[0], m.gyro[1],
                                   m.gyro[2], m.accel[0], m.accel[1], m.accel[2],
                                   m.timestampDeltaUs);
    sendEncrypted(session, dish_wire::MSG_MOTION, payload, sizeof(payload));
    session->motionByCtrl[controllerIndex & CTRL_INDEX_MASK].fetch_add(1,
                                                                       std::memory_order_relaxed);
}

void sendBattery(const std::shared_ptr<Session>& session, const int controllerIndex,
                 const int level, const int status) {
    uint8_t payload[dish_wire::BATTERY_PAYLOAD_BYTES];
    dish_wire::encodeBatteryPayload(payload, (uint8_t)(controllerIndex & 0xFF),
                                    (uint8_t)(level & 0xFF), (uint8_t)(status & 0xFF));
    sendEncrypted(session, dish_wire::MSG_BATTERY, payload, sizeof(payload));
}

// Protocol 2 widened the payload to 19 bytes with the mouse buttons and the wheel; a protocol 1
// satellite still gets the 16-byte form, which has neither, so those fields are dropped for it.
void stageTouchpadFrame(const std::shared_ptr<Session>& session, const uint8_t idx,
                        const gamepad::TouchpadState& t, const bool rightPressed,
                        const bool middlePressed, const uint32_t eventTimeMs,
                        const int16_t scrollDelta, PendingDatagram& out) {
    uint8_t payload[dish_wire::TOUCHPAD_V2_PAYLOAD_BYTES];
    const bool isProtocol2 =
        session->protocolVersion.load() >= dish_wire::PROTOCOL_VERSION_TOUCHPAD_V2;
    if (isProtocol2) {
        dish_wire::encodeTouchpadPayloadV2(payload, idx, t.f0Active, t.f1Active, t.clickDown,
                                           rightPressed, middlePressed, t.f0Id, t.f0X, t.f0Y,
                                           t.f1Id, t.f1X, t.f1Y, eventTimeMs, scrollDelta);
        stageDatagram(session, dish_wire::MSG_TOUCHPAD, payload,
                      dish_wire::TOUCHPAD_V2_PAYLOAD_BYTES, out);
        return;
    }
    dish_wire::encodeTouchpadPayloadV1(payload, idx, t.f0Active, t.f1Active, t.clickDown, t.f0Id,
                                       t.f0X, t.f0Y, t.f1Id, t.f1X, t.f1Y, eventTimeMs);
    stageDatagram(session, dish_wire::MSG_TOUCHPAD, payload, dish_wire::TOUCHPAD_V1_PAYLOAD_BYTES,
                  out);
}

void sendTouchpadFrame(const std::shared_ptr<Session>& session, const uint8_t idx,
                       const gamepad::TouchpadState& t, const bool rightPressed,
                       const bool middlePressed, const uint32_t eventTimeMs,
                       const int16_t scrollDelta) {
    PendingDatagram datagram;
    stageTouchpadFrame(session, idx, t, rightPressed, middlePressed, eventTimeMs, scrollDelta,
                       datagram);
    transmitDatagram(datagram);
}

// Refusing a window that is not exactly AUDIO_FRAME_SAMPLES is the encoder's
// job, not a check duplicated here: a mis-framed buffer must not become a
// packet the satellite cannot place in its timeline.
bool sendMicFrame(const std::shared_ptr<Session>& session, const uint8_t idx, const int16_t* pcm) {
    Session& s = *session;
    // The wire ceiling, not a guess at the bitrate: libopus treats the output
    // size as a hard limit it encodes down to, so the only refusal on this path
    // should be one the contract actually imposes.
    uint8_t payload[dish_wire::AUDIO_WIRE_HEADER_BYTES + dish_wire::AUDIO_WIRE_MAX_OPUS_BYTES];
    size_t opusBytes = 0;
    uint16_t seq = 0;
    {
        std::lock_guard<std::mutex> lock(s.audioMtx);
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

void runHeartbeat(const std::shared_ptr<Session>& session, const std::atomic<int>& intervalMs) {
    Session& s = *session;
    while (s.heartbeat.running()) {
        sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0);
        s.rtt.pings.fetch_add(1, std::memory_order_relaxed);
        armLatencyPing(s);

        const int interval = intervalMs.load(std::memory_order_relaxed);
        countMissedAck(s, interval);
        sleepUntilNextHeartbeat(s, interval);
    }
}

} // namespace dish_send
