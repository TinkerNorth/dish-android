// SPDX-License-Identifier: LGPL-3.0-or-later

#include <jni.h>
#include <android/log.h>
#include <android/input.h>
#include <android/keycodes.h>
#include <android/looper.h>
#include <game-activity/GameActivity.h>
#include <game-activity/GameActivityEvents.h>
#include <game-activity/native_app_glue/android_native_app_glue.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <time.h>
#include <stdint.h>
#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>
#include <sodium.h>

#include "audio_codec.h"
#include "audio_jitter.h"
#include "dispatch.h"
#include "gamepad_input.h"
#include "hotpath_latency.h"
#include "send_counter.h"
#include "thread_priority.h"
#include "usb_host.h"
#include "usb_parsers.h"
#include "wire_encoders.h"

#define TAG "SatelliteJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Steady-state cadence is contractual (satellite docs/contract.md: 2000 ms). Probe
// mode densifies RTT sampling only while the diagnostics latency panel is open; the
// death window stays ~10 s wall clock because the miss threshold scales with it.
static constexpr int HEARTBEAT_INTERVAL_DEFAULT_MS = 2000;
static constexpr int HEARTBEAT_INTERVAL_PROBE_MS = 250;
static constexpr int HEARTBEAT_DEATH_TIMEOUT_MS = 10000;
static std::atomic<int> g_heartbeatIntervalMs{HEARTBEAT_INTERVAL_DEFAULT_MS};
// Topology mutation is REST-only (satellite docs/contract.md): the native
// layer carries streams + the two authenticated notifications, nothing else.
static constexpr uint16_t MSG_GAMEPAD_DATA = 0x0001;
static constexpr uint16_t MSG_HEARTBEAT_PING = 0x0002;
static constexpr uint16_t MSG_HEARTBEAT_ACK = 0x0003;
static constexpr uint16_t MSG_RUMBLE = 0x0009;
static constexpr uint16_t MSG_MOTION = 0x000A;
static constexpr uint16_t MSG_BATTERY = 0x000B;
static constexpr uint16_t MSG_TOUCHPAD = 0x000C;
static constexpr uint16_t MSG_LIGHTBAR = 0x000D;
static constexpr uint16_t MSG_SESSION_CLOSE = 0x000F;
static constexpr uint16_t MSG_TRIGGER_EFFECTS = 0x0010;
static constexpr uint16_t MSG_PLAYER_LEDS = 0x0011;
// Controller audio: the emulated pad's OWN endpoints, never the host's game
// audio. The two stream messages share one payload shape (wire_encoders.h);
// MSG_MIC_LED is the mute lamp the game asked for, coalesced like MSG_LIGHTBAR.
static constexpr uint16_t MSG_MIC_AUDIO = 0x0012;
static constexpr uint16_t MSG_SPEAKER_AUDIO = 0x0013;
static constexpr uint16_t MSG_MIC_LED = 0x0014;

// Nonce direction byte: the two directions of one session key never share a
// nonce (contract §Crypto).
static constexpr uint8_t CRYPTO_DIR_CLIENT_TO_SERVER = 0x00;
static constexpr uint8_t CRYPTO_DIR_SERVER_TO_CLIENT = 0x01;

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

// One bound controller's audio working set: the outbound mic encoder with its
// wrapping wire sequence, and the inbound speaker pair (reorder window feeding
// the decoder whose gaps it declares). Allocated lazily on the slot's first
// frame in that direction, because an Opus codec is ~20 KB of state each and
// most controllers never carry audio at all.
struct ControllerAudio {
    std::unique_ptr<dish_audio::OpusStreamEncoder> micEncoder;
    uint16_t micSeq = 0;

    dish_audio::AudioJitterWindow speakerWindow;
    std::unique_ptr<dish_audio::OpusStreamDecoder> speakerDecoder;
};

struct Session {
    int udpSock = -1;
    struct sockaddr_in dest = {};
    uint8_t token[4] = {};
    uint8_t key[32] = {}; // per-session key (HKDF-derived in Kotlin), never the pairing key
    // 64-bit so exhaustion goes silent instead of wrapping (send_counter.h).
    std::atomic<uint64_t> counter{1};
    // Linux UDP sendto is thread-safe per-socket; userspace lock would only serialise stalls.

    std::thread heartbeatThread;
    std::atomic<bool> heartbeatRunning{false};
    std::atomic<int> missedAcks{0};
    std::atomic<bool> connectionAlive{true};
    std::atomic<int64_t> lastPingNs{0};

    // Downstream replay guard (server → client direction).
    std::atomic<uint32_t> lastRxCounter{0};

    // Latest enriched heartbeat-ack material (-1 = none seen this session). The
    // Kotlin alive-poll compares epoch/bitmap against its applied state and
    // reconciles via REST on mismatch.
    std::atomic<int32_t> serverEpoch{-1};
    std::atomic<int32_t> activeBitmap{-1};

    // CLOSE_REASON_* from MSG_SESSION_CLOSE; -1 = none. Terminal for the session.
    std::atomic<int32_t> closeReason{-1};

    std::atomic<int8_t> vigemAvailable{-1};
    std::atomic<int8_t> activeControllerCount{-1};

    // Negotiated at session PUT; picks which MSG_TOUCHPAD frame this session encodes.
    std::atomic<int32_t> protocolVersion{1};

    // Per-controller audio state, keyed by ctrlIdx. Touched by the mic capture
    // thread (encode) and the audio dispatch thread (decode), never by the
    // receive thread, so one lock covers it: at 50 frames/s per direction the
    // two threads are each inside for well under a millisecond a second.
    std::mutex audioMtx;
    std::unordered_map<uint8_t, ControllerAudio> audio;
    // Set by closeSocket. Queued speaker frames hold a strong reference to this
    // session, so they can outlive it by a few milliseconds; decoding into a
    // torn-down session would deliver audio to a slot that is already gone.
    std::atomic<bool> closed{false};
};

static std::mutex g_sessionsMtx;
static std::unordered_map<int, std::shared_ptr<Session>> g_sessions;
static std::atomic<int> g_nextHandle{1};

static std::shared_ptr<Session> getSession(int handle) {
    std::lock_guard<std::mutex> lock(g_sessionsMtx);
    auto it = g_sessions.find(handle);
    return it == g_sessions.end() ? nullptr : it->second;
}

static bool sendEncrypted(Session* s, uint16_t msgType, const uint8_t* payload,
                          uint16_t payloadLen);

using gamepad::DeviceState;

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
    // Kotlin-side connection id for the bridge kinds (Bluetooth / Moonlight).
    std::string bridgeConnectionId;
};

static std::mutex g_devicesMtx;
static std::unordered_map<int32_t, DeviceState> g_devices;
static std::unordered_map<int32_t, uint64_t> g_frameworkEventCounts;

static std::mutex g_slotsMtx;
static std::unordered_map<int32_t, SlotBinding> g_slots;

static JavaVM* g_jvm = nullptr;
static jclass g_btBridgeClass = nullptr;
static jmethodID g_btDispatchMethod = nullptr;

static jclass g_moonlightBridgeClass = nullptr;
static jmethodID g_moonlightDispatchMethod = nullptr;
static jmethodID g_moonlightMotionMethod = nullptr;
static jmethodID g_moonlightTouchMethod = nullptr;

static jclass g_rumbleBridgeClass = nullptr;
static jmethodID g_rumbleDispatchMethod = nullptr;
static jclass g_feedbackBridgeClass = nullptr;
static jmethodID g_feedbackLightbarMethod = nullptr;
static jmethodID g_feedbackTriggerEffectsMethod = nullptr;
static jmethodID g_feedbackPlayerLedsMethod = nullptr;
static jmethodID g_feedbackMicLedMethod = nullptr;

static jclass g_speakerAudioBridgeClass = nullptr;
static jmethodID g_speakerAudioFrameMethod = nullptr;

static jclass g_micMuteBridgeClass = nullptr;
static jmethodID g_micMutePadMethod = nullptr;

// Bridge kinds (Bluetooth, Moonlight) run off the UI thread: BluetoothHidDevice.sendReport is
// Binder IPC, and the Moonlight path encrypts + frames in Kotlin. One queue + thread serves both;
// the report's kind picks the Kotlin bridge to upcall.
struct BridgeReport {
    SlotKind kind;
    // What the report carries: the Moonlight lane also ferries motion and
    // touch samples off the USB reader thread (Bluetooth stays gamepad-only).
    enum Payload : uint8_t { GAMEPAD = 0, MOTION = 1, TOUCH = 2 };
    Payload payload = GAMEPAD;
    std::string connectionId;
    int32_t controllerNumber;
    uint16_t wButtons;
    uint8_t bLT, bRT;
    int16_t sLX, sLY, sRX, sRY;
    int16_t gyro[3];
    int16_t accel[3];
    uint32_t timestampDeltaUs;
    gamepad::TouchpadState touch;
};

static std::mutex g_bridgeQueueMtx;
static std::condition_variable g_bridgeQueueCv;
static std::deque<BridgeReport> g_bridgeQueue;
static std::thread g_bridgeDispatchThread;
static std::atomic<bool> g_bridgeDispatchRunning{false};
static constexpr size_t BRIDGE_QUEUE_MAX = 64;

// A pad's mic-mute latch flipped. It shares the bridge thread (already attached to the JVM, and
// the USB reader thread is not) but NOT the report queue: reports are drop-oldest because a stale
// stick position is worth nothing, while a dropped mute edge would leave the app believing a muted
// pad is live. One entry per button press, so the queue never grows.
static std::deque<std::pair<int32_t, bool>> g_micMuteQueue;

static void enqueueBridgeReport(BridgeReport&& r) {
    {
        std::lock_guard<std::mutex> lock(g_bridgeQueueMtx);
        if (g_bridgeQueue.size() >= BRIDGE_QUEUE_MAX) g_bridgeQueue.pop_front();
        g_bridgeQueue.push_back(std::move(r));
    }
    g_bridgeQueueCv.notify_one();
}

static void bridgeDispatchLoop() {
    JNIEnv* env = nullptr;
    if (!g_jvm || g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        LOGE("bridgeDispatchLoop: AttachCurrentThread failed");
        return;
    }
    dish::elevateCurrentThreadToInputPriority();
    LOGI("Bridge dispatch thread started");
    while (g_bridgeDispatchRunning.load(std::memory_order_relaxed)) {
        BridgeReport r;
        {
            std::unique_lock<std::mutex> lock(g_bridgeQueueMtx);
            g_bridgeQueueCv.wait(lock, [] {
                return !g_bridgeDispatchRunning.load(std::memory_order_relaxed) ||
                       !g_bridgeQueue.empty() || !g_micMuteQueue.empty();
            });
            if (!g_bridgeDispatchRunning.load(std::memory_order_relaxed) &&
                g_bridgeQueue.empty() && g_micMuteQueue.empty())
                break;
            // Mute edges first: they are rarer and they gate capture, so they must not wait
            // behind a queue of input reports.
            if (!g_micMuteQueue.empty()) {
                auto ev = g_micMuteQueue.front();
                g_micMuteQueue.pop_front();
                lock.unlock();
                if (g_micMuteBridgeClass != nullptr && g_micMutePadMethod != nullptr) {
                    env->CallStaticVoidMethod(g_micMuteBridgeClass, g_micMutePadMethod,
                                              (jint)ev.first, (jboolean)ev.second);
                    if (env->ExceptionCheck()) env->ExceptionClear();
                }
                continue;
            }
            if (g_bridgeQueue.empty()) continue;
            r = std::move(g_bridgeQueue.front());
            g_bridgeQueue.pop_front();
        }
        jclass cls = r.kind == SLOT_MOONLIGHT ? g_moonlightBridgeClass : g_btBridgeClass;
        jmethodID method =
            r.kind == SLOT_MOONLIGHT ? g_moonlightDispatchMethod : g_btDispatchMethod;
        if (r.kind == SLOT_MOONLIGHT && r.payload == BridgeReport::MOTION)
            method = g_moonlightMotionMethod;
        if (r.kind == SLOT_MOONLIGHT && r.payload == BridgeReport::TOUCH)
            method = g_moonlightTouchMethod;
        if (cls == nullptr || method == nullptr) continue;
        jstring connId = env->NewStringUTF(r.connectionId.c_str());
        // A Moonlight session carries up to four pads on one stream, so its upcall also
        // names which pad the report belongs to; the Bluetooth link is one pad by nature.
        if (r.kind == SLOT_MOONLIGHT && r.payload == BridgeReport::MOTION) {
            env->CallStaticVoidMethod(cls, method, connId, (jint)r.controllerNumber,
                                      (jint)r.gyro[0], (jint)r.gyro[1], (jint)r.gyro[2],
                                      (jint)r.accel[0], (jint)r.accel[1], (jint)r.accel[2],
                                      (jint)r.timestampDeltaUs);
        } else if (r.kind == SLOT_MOONLIGHT && r.payload == BridgeReport::TOUCH) {
            env->CallStaticVoidMethod(
                cls, method, connId, (jint)r.controllerNumber, (jboolean)r.touch.f0Active,
                (jint)r.touch.f0Id, (jint)r.touch.f0X, (jint)r.touch.f0Y,
                (jboolean)r.touch.f1Active, (jint)r.touch.f1Id, (jint)r.touch.f1X,
                (jint)r.touch.f1Y, (jboolean)r.touch.clickDown);
        } else if (r.kind == SLOT_MOONLIGHT) {
            env->CallStaticVoidMethod(cls, method, connId, (jint)r.controllerNumber,
                                      (jint)r.wButtons, (jint)r.bLT, (jint)r.bRT, (jint)r.sLX,
                                      (jint)r.sLY, (jint)r.sRX, (jint)r.sRY);
        } else {
            env->CallStaticVoidMethod(cls, method, connId, (jint)r.wButtons, (jint)r.bLT,
                                      (jint)r.bRT, (jint)r.sLX, (jint)r.sLY, (jint)r.sRX,
                                      (jint)r.sRY);
        }
        env->DeleteLocalRef(connId);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    g_jvm->DetachCurrentThread();
    LOGI("Bridge dispatch thread stopped");
}

static void startBridgeDispatchThread() {
    bool was = g_bridgeDispatchRunning.exchange(true, std::memory_order_relaxed);
    if (was) return;
    g_bridgeDispatchThread = std::thread(bridgeDispatchLoop);
}

// The inbound MSG_SPEAKER_AUDIO path.
//
// Its own queue and thread rather than the input bridge's, for two reasons: an
// audio frame must never evict a gamepad report from that queue, and the sink
// at the far end is an AudioTrack whose write blocks until the buffer takes the
// samples. The four other return-path arms upcall straight out of receiveAck
// because they hand over a handful of bytes and return; doing that here would
// let a full AudioTrack stall the session's entire UDP drain, heartbeat acks
// included. So the receive thread only copies the Opus packet in: reorder
// window, decode and concealment all run over here, off the socket.
struct SpeakerFrame {
    // Strong: the session's codecs must outlive a closeSocket that races a
    // queued frame. `closed` is what stops us decoding into a dead session.
    std::shared_ptr<Session> session;
    int handle = -1;
    uint8_t ctrlIdx = 0;
    uint16_t seq = 0;
    std::vector<uint8_t> opus;
};

static std::mutex g_audioQueueMtx;
static std::condition_variable g_audioQueueCv;
static std::deque<SpeakerFrame> g_audioQueue;
static std::thread g_audioDispatchThread;
static std::atomic<bool> g_audioDispatchRunning{false};
// 8 frames = 160 ms. A backlog past that is audio too old to play on time, and
// dropping the oldest is exactly what the reorder window's gap handling covers.
static constexpr size_t AUDIO_QUEUE_MAX = 8;

static void enqueueSpeakerFrame(SpeakerFrame&& f) {
    {
        std::lock_guard<std::mutex> lock(g_audioQueueMtx);
        if (g_audioQueue.size() >= AUDIO_QUEUE_MAX) g_audioQueue.pop_front();
        g_audioQueue.push_back(std::move(f));
    }
    g_audioQueueCv.notify_one();
}

// Reorder + decode one queued frame into `pcm` (which holds up to
// AUDIO_JITTER_MAX_EVENTS_PER_PUSH consecutive interleaved-stereo frames).
// Returns how many frames it produced and, per frame, whether it was concealed
// rather than decoded from a packet that actually arrived.
//
// Runs entirely under the session's audio lock so the window's event pointers
// (which alias its own storage) stay valid; the JVM upcalls deliberately happen
// after it is released, so a blocking AudioTrack cannot hold up mic capture.
static int decodeSpeakerFrame(const SpeakerFrame& f, std::vector<int16_t>& pcm,
                              bool concealed[dish_audio::AUDIO_JITTER_MAX_EVENTS_PER_PUSH]) {
    std::lock_guard<std::mutex> lock(f.session->audioMtx);
    ControllerAudio& ca = f.session->audio[f.ctrlIdx];
    if (!ca.speakerDecoder) {
        ca.speakerDecoder = dish_audio::OpusStreamDecoder::create(dish_audio::Stream::Speaker);
        if (!ca.speakerDecoder) {
            LOGE("speaker audio: no Opus decoder for ctrl %u", (unsigned)f.ctrlIdx);
            return 0;
        }
    }

    const auto r = ca.speakerWindow.push(f.seq, f.opus.data(), f.opus.size());
    int produced = 0;
    for (int i = 0; i < r.count; i++) {
        const auto& e = r.events[i];
        int16_t* out = pcm.data() + produced * dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES;
        size_t frames = 0;
        if (e.kind == dish_audio::AudioJitterWindow::Event::Kind::Packet) {
            frames = ca.speakerDecoder->decode(e.data, e.len, out, dish_audio::AUDIO_FRAME_SAMPLES);
        } else {
            // Unconditionally the FEC entry: whether the carrier holds a
            // redundant copy of the missing frame is an encoder-side decision
            // we cannot see, and libopus falls back to plain concealment by
            // itself when it does not. A null carrier means conceal blind.
            frames = ca.speakerDecoder->decodeFec(e.fecCarrier, e.fecCarrierLen, out,
                                                  dish_audio::AUDIO_FRAME_SAMPLES);
        }
        if (frames != static_cast<size_t>(dish_audio::AUDIO_FRAME_SAMPLES)) continue;
        concealed[produced] = e.kind == dish_audio::AudioJitterWindow::Event::Kind::Gap;
        produced++;
    }
    return produced;
}

static void deliverSpeakerPcm(JNIEnv* env, int handle, uint8_t ctrlIdx, const int16_t* pcm,
                              bool concealed) {
    if (g_speakerAudioBridgeClass == nullptr || g_speakerAudioFrameMethod == nullptr) return;
    // A fresh array per 20 ms frame: 3.8 KB of short-lived garbage 50 times a
    // second is far below what a pinned reusable buffer would cost in JNI
    // critical sections, and the samples have to be copied into the JVM heap
    // either way.
    jshortArray samples = env->NewShortArray(dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES);
    if (samples == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    env->SetShortArrayRegion(samples, 0, dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES,
                             reinterpret_cast<const jshort*>(pcm));
    env->CallStaticVoidMethod(g_speakerAudioBridgeClass, g_speakerAudioFrameMethod, (jint)handle,
                              (jint)ctrlIdx, samples, (jboolean)concealed);
    env->DeleteLocalRef(samples);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static void audioDispatchLoop() {
    JNIEnv* env = nullptr;
    if (!g_jvm || g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        LOGE("audioDispatchLoop: AttachCurrentThread failed");
        return;
    }
    // Named for its first caller; the value it sets is URGENT_AUDIO niceness,
    // which is exactly what a 20 ms decode cadence wants.
    dish::elevateCurrentThreadToInputPriority();
    LOGI("Speaker audio dispatch thread started");
    // Allocated once for the thread: one push can drain the whole window and
    // conceal ahead of it, so size for the worst case rather than reallocating.
    std::vector<int16_t> pcm(static_cast<size_t>(dish_audio::AUDIO_JITTER_MAX_EVENTS_PER_PUSH) *
                             dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES);
    bool concealed[dish_audio::AUDIO_JITTER_MAX_EVENTS_PER_PUSH] = {};
    while (g_audioDispatchRunning.load(std::memory_order_relaxed)) {
        SpeakerFrame f;
        {
            std::unique_lock<std::mutex> lock(g_audioQueueMtx);
            g_audioQueueCv.wait(lock, [] {
                return !g_audioDispatchRunning.load(std::memory_order_relaxed) ||
                       !g_audioQueue.empty();
            });
            if (!g_audioDispatchRunning.load(std::memory_order_relaxed) && g_audioQueue.empty())
                break;
            f = std::move(g_audioQueue.front());
            g_audioQueue.pop_front();
        }
        if (!f.session || f.session->closed.load(std::memory_order_acquire)) continue;
        const int produced = decodeSpeakerFrame(f, pcm, concealed);
        for (int i = 0; i < produced; i++) {
            deliverSpeakerPcm(env, f.handle, f.ctrlIdx,
                              pcm.data() + i * dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES,
                              concealed[i]);
        }
    }
    g_jvm->DetachCurrentThread();
    LOGI("Speaker audio dispatch thread stopped");
}

static void startAudioDispatchThread() {
    bool was = g_audioDispatchRunning.exchange(true, std::memory_order_relaxed);
    if (was) return;
    g_audioDispatchThread = std::thread(audioDispatchLoop);
}

static inline float axisCur(const GameActivityMotionEvent* ev, int axis) {
    if (ev->pointerCount == 0) return 0.f;
    return ev->pointers[0].axisValues[axis];
}

// Lock order: devices < slots < (sessions | btQueue).
static void publishIfChanged(int32_t deviceId, DeviceState& s) {
    std::lock_guard<std::mutex> lock(g_slotsMtx);
    auto it = g_slots.find(deviceId);
    if (it == g_slots.end()) return;
    // Bind-check before consume: a sample dropped for lack of a slot must not burn the latch, or
    // the slot it later binds to never sees that state.
    if (!gamepad::consumePublishIfChanged(s)) return;
    const SlotBinding& binding = it->second;

    if (binding.kind == SLOT_SATELLITE) {
        auto session = getSession(binding.sessionHandle);
        if (!session) return;
        uint8_t payload[1 + sizeof(XUSB_REPORT)];
        payload[0] = (uint8_t)(binding.controllerIndex & 0xFF);
        XUSB_REPORT* r = (XUSB_REPORT*)(payload + 1);
        r->wButtons = s.wButtons;
        r->bLeftTrigger = s.bLT;
        r->bRightTrigger = s.bRT;
        r->sThumbLX = s.sLX;
        r->sThumbLY = s.sLY;
        r->sThumbRX = s.sRX;
        r->sThumbRY = s.sRY;
        sendEncrypted(session.get(), MSG_GAMEPAD_DATA, payload, sizeof(payload));
        hotpath::markGamepadSent(); // stage-1 end: the URB-driven packet has left sendto()
    } else if (binding.kind == SLOT_BLUETOOTH || binding.kind == SLOT_MOONLIGHT) {
        if (binding.bridgeConnectionId.empty()) return;
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
        enqueueBridgeReport(std::move(r));
    }
}

// deviceId is reused across reconnects, so the new pad would inherit stale held inputs; reset and
// re-arm so it syncs to neutral. Call without g_slotsMtx held: publishIfChanged retakes it.
static void syncSlotBaseline(int32_t deviceId) {
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    auto it = g_devices.find(deviceId);
    if (it == g_devices.end()) return;
    gamepad::resetState(it->second);
    gamepad::resetPublishLatch(it->second);
    publishIfChanged(deviceId, it->second);
}

// USB-direct reports skip gamepad::applyAxes, so the per-device flat (deadzone) is never applied; a
// resting stick would otherwise stream jitter and drift. Radial hard cutoff, ~8% of int16 range.
static constexpr int32_t kUsbStickDeadzone = 2600;
static inline void applyUsbStickDeadzone(int16_t& x, int16_t& y) {
    const int64_t mag2 = static_cast<int64_t>(x) * x + static_cast<int64_t>(y) * y;
    if (mag2 < static_cast<int64_t>(kUsbStickDeadzone) * kUsbStickDeadzone) {
        x = 0;
        y = 0;
    }
}

namespace dispatch {

void prewarmDevice(int32_t deviceId) {
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    g_devices[deviceId];
}

// Diagnostics-only mirror gate: motion/touch land in g_devices solely for the inspector
// snapshot, and only while an inspector screen is open. Off costs one relaxed load per report,
// the same budget as the latency bench markers.
static std::atomic<bool> g_inspect{false};

void applyUsbReport(int32_t deviceId, const gamepad::DeviceState& nu) {
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    auto& s = g_devices[deviceId];
    s.wButtons = nu.wButtons;
    s.bLT = nu.bLT;
    s.bRT = nu.bRT;
    s.sLX = nu.sLX;
    s.sLY = nu.sLY;
    s.sRX = nu.sRX;
    s.sRY = nu.sRY;
    applyUsbStickDeadzone(s.sLX, s.sLY);
    applyUsbStickDeadzone(s.sRX, s.sRY);
    if (g_inspect.load(std::memory_order_relaxed)) {
        // Copy-on-valid keeps the last known sample visible: a report without a touch
        // update must not read as a lift in the inspector.
        if (nu.motionValid) {
            s.motionValid = true;
            s.gyroX = nu.gyroX;
            s.gyroY = nu.gyroY;
            s.gyroZ = nu.gyroZ;
            s.accelX = nu.accelX;
            s.accelY = nu.accelY;
            s.accelZ = nu.accelZ;
        }
        if (nu.touchValid) {
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
    }
    publishIfChanged(deviceId, s);
}

void resetAndPublish(int32_t deviceId) {
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    auto it = g_devices.find(deviceId);
    if (it == g_devices.end()) return;
    gamepad::resetState(it->second);
    publishIfChanged(deviceId, it->second);
}

void forgetDevice(int32_t deviceId) {
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    g_devices.erase(deviceId);
    g_frameworkEventCounts.erase(deviceId);
}

// Called on the USB reader thread, which is not attached to the JVM: hand the edge to the bridge
// thread (which is) rather than attaching per press. Nothing here touches the wire; the mute bit
// is already in the report the decoder produced.
void applyPadMicMute(int32_t deviceId, bool muted) {
    {
        std::lock_guard<std::mutex> lock(g_bridgeQueueMtx);
        g_micMuteQueue.emplace_back(deviceId, muted);
    }
    g_bridgeQueueCv.notify_one();
}

void applyUsbMotion(int32_t deviceId, int16_t gyroX, int16_t gyroY, int16_t gyroZ, int16_t accelX,
                    int16_t accelY, int16_t accelZ, uint32_t timestampDeltaUs) {
    std::lock_guard<std::mutex> lock(g_slotsMtx);
    auto it = g_slots.find(deviceId);
    if (it == g_slots.end()) return;
    const SlotBinding& binding = it->second;
    if (binding.kind == SLOT_MOONLIGHT) {
        // Kotlin translates to CONTROLLER_MOTION and drops samples the host
        // never asked for (MOTION_EVENT gate), so this stays fire-and-forget.
        if (binding.bridgeConnectionId.empty()) return;
        BridgeReport r{};
        r.kind = SLOT_MOONLIGHT;
        r.payload = BridgeReport::MOTION;
        r.connectionId = binding.bridgeConnectionId;
        r.controllerNumber = binding.controllerIndex;
        r.gyro[0] = gyroX;
        r.gyro[1] = gyroY;
        r.gyro[2] = gyroZ;
        r.accel[0] = accelX;
        r.accel[1] = accelY;
        r.accel[2] = accelZ;
        r.timestampDeltaUs = timestampDeltaUs;
        enqueueBridgeReport(std::move(r));
        return;
    }
    if (binding.kind != SLOT_SATELLITE) return;
    auto session = getSession(binding.sessionHandle);
    if (!session) return;
    uint8_t payload[17];
    dish_wire::encodeMotionPayload(payload, (uint8_t)(binding.controllerIndex & 0xFF), gyroX, gyroY,
                                   gyroZ, accelX, accelY, accelZ, timestampDeltaUs);
    sendEncrypted(session.get(), MSG_MOTION, payload, sizeof(payload));
}

// The Bluetooth HID descriptor is a plain gamepad, so touch has nowhere to go on that
// transport. Satellite gets the full-state frame; Moonlight gets it via the bridge, where
// Kotlin diffs it into CONTROLLER_TOUCH events. Routing (ds4 pad vs mouse vs off) is the
// satellite receiver's job, declared per slot in the descriptor.
void applyUsbTouchpad(int32_t deviceId, const gamepad::TouchpadState& t, uint32_t eventTimeMs) {
    std::lock_guard<std::mutex> lock(g_slotsMtx);
    auto it = g_slots.find(deviceId);
    if (it == g_slots.end()) return;
    const SlotBinding& binding = it->second;
    if (binding.kind == SLOT_MOONLIGHT) {
        if (binding.bridgeConnectionId.empty()) return;
        BridgeReport r{};
        r.kind = SLOT_MOONLIGHT;
        r.payload = BridgeReport::TOUCH;
        r.connectionId = binding.bridgeConnectionId;
        r.controllerNumber = binding.controllerIndex;
        r.touch = t;
        (void)eventTimeMs; // events are re-timed by the reliable control stream
        enqueueBridgeReport(std::move(r));
        return;
    }
    if (binding.kind != SLOT_SATELLITE) return;
    auto session = getSession(binding.sessionHandle);
    if (!session) return;
    uint8_t payload[19];
    const uint8_t idx = (uint8_t)(binding.controllerIndex & 0xFF);
    if (session->protocolVersion.load() >= 2) {
        dish_wire::encodeTouchpadPayloadV2(payload, idx, t.f0Active, t.f1Active, t.clickDown, false,
                                           false, t.f0Id, t.f0X, t.f0Y, t.f1Id, t.f1X, t.f1Y,
                                           eventTimeMs, 0);
        sendEncrypted(session.get(), MSG_TOUCHPAD, payload, 19);
    } else {
        dish_wire::encodeTouchpadPayloadV1(payload, idx, t.f0Active, t.f1Active, t.clickDown,
                                           t.f0Id, t.f0X, t.f0Y, t.f1Id, t.f1X, t.f1Y, eventTimeMs);
        sendEncrypted(session.get(), MSG_TOUCHPAD, payload, 16);
    }
}

} // namespace dispatch

// Returning true consumes the event so it can't trigger incidental View focus navigation.
static bool gamepadKeyFilter(const GameActivityKeyEvent* ev) {
    int32_t source = ev->source;
    bool isGame = (source & AINPUT_SOURCE_GAMEPAD) == AINPUT_SOURCE_GAMEPAD ||
                  (source & AINPUT_SOURCE_JOYSTICK) == AINPUT_SOURCE_JOYSTICK;
    if (!isGame) return false;
    int32_t kc = ev->keyCode;
    int32_t deviceId = ev->deviceId;
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    auto it = g_devices.find(deviceId);
    uint8_t quirk = it != g_devices.end() ? it->second.quirk : 0;
    bool isMappedKey = (quirk & gamepad::QUIRK_SWITCH_LAYOUT)
                           ? gamepad::switchLayoutConsumesKey(kc)
                           : (kc == AKEYCODE_BUTTON_L2 || kc == AKEYCODE_BUTTON_R2) ||
                                 gamepad::keycodeToXusb(kc) != 0;
    if (!isMappedKey) return false;

    int32_t action = ev->action;
    if (action == AKEY_EVENT_ACTION_DOWN || action == AKEY_EVENT_ACTION_UP) {
        g_frameworkEventCounts[deviceId]++;
        auto& state = g_devices[deviceId];
        if (gamepad::applyKey(state, kc, action == AKEY_EVENT_ACTION_DOWN)) {
            publishIfChanged(deviceId, state);
        }
    }
    return true;
}

static bool gamepadMotionFilter(const GameActivityMotionEvent* ev) {
    if ((ev->source & AINPUT_SOURCE_JOYSTICK) != AINPUT_SOURCE_JOYSTICK) return false;
    int32_t action = ev->action & AMOTION_EVENT_ACTION_MASK;
    int32_t deviceId = ev->deviceId;

    std::lock_guard<std::mutex> lock(g_devicesMtx);
    auto& state = g_devices[deviceId];

    if (action == AMOTION_EVENT_ACTION_CANCEL) {
        gamepad::resetState(state);
        publishIfChanged(deviceId, state);
        return true;
    }
    if (action != AMOTION_EVENT_ACTION_MOVE) return true;
    g_frameworkEventCounts[deviceId]++;

    // Latest sample wins: historicals are intermediate states the next apply overwrites anyway.
    float z = axisCur(ev, AMOTION_EVENT_AXIS_Z);
    float rz = axisCur(ev, AMOTION_EVENT_AXIS_RZ);
    float rx = axisCur(ev, AMOTION_EVENT_AXIS_RX);
    float ry = axisCur(ev, AMOTION_EVENT_AXIS_RY);
    // Right-stick layout varies (Z/RZ vs RX/RY); pick the larger-magnitude pair.
    float rightX = std::fabs(z) >= std::fabs(rx) ? z : rx;
    float rightY = std::fabs(rz) >= std::fabs(ry) ? rz : ry;
    float lt =
        std::max(axisCur(ev, AMOTION_EVENT_AXIS_LTRIGGER), axisCur(ev, AMOTION_EVENT_AXIS_BRAKE));
    float rt =
        std::max(axisCur(ev, AMOTION_EVENT_AXIS_RTRIGGER), axisCur(ev, AMOTION_EVENT_AXIS_GAS));
    gamepad::applyAxes(state, axisCur(ev, AMOTION_EVENT_AXIS_X), axisCur(ev, AMOTION_EVENT_AXIS_Y),
                       rightX, rightY, lt, rt, axisCur(ev, AMOTION_EVENT_AXIS_HAT_X),
                       axisCur(ev, AMOTION_EVENT_AXIS_HAT_Y));
    publishIfChanged(deviceId, state);
    return true;
}

static uint64_t nowMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

static void putBE16(uint8_t* dst, uint16_t v) {
    dst[0] = (uint8_t)(v >> 8);
    dst[1] = (uint8_t)(v);
}
static void putBE32(uint8_t* dst, uint32_t v) {
    dst[0] = (uint8_t)(v >> 24);
    dst[1] = (uint8_t)(v >> 16);
    dst[2] = (uint8_t)(v >> 8);
    dst[3] = (uint8_t)(v);
}

static bool sendEncrypted(Session* s, uint16_t msgType, const uint8_t* payload,
                          uint16_t payloadLen) {
    if (!s || s->udpSock < 0) return false;

    // Sized from the contract's datagram ceiling, not from the message set:
    // MSG_MIC_AUDIO carries a whole Opus packet, and the bound has to hold
    // structurally rather than by what today's senders happen to emit.
    uint8_t inner[dish_wire::INNER_HEADER_BYTES + dish_wire::MAX_INNER_PAYLOAD_BYTES];
    if (!dish_wire::innerPayloadFits(payloadLen)) return false;
    uint16_t innerLen = static_cast<uint16_t>(dish_wire::INNER_HEADER_BYTES + payloadLen);
    putBE16(inner, msgType);
    putBE16(inner + 2, payloadLen);
    if (payloadLen > 0) memcpy(inner + 4, payload, payloadLen);

    uint32_t ctr = 0;
    if (!dish_counter::acquireSendCounter(s->counter, &ctr)) return false;

    // Nonce: dir(1) | 0×7 | counter(4 BE). The direction byte keeps this
    // direction's nonces disjoint from the server's under the shared key.
    uint8_t nonce[12] = {};
    nonce[0] = CRYPTO_DIR_CLIENT_TO_SERVER;
    putBE32(nonce + 8, ctr);

    uint8_t ciphertext[sizeof(inner) + crypto_aead_chacha20poly1305_ietf_ABYTES];
    unsigned long long cipherLen = 0;
    crypto_aead_chacha20poly1305_ietf_encrypt(ciphertext, &cipherLen, inner, innerLen, s->token, 4,
                                              nullptr, nonce, s->key);

    uint8_t packet[8 + sizeof(ciphertext)];
    // The ceiling made structural: a full-size inner payload plus header and
    // tag is exactly one Ethernet MTU, so nothing this path emits can fragment.
    static_assert(sizeof(packet) == dish_wire::MAX_DATAGRAM_BYTES,
                  "send buffer must be exactly the contract's datagram ceiling");
    memcpy(packet, s->token, 4);
    putBE32(packet + 4, ctr);
    memcpy(packet + 8, ciphertext, (size_t)cipherLen);

    size_t totalLen = 8 + (size_t)cipherLen;
    // MSG_DONTWAIT: a blocking sendto was observed to stall 1.5s during Wi-Fi power-save
    // transitions.
    ssize_t sent = sendto(s->udpSock, packet, totalLen, MSG_DONTWAIT, (struct sockaddr*)&s->dest,
                          sizeof(s->dest));
    // Soft-drop on buffer-full: UDP semantics absorb it and the next tick refreshes state.
    if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) { return true; }
    return sent == (ssize_t)totalLen;
}

static void heartbeatLoop(std::shared_ptr<Session> s) {
    LOGI("Heartbeat thread started (sock=%d)", s->udpSock);
    while (s->heartbeatRunning.load(std::memory_order_relaxed)) {
        sendEncrypted(s.get(), MSG_HEARTBEAT_PING, nullptr, 0);
        if (hotpath::enabled()) {
            // stage-2: round-trip clock starts here, on this session's own clock.
            const int64_t now = hotpath::nowMonotonicNs();
            if (hotpath::shouldArmPing(s->lastPingNs.load(std::memory_order_relaxed), now)) {
                s->lastPingNs.store(now, std::memory_order_relaxed);
            }
        }

        const int intervalMs = g_heartbeatIntervalMs.load(std::memory_order_relaxed);
        const int missMax = HEARTBEAT_DEATH_TIMEOUT_MS / intervalMs;
        if (s->missedAcks.fetch_add(1, std::memory_order_relaxed) + 1 >= missMax) {
            LOGE("Missed %d heartbeat ACKs, connection dead", missMax);
            s->connectionAlive.store(false, std::memory_order_relaxed);
        }

        for (int i = 0; i < intervalMs / 50; i++) {
            if (!s->heartbeatRunning.load(std::memory_order_relaxed)) break;
            usleep(50000);
        }
    }
    LOGI("Heartbeat thread stopped");
}

void android_main(struct android_app* app) {
    LOGI("android_main started (filter-inline input mode)");

    // GameActivity only fills AXIS_X/Y by default; opt-in to every axis the motion filter reads.
    GameActivityPointerAxes_enableAxis(AMOTION_EVENT_AXIS_Z);
    GameActivityPointerAxes_enableAxis(AMOTION_EVENT_AXIS_RZ);
    GameActivityPointerAxes_enableAxis(AMOTION_EVENT_AXIS_RX);
    GameActivityPointerAxes_enableAxis(AMOTION_EVENT_AXIS_RY);
    GameActivityPointerAxes_enableAxis(AMOTION_EVENT_AXIS_LTRIGGER);
    GameActivityPointerAxes_enableAxis(AMOTION_EVENT_AXIS_RTRIGGER);
    GameActivityPointerAxes_enableAxis(AMOTION_EVENT_AXIS_BRAKE);
    GameActivityPointerAxes_enableAxis(AMOTION_EVENT_AXIS_GAS);
    GameActivityPointerAxes_enableAxis(AMOTION_EVENT_AXIS_HAT_X);
    GameActivityPointerAxes_enableAxis(AMOTION_EVENT_AXIS_HAT_Y);

    app->keyEventFilter = gamepadKeyFilter;
    app->motionEventFilter = gamepadMotionFilter;

    while (!app->destroyRequested) {
        int events;
        struct android_poll_source* source = nullptr;
        int result = ALooper_pollOnce(-1, nullptr, &events, (void**)&source);
        if (result == ALOOPER_POLL_ERROR) {
            LOGE("ALooper_pollOnce returned ALOOPER_POLL_ERROR");
            break;
        }
        if (source != nullptr) source->process(source->app, source);
        if (app->destroyRequested) break;

        struct android_input_buffer* ib = android_app_swap_input_buffers(app);
        if (ib == nullptr) continue;
        if (ib->motionEventsCount > 0) android_app_clear_motion_events(ib);
        if (ib->keyEventsCount > 0) android_app_clear_key_events(ib);
    }
    LOGI("android_main: destroy requested, exiting");
}

extern "C" {

static std::once_flag g_sodiumInit;
static void ensureSodiumInit() {
    std::call_once(g_sodiumInit, []() {
        if (sodium_init() < 0)
            LOGE("sodium_init() failed!");
        else
            LOGI("libsodium initialized");
    });
}

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_openSocket(JNIEnv* env,
                                                                                     jobject,
                                                                                     jstring ip,
                                                                                     jint port) {
    ensureSodiumInit();
    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) {
        LOGE("socket() failed");
        return -1;
    }

    int tos = 0xB8;
    if (setsockopt(sock, IPPROTO_IP, IP_TOS, &tos, sizeof(tos)) < 0)
        LOGI("IP_TOS not supported (non-fatal): %s", strerror(errno));

    int busyPoll = 50;
    if (setsockopt(sock, SOL_SOCKET, SO_BUSY_POLL, &busyPoll, sizeof(busyPoll)) < 0)
        LOGI("SO_BUSY_POLL not supported (non-fatal): %s", strerror(errno));

    struct timeval rtv = {0, 500000};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &rtv, sizeof(rtv));

    auto session = std::make_shared<Session>();
    session->udpSock = sock;
    const char* s = env->GetStringUTFChars(ip, nullptr);
    session->dest.sin_family = AF_INET;
    session->dest.sin_port = htons((uint16_t)port);
    // An unparseable address (e.g. an IPv6 literal from mDNS) must fail the
    // connect, not silently stream every packet to 0.0.0.0.
    const int ptonOk = inet_pton(AF_INET, s, &session->dest.sin_addr);
    env->ReleaseStringUTFChars(ip, s);
    if (ptonOk != 1) {
        LOGE("openSocket: not an IPv4 literal, refusing");
        close(sock);
        return -1;
    }

    int handle = g_nextHandle.fetch_add(1, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(g_sessionsMtx);
        g_sessions[handle] = session;
    }
    LOGI("UDP session %d opened -> port %d (TOS=0x%02X)", handle, port, tos);
    return handle;
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_closeSocket(JNIEnv*,
                                                                                      jobject,
                                                                                      jint handle) {
    std::shared_ptr<Session> s;
    {
        std::lock_guard<std::mutex> lock(g_sessionsMtx);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) return;
        s = it->second;
        g_sessions.erase(it);
    }
    s->heartbeatRunning.store(false);
    if (s->heartbeatThread.joinable()) s->heartbeatThread.join();
    // Before the codecs go: a speaker frame already queued still holds a strong
    // reference to this session, and decoding it now would deliver audio to a
    // slot that no longer exists.
    s->closed.store(true, std::memory_order_release);
    {
        std::lock_guard<std::mutex> audioLock(s->audioMtx);
        s->audio.clear();
    }
    if (s->udpSock >= 0) {
        close(s->udpSock);
        s->udpSock = -1;
    }
    LOGI("UDP session %d closed", handle);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_setConnectionParams(
    JNIEnv* env, jobject, jint handle, jbyteArray tokenArr, jbyteArray keyArr,
    jint protocolVersion) {
    ensureSodiumInit();
    auto s = getSession(handle);
    if (!s) return;
    jbyte* tokenBytes = env->GetByteArrayElements(tokenArr, nullptr);
    jbyte* keyBytes = env->GetByteArrayElements(keyArr, nullptr);
    memcpy(s->token, tokenBytes, 4);
    memcpy(s->key, keyBytes, 32);
    // Counters restart with each (token, sessionKey) pair (contract §Crypto).
    s->counter.store(1);
    s->lastRxCounter.store(0);
    s->missedAcks.store(0);
    s->connectionAlive.store(true);
    s->serverEpoch.store(-1);
    s->activeBitmap.store(-1);
    s->closeReason.store(-1);
    s->protocolVersion.store(protocolVersion);
    // Audio streams restart with the (token, sessionKey) pair too: a re-PUT
    // replugs the pads server-side, so the far end's sequence numbers start
    // over and the old reorder window would read the new stream's first second
    // as ancient history.
    {
        std::lock_guard<std::mutex> audioLock(s->audioMtx);
        s->audio.clear();
    }
    env->ReleaseByteArrayElements(tokenArr, tokenBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(keyArr, keyBytes, JNI_ABORT);
    LOGI("Session %d params set (token=%02x%02x%02x%02x, protocol=%d)", handle, s->token[0],
         s->token[1], s->token[2], s->token[3], (int)protocolVersion);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendReport(
    JNIEnv*, jobject, jint handle, jint controllerIndex, jint wB, jint bLT, jint bRT, jint sLX,
    jint sLY, jint sRX, jint sRY) {
    auto s = getSession(handle);
    if (!s) return;
    uint8_t payload[13];
    payload[0] = (uint8_t)(controllerIndex & 0xFF);
    XUSB_REPORT* r = (XUSB_REPORT*)(payload + 1);
    r->wButtons = (uint16_t)(wB & 0xFFFF);
    r->bLeftTrigger = (uint8_t)(bLT & 0xFF);
    r->bRightTrigger = (uint8_t)(bRT & 0xFF);
    r->sThumbLX = (int16_t)sLX;
    r->sThumbLY = (int16_t)sLY;
    r->sThumbRX = (int16_t)sRX;
    r->sThumbRY = (int16_t)sRY;
    sendEncrypted(s.get(), MSG_GAMEPAD_DATA, payload, 13);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendMotion(
    JNIEnv*, jobject, jint handle, jint controllerIndex, jshort gyroX, jshort gyroY, jshort gyroZ,
    jshort accelX, jshort accelY, jshort accelZ, jint timestampDeltaUs) {
    auto s = getSession(handle);
    if (!s) return;
    uint8_t payload[17];
    dish_wire::encodeMotionPayload(payload, (uint8_t)(controllerIndex & 0xFF), (int16_t)gyroX,
                                   (int16_t)gyroY, (int16_t)gyroZ, (int16_t)accelX, (int16_t)accelY,
                                   (int16_t)accelZ, (uint32_t)timestampDeltaUs);
    sendEncrypted(s.get(), MSG_MOTION, payload, sizeof(payload));
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendBattery(
    JNIEnv*, jobject, jint handle, jint controllerIndex, jint level, jint status) {
    auto s = getSession(handle);
    if (!s) return;
    uint8_t payload[3];
    dish_wire::encodeBatteryPayload(payload, (uint8_t)(controllerIndex & 0xFF),
                                    (uint8_t)(level & 0xFF), (uint8_t)(status & 0xFF));
    sendEncrypted(s.get(), MSG_BATTERY, payload, sizeof(payload));
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendTouchpad(
    JNIEnv*, jobject, jint handle, jint controllerIndex, jboolean f0Active, jboolean f1Active,
    jboolean buttonPressed, jboolean rightPressed, jboolean middlePressed, jint f0TrackingId,
    jshort f0x, jshort f0y, jint f1TrackingId, jshort f1x, jshort f1y, jlong eventTimeMs,
    jshort scrollDelta) {
    auto s = getSession(handle);
    if (!s) return;
    uint8_t payload[19];
    const uint8_t idx = (uint8_t)(controllerIndex & 0xFF);
    if (s->protocolVersion.load() >= 2) {
        dish_wire::encodeTouchpadPayloadV2(
            payload, idx, f0Active == JNI_TRUE, f1Active == JNI_TRUE, buttonPressed == JNI_TRUE,
            rightPressed == JNI_TRUE, middlePressed == JNI_TRUE, (uint8_t)(f0TrackingId & 0xFF),
            (int16_t)f0x, (int16_t)f0y, (uint8_t)(f1TrackingId & 0xFF), (int16_t)f1x, (int16_t)f1y,
            (uint32_t)(eventTimeMs & 0xFFFFFFFFLL), (int16_t)scrollDelta);
        sendEncrypted(s.get(), MSG_TOUCHPAD, payload, 19);
    } else {
        // v1 has no mouse buttons and no wheel; the overlay never offers them on a v1
        // session, so dropping the fields here loses nothing.
        dish_wire::encodeTouchpadPayloadV1(
            payload, idx, f0Active == JNI_TRUE, f1Active == JNI_TRUE, buttonPressed == JNI_TRUE,
            (uint8_t)(f0TrackingId & 0xFF), (int16_t)f0x, (int16_t)f0y,
            (uint8_t)(f1TrackingId & 0xFF), (int16_t)f1x, (int16_t)f1y,
            (uint32_t)(eventTimeMs & 0xFFFFFFFFLL));
        sendEncrypted(s.get(), MSG_TOUCHPAD, payload, 16);
    }
}

// One 20 ms mono window straight from AudioRecord: encode it and put it on the
// wire as MSG_MIC_AUDIO. Called from the capture thread, so all of the work
// (Opus encode included) happens there rather than on the caller's.
//
// Refusing a window that is not exactly AUDIO_FRAME_SAMPLES is the encoder's
// job, not a check duplicated here: a mis-framed buffer must not become a
// packet the satellite cannot place in its timeline.
JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendMicFrame(
    JNIEnv* env, jobject, jint handle, jint controllerIndex, jshortArray pcmMono) {
    auto s = getSession(handle);
    if (!s || pcmMono == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(pcmMono) != dish_audio::AUDIO_FRAME_SAMPLES) return JNI_FALSE;
    int16_t pcm[dish_audio::AUDIO_FRAME_SAMPLES];
    env->GetShortArrayRegion(pcmMono, 0, dish_audio::AUDIO_FRAME_SAMPLES,
                             reinterpret_cast<jshort*>(pcm));

    // The wire ceiling, not a guess at the bitrate: libopus treats the output
    // size as a hard limit it encodes down to, so the only refusal on this path
    // should be one the contract actually imposes.
    uint8_t payload[dish_wire::AUDIO_WIRE_HEADER_BYTES + dish_wire::AUDIO_WIRE_MAX_OPUS_BYTES];
    const uint8_t idx = (uint8_t)(controllerIndex & 0xFF);
    size_t opusBytes = 0;
    uint16_t seq = 0;
    {
        std::lock_guard<std::mutex> lock(s->audioMtx);
        ControllerAudio& ca = s->audio[idx];
        if (!ca.micEncoder) {
            ca.micEncoder = dish_audio::OpusStreamEncoder::create(dish_audio::Stream::Mic);
            if (!ca.micEncoder) {
                LOGE("mic audio: no Opus encoder for ctrl %u", (unsigned)idx);
                return JNI_FALSE;
            }
        }
        opusBytes = ca.micEncoder->encode(pcm, dish_audio::AUDIO_FRAME_SAMPLES,
                                          payload + dish_wire::AUDIO_WIRE_HEADER_BYTES,
                                          dish_wire::AUDIO_WIRE_MAX_OPUS_BYTES);
        if (opusBytes == 0) return JNI_FALSE;
        seq = ca.micSeq++; // u16, wraps by design (contract §Controller audio)
    }
    dish_wire::encodeAudioFrameHeader(payload, idx, seq);
    const uint16_t payloadLen =
        static_cast<uint16_t>(dish_wire::AUDIO_WIRE_HEADER_BYTES + opusBytes);
    return sendEncrypted(s.get(), MSG_MIC_AUDIO, payload, payloadLen) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_startHeartbeat(JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return;
    if (s->heartbeatRunning.load()) return;
    s->heartbeatRunning.store(true);
    s->missedAcks.store(0);
    s->connectionAlive.store(true);
    s->heartbeatThread = std::thread(heartbeatLoop, s);
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_stopHeartbeat(JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return;
    s->heartbeatRunning.store(false);
    if (s->heartbeatThread.joinable()) s->heartbeatThread.join();
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_isConnectionAlive(
    JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return JNI_FALSE;
    return s->connectionAlive.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_getServerEpoch(JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return -1;
    return s->serverEpoch.load(std::memory_order_acquire);
}

JNIEXPORT jint JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_getActiveBitmap(JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return -1;
    return s->activeBitmap.load(std::memory_order_acquire);
}

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_getSessionCloseReason(
    JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return -1;
    return s->closeReason.load(std::memory_order_acquire);
}

JNIEXPORT jlong JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_getSendCounter(JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return 0;
    return (jlong)dish_counter::sendCounterView(s->counter);
}

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_getVigemAvailable(
    JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return -1;
    return (jint)s->vigemAvailable.load(std::memory_order_acquire);
}

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_getActiveControllerCount(
    JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return -1;
    return (jint)s->activeControllerCount.load(std::memory_order_acquire);
}

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_receiveAck(JNIEnv* env,
                                                                                     jobject,
                                                                                     jint handle) {
    auto s = getSession(handle);
    if (!s || s->udpSock < 0) return -1;
    // A whole datagram: MSG_SPEAKER_AUDIO carries an Opus packet, and a short
    // read would truncate the ciphertext into an AEAD failure rather than an
    // honest error (satellite docs/contract.md §Packet format).
    uint8_t buf[dish_wire::MAX_DATAGRAM_BYTES];
    static_assert(sizeof(buf) >= dish_wire::OUTER_HEADER_BYTES + dish_wire::INNER_HEADER_BYTES +
                                     dish_wire::MAX_INNER_PAYLOAD_BYTES + dish_wire::AEAD_TAG_BYTES,
                  "recv buffer must hold a maximal datagram, framing and tag included");
    struct sockaddr_in from = {};
    socklen_t fl = sizeof(from);
    ssize_t n = recvfrom(s->udpSock, buf, sizeof(buf), 0, (struct sockaddr*)&from, &fl);
    if (n < 0) {
        // The SO_RCVTIMEO tick (or a signal) is routine pacing. Any other
        // recv error returns instantly and would do so forever, so the Kotlin
        // drain loop must treat it as terminal or it busy-spins a core.
        return (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) ? 0 : -1;
    }
    if (n < 8) return 0;

    if (memcmp(buf, s->token, 4) != 0) return 0;

    uint32_t ctr = ((uint32_t)buf[4] << 24) | ((uint32_t)buf[5] << 16) | ((uint32_t)buf[6] << 8) |
                   (uint32_t)buf[7];

    // Downstream replay guard, mirroring the server's (first packet exempt
    // while lastRxCounter == 0).
    const uint32_t lastRx = s->lastRxCounter.load(std::memory_order_relaxed);
    if (ctr <= lastRx && lastRx != 0) return 0;

    uint8_t nonce[12] = {};
    nonce[0] = CRYPTO_DIR_SERVER_TO_CLIENT;
    putBE32(nonce + 8, ctr);

    // Must size with buf not the message-set max: bound holds structurally, not by luck.
    uint8_t decrypted[sizeof(buf)];
    unsigned long long decLen = 0;
    size_t cipherLen = (size_t)n - 8;
    if (crypto_aead_chacha20poly1305_ietf_decrypt(decrypted, &decLen, nullptr, buf + 8, cipherLen,
                                                  s->token, 4, nonce, s->key) != 0) {
        return 0;
    }
    s->lastRxCounter.store(ctr, std::memory_order_relaxed);

    if (decLen < 4) return 0;
    uint16_t msgType = ((uint16_t)decrypted[0] << 8) | decrypted[1];
    uint16_t msgLen = ((uint16_t)decrypted[2] << 8) | decrypted[3];

    if (msgType == MSG_HEARTBEAT_ACK) {
        if (hotpath::enabled()) {
            const int64_t sent = s->lastPingNs.exchange(0, std::memory_order_relaxed);
            if (sent != 0) hotpath::addRttSample(sent, hotpath::nowMonotonicNs());
        }
        s->missedAcks.store(0);
        s->connectionAlive.store(true);
        // Enriched ack: backendAvailable(1) + totalActive(1) + epoch(u16 BE) +
        // active-controller bitmap(u16 BE). The epoch/bitmap pair drives the
        // Kotlin-side reconcile against involuntary server-side topology loss.
        if (msgLen >= 6 && decLen >= 10) {
            uint8_t backend = decrypted[4];
            uint8_t count = decrypted[5];
            int32_t epoch = ((int32_t)decrypted[6] << 8) | (int32_t)decrypted[7];
            int32_t bitmap = ((int32_t)decrypted[8] << 8) | (int32_t)decrypted[9];
            s->vigemAvailable.store((int8_t)(backend ? 1 : 0), std::memory_order_release);
            s->activeControllerCount.store((int8_t)count, std::memory_order_release);
            s->serverEpoch.store(epoch, std::memory_order_release);
            s->activeBitmap.store(bitmap, std::memory_order_release);
        }
    } else if (msgType == MSG_SESSION_CLOSE && msgLen >= 1 && decLen >= 5) {
        // Authenticated best-effort close notify; terminal for this session.
        const int32_t reason = (int32_t)decrypted[4];
        s->closeReason.store(reason, std::memory_order_release);
        s->connectionAlive.store(false, std::memory_order_release);
        LOGI("Session %d close notify: reason=%d", handle, reason);
    } else if (msgType == MSG_RUMBLE && msgLen == 7 && decLen >= 11) {
        // 7B fixed payload: ctrlIdx, strong BE16, weak BE16, durMs BE16.
        if (g_rumbleBridgeClass == nullptr || g_rumbleDispatchMethod == nullptr) return 1;
        const jint ctrlIdx = (jint)decrypted[4];
        const jint strong = ((jint)decrypted[5] << 8) | (jint)decrypted[6];
        const jint weakMag = ((jint)decrypted[7] << 8) | (jint)decrypted[8];
        const jint durMs = ((jint)decrypted[9] << 8) | (jint)decrypted[10];
        env->CallStaticVoidMethod(g_rumbleBridgeClass, g_rumbleDispatchMethod, handle, ctrlIdx,
                                  strong, weakMag, durMs);
        if (env->ExceptionCheck()) env->ExceptionClear();
    } else if (msgType == MSG_LIGHTBAR && msgLen == 4 && decLen >= 8) {
        // Routed like rumble: the FeedbackRouter lands it on a Direct-claimed
        // DS4/DualSense; every other target has no LED sink and drops it.
        if (g_feedbackBridgeClass == nullptr || g_feedbackLightbarMethod == nullptr) return 1;
        const dish_wire::LightbarPayload lb = dish_wire::decodeLightbarPayload(decrypted + 4);
        env->CallStaticVoidMethod(g_feedbackBridgeClass, g_feedbackLightbarMethod, handle,
                                  (jint)lb.ctrlIdx, (jint)lb.r, (jint)lb.g, (jint)lb.b);
        if (env->ExceptionCheck()) env->ExceptionClear();
    } else if (msgType == MSG_TRIGGER_EFFECTS &&
               msgLen == 1 + dish_wire::TRIGGER_EFFECTS_PAYLOAD_BYTES &&
               decLen >= (unsigned long long)(4 + 1 + dish_wire::TRIGGER_EFFECTS_PAYLOAD_BYTES)) {
        if (g_feedbackBridgeClass == nullptr || g_feedbackTriggerEffectsMethod == nullptr) return 1;
        const jint ctrlIdx = (jint)decrypted[4];
        jbyteArray blocks = env->NewByteArray(dish_wire::TRIGGER_EFFECTS_PAYLOAD_BYTES);
        if (blocks == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return 1;
        }
        env->SetByteArrayRegion(blocks, 0, dish_wire::TRIGGER_EFFECTS_PAYLOAD_BYTES,
                                (const jbyte*)(decrypted + 5));
        env->CallStaticVoidMethod(g_feedbackBridgeClass, g_feedbackTriggerEffectsMethod, handle,
                                  ctrlIdx, blocks);
        env->DeleteLocalRef(blocks);
        if (env->ExceptionCheck()) env->ExceptionClear();
    } else if (msgType == MSG_PLAYER_LEDS && msgLen == 2 && decLen >= 6) {
        if (g_feedbackBridgeClass == nullptr || g_feedbackPlayerLedsMethod == nullptr) return 1;
        env->CallStaticVoidMethod(g_feedbackBridgeClass, g_feedbackPlayerLedsMethod, handle,
                                  (jint)decrypted[4], (jint)decrypted[5]);
        if (env->ExceptionCheck()) env->ExceptionClear();
    } else if (msgType == MSG_SPEAKER_AUDIO && msgLen >= dish_wire::AUDIO_WIRE_MIN_PAYLOAD_BYTES &&
               decLen >= 4 + (unsigned long long)msgLen) {
        // Queued, not decoded here: see the audio dispatch thread. With no
        // playback engine installed there is nothing to decode FOR, so the
        // frame is dropped before it costs a copy (same shape as the feedback
        // arms, which bail on a missing bridge class).
        if (g_speakerAudioBridgeClass == nullptr) return 1;
        const dish_wire::AudioFrameHeader h = dish_wire::decodeAudioFrameHeader(decrypted + 4);
        const uint8_t* opus = decrypted + 4 + dish_wire::AUDIO_WIRE_HEADER_BYTES;
        const size_t opusLen = msgLen - dish_wire::AUDIO_WIRE_HEADER_BYTES;
        SpeakerFrame f;
        f.session = s;
        f.handle = handle;
        f.ctrlIdx = h.ctrlIdx;
        f.seq = h.seq;
        f.opus.assign(opus, opus + opusLen);
        enqueueSpeakerFrame(std::move(f));
    } else if (msgType == MSG_MIC_LED && msgLen == dish_wire::MIC_LED_PAYLOAD_BYTES &&
               decLen >= 4 + dish_wire::MIC_LED_PAYLOAD_BYTES) {
        // Routed like the lightbar: the FeedbackRouter lands it on whichever
        // sink the slot has. A state we do not know can only come from a host
        // speaking something newer, and rendering it as a guess would be worse
        // than rendering nothing, so it is dropped here rather than clamped.
        const dish_wire::MicLedPayload led = dish_wire::decodeMicLedPayload(decrypted + 4);
        if (!dish_wire::micLedStateValid(led.state)) return 1;
        if (g_feedbackBridgeClass == nullptr || g_feedbackMicLedMethod == nullptr) return 1;
        env->CallStaticVoidMethod(g_feedbackBridgeClass, g_feedbackMicLedMethod, handle,
                                  (jint)led.ctrlIdx, (jint)led.state);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    return 1;
}

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_discoverServers(
    JNIEnv* env, jobject, jint discPort, jint timeoutMs) {
    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) return env->NewStringUTF("[]");
    int reuse = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    struct sockaddr_in ba = {};
    ba.sin_family = AF_INET;
    ba.sin_port = htons((uint16_t)discPort);
    ba.sin_addr.s_addr = INADDR_ANY;
    if (bind(sock, (struct sockaddr*)&ba, sizeof(ba)) < 0) {
        LOGE("discovery bind() failed: %s", strerror(errno));
        close(sock);
        return env->NewStringUTF("[]");
    }
    struct timeval tv = {0, 300000};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    std::string result = "[";
    bool first = true;
    std::vector<std::string> seen;
    const uint64_t deadline = nowMs() + (uint64_t)timeoutMs;
    while (nowMs() < deadline) {
        char buf[1024];
        struct sockaddr_in from = {};
        socklen_t fl = sizeof(from);
        int n = (int)recvfrom(sock, buf, sizeof(buf) - 1, 0, (struct sockaddr*)&from, &fl);
        if (n <= 0) continue;
        buf[n] = '\0';
        if (!strstr(buf, "\"service\":\"satellite\"")) continue;
        char ip[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &from.sin_addr, ip, sizeof(ip));
        bool dup = false;
        for (auto& s : seen) {
            if (s == ip) {
                dup = true;
                break;
            }
        }
        if (dup) continue;
        seen.push_back(ip);
        std::string beacon(buf, (size_t)n);
        size_t cp = beacon.rfind('}');
        if (cp == std::string::npos) continue;
        std::string entry = beacon.substr(0, cp) + ",\"ip\":\"" + ip + "\"}";
        if (!first) result += ",";
        first = false;
        result += entry;
        LOGI("Discovered: %s", ip);
    }
    close(sock);
    result += "]";
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_bindPhysicalSlotSatellite(
    JNIEnv*, jobject, jint deviceId, jint sessionHandle, jint controllerIndex) {
    {
        std::lock_guard<std::mutex> lock(g_slotsMtx);
        auto& b = g_slots[deviceId];
        b.kind = SLOT_SATELLITE;
        b.sessionHandle = sessionHandle;
        b.controllerIndex = controllerIndex;
        b.bridgeConnectionId.clear();
    }
    syncSlotBaseline(deviceId);
}

static void bindPhysicalSlotBridge(JNIEnv* env, jint deviceId, jstring connectionId, SlotKind kind,
                                   jint controllerIndex) {
    const char* cstr = env->GetStringUTFChars(connectionId, nullptr);
    std::string copy = cstr ? std::string(cstr) : std::string();
    if (cstr) env->ReleaseStringUTFChars(connectionId, cstr);
    {
        std::lock_guard<std::mutex> lock(g_slotsMtx);
        auto& b = g_slots[deviceId];
        b.kind = kind;
        b.sessionHandle = -1;
        b.controllerIndex = controllerIndex;
        b.bridgeConnectionId = std::move(copy);
    }
    syncSlotBaseline(deviceId);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_bindPhysicalSlotBluetooth(
    JNIEnv* env, jobject, jint deviceId, jstring connectionId) {
    bindPhysicalSlotBridge(env, deviceId, connectionId, SLOT_BLUETOOTH, -1);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_bindPhysicalSlotMoonlight(
    JNIEnv* env, jobject, jint deviceId, jstring connectionId, jint controllerNumber) {
    bindPhysicalSlotBridge(env, deviceId, connectionId, SLOT_MOONLIGHT, controllerNumber);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_unbindPhysicalSlot(
    JNIEnv*, jobject, jint deviceId) {
    std::lock_guard<std::mutex> lock(g_slotsMtx);
    g_slots.erase(deviceId);
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_clearAllPhysicalSlots(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_slotsMtx);
    g_slots.clear();
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_forgetPhysicalDevice(
    JNIEnv*, jobject, jint deviceId) {
    dispatch::forgetDevice((int32_t)deviceId);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_setDeviceDeadzones(
    JNIEnv*, jobject, jint deviceId, jfloat flatX, jfloat flatY, jfloat flatZ, jfloat flatRZ) {
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    auto& s = g_devices[deviceId];
    s.flatX = flatX;
    s.flatY = flatY;
    s.flatZ = flatZ;
    s.flatRZ = flatRZ;
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_setDeviceQuirk(
    JNIEnv*, jobject, jint deviceId, jint quirk) {
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    g_devices[deviceId].quirk = (uint8_t)(quirk & 0xFF);
}

// Activity-level dispatch is needed because GameActivity's SurfaceView sits below the
// input layer that synthesizes DPAD keys from stick motion on some controllers.
JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_processGamepadKeyEvent(
    JNIEnv*, jobject, jint deviceId, jint /*source*/, jint action, jint keyCode) {
    // Source bits are unreliable; gate on the mapped-keycode check instead.
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    auto it = g_devices.find(deviceId);
    uint8_t quirk = it != g_devices.end() ? it->second.quirk : 0;
    bool isMappedKey = (quirk & gamepad::QUIRK_SWITCH_LAYOUT)
                           ? gamepad::switchLayoutConsumesKey(keyCode)
                           : (keyCode == AKEYCODE_BUTTON_L2 || keyCode == AKEYCODE_BUTTON_R2 ||
                              keyCode == AKEYCODE_BUTTON_7 || keyCode == AKEYCODE_BUTTON_8) ||
                                 gamepad::keycodeToXusb(keyCode) != 0;
    if (!isMappedKey) return JNI_FALSE;
    if (action != AKEY_EVENT_ACTION_DOWN && action != AKEY_EVENT_ACTION_UP) return JNI_FALSE;
    g_frameworkEventCounts[deviceId]++;
    auto& state = g_devices[deviceId];
    if (gamepad::applyKey(state, keyCode, action == AKEY_EVENT_ACTION_DOWN)) {
        publishIfChanged(deviceId, state);
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_processGamepadMotionEvent(
    JNIEnv*, jobject, jint deviceId, jint source, jint action, jfloat x, jfloat y, jfloat z,
    jfloat rz, jfloat rx, jfloat ry, jfloat hatX, jfloat hatY, jfloat lTrigger, jfloat rTrigger,
    jfloat brake, jfloat gas) {
    if ((source & AINPUT_SOURCE_JOYSTICK) != AINPUT_SOURCE_JOYSTICK) return JNI_FALSE;
    int32_t maskedAction = action & AMOTION_EVENT_ACTION_MASK;
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    auto& state = g_devices[deviceId];
    if (maskedAction == AMOTION_EVENT_ACTION_CANCEL) {
        gamepad::resetState(state);
        publishIfChanged(deviceId, state);
        return JNI_TRUE;
    }
    if (maskedAction != AMOTION_EVENT_ACTION_MOVE) return JNI_TRUE;
    g_frameworkEventCounts[deviceId]++;
    // Right-stick layout varies (Z/RZ vs RX/RY); pick the larger-magnitude pair.
    float rightX = std::fabs(z) >= std::fabs(rx) ? z : rx;
    float rightY = std::fabs(rz) >= std::fabs(ry) ? rz : ry;
    float lt = std::max(lTrigger, brake);
    float rt = std::max(rTrigger, gas);
    gamepad::applyAxes(state, x, y, rightX, rightY, lt, rt, hatX, hatY);
    publishIfChanged(deviceId, state);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_releaseAllPhysicalReports(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    for (auto& kv : g_devices) {
        gamepad::resetState(kv.second);
        publishIfChanged(kv.first, kv.second);
    }
}

// Class registration cannot live in JNI_OnLoad: FindClass there uses the system loader, not the
// app's.
JNIEXPORT void JNICALL Java_com_tinkernorth_dish_hotpath_input_BluetoothGamepadBridge_nativeInstall(
    JNIEnv* env, jclass bridgeCls) {
    if (g_btBridgeClass == nullptr) { g_btBridgeClass = (jclass)env->NewGlobalRef(bridgeCls); }
    if (g_btDispatchMethod == nullptr) {
        g_btDispatchMethod = env->GetStaticMethodID(g_btBridgeClass, "dispatchReport",
                                                    "(Ljava/lang/String;IIIIIII)V");
        if (g_btDispatchMethod == nullptr) {
            LOGE("BluetoothGamepadBridge.dispatchReport not found");
            env->ExceptionClear();
        }
    }
    startBridgeDispatchThread();
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_hotpath_input_MoonlightGamepadBridge_nativeInstall(
    JNIEnv* env, jclass bridgeCls) {
    if (g_moonlightBridgeClass == nullptr) {
        g_moonlightBridgeClass = (jclass)env->NewGlobalRef(bridgeCls);
    }
    if (g_moonlightDispatchMethod == nullptr) {
        g_moonlightDispatchMethod = env->GetStaticMethodID(g_moonlightBridgeClass, "dispatchReport",
                                                           "(Ljava/lang/String;IIIIIIII)V");
        if (g_moonlightDispatchMethod == nullptr) {
            LOGE("MoonlightGamepadBridge.dispatchReport not found");
            env->ExceptionClear();
        }
    }
    if (g_moonlightMotionMethod == nullptr) {
        g_moonlightMotionMethod = env->GetStaticMethodID(g_moonlightBridgeClass, "dispatchMotion",
                                                         "(Ljava/lang/String;IIIIIIII)V");
        if (g_moonlightMotionMethod == nullptr) {
            LOGE("MoonlightGamepadBridge.dispatchMotion not found");
            env->ExceptionClear();
        }
    }
    if (g_moonlightTouchMethod == nullptr) {
        g_moonlightTouchMethod = env->GetStaticMethodID(g_moonlightBridgeClass, "dispatchTouch",
                                                        "(Ljava/lang/String;IZIIIZIIIZ)V");
        if (g_moonlightTouchMethod == nullptr) {
            LOGE("MoonlightGamepadBridge.dispatchTouch not found");
            env->ExceptionClear();
        }
    }
    startBridgeDispatchThread();
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_hotpath_input_RumbleBridge_nativeInstall(JNIEnv* env, jclass bridgeCls) {
    if (g_rumbleBridgeClass == nullptr) {
        g_rumbleBridgeClass = (jclass)env->NewGlobalRef(bridgeCls);
    }
    if (g_rumbleDispatchMethod == nullptr) {
        g_rumbleDispatchMethod =
            env->GetStaticMethodID(g_rumbleBridgeClass, "dispatchRumble", "(IIIII)V");
        if (g_rumbleDispatchMethod == nullptr) {
            LOGE("RumbleBridge.dispatchRumble not found");
            env->ExceptionClear();
        }
    }
}

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_attachUsbDevice(
    JNIEnv*, jobject, jint fd, jint vid, jint pid, jint interfaceNumber, jint epIn,
    jint epInMaxPacket, jint epOut, jint ifClass, jint ifSubclass, jint ifProtocol) {
    int dupFd = dup(fd);
    if (dupFd < 0) {
        LOGE("attachUsbDevice: dup(%d) failed: %s", fd, strerror(errno));
        return 0;
    }
    usbhost::AttachResult r = usbhost::attachDevice(
        dupFd, (uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF), interfaceNumber,
        (uint8_t)(epIn & 0xFF), (uint16_t)(epInMaxPacket & 0xFFFF), (uint8_t)(epOut & 0xFF),
        (uint8_t)(ifClass & 0xFF), (uint8_t)(ifSubclass & 0xFF), (uint8_t)(ifProtocol & 0xFF));
    return r.ok ? (jint)r.syntheticDeviceId : 0;
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_detachUsbDevice(
    JNIEnv*, jobject, jint syntheticDeviceId) {
    usbhost::detachDevice((int32_t)syntheticDeviceId);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendUsbRumble(
    JNIEnv*, jobject, jint syntheticDeviceId, jint strong, jint weak) {
    usbhost::sendRumble((int32_t)syntheticDeviceId, (uint16_t)(strong & 0xFFFF),
                        (uint16_t)(weak & 0xFFFF));
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_hotpath_input_FeedbackBridge_nativeInstall(JNIEnv* env, jclass cls) {
    if (g_feedbackBridgeClass == nullptr) {
        g_feedbackBridgeClass = (jclass)env->NewGlobalRef(cls);
    }
    if (g_feedbackLightbarMethod == nullptr) {
        g_feedbackLightbarMethod =
            env->GetStaticMethodID(g_feedbackBridgeClass, "dispatchLightbar", "(IIIII)V");
        if (g_feedbackLightbarMethod == nullptr) {
            LOGE("FeedbackBridge.dispatchLightbar not found");
            env->ExceptionClear();
        }
    }
    if (g_feedbackTriggerEffectsMethod == nullptr) {
        g_feedbackTriggerEffectsMethod =
            env->GetStaticMethodID(g_feedbackBridgeClass, "dispatchTriggerEffects", "(II[B)V");
        if (g_feedbackTriggerEffectsMethod == nullptr) {
            LOGE("FeedbackBridge.dispatchTriggerEffects not found");
            env->ExceptionClear();
        }
    }
    if (g_feedbackPlayerLedsMethod == nullptr) {
        g_feedbackPlayerLedsMethod =
            env->GetStaticMethodID(g_feedbackBridgeClass, "dispatchPlayerLeds", "(III)V");
        if (g_feedbackPlayerLedsMethod == nullptr) {
            LOGE("FeedbackBridge.dispatchPlayerLeds not found");
            env->ExceptionClear();
        }
    }
    if (g_feedbackMicLedMethod == nullptr) {
        g_feedbackMicLedMethod =
            env->GetStaticMethodID(g_feedbackBridgeClass, "dispatchMicLed", "(III)V");
        if (g_feedbackMicLedMethod == nullptr) {
            LOGE("FeedbackBridge.dispatchMicLed not found");
            env->ExceptionClear();
        }
    }
}

// The pad's own mic-mute button, going UP. FeedbackBridge carries what the host asked the pad to
// do; this is the pad telling the app what the user did to it, and the only signal on the USB
// input path that Kotlin needs by event rather than by report.
JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_hotpath_input_MicMuteBridge_nativeInstall(JNIEnv* env, jclass cls) {
    if (g_micMuteBridgeClass == nullptr) { g_micMuteBridgeClass = (jclass)env->NewGlobalRef(cls); }
    if (g_micMutePadMethod == nullptr) {
        g_micMutePadMethod = env->GetStaticMethodID(g_micMuteBridgeClass, "dispatchPadMicMute",
                                                    "(IZ)V");
        if (g_micMutePadMethod == nullptr) {
            LOGE("MicMuteBridge.dispatchPadMicMute not found");
            env->ExceptionClear();
        }
    }
    startBridgeDispatchThread();
}

// Speaker audio has its own bridge rather than riding FeedbackBridge: it is a
// continuous stream with a queue and a thread behind it, not a coalesced
// last-value-wins signal, and its sink is an audio engine rather than an
// actuator router.
JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_hotpath_audio_SpeakerAudioBridge_nativeInstall(JNIEnv* env, jclass cls) {
    if (g_speakerAudioBridgeClass == nullptr) {
        g_speakerAudioBridgeClass = (jclass)env->NewGlobalRef(cls);
    }
    if (g_speakerAudioFrameMethod == nullptr) {
        g_speakerAudioFrameMethod =
            env->GetStaticMethodID(g_speakerAudioBridgeClass, "dispatchSpeakerFrame", "(II[SZ)V");
        if (g_speakerAudioFrameMethod == nullptr) {
            LOGE("SpeakerAudioBridge.dispatchSpeakerFrame not found");
            env->ExceptionClear();
        }
    }
    startAudioDispatchThread();
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendUsbTriggerRumble(
    JNIEnv*, jobject, jint syntheticDeviceId, jint leftMag, jint rightMag) {
    usbhost::sendTriggerRumble((int32_t)syntheticDeviceId, (uint16_t)(leftMag & 0xFFFF),
                               (uint16_t)(rightMag & 0xFFFF));
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendUsbLightbar(
    JNIEnv*, jobject, jint syntheticDeviceId, jint r, jint g, jint b) {
    usbhost::sendLightbar((int32_t)syntheticDeviceId, (uint8_t)(r & 0xFF), (uint8_t)(g & 0xFF),
                          (uint8_t)(b & 0xFF));
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendUsbPlayerLeds(
    JNIEnv*, jobject, jint syntheticDeviceId, jint ledMask) {
    usbhost::sendPlayerLeds((int32_t)syntheticDeviceId, (uint8_t)(ledMask & 0xFF));
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendUsbTriggerEffects(
    JNIEnv* env, jobject, jint syntheticDeviceId, jbyteArray blocks) {
    if (blocks == nullptr) return;
    if (env->GetArrayLength(blocks) < 2 * usbparsers::TRIGGER_EFFECT_BLOCK_LEN) return;
    uint8_t buf[2 * usbparsers::TRIGGER_EFFECT_BLOCK_LEN];
    env->GetByteArrayRegion(blocks, 0, sizeof(buf), (jbyte*)buf);
    // Wire order left then right (contract 0x0010).
    usbhost::sendTriggerEffects((int32_t)syntheticDeviceId, buf,
                                buf + usbparsers::TRIGGER_EFFECT_BLOCK_LEN);
}

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_lookupKnownModelName(
    JNIEnv* env, jobject, jint vid, jint pid) {
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF));
    return env->NewStringUTF(k ? k->name : "");
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_isKnownFastLaneModel(
    JNIEnv*, jobject, jint vid, jint pid) {
    return usbparsers::isVerifiedFastLane((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF))
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_modelHasImu(
    JNIEnv*, jobject, jint vid, jint pid) {
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF));
    if (!k) return JNI_FALSE;
    return usbparsers::parserHasImu(k->parser) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_modelExpectsFrameworkGamepad(JNIEnv*, jobject,
                                                                                jint vid,
                                                                                jint pid) {
    return usbparsers::modelExpectsFrameworkGamepad((uint16_t)(vid & 0xFFFF),
                                                    (uint16_t)(pid & 0xFFFF))
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_modelHasRumble(
    JNIEnv*, jobject, jint vid, jint pid) {
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF));
    if (!k) return JNI_FALSE;
    return usbparsers::parserHasRumble(k->parser) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_modelHasLightbar(
    JNIEnv*, jobject, jint vid, jint pid) {
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF));
    if (!k) return JNI_FALSE;
    return usbparsers::parserHasLightbar(k->parser) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_modelHasPlayerLeds(
    JNIEnv*, jobject, jint vid, jint pid) {
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF));
    if (!k) return JNI_FALSE;
    return usbparsers::parserHasPlayerLeds(k->parser) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_modelHasTriggerEffects(JNIEnv*, jobject,
                                                                          jint vid, jint pid) {
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF));
    if (!k) return JNI_FALSE;
    return usbparsers::parserHasTriggerEffects(k->parser) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_modelHasTriggerRumble(
    JNIEnv*, jobject, jint vid, jint pid) {
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF));
    if (!k) return JNI_FALSE;
    return usbparsers::parserHasTriggerRumble(k->parser) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_modelHasTouchpad(
    JNIEnv*, jobject, jint vid, jint pid) {
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF));
    if (!k) return JNI_FALSE;
    return usbparsers::parserHasTouchpad(k->parser) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_modelFrameworkRumbleUnreliable(JNIEnv*, jobject,
                                                                                  jint vid,
                                                                                  jint pid) {
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF));
    if (!k) return JNI_FALSE;
    return usbparsers::parserFrameworkRumbleUnreliable(k->parser) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_getDeviceUrbCount(
    JNIEnv*, jobject, jint deviceId) {
    return (jlong)usbhost::getUrbCount((int32_t)deviceId);
}

JNIEXPORT jlong JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_getDeviceMotionCount(
    JNIEnv*, jobject, jint deviceId) {
    return (jlong)usbhost::getMotionCount((int32_t)deviceId);
}

// Opt-in hot-path latency benchmark (stage 1 USB-direct + stage 2 heartbeat RTT).
// Off by default; see hotpath_latency.h and satellite tools/bench/README.md.
JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_setHotPathBench(JNIEnv*, jobject, jboolean on) {
    hotpath::setEnabled(on == JNI_TRUE);
}

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_hotPathBenchJson(
    JNIEnv* env, jobject, jboolean reset) {
    return env->NewStringUTF(hotpath::statsJson(reset == JNI_TRUE).c_str());
}

// Densify heartbeat pings for RTT measurement while the diagnostics latency panel is
// open. The RTT window is dropped on enable so the readout reflects probe-era samples,
// not the idle-radio tail accumulated before the panel opened.
JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_setLatencyProbe(JNIEnv*, jobject, jboolean on) {
    g_heartbeatIntervalMs.store(on == JNI_TRUE ? HEARTBEAT_INTERVAL_PROBE_MS
                                               : HEARTBEAT_INTERVAL_DEFAULT_MS,
                                std::memory_order_relaxed);
    if (on == JNI_TRUE) hotpath::resetRttWindow();
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_setInputInspection(
    JNIEnv*, jobject, jboolean on) {
    dispatch::g_inspect.store(on == JNI_TRUE, std::memory_order_relaxed);
}

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_deviceStateJson(
    JNIEnv* env, jobject, jint deviceId) {
    char buf[512];
    size_t n = 0;
    {
        std::lock_guard<std::mutex> lock(g_devicesMtx);
        auto it = g_devices.find((int32_t)deviceId);
        if (it != g_devices.end()) n = gamepad::formatDeviceStateJson(it->second, buf, sizeof(buf));
    }
    return env->NewStringUTF(n > 0 ? buf : "");
}

JNIEXPORT jlong JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_getDeviceInputEventCount(
    JNIEnv*, jobject, jint deviceId) {
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    auto it = g_frameworkEventCounts.find((int32_t)deviceId);
    return it == g_frameworkEventCounts.end() ? 0 : (jlong)it->second;
}
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}
