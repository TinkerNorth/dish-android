// SPDX-License-Identifier: LGPL-3.0-or-later

#include "receive_path.h"

#include <errno.h>
#include <string.h>

#include <sodium.h>

#include "hotpath_latency.h"
#include "log_port.h"
#include "udp_socket.h"

namespace dish_receive {

using dish_session::Session;

namespace {

void putBE32(uint8_t* dst, uint32_t v) {
    dst[0] = (uint8_t)(v >> 24);
    dst[1] = (uint8_t)(v >> 16);
    dst[2] = (uint8_t)(v >> 8);
    dst[3] = (uint8_t)(v);
}

void handleHeartbeatAck(Session& s, const uint8_t* decrypted, const uint16_t msgLen,
                        const unsigned long long decLen) {
    s.rtt.acks.fetch_add(1, std::memory_order_relaxed);
    if (hotpath::enabled()) {
        const int64_t sent = s.lastPingNs.exchange(0, std::memory_order_relaxed);
        if (sent != 0) hotpath::addRttSample(&s.rtt, sent, hotpath::nowMonotonicNs());
    }
    s.missedAcks.store(0);
    s.connectionAlive.store(true);
    // Enriched ack: backendAvailable(1) + totalActive(1) + epoch(u16 BE) +
    // active-controller bitmap(u16 BE). The epoch/bitmap pair drives the
    // Kotlin-side reconcile against involuntary server-side topology loss.
    if (msgLen >= dish_wire::HEARTBEAT_ACK_ENRICHED_BYTES &&
        decLen >= 4 + dish_wire::HEARTBEAT_ACK_ENRICHED_BYTES) {
        uint8_t backend = decrypted[4];
        uint8_t count = decrypted[5];
        int32_t epoch = ((int32_t)decrypted[6] << 8) | (int32_t)decrypted[7];
        int32_t bitmap = ((int32_t)decrypted[8] << 8) | (int32_t)decrypted[9];
        s.vigemAvailable.store((int8_t)(backend ? 1 : 0), std::memory_order_release);
        s.activeControllerCount.store((int8_t)count, std::memory_order_release);
        s.serverEpoch.store(epoch, std::memory_order_release);
        s.activeBitmap.store(bitmap, std::memory_order_release);
    }
}

} // namespace

int receiveDatagram(dish_session::SessionTable& sessions, const int handle, FeedbackSink& sink) {
    auto s = sessions.get(handle);
    if (!s || s->udpSock < 0) return -1;
    // A whole datagram: MSG_SPEAKER_AUDIO carries an Opus packet, and a short
    // read would truncate the ciphertext into an AEAD failure rather than an
    // honest error (satellite docs/contract.md §Packet format).
    uint8_t buf[dish_wire::MAX_DATAGRAM_BYTES];
    static_assert(sizeof(buf) >= dish_wire::OUTER_HEADER_BYTES + dish_wire::INNER_HEADER_BYTES +
                                     dish_wire::MAX_INNER_PAYLOAD_BYTES + dish_wire::AEAD_TAG_BYTES,
                  "recv buffer must hold a maximal datagram, framing and tag included");
    const ssize_t n = dish_net::receiveDatagram(s->udpSock, buf, sizeof(buf));
    if (n < 0) {
        // The SO_RCVTIMEO tick (or a signal) is routine pacing. Any other
        // recv error returns instantly and would do so forever, so the Kotlin
        // drain loop must treat it as terminal or it busy-spins a core.
        return (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) ? 0 : -1;
    }
    return handleDatagram(s, handle, buf, (size_t)n, sink);
}

int handleDatagram(const std::shared_ptr<Session>& session, const int handle, const uint8_t* buf,
                   const size_t n, FeedbackSink& sink) {
    Session& s = *session;
    if (n < 8 || n > dish_wire::MAX_DATAGRAM_BYTES) return 0;

    if (memcmp(buf, s.token, 4) != 0) return 0;

    uint32_t ctr = ((uint32_t)buf[4] << 24) | ((uint32_t)buf[5] << 16) | ((uint32_t)buf[6] << 8) |
                   (uint32_t)buf[7];

    // Downstream replay guard, mirroring the server's (first packet exempt
    // while lastRxCounter == 0).
    const uint32_t lastRx = s.lastRxCounter.load(std::memory_order_relaxed);
    if (ctr <= lastRx && lastRx != 0) return 0;

    uint8_t nonce[12] = {};
    nonce[0] = dish_wire::CRYPTO_DIR_SERVER_TO_CLIENT;
    putBE32(nonce + 8, ctr);

    // Must size with the datagram ceiling not the message-set max: bound holds structurally.
    uint8_t decrypted[dish_wire::MAX_DATAGRAM_BYTES];
    unsigned long long decLen = 0;
    size_t cipherLen = n - 8;
    if (crypto_aead_chacha20poly1305_ietf_decrypt(decrypted, &decLen, nullptr, buf + 8, cipherLen,
                                                  s.token, 4, nonce, s.key) != 0) {
        return 0;
    }
    s.lastRxCounter.store(ctr, std::memory_order_relaxed);

    if (decLen < 4) return 0;
    uint16_t msgType = ((uint16_t)decrypted[0] << 8) | decrypted[1];
    uint16_t msgLen = ((uint16_t)decrypted[2] << 8) | decrypted[3];

    if (msgType == dish_wire::MSG_HEARTBEAT_ACK) {
        handleHeartbeatAck(s, decrypted, msgLen, decLen);
    } else if (msgType == dish_wire::MSG_SESSION_CLOSE && msgLen >= 1 && decLen >= 5) {
        // Authenticated best-effort close notify; terminal for this session.
        const int32_t reason = (int32_t)decrypted[4];
        s.closeReason.store(reason, std::memory_order_release);
        s.connectionAlive.store(false, std::memory_order_release);
        dish_log::info("Session %d close notify: reason=%d", handle, reason);
    } else if (msgType == dish_wire::MSG_RUMBLE && msgLen == dish_wire::RUMBLE_PAYLOAD_BYTES &&
               decLen >= 4 + dish_wire::RUMBLE_PAYLOAD_BYTES) {
        // 7B fixed payload: ctrlIdx, strong BE16, weak BE16, durMs BE16.
        const uint16_t strong = (uint16_t)((decrypted[5] << 8) | decrypted[6]);
        const uint16_t weakMag = (uint16_t)((decrypted[7] << 8) | decrypted[8]);
        const uint16_t durMs = (uint16_t)((decrypted[9] << 8) | decrypted[10]);
        sink.rumble(handle, decrypted[4], strong, weakMag, durMs);
    } else if (msgType == dish_wire::MSG_LIGHTBAR && msgLen == dish_wire::LIGHTBAR_PAYLOAD_BYTES &&
               decLen >= 4 + dish_wire::LIGHTBAR_PAYLOAD_BYTES) {
        // Routed like rumble: the FeedbackRouter lands it on a Direct-claimed
        // DS4/DualSense; every other target has no LED sink and drops it.
        sink.lightbar(handle, dish_wire::decodeLightbarPayload(decrypted + 4));
    } else if (msgType == dish_wire::MSG_TRIGGER_EFFECTS &&
               msgLen == 1 + dish_wire::TRIGGER_EFFECTS_PAYLOAD_BYTES &&
               decLen >= (unsigned long long)(4 + 1 + dish_wire::TRIGGER_EFFECTS_PAYLOAD_BYTES)) {
        sink.triggerEffects(handle, decrypted[4], decrypted + 5);
    } else if (msgType == dish_wire::MSG_PLAYER_LEDS &&
               msgLen == dish_wire::PLAYER_LEDS_PAYLOAD_BYTES &&
               decLen >= 4 + dish_wire::PLAYER_LEDS_PAYLOAD_BYTES) {
        sink.playerLeds(handle, dish_wire::decodePlayerLedsPayload(decrypted + 4));
    } else if ((msgType == dish_wire::MSG_SPEAKER_AUDIO ||
                msgType == dish_wire::MSG_HAPTIC_AUDIO) &&
               msgLen >= dish_wire::AUDIO_WIRE_MIN_PAYLOAD_BYTES &&
               decLen >= 4 + (unsigned long long)msgLen) {
        // Queued, not decoded here: see speaker_queue.h. The haptic lane rides the same queue
        // and thread: same shape, same cadence, its own window.
        const dish_wire::AudioFrameHeader h = dish_wire::decodeAudioFrameHeader(decrypted + 4);
        const uint8_t* opus = decrypted + 4 + dish_wire::AUDIO_WIRE_HEADER_BYTES;
        const size_t opusLen = msgLen - dish_wire::AUDIO_WIRE_HEADER_BYTES;
        const int lane =
            msgType == dish_wire::MSG_HAPTIC_AUDIO ? AUDIO_LANE_HAPTICS : AUDIO_LANE_SPEAKER;
        sink.audio(handle, lane, h, opus, opusLen);
    } else if (msgType == dish_wire::MSG_MIC_LED && msgLen == dish_wire::MIC_LED_PAYLOAD_BYTES &&
               decLen >= 4 + dish_wire::MIC_LED_PAYLOAD_BYTES) {
        // Routed like the lightbar: the FeedbackRouter lands it on whichever
        // sink the slot has. A state we do not know can only come from a host
        // speaking something newer, and rendering it as a guess would be worse
        // than rendering nothing, so it is dropped here rather than clamped.
        const dish_wire::MicLedPayload led = dish_wire::decodeMicLedPayload(decrypted + 4);
        if (!dish_wire::micLedStateValid(led.state)) return 1;
        sink.micLed(handle, led);
    }
    return 1;
}

// Runs entirely under the session's audio lock so the window's event pointers
// (which alias its own storage) stay valid; the JVM upcalls deliberately happen
// after it is released, so a blocking AudioTrack cannot hold up mic capture.
int decodeSpeakerAudio(Session& session, const uint8_t ctrlIdx, const int lane, const uint16_t seq,
                       const uint8_t* opus, const size_t opusLen, std::vector<int16_t>& pcm,
                       bool concealed[dish_audio::AUDIO_JITTER_MAX_EVENTS_PER_PUSH]) {
    std::lock_guard<TrackedMutex> lock(session.audioMtx);
    dish_session::ControllerAudio& ca = session.audio[ctrlIdx];
    // The haptic lane is the speaker's wire format, so one decoder shape
    // serves both; only the state is per lane.
    const bool haptics = lane == AUDIO_LANE_HAPTICS;
    std::unique_ptr<dish_audio::OpusStreamDecoder>& decoder =
        haptics ? ca.hapticDecoder : ca.speakerDecoder;
    dish_audio::AudioJitterWindow& window = haptics ? ca.hapticWindow : ca.speakerWindow;
    if (!decoder) {
        decoder = dish_audio::OpusStreamDecoder::create(dish_audio::Stream::Speaker);
        if (!decoder) {
            dish_log::error("%s audio: no Opus decoder for ctrl %u", haptics ? "haptic" : "speaker",
                            (unsigned)ctrlIdx);
            return 0;
        }
    }

    const auto r = window.push(seq, opus, opusLen);
    int produced = 0;
    for (int i = 0; i < r.count; i++) {
        const auto& e = r.events[i];
        int16_t* out = pcm.data() + produced * dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES;
        size_t frames = 0;
        if (e.kind == dish_audio::AudioJitterWindow::Event::Kind::Packet) {
            frames = decoder->decode(e.data, e.len, out, dish_audio::AUDIO_FRAME_SAMPLES);
        } else {
            // Unconditionally the FEC entry: whether the carrier holds a
            // redundant copy of the missing frame is an encoder-side decision
            // we cannot see, and libopus falls back to plain concealment by
            // itself when it does not. A null carrier means conceal blind.
            frames = decoder->decodeFec(e.fecCarrier, e.fecCarrierLen, out,
                                        dish_audio::AUDIO_FRAME_SAMPLES);
        }
        if (frames != static_cast<size_t>(dish_audio::AUDIO_FRAME_SAMPLES)) continue;
        concealed[produced] = e.kind == dish_audio::AudioJitterWindow::Event::Kind::Gap;
        produced++;
    }
    return produced;
}

} // namespace dish_receive
