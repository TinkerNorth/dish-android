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
#include <atomic>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>
#include <sodium.h>

#include "audio_codec.h"
#include "audio_jitter.h"
#include "bridge_connection_ids.h"
#include "dispatch.h"
#include "gamepad_input.h"
#include "hotpath_latency.h"
#include "receive_path.h"
#include "send_counter.h"
#include "send_path.h"
#include "session_table.h"
#include "slot_table.h"
#include "thread_priority.h"
#include "usb_host.h"
#include "usb_parsers.h"
#include "wire_encoders.h"

#define TAG "SatelliteJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using dish_bridge::BridgeConnectionId;
using dish_session::CTRL_INDEX_MASK;
using dish_slots::BridgeReport;
using dish_slots::SlotKind;

static std::atomic<int> g_heartbeatIntervalMs{dish_send::HEARTBEAT_INTERVAL_DEFAULT_MS};

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

static bool bridgeHasWork() {
    return !g_bridgeDispatchRunning.load(std::memory_order_relaxed) || !g_bridgeQueue.empty() ||
           !g_micMuteQueue.empty();
}

static bool bridgeIsDrainedAndStopping() {
    return !g_bridgeDispatchRunning.load(std::memory_order_relaxed) && g_bridgeQueue.empty() &&
           g_micMuteQueue.empty();
}

static void callMicMuteUpcall(JNIEnv* env, const int32_t deviceId, const bool muted) {
    if (g_micMuteBridgeClass == nullptr || g_micMutePadMethod == nullptr) return;
    env->CallStaticVoidMethod(g_micMuteBridgeClass, g_micMutePadMethod, (jint)deviceId,
                              (jboolean)muted);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// Which static method carries this report. Answers false when the class or the method never
// resolved, which is the honest answer for a bridge Kotlin has not installed.
static bool bridgeTargetFor(const BridgeReport& r, jclass& outCls, jmethodID& outMethod) {
    const bool isMoonlight = r.kind == dish_slots::SLOT_MOONLIGHT;
    outCls = isMoonlight ? g_moonlightBridgeClass : g_btBridgeClass;
    outMethod = isMoonlight ? g_moonlightDispatchMethod : g_btDispatchMethod;
    if (isMoonlight && r.payload == BridgeReport::MOTION) outMethod = g_moonlightMotionMethod;
    if (isMoonlight && r.payload == BridgeReport::TOUCH) outMethod = g_moonlightTouchMethod;
    return outCls != nullptr && outMethod != nullptr;
}

// A Moonlight session carries up to four pads on one stream, so its upcall also names which pad
// the report belongs to; the Bluetooth link is one pad by nature.
static void callBridgeUpcall(JNIEnv* env, jclass cls, jmethodID method, jstring connId,
                             const BridgeReport& r) {
    const bool isMoonlight = r.kind == dish_slots::SLOT_MOONLIGHT;
    const bool isMotion = isMoonlight && r.payload == BridgeReport::MOTION;
    const bool isTouch = isMoonlight && r.payload == BridgeReport::TOUCH;

    if (isMotion) {
        env->CallStaticVoidMethod(cls, method, connId, (jint)r.controllerNumber, (jint)r.gyro[0],
                                  (jint)r.gyro[1], (jint)r.gyro[2], (jint)r.accel[0],
                                  (jint)r.accel[1], (jint)r.accel[2], (jint)r.timestampDeltaUs);
        return;
    }
    if (isTouch) {
        env->CallStaticVoidMethod(
            cls, method, connId, (jint)r.controllerNumber, (jboolean)r.touch.f0Active,
            (jint)r.touch.f0Id, (jint)r.touch.f0X, (jint)r.touch.f0Y, (jboolean)r.touch.f1Active,
            (jint)r.touch.f1Id, (jint)r.touch.f1X, (jint)r.touch.f1Y, (jboolean)r.touch.clickDown);
        return;
    }
    if (isMoonlight) {
        env->CallStaticVoidMethod(cls, method, connId, (jint)r.controllerNumber, (jint)r.wButtons,
                                  (jint)r.bLT, (jint)r.bRT, (jint)r.sLX, (jint)r.sLY, (jint)r.sRX,
                                  (jint)r.sRY);
        return;
    }
    env->CallStaticVoidMethod(cls, method, connId, (jint)r.wButtons, (jint)r.bLT, (jint)r.bRT,
                              (jint)r.sLX, (jint)r.sLY, (jint)r.sRX, (jint)r.sRY);
}

// The connection-id cache's refs: a global ref to a Java string per bind, made and released on the
// bridge thread, the one thread that upcalls with them.
struct JniConnectionRefs {
    JNIEnv* const env;

    jstring make(const std::string& id) const {
        const jstring local = env->NewStringUTF(id.c_str());
        if (local == nullptr) return nullptr;
        const auto global = static_cast<jstring>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        return global;
    }

    void release(const jstring ref) const { env->DeleteGlobalRef(ref); }
};

// Eight binds at once keep a string each; a ninth takes an entry over in turn, which stays correct.
static constexpr size_t BRIDGE_CONNECTION_REFS = 8;

// Bridge thread only.
static dish_bridge::ConnectionRefCache<jstring, BRIDGE_CONNECTION_REFS> g_bridgeConnectionRefs;

static void dispatchBridgeReport(JNIEnv* env, const BridgeReport& r) {
    jclass cls = nullptr;
    jmethodID method = nullptr;
    if (!bridgeTargetFor(r, cls, method)) return;

    JniConnectionRefs refs{env};
    const jstring connId = g_bridgeConnectionRefs.refFor(r.connectionId, refs);
    if (connId == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    callBridgeUpcall(env, cls, method, connId, r);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// Attaches the calling thread to the JVM for the life of a dispatch loop. Null means the attach
// failed, which that loop cannot recover from.
static JNIEnv* attachDispatchThread(const char* what) {
    JNIEnv* env = nullptr;
    const bool attached = g_jvm != nullptr && g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK;
    if (attached && env != nullptr) return env;
    LOGE("%s: AttachCurrentThread failed", what);
    return nullptr;
}

// What one wait on the bridge queue produced. Nothing means a spurious wake with both queues
// empty, which is not a reason to stop.
enum class BridgeWork { Report, Mute, Nothing, Stop };

// Both queues are drained under one lock, and the lock is gone by the time the caller makes its
// JVM upcall. Mute edges come first: they are rarer and they gate capture, so they must not wait
// behind a queue of input reports.
static BridgeWork takeNextBridgeWork(BridgeReport& report, std::pair<int32_t, bool>& mute) {
    std::unique_lock<std::mutex> lock(g_bridgeQueueMtx);
    g_bridgeQueueCv.wait(lock, bridgeHasWork);
    if (bridgeIsDrainedAndStopping()) return BridgeWork::Stop;
    if (!g_micMuteQueue.empty()) {
        mute = g_micMuteQueue.front();
        g_micMuteQueue.pop_front();
        return BridgeWork::Mute;
    }
    if (g_bridgeQueue.empty()) return BridgeWork::Nothing;
    report = std::move(g_bridgeQueue.front());
    g_bridgeQueue.pop_front();
    return BridgeWork::Report;
}

static void bridgeDispatchLoop() {
    JNIEnv* env = attachDispatchThread("bridgeDispatchLoop");
    if (env == nullptr) return;
    dish::elevateCurrentThreadToInputPriority();
    LOGI("Bridge dispatch thread started");
    while (g_bridgeDispatchRunning.load(std::memory_order_relaxed)) {
        BridgeReport report;
        std::pair<int32_t, bool> mute{};
        const BridgeWork work = takeNextBridgeWork(report, mute);
        if (work == BridgeWork::Stop) break;
        if (work == BridgeWork::Nothing) continue;
        if (work == BridgeWork::Mute) {
            callMicMuteUpcall(env, mute.first, mute.second);
            continue;
        }
        dispatchBridgeReport(env, report);
    }
    JniConnectionRefs refs{env};
    g_bridgeConnectionRefs.releaseAll(refs);
    g_jvm->DetachCurrentThread();
    LOGI("Bridge dispatch thread stopped");
}

static void startBridgeDispatchThread() {
    bool was = g_bridgeDispatchRunning.exchange(true, std::memory_order_relaxed);
    if (was) return;
    g_bridgeDispatchThread = std::thread(bridgeDispatchLoop);
}

// The slot table's bridge kinds leave through the bridge thread's queue.
class JniBridgeSink final : public dish_slots::BridgeSink {
  public:
    void push(BridgeReport&& report) override { enqueueBridgeReport(std::move(report)); }
};

// The inbound MSG_SPEAKER_AUDIO / MSG_HAPTIC_AUDIO path.
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
    std::shared_ptr<dish_session::Session> session;
    int handle = -1;
    uint8_t ctrlIdx = 0;
    int lane = dish_receive::AUDIO_LANE_SPEAKER;
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

static void deliverSpeakerPcm(JNIEnv* env, int handle, uint8_t ctrlIdx, int lane,
                              const int16_t* pcm, bool concealed) {
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
                              (jint)ctrlIdx, (jint)lane, samples, (jboolean)concealed);
    env->DeleteLocalRef(samples);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static bool audioHasWork() {
    return !g_audioDispatchRunning.load(std::memory_order_relaxed) || !g_audioQueue.empty();
}

static bool audioIsDrainedAndStopping() {
    return !g_audioDispatchRunning.load(std::memory_order_relaxed) && g_audioQueue.empty();
}

// Answers whether a frame was taken. False means the loop has been stopped and the queue is
// empty, so there is nothing left to deliver.
static bool takeNextSpeakerFrame(SpeakerFrame& out) {
    std::unique_lock<std::mutex> lock(g_audioQueueMtx);
    g_audioQueueCv.wait(lock, audioHasWork);
    if (audioIsDrainedAndStopping()) return false;
    out = std::move(g_audioQueue.front());
    g_audioQueue.pop_front();
    return true;
}

static void deliverSpeakerFrames(JNIEnv* env, const SpeakerFrame& f, const int16_t* pcm,
                                 const bool* concealed, const int produced) {
    for (int i = 0; i < produced; i++) {
        deliverSpeakerPcm(env, f.handle, f.ctrlIdx, f.lane,
                          pcm + i * dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES, concealed[i]);
    }
}

static void audioDispatchLoop() {
    JNIEnv* env = attachDispatchThread("audioDispatchLoop");
    if (env == nullptr) return;
    // Named for its first caller; the value it sets is URGENT_AUDIO niceness, which is exactly
    // what a 20 ms decode cadence wants.
    dish::elevateCurrentThreadToInputPriority();
    LOGI("Speaker audio dispatch thread started");
    // Allocated once for the thread: one push can drain the whole window and conceal ahead of it,
    // so this is sized for the worst case rather than reallocated.
    std::vector<int16_t> pcm(static_cast<size_t>(dish_audio::AUDIO_JITTER_MAX_EVENTS_PER_PUSH) *
                             dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES);
    bool concealed[dish_audio::AUDIO_JITTER_MAX_EVENTS_PER_PUSH] = {};
    while (g_audioDispatchRunning.load(std::memory_order_relaxed)) {
        SpeakerFrame f;
        if (!takeNextSpeakerFrame(f)) break;
        if (!f.session || f.session->closed.load(std::memory_order_acquire)) continue;
        const int produced = dish_receive::decodeSpeakerAudio(
            *f.session, f.ctrlIdx, f.lane, f.seq, f.opus.data(), f.opus.size(), pcm, concealed);
        deliverSpeakerFrames(env, f, pcm.data(), concealed, produced);
    }
    g_jvm->DetachCurrentThread();
    LOGI("Speaker audio dispatch thread stopped");
}

static void startAudioDispatchThread() {
    bool was = g_audioDispatchRunning.exchange(true, std::memory_order_relaxed);
    if (was) return;
    g_audioDispatchThread = std::thread(audioDispatchLoop);
}

// The receive path's feedback, made into the Kotlin upcalls on the receive thread; audio is only
// copied onto the audio dispatch thread's queue. A bridge Kotlin has not installed drops it.
class JniFeedbackSink final : public dish_receive::FeedbackSink {
  public:
    explicit JniFeedbackSink(JNIEnv* env) : env_(env) {}

    void rumble(const int handle, const uint8_t ctrlIdx, const uint16_t strong, const uint16_t weak,
                const uint16_t durationMs) override {
        if (g_rumbleBridgeClass == nullptr || g_rumbleDispatchMethod == nullptr) return;
        env_->CallStaticVoidMethod(g_rumbleBridgeClass, g_rumbleDispatchMethod, (jint)handle,
                                   (jint)ctrlIdx, (jint)strong, (jint)weak, (jint)durationMs);
        if (env_->ExceptionCheck()) env_->ExceptionClear();
    }

    void lightbar(const int handle, const dish_wire::LightbarPayload& lb) override {
        if (g_feedbackBridgeClass == nullptr || g_feedbackLightbarMethod == nullptr) return;
        env_->CallStaticVoidMethod(g_feedbackBridgeClass, g_feedbackLightbarMethod, (jint)handle,
                                   (jint)lb.ctrlIdx, (jint)lb.r, (jint)lb.g, (jint)lb.b);
        if (env_->ExceptionCheck()) env_->ExceptionClear();
    }

    void triggerEffects(const int handle, const uint8_t ctrlIdx, const uint8_t* blocks) override {
        if (g_feedbackBridgeClass == nullptr || g_feedbackTriggerEffectsMethod == nullptr) return;
        jbyteArray array = env_->NewByteArray(dish_wire::TRIGGER_EFFECTS_PAYLOAD_BYTES);
        if (array == nullptr) {
            if (env_->ExceptionCheck()) env_->ExceptionClear();
            return;
        }
        env_->SetByteArrayRegion(array, 0, dish_wire::TRIGGER_EFFECTS_PAYLOAD_BYTES,
                                 (const jbyte*)blocks);
        env_->CallStaticVoidMethod(g_feedbackBridgeClass, g_feedbackTriggerEffectsMethod,
                                   (jint)handle, (jint)ctrlIdx, array);
        env_->DeleteLocalRef(array);
        if (env_->ExceptionCheck()) env_->ExceptionClear();
    }

    void playerLeds(const int handle, const dish_wire::PlayerLedsPayload& leds) override {
        if (g_feedbackBridgeClass == nullptr || g_feedbackPlayerLedsMethod == nullptr) return;
        env_->CallStaticVoidMethod(g_feedbackBridgeClass, g_feedbackPlayerLedsMethod, (jint)handle,
                                   (jint)leds.ctrlIdx, (jint)leds.ledMask);
        if (env_->ExceptionCheck()) env_->ExceptionClear();
    }

    void micLed(const int handle, const dish_wire::MicLedPayload& led) override {
        if (g_feedbackBridgeClass == nullptr || g_feedbackMicLedMethod == nullptr) return;
        env_->CallStaticVoidMethod(g_feedbackBridgeClass, g_feedbackMicLedMethod, (jint)handle,
                                   (jint)led.ctrlIdx, (jint)led.state);
        if (env_->ExceptionCheck()) env_->ExceptionClear();
    }

    // With no playback engine installed there is nothing to decode FOR, so the frame is dropped
    // before it costs a copy.
    void audio(const std::shared_ptr<dish_session::Session>& session, const int handle,
               const int lane, const dish_wire::AudioFrameHeader& header, const uint8_t* opus,
               const size_t opusLen) override {
        if (g_speakerAudioBridgeClass == nullptr) return;
        SpeakerFrame f;
        f.session = session;
        f.handle = handle;
        f.ctrlIdx = header.ctrlIdx;
        f.lane = lane;
        f.seq = header.seq;
        f.opus.assign(opus, opus + opusLen);
        enqueueSpeakerFrame(std::move(f));
    }

  private:
    JNIEnv* const env_;
};

static JniBridgeSink g_bridgeSink;
static dish_session::SessionTable g_sessionTable;
static dish_slots::SlotTable g_slotTable(g_sessionTable, g_bridgeSink);

namespace dispatch {

void prewarmDevice(int32_t deviceId) { g_slotTable.prewarmDevice(deviceId); }

void applyUsbReport(int32_t deviceId, const gamepad::DeviceState& nu) {
    g_slotTable.applyUsbReport(deviceId, nu);
}

void resetAndPublish(int32_t deviceId) { g_slotTable.resetAndPublish(deviceId); }

void forgetDevice(int32_t deviceId) { g_slotTable.forgetDevice(deviceId); }

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
    const dish_send::MotionSample sample{
        {gyroX, gyroY, gyroZ}, {accelX, accelY, accelZ}, timestampDeltaUs};
    g_slotTable.applyUsbMotion(deviceId, sample);
}

void applyUsbTouchpad(int32_t deviceId, const gamepad::TouchpadState& t, uint32_t eventTimeMs) {
    g_slotTable.applyUsbTouchpad(deviceId, t, eventTimeMs);
}

} // namespace dispatch

static inline float axisCur(const GameActivityMotionEvent* ev, int axis) {
    if (ev->pointerCount == 0) return 0.f;
    return ev->pointers[0].axisValues[axis];
}

// Returning true consumes the event so it can't trigger incidental View focus navigation.
static bool isGamepadSource(const int32_t source) {
    return (source & AINPUT_SOURCE_GAMEPAD) == AINPUT_SOURCE_GAMEPAD ||
           (source & AINPUT_SOURCE_JOYSTICK) == AINPUT_SOURCE_JOYSTICK;
}

static_assert(gamepad::KEY_ACTION_DOWN == AKEY_EVENT_ACTION_DOWN &&
                  gamepad::KEY_ACTION_UP == AKEY_EVENT_ACTION_UP,
              "gamepad_input.h mirrors the NDK's key actions");
static_assert(gamepad::MOTION_ACTION_MOVE == AMOTION_EVENT_ACTION_MOVE &&
                  gamepad::MOTION_ACTION_CANCEL == AMOTION_EVENT_ACTION_CANCEL,
              "gamepad_input.h mirrors the NDK's motion actions");

static bool gamepadKeyFilter(const GameActivityKeyEvent* ev) {
    if (!isGamepadSource(ev->source)) return false;
    return g_slotTable.applyFrameworkKey(ev->deviceId, ev->keyCode, ev->action);
}

// Latest sample wins: historicals are intermediate states the next apply overwrites anyway.
static gamepad::FrameworkAxes joystickSampleOf(const GameActivityMotionEvent* ev) {
    return gamepad::FrameworkAxes{
        axisCur(ev, AMOTION_EVENT_AXIS_X),        axisCur(ev, AMOTION_EVENT_AXIS_Y),
        axisCur(ev, AMOTION_EVENT_AXIS_Z),        axisCur(ev, AMOTION_EVENT_AXIS_RZ),
        axisCur(ev, AMOTION_EVENT_AXIS_RX),       axisCur(ev, AMOTION_EVENT_AXIS_RY),
        axisCur(ev, AMOTION_EVENT_AXIS_HAT_X),    axisCur(ev, AMOTION_EVENT_AXIS_HAT_Y),
        axisCur(ev, AMOTION_EVENT_AXIS_LTRIGGER), axisCur(ev, AMOTION_EVENT_AXIS_RTRIGGER),
        axisCur(ev, AMOTION_EVENT_AXIS_BRAKE),    axisCur(ev, AMOTION_EVENT_AXIS_GAS),
    };
}

static bool gamepadMotionFilter(const GameActivityMotionEvent* ev) {
    const bool isJoystick = (ev->source & AINPUT_SOURCE_JOYSTICK) == AINPUT_SOURCE_JOYSTICK;
    if (!isJoystick) return false;
    const int32_t action = ev->action & AMOTION_EVENT_ACTION_MASK;
    g_slotTable.applyFrameworkMotion(ev->deviceId, action, joystickSampleOf(ev));
    return true;
}

static uint64_t nowMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

static void heartbeatThread(const std::shared_ptr<dish_session::Session>& s) {
    // Each ping holds its session's send turn, which the input threads wait on, so the thread runs
    // at their priority: a ping descheduled mid-send would hold every sender of the session.
    dish::elevateCurrentThreadToInputPriority();
    LOGI("Heartbeat thread started (sock=%d)", s->udpSock);
    dish_send::runHeartbeat(s, g_heartbeatIntervalMs);
    LOGI("Heartbeat thread stopped");
}

// GameActivity only fills AXIS_X/Y by default; opt-in to every axis the motion filter reads.
static void enableGamepadAxes() {
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
}

// Answers whether the loop should run again: a looper error or a destroy request ends it.
static bool pumpLooperOnce(struct android_app* app) {
    int events = 0;
    struct android_poll_source* source = nullptr;
    const int result = ALooper_pollOnce(-1, nullptr, &events, (void**)&source);
    if (result == ALOOPER_POLL_ERROR) {
        LOGE("ALooper_pollOnce returned ALOOPER_POLL_ERROR");
        return false;
    }
    if (source != nullptr) source->process(source->app, source);
    return !app->destroyRequested;
}

// The filters consume every event inline on the input thread, so the buffers are only drained
// here; nothing reads them.
static void drainInputBuffers(struct android_app* app) {
    struct android_input_buffer* ib = android_app_swap_input_buffers(app);
    if (ib == nullptr) return;
    if (ib->motionEventsCount > 0) android_app_clear_motion_events(ib);
    if (ib->keyEventsCount > 0) android_app_clear_key_events(ib);
}

void android_main(struct android_app* app) {
    LOGI("android_main started (filter-inline input mode)");
    enableGamepadAxes();
    app->keyEventFilter = gamepadKeyFilter;
    app->motionEventFilter = gamepadMotionFilter;

    while (!app->destroyRequested) {
        if (!pumpLooperOnce(app)) break;
        drainInputBuffers(app);
    }
    LOGI("android_main: destroy requested, exiting");
}

extern "C" {

static std::once_flag g_sodiumInit;
static void initSodium() {
    if (sodium_init() < 0) {
        LOGE("sodium_init() failed!");
        return;
    }
    LOGI("libsodium initialized");
}
static void ensureSodiumInit() { std::call_once(g_sodiumInit, initSodium); }

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_core_jni_SessionNative_openSocket(JNIEnv* env,
                                                                                   jobject,
                                                                                   jstring ip,
                                                                                   jint port) {
    ensureSodiumInit();
    const char* s = env->GetStringUTFChars(ip, nullptr);
    const int handle = g_sessionTable.open(s, (int)port);
    env->ReleaseStringUTFChars(ip, s);
    return handle;
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SessionNative_closeSocket(JNIEnv*,
                                                                                    jobject,
                                                                                    jint handle) {
    g_sessionTable.close(handle);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SessionNative_setConnectionParams(
    JNIEnv* env, jobject, jint handle, jbyteArray tokenArr, jbyteArray keyArr,
    jint protocolVersion) {
    ensureSodiumInit();
    auto s = g_sessionTable.get(handle);
    if (!s) return;
    jbyte* tokenBytes = env->GetByteArrayElements(tokenArr, nullptr);
    jbyte* keyBytes = env->GetByteArrayElements(keyArr, nullptr);
    dish_session::setConnectionParams(*s, reinterpret_cast<const uint8_t*>(tokenBytes),
                                      reinterpret_cast<const uint8_t*>(keyBytes),
                                      (int32_t)protocolVersion);
    env->ReleaseByteArrayElements(tokenArr, tokenBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(keyArr, keyBytes, JNI_ABORT);
    LOGI("Session %d params set (token=%02x%02x%02x%02x, protocol=%d)", handle, s->token[0],
         s->token[1], s->token[2], s->token[3], (int)protocolVersion);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SlotReportNative_sendReport(
    JNIEnv*, jobject, jint handle, jint controllerIndex, jint wB, jint bLT, jint bRT, jint sLX,
    jint sLY, jint sRX, jint sRY) {
    auto s = g_sessionTable.get(handle);
    if (!s) return;
    dish_send::XUSB_REPORT report{};
    report.wButtons = (uint16_t)(wB & 0xFFFF);
    report.bLeftTrigger = (uint8_t)(bLT & 0xFF);
    report.bRightTrigger = (uint8_t)(bRT & 0xFF);
    report.sThumbLX = (int16_t)sLX;
    report.sThumbLY = (int16_t)sLY;
    report.sThumbRX = (int16_t)sRX;
    report.sThumbRY = (int16_t)sRY;
    dish_send::sendGamepadReport(s, controllerIndex, report);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SlotReportNative_sendMotion(
    JNIEnv*, jobject, jint handle, jint controllerIndex, jshort gyroX, jshort gyroY, jshort gyroZ,
    jshort accelX, jshort accelY, jshort accelZ, jint timestampDeltaUs) {
    auto s = g_sessionTable.get(handle);
    if (!s) return;
    const dish_send::MotionSample sample{{(int16_t)gyroX, (int16_t)gyroY, (int16_t)gyroZ},
                                         {(int16_t)accelX, (int16_t)accelY, (int16_t)accelZ},
                                         (uint32_t)timestampDeltaUs};
    dish_send::sendMotion(s, controllerIndex, sample);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SlotReportNative_sendBattery(
    JNIEnv*, jobject, jint handle, jint controllerIndex, jint level, jint status) {
    auto s = g_sessionTable.get(handle);
    if (!s) return;
    dish_send::sendBattery(s, controllerIndex, level, status);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SlotReportNative_sendTouchpad(
    JNIEnv*, jobject, jint handle, jint controllerIndex, jboolean f0Active, jboolean f1Active,
    jboolean buttonPressed, jboolean rightPressed, jboolean middlePressed, jint f0TrackingId,
    jshort f0x, jshort f0y, jint f1TrackingId, jshort f1x, jshort f1y, jlong eventTimeMs,
    jshort scrollDelta) {
    auto s = g_sessionTable.get(handle);
    if (!s) return;
    gamepad::TouchpadState t;
    t.f0Active = f0Active == JNI_TRUE;
    t.f1Active = f1Active == JNI_TRUE;
    t.clickDown = buttonPressed == JNI_TRUE;
    t.f0Id = (uint8_t)(f0TrackingId & 0xFF);
    t.f1Id = (uint8_t)(f1TrackingId & 0xFF);
    t.f0X = (int16_t)f0x;
    t.f0Y = (int16_t)f0y;
    t.f1X = (int16_t)f1x;
    t.f1Y = (int16_t)f1y;
    const uint8_t idx = (uint8_t)(controllerIndex & 0xFF);
    dish_send::sendTouchpadFrame(s, idx, t, rightPressed == JNI_TRUE, middlePressed == JNI_TRUE,
                                 (uint32_t)(eventTimeMs & 0xFFFFFFFFLL), (int16_t)scrollDelta);
}

// One 20 ms mono window straight from AudioRecord: encode it and put it on the
// wire as MSG_MIC_AUDIO. Called from the capture thread, so all of the work
// (Opus encode included) happens there rather than on the caller's.
JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_SlotReportNative_sendMicFrame(
    JNIEnv* env, jobject, jint handle, jint controllerIndex, jshortArray pcmMono) {
    auto s = g_sessionTable.get(handle);
    if (!s || pcmMono == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(pcmMono) != dish_audio::AUDIO_FRAME_SAMPLES) return JNI_FALSE;
    int16_t pcm[dish_audio::AUDIO_FRAME_SAMPLES];
    env->GetShortArrayRegion(pcmMono, 0, dish_audio::AUDIO_FRAME_SAMPLES,
                             reinterpret_cast<jshort*>(pcm));
    const uint8_t idx = (uint8_t)(controllerIndex & 0xFF);
    return dish_send::sendMicFrame(s, idx, pcm) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_core_jni_SessionNative_startHeartbeat(JNIEnv*, jobject, jint handle) {
    auto s = g_sessionTable.get(handle);
    if (!s) return;
    if (s->heartbeat.running()) return;
    s->missedAcks.store(0);
    s->connectionAlive.store(true);
    s->heartbeat.start([s] { heartbeatThread(s); });
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SessionNative_stopHeartbeat(JNIEnv*,
                                                                                      jobject,
                                                                                      jint handle) {
    auto s = g_sessionTable.get(handle);
    if (!s) return;
    s->heartbeat.stop();
}

JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_core_jni_SessionNative_isConnectionAlive(JNIEnv*, jobject, jint handle) {
    auto s = g_sessionTable.get(handle);
    if (!s) return JNI_FALSE;
    return s->connectionAlive.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_tinkernorth_dish_core_jni_SessionNative_getServerEpoch(JNIEnv*, jobject, jint handle) {
    auto s = g_sessionTable.get(handle);
    if (!s) return -1;
    return s->serverEpoch.load(std::memory_order_acquire);
}

JNIEXPORT jint JNICALL
Java_com_tinkernorth_dish_core_jni_SessionNative_getActiveBitmap(JNIEnv*, jobject, jint handle) {
    auto s = g_sessionTable.get(handle);
    if (!s) return -1;
    return s->activeBitmap.load(std::memory_order_acquire);
}

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_core_jni_SessionNative_getSessionCloseReason(
    JNIEnv*, jobject, jint handle) {
    auto s = g_sessionTable.get(handle);
    if (!s) return -1;
    return s->closeReason.load(std::memory_order_acquire);
}

JNIEXPORT jlong JNICALL
Java_com_tinkernorth_dish_core_jni_SessionNative_getSendCounter(JNIEnv*, jobject, jint handle) {
    auto s = g_sessionTable.get(handle);
    if (!s) return 0;
    return (jlong)dish_counter::sendCounterView(s->counter);
}

JNIEXPORT jint JNICALL
Java_com_tinkernorth_dish_core_jni_SessionNative_getVigemAvailable(JNIEnv*, jobject, jint handle) {
    auto s = g_sessionTable.get(handle);
    if (!s) return -1;
    return (jint)s->vigemAvailable.load(std::memory_order_acquire);
}

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_core_jni_SessionNative_getActiveControllerCount(
    JNIEnv*, jobject, jint handle) {
    auto s = g_sessionTable.get(handle);
    if (!s) return -1;
    return (jint)s->activeControllerCount.load(std::memory_order_acquire);
}

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_core_jni_SessionNative_receiveAck(JNIEnv* env,
                                                                                   jobject,
                                                                                   jint handle) {
    JniFeedbackSink sink(env);
    return dish_receive::receiveDatagram(g_sessionTable, handle, sink);
}

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_core_jni_SessionNative_discoverServers(
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

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_core_jni_PhysicalSlotNative_bindPhysicalSlotSatellite(
    JNIEnv*, jobject, jint deviceId, jint sessionHandle, jint controllerIndex) {
    g_slotTable.bindSatellite(deviceId, sessionHandle, controllerIndex);
}

// An empty id names no connection, as a missing one does: the slot then publishes nothing.
static BridgeConnectionId bridgeConnectionIdOf(const char* utf) {
    if (utf == nullptr || utf[0] == '\0') return nullptr;
    return std::make_shared<const std::string>(utf);
}

static void bindPhysicalSlotBridge(JNIEnv* env, jint deviceId, jstring connectionId, SlotKind kind,
                                   jint controllerIndex) {
    const char* cstr = env->GetStringUTFChars(connectionId, nullptr);
    BridgeConnectionId id = bridgeConnectionIdOf(cstr);
    if (cstr) env->ReleaseStringUTFChars(connectionId, cstr);
    g_slotTable.bindBridge(deviceId, std::move(id), kind, controllerIndex);
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_core_jni_PhysicalSlotNative_bindPhysicalSlotBluetooth(
    JNIEnv* env, jobject, jint deviceId, jstring connectionId) {
    bindPhysicalSlotBridge(env, deviceId, connectionId, dish_slots::SLOT_BLUETOOTH, -1);
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_core_jni_PhysicalSlotNative_bindPhysicalSlotMoonlight(
    JNIEnv* env, jobject, jint deviceId, jstring connectionId, jint controllerNumber) {
    bindPhysicalSlotBridge(env, deviceId, connectionId, dish_slots::SLOT_MOONLIGHT,
                           controllerNumber);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_PhysicalSlotNative_unbindPhysicalSlot(
    JNIEnv*, jobject, jint deviceId) {
    g_slotTable.unbind(deviceId);
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_core_jni_PhysicalSlotNative_clearAllPhysicalSlots(JNIEnv*, jobject) {
    g_slotTable.clearAll();
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_PhysicalSlotNative_forgetPhysicalDevice(
    JNIEnv*, jobject, jint deviceId) {
    dispatch::forgetDevice((int32_t)deviceId);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_PhysicalSlotNative_setDeviceDeadzones(
    JNIEnv*, jobject, jint deviceId, jfloat flatX, jfloat flatY, jfloat flatZ, jfloat flatRZ) {
    g_slotTable.setDeadzones(deviceId, flatX, flatY, flatZ, flatRZ);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_PhysicalSlotNative_setDeviceQuirk(
    JNIEnv*, jobject, jint deviceId, jint quirk) {
    g_slotTable.setQuirk(deviceId, (uint8_t)(quirk & 0xFF));
}

// Activity-level dispatch is needed because GameActivity's SurfaceView sits below the
// input layer that synthesizes DPAD keys from stick motion on some controllers.
JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_core_jni_PhysicalSlotNative_processGamepadKeyEvent(
    JNIEnv*, jobject, jint deviceId, jint /*source*/, jint action, jint keyCode) {
    // Source bits are unreliable; gate on the mapped-keycode check instead.
    const bool consumed = g_slotTable.applyFrameworkKey(deviceId, keyCode, action);
    return consumed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_core_jni_PhysicalSlotNative_processGamepadMotionEvent(
    JNIEnv*, jobject, jint deviceId, jint source, jint action, jfloat x, jfloat y, jfloat z,
    jfloat rz, jfloat rx, jfloat ry, jfloat hatX, jfloat hatY, jfloat lTrigger, jfloat rTrigger,
    jfloat brake, jfloat gas) {
    if ((source & AINPUT_SOURCE_JOYSTICK) != AINPUT_SOURCE_JOYSTICK) return JNI_FALSE;
    const int32_t maskedAction = action & AMOTION_EVENT_ACTION_MASK;
    const gamepad::FrameworkAxes sample{x,    y,    z,        rz,       rx,    ry,
                                        hatX, hatY, lTrigger, rTrigger, brake, gas};
    g_slotTable.applyFrameworkMotion(deviceId, maskedAction, sample);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_core_jni_PhysicalSlotNative_releaseAllPhysicalReports(JNIEnv*, jobject) {
    g_slotTable.releaseAll();
}

// A bridge method that is missing degrades to a silent bridge rather than a pending exception:
// the miss is logged once here and cleared.
static jmethodID resolveStaticMethod(JNIEnv* env, const jclass cls, const char* owner,
                                     const char* name, const char* signature) {
    const jmethodID method = env->GetStaticMethodID(cls, name, signature);
    if (method != nullptr) return method;
    LOGE("%s.%s not found", owner, name);
    env->ExceptionClear();
    return nullptr;
}

// Class registration cannot live in JNI_OnLoad: FindClass there uses the system loader, not the
// app's.
JNIEXPORT void JNICALL Java_com_tinkernorth_dish_hotpath_input_BluetoothGamepadBridge_nativeInstall(
    JNIEnv* env, jclass bridgeCls) {
    if (g_btBridgeClass == nullptr) { g_btBridgeClass = (jclass)env->NewGlobalRef(bridgeCls); }
    if (g_btDispatchMethod == nullptr) {
        g_btDispatchMethod = resolveStaticMethod(env, g_btBridgeClass, "BluetoothGamepadBridge",
                                                 "dispatchReport", "(Ljava/lang/String;IIIIIII)V");
    }
    startBridgeDispatchThread();
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_hotpath_input_MoonlightGamepadBridge_nativeInstall(
    JNIEnv* env, jclass bridgeCls) {
    if (g_moonlightBridgeClass == nullptr) {
        g_moonlightBridgeClass = (jclass)env->NewGlobalRef(bridgeCls);
    }
    if (g_moonlightDispatchMethod == nullptr) {
        g_moonlightDispatchMethod =
            resolveStaticMethod(env, g_moonlightBridgeClass, "MoonlightGamepadBridge",
                                "dispatchReport", "(Ljava/lang/String;IIIIIIII)V");
    }
    if (g_moonlightMotionMethod == nullptr) {
        g_moonlightMotionMethod =
            resolveStaticMethod(env, g_moonlightBridgeClass, "MoonlightGamepadBridge",
                                "dispatchMotion", "(Ljava/lang/String;IIIIIIII)V");
    }
    if (g_moonlightTouchMethod == nullptr) {
        g_moonlightTouchMethod =
            resolveStaticMethod(env, g_moonlightBridgeClass, "MoonlightGamepadBridge",
                                "dispatchTouch", "(Ljava/lang/String;IZIIIZIIIZ)V");
    }
    startBridgeDispatchThread();
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_hotpath_input_RumbleBridge_nativeInstall(JNIEnv* env, jclass bridgeCls) {
    if (g_rumbleBridgeClass == nullptr) {
        g_rumbleBridgeClass = (jclass)env->NewGlobalRef(bridgeCls);
    }
    if (g_rumbleDispatchMethod == nullptr) {
        g_rumbleDispatchMethod = resolveStaticMethod(env, g_rumbleBridgeClass, "RumbleBridge",
                                                     "dispatchRumble", "(IIIII)V");
    }
}

// The claim is a Kotlin UsbInterfaceClaim; its Int fields are read by name (proguard-rules.pro
// keeps them). A missing field means the two sides disagree, which is a build defect, not a
// device condition: the attach is refused rather than guessed at.
struct ClaimFields {
    jint interfaceNumber;
    jint epIn;
    jint epInMaxPacket;
    jint epOut;
    jint ifClass;
    jint ifSubclass;
    jint ifProtocol;
};

static bool readClaimField(JNIEnv* env, jclass cls, jobject claim, const char* name, jint* out) {
    jfieldID id = env->GetFieldID(cls, name, "I");
    if (id == nullptr) {
        env->ExceptionClear();
        LOGE("attachUsbDevice: UsbInterfaceClaim.%s missing", name);
        return false;
    }
    *out = env->GetIntField(claim, id);
    return true;
}

static bool readClaim(JNIEnv* env, jobject claim, ClaimFields* out) {
    jclass cls = env->GetObjectClass(claim);
    bool ok = readClaimField(env, cls, claim, "interfaceNumber", &out->interfaceNumber) &&
              readClaimField(env, cls, claim, "endpointIn", &out->epIn) &&
              readClaimField(env, cls, claim, "endpointInMaxPacket", &out->epInMaxPacket) &&
              readClaimField(env, cls, claim, "endpointOut", &out->epOut) &&
              readClaimField(env, cls, claim, "interfaceClass", &out->ifClass) &&
              readClaimField(env, cls, claim, "interfaceSubclass", &out->ifSubclass) &&
              readClaimField(env, cls, claim, "interfaceProtocol", &out->ifProtocol);
    env->DeleteLocalRef(cls);
    return ok;
}

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_attachUsbDevice(
    JNIEnv* env, jobject, jint fd, jint vid, jint pid, jobject claim) {
    ClaimFields c{};
    if (claim == nullptr || !readClaim(env, claim, &c)) return 0;
    int dupFd = dup(fd);
    if (dupFd < 0) {
        LOGE("attachUsbDevice: dup(%d) failed: %s", fd, strerror(errno));
        return 0;
    }
    usbhost::AttachResult r = usbhost::attachDevice(
        dupFd, (uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF), c.interfaceNumber,
        (uint8_t)(c.epIn & 0xFF), (uint16_t)(c.epInMaxPacket & 0xFFFF), (uint8_t)(c.epOut & 0xFF),
        (uint8_t)(c.ifClass & 0xFF), (uint8_t)(c.ifSubclass & 0xFF),
        (uint8_t)(c.ifProtocol & 0xFF));
    return r.ok ? (jint)r.syntheticDeviceId : 0;
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_detachUsbDevice(
    JNIEnv*, jobject, jint syntheticDeviceId) {
    usbhost::detachDevice((int32_t)syntheticDeviceId);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_sendUsbRumble(
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
        g_feedbackLightbarMethod = resolveStaticMethod(env, g_feedbackBridgeClass, "FeedbackBridge",
                                                       "dispatchLightbar", "(IIIII)V");
    }
    if (g_feedbackTriggerEffectsMethod == nullptr) {
        g_feedbackTriggerEffectsMethod = resolveStaticMethod(
            env, g_feedbackBridgeClass, "FeedbackBridge", "dispatchTriggerEffects", "(II[B)V");
    }
    if (g_feedbackPlayerLedsMethod == nullptr) {
        g_feedbackPlayerLedsMethod = resolveStaticMethod(
            env, g_feedbackBridgeClass, "FeedbackBridge", "dispatchPlayerLeds", "(III)V");
    }
    if (g_feedbackMicLedMethod == nullptr) {
        g_feedbackMicLedMethod = resolveStaticMethod(env, g_feedbackBridgeClass, "FeedbackBridge",
                                                     "dispatchMicLed", "(III)V");
    }
}

// The pad's own mic-mute button, going UP. FeedbackBridge carries what the host asked the pad to
// do; this is the pad telling the app what the user did to it, and the only signal on the USB
// input path that Kotlin needs by event rather than by report.
JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_hotpath_input_MicMuteBridge_nativeInstall(JNIEnv* env, jclass cls) {
    if (g_micMuteBridgeClass == nullptr) { g_micMuteBridgeClass = (jclass)env->NewGlobalRef(cls); }
    if (g_micMutePadMethod == nullptr) {
        g_micMutePadMethod = resolveStaticMethod(env, g_micMuteBridgeClass, "MicMuteBridge",
                                                 "dispatchPadMicMute", "(IZ)V");
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
            resolveStaticMethod(env, g_speakerAudioBridgeClass, "SpeakerAudioBridge",
                                "dispatchAudioFrame", "(III[SZ)V");
    }
    startAudioDispatchThread();
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_sendUsbTriggerRumble(
    JNIEnv*, jobject, jint syntheticDeviceId, jint leftMag, jint rightMag) {
    usbhost::sendTriggerRumble((int32_t)syntheticDeviceId, (uint16_t)(leftMag & 0xFFFF),
                               (uint16_t)(rightMag & 0xFFFF));
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_sendUsbLightbar(
    JNIEnv*, jobject, jint syntheticDeviceId, jint r, jint g, jint b) {
    usbhost::sendLightbar((int32_t)syntheticDeviceId, (uint8_t)(r & 0xFF), (uint8_t)(g & 0xFF),
                          (uint8_t)(b & 0xFF));
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_sendUsbPlayerLeds(
    JNIEnv*, jobject, jint syntheticDeviceId, jint ledMask) {
    usbhost::sendPlayerLeds((int32_t)syntheticDeviceId, (uint8_t)(ledMask & 0xFF));
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_sendUsbTriggerEffects(
    JNIEnv* env, jobject, jint syntheticDeviceId, jbyteArray blocks) {
    if (blocks == nullptr) return;
    if (env->GetArrayLength(blocks) < 2 * usbparsers::TRIGGER_EFFECT_BLOCK_LEN) return;
    uint8_t buf[2 * usbparsers::TRIGGER_EFFECT_BLOCK_LEN];
    env->GetByteArrayRegion(blocks, 0, sizeof(buf), (jbyte*)buf);
    // Wire order left then right (contract 0x0010).
    usbhost::sendTriggerEffects((int32_t)syntheticDeviceId, buf,
                                buf + usbparsers::TRIGGER_EFFECT_BLOCK_LEN);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_sendUsbMicMuteLed(
    JNIEnv*, jobject, jint syntheticDeviceId, jint state) {
    usbhost::sendMicMuteLed((int32_t)syntheticDeviceId, (uint8_t)(state & 0xFF));
}

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_core_jni_ModelTableNative_lookupKnownModelName(
    JNIEnv* env, jobject, jint vid, jint pid) {
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF));
    return env->NewStringUTF(k ? k->name : "");
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_ModelTableNative_isKnownFastLaneModel(
    JNIEnv*, jobject, jint vid, jint pid) {
    return usbparsers::isVerifiedFastLane((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF))
               ? JNI_TRUE
               : JNI_FALSE;
}

// Every model predicate reads the same way: look the pad up; an unknown model has nothing.
static jboolean modelPredicate(const jint vid, const jint pid,
                               bool (*const predicate)(usbparsers::Parser)) {
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF));
    if (!k) return JNI_FALSE;
    return predicate(k->parser) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_ModelTableNative_modelHasImu(
    JNIEnv*, jobject, jint vid, jint pid) {
    return modelPredicate(vid, pid, usbparsers::parserHasImu);
}

JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_core_jni_ModelTableNative_modelExpectsFrameworkGamepad(JNIEnv*, jobject,
                                                                                 jint vid,
                                                                                 jint pid) {
    return usbparsers::modelExpectsFrameworkGamepad((uint16_t)(vid & 0xFFFF),
                                                    (uint16_t)(pid & 0xFFFF))
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_ModelTableNative_modelHasRumble(
    JNIEnv*, jobject, jint vid, jint pid) {
    return modelPredicate(vid, pid, usbparsers::parserHasRumble);
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_ModelTableNative_modelHasLightbar(
    JNIEnv*, jobject, jint vid, jint pid) {
    return modelPredicate(vid, pid, usbparsers::parserHasLightbar);
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_ModelTableNative_modelHasPlayerLeds(
    JNIEnv*, jobject, jint vid, jint pid) {
    return modelPredicate(vid, pid, usbparsers::parserHasPlayerLeds);
}

JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_core_jni_ModelTableNative_modelHasTriggerEffects(JNIEnv*, jobject,
                                                                           jint vid, jint pid) {
    return modelPredicate(vid, pid, usbparsers::parserHasTriggerEffects);
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_ModelTableNative_modelHasHapticLanes(
    JNIEnv*, jobject, jint vid, jint pid) {
    return modelPredicate(vid, pid, usbparsers::parserHasHapticLanes);
}

JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_core_jni_ModelTableNative_modelHasTriggerRumble(JNIEnv*, jobject,
                                                                          jint vid, jint pid) {
    return modelPredicate(vid, pid, usbparsers::parserHasTriggerRumble);
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_ModelTableNative_modelHasTouchpad(
    JNIEnv*, jobject, jint vid, jint pid) {
    return modelPredicate(vid, pid, usbparsers::parserHasTouchpad);
}

JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_core_jni_ModelTableNative_modelFrameworkRumbleUnreliable(JNIEnv*, jobject,
                                                                                   jint vid,
                                                                                   jint pid) {
    return modelPredicate(vid, pid, usbparsers::parserFrameworkRumbleUnreliable);
}

JNIEXPORT jlong JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_getDeviceUrbCount(
    JNIEnv*, jobject, jint deviceId) {
    return (jlong)usbhost::getUrbCount((int32_t)deviceId);
}

JNIEXPORT jlong JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_getDeviceMotionCount(
    JNIEnv*, jobject, jint deviceId) {
    return (jlong)usbhost::getMotionCount((int32_t)deviceId);
}

JNIEXPORT jlong JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_getDeviceUrbErrorCount(
    JNIEnv*, jobject, jint deviceId) {
    return (jlong)usbhost::getUrbErrorCount((int32_t)deviceId);
}

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_getDirectPadBattery(
    JNIEnv*, jobject, jint deviceId) {
    return (jint)usbhost::getPadBattery((int32_t)deviceId);
}

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_deviceInfoJson(
    JNIEnv* env, jobject, jint deviceId) {
    return env->NewStringUTF(usbhost::deviceInfoJson((int32_t)deviceId).c_str());
}

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_core_jni_UsbDirectNative_deviceLatencyJson(
    JNIEnv* env, jobject, jint deviceId) {
    return env->NewStringUTF(hotpath::deviceLatencyJson((int32_t)deviceId).c_str());
}

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_core_jni_SessionNative_sessionStatsJson(
    JNIEnv* env, jobject, jint handle) {
    auto s = g_sessionTable.get(handle);
    if (!s) return env->NewStringUTF("");
    return env->NewStringUTF(
        hotpath::sessionStatsJson(s->rtt, s->missedAcks.load(std::memory_order_relaxed)).c_str());
}

JNIEXPORT jlong JNICALL Java_com_tinkernorth_dish_core_jni_SlotReportNative_getSlotSendCount(
    JNIEnv*, jobject, jint handle, jint controllerIndex) {
    auto s = g_sessionTable.get(handle);
    if (!s) return 0;
    return (jlong)s->sentByCtrl[controllerIndex & CTRL_INDEX_MASK].load(std::memory_order_relaxed);
}

JNIEXPORT jlong JNICALL Java_com_tinkernorth_dish_core_jni_SlotReportNative_getSlotMotionCount(
    JNIEnv*, jobject, jint handle, jint controllerIndex) {
    auto s = g_sessionTable.get(handle);
    if (!s) return 0;
    return (jlong)s->motionByCtrl[controllerIndex & CTRL_INDEX_MASK].load(
        std::memory_order_relaxed);
}

// Opt-in hot-path latency benchmark (stage 1 USB-direct + stage 2 heartbeat RTT).
// Off by default; see hotpath_latency.h and satellite tools/bench/README.md.
JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_InstrumentationNative_setHotPathBench(
    JNIEnv*, jobject, jboolean on) {
    hotpath::setEnabled(on == JNI_TRUE);
}

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_core_jni_InstrumentationNative_hotPathBenchJson(
    JNIEnv* env, jobject, jboolean reset) {
    return env->NewStringUTF(hotpath::statsJson(reset == JNI_TRUE).c_str());
}

// Densify heartbeat pings for RTT measurement while the diagnostics latency panel is
// open. The RTT window is dropped on enable so the readout reflects probe-era samples,
// not the idle-radio tail accumulated before the panel opened.
JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_InstrumentationNative_setLatencyProbe(
    JNIEnv*, jobject, jboolean on) {
    g_heartbeatIntervalMs.store(on == JNI_TRUE ? dish_send::HEARTBEAT_INTERVAL_PROBE_MS
                                               : dish_send::HEARTBEAT_INTERVAL_DEFAULT_MS,
                                std::memory_order_relaxed);
    if (on != JNI_TRUE) return;
    hotpath::resetRttWindow();
    g_sessionTable.clearRtt();
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_InstrumentationNative_setInputInspection(
    JNIEnv*, jobject, jboolean on) {
    g_slotTable.setInspecting(on == JNI_TRUE);
}

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_core_jni_PhysicalSlotNative_deviceStateJson(
    JNIEnv* env, jobject, jint deviceId) {
    char buf[512];
    const size_t n = g_slotTable.formatDeviceState((int32_t)deviceId, buf, sizeof(buf));
    return env->NewStringUTF(n > 0 ? buf : "");
}

JNIEXPORT jlong JNICALL
Java_com_tinkernorth_dish_core_jni_PhysicalSlotNative_getDeviceInputEventCount(JNIEnv*, jobject,
                                                                               jint deviceId) {
    return (jlong)g_slotTable.frameworkEventCount((int32_t)deviceId);
}
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}
