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
#include <vector>
#include <sodium.h>

#include "dispatch.h"
#include "gamepad_input.h"
#include "hotpath_latency.h"
#include "thread_priority.h"
#include "usb_host.h"
#include "usb_parsers.h"
#include "wire_encoders.h"

#define TAG "SatelliteJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static constexpr int HEARTBEAT_INTERVAL_MS = 2000;
static constexpr int HEARTBEAT_MISS_MAX = 5;
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

struct Session {
    int udpSock = -1;
    struct sockaddr_in dest = {};
    uint8_t token[4] = {};
    uint8_t key[32] = {}; // per-session key (HKDF-derived in Kotlin), never the pairing key
    std::atomic<uint32_t> counter{1};
    // Linux UDP sendto is thread-safe per-socket; userspace lock would only serialise stalls.

    std::thread heartbeatThread;
    std::atomic<bool> heartbeatRunning{false};
    std::atomic<int> missedAcks{0};
    std::atomic<bool> connectionAlive{true};

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

enum SlotKind : uint8_t { SLOT_NONE = 0, SLOT_SATELLITE = 1, SLOT_BLUETOOTH = 2 };

struct SlotBinding {
    SlotKind kind = SLOT_NONE;
    int sessionHandle = -1;
    int controllerIndex = -1;
    std::string btConnectionId;
};

static std::mutex g_devicesMtx;
static std::unordered_map<int32_t, DeviceState> g_devices;
static std::unordered_map<int32_t, uint64_t> g_frameworkEventCounts;

static std::mutex g_slotsMtx;
static std::unordered_map<int32_t, SlotBinding> g_slots;

static JavaVM* g_jvm = nullptr;
static jclass g_btBridgeClass = nullptr;
static jmethodID g_btDispatchMethod = nullptr;

static jclass g_rumbleBridgeClass = nullptr;
static jmethodID g_rumbleDispatchMethod = nullptr;

// BT path runs off the UI thread because BluetoothHidDevice.sendReport is Binder IPC.
struct BtReport {
    std::string connectionId;
    uint16_t wButtons;
    uint8_t bLT, bRT;
    int16_t sLX, sLY, sRX, sRY;
};

static std::mutex g_btQueueMtx;
static std::condition_variable g_btQueueCv;
static std::deque<BtReport> g_btQueue;
static std::thread g_btDispatchThread;
static std::atomic<bool> g_btDispatchRunning{false};
static constexpr size_t BT_QUEUE_MAX = 64;

static void enqueueBtReport(BtReport&& r) {
    {
        std::lock_guard<std::mutex> lock(g_btQueueMtx);
        if (g_btQueue.size() >= BT_QUEUE_MAX) g_btQueue.pop_front();
        g_btQueue.push_back(std::move(r));
    }
    g_btQueueCv.notify_one();
}

static void btDispatchLoop() {
    JNIEnv* env = nullptr;
    if (!g_jvm || g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        LOGE("btDispatchLoop: AttachCurrentThread failed");
        return;
    }
    dish::elevateCurrentThreadToInputPriority();
    LOGI("BT dispatch thread started");
    while (g_btDispatchRunning.load(std::memory_order_relaxed)) {
        BtReport r;
        {
            std::unique_lock<std::mutex> lock(g_btQueueMtx);
            g_btQueueCv.wait(lock, [] {
                return !g_btDispatchRunning.load(std::memory_order_relaxed) || !g_btQueue.empty();
            });
            if (!g_btDispatchRunning.load(std::memory_order_relaxed) && g_btQueue.empty()) break;
            r = std::move(g_btQueue.front());
            g_btQueue.pop_front();
        }
        if (g_btBridgeClass == nullptr || g_btDispatchMethod == nullptr) continue;
        jstring connId = env->NewStringUTF(r.connectionId.c_str());
        env->CallStaticVoidMethod(g_btBridgeClass, g_btDispatchMethod, connId, (jint)r.wButtons,
                                  (jint)r.bLT, (jint)r.bRT, (jint)r.sLX, (jint)r.sLY, (jint)r.sRX,
                                  (jint)r.sRY);
        env->DeleteLocalRef(connId);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    g_jvm->DetachCurrentThread();
    LOGI("BT dispatch thread stopped");
}

static void startBtDispatchThread() {
    bool was = g_btDispatchRunning.exchange(true, std::memory_order_relaxed);
    if (was) return;
    g_btDispatchThread = std::thread(btDispatchLoop);
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
    } else if (binding.kind == SLOT_BLUETOOTH) {
        if (binding.btConnectionId.empty()) return;
        enqueueBtReport(BtReport{
            binding.btConnectionId,
            s.wButtons,
            s.bLT,
            s.bRT,
            s.sLX,
            s.sLY,
            s.sRX,
            s.sRY,
        });
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

void applyUsbMotion(int32_t deviceId, int16_t gyroX, int16_t gyroY, int16_t gyroZ, int16_t accelX,
                    int16_t accelY, int16_t accelZ, uint32_t timestampDeltaUs) {
    std::lock_guard<std::mutex> lock(g_slotsMtx);
    auto it = g_slots.find(deviceId);
    if (it == g_slots.end()) return;
    const SlotBinding& binding = it->second;
    if (binding.kind != SLOT_SATELLITE) return;
    auto session = getSession(binding.sessionHandle);
    if (!session) return;
    uint8_t payload[17];
    dish_wire::encodeMotionPayload(payload, (uint8_t)(binding.controllerIndex & 0xFF), gyroX, gyroY,
                                   gyroZ, accelX, accelY, accelZ, timestampDeltaUs);
    sendEncrypted(session.get(), MSG_MOTION, payload, sizeof(payload));
}

// Satellite-only like motion: the Bluetooth HID descriptor is a plain gamepad, so touch has
// nowhere to go on that transport. Routing (ds4 pad vs mouse vs off) is the receiver's job,
// declared per slot in the descriptor.
void applyUsbTouchpad(int32_t deviceId, const gamepad::TouchpadState& t, uint32_t eventTimeMs) {
    std::lock_guard<std::mutex> lock(g_slotsMtx);
    auto it = g_slots.find(deviceId);
    if (it == g_slots.end()) return;
    const SlotBinding& binding = it->second;
    if (binding.kind != SLOT_SATELLITE) return;
    auto session = getSession(binding.sessionHandle);
    if (!session) return;
    uint8_t payload[16];
    dish_wire::encodeTouchpadPayload(payload, (uint8_t)(binding.controllerIndex & 0xFF), t.f0Active,
                                     t.f1Active, t.clickDown, t.f0Id, t.f0X, t.f0Y, t.f1Id, t.f1X,
                                     t.f1Y, eventTimeMs);
    sendEncrypted(session.get(), MSG_TOUCHPAD, payload, sizeof(payload));
}

} // namespace dispatch

// Returning true consumes the event so it can't trigger incidental View focus navigation.
static bool gamepadKeyFilter(const GameActivityKeyEvent* ev) {
    int32_t source = ev->source;
    bool isGame = (source & AINPUT_SOURCE_GAMEPAD) == AINPUT_SOURCE_GAMEPAD ||
                  (source & AINPUT_SOURCE_JOYSTICK) == AINPUT_SOURCE_JOYSTICK;
    if (!isGame) return false;
    int32_t kc = ev->keyCode;
    bool isMappedKey =
        (kc == AKEYCODE_BUTTON_L2 || kc == AKEYCODE_BUTTON_R2) || gamepad::keycodeToXusb(kc) != 0;
    if (!isMappedKey) return false;

    int32_t action = ev->action;
    if (action == AKEY_EVENT_ACTION_DOWN || action == AKEY_EVENT_ACTION_UP) {
        int32_t deviceId = ev->deviceId;
        std::lock_guard<std::mutex> lock(g_devicesMtx);
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

    uint16_t innerLen = 4 + payloadLen;
    uint8_t inner[4 + 256];
    if (innerLen > sizeof(inner)) return false;
    putBE16(inner, msgType);
    putBE16(inner + 2, payloadLen);
    if (payloadLen > 0) memcpy(inner + 4, payload, payloadLen);

    uint32_t ctr = s->counter.fetch_add(1, std::memory_order_relaxed);

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
        hotpath::markPingSent(); // stage-2: round-trip clock starts here
        s->missedAcks.fetch_add(1, std::memory_order_relaxed);

        if (s->missedAcks.load(std::memory_order_relaxed) >= HEARTBEAT_MISS_MAX) {
            LOGE("Missed %d heartbeat ACKs, connection dead", HEARTBEAT_MISS_MAX);
            s->connectionAlive.store(false, std::memory_order_relaxed);
        }

        for (int i = 0; i < HEARTBEAT_INTERVAL_MS / 100; i++) {
            if (!s->heartbeatRunning.load(std::memory_order_relaxed)) break;
            usleep(100000);
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
    if (s->udpSock >= 0) {
        close(s->udpSock);
        s->udpSock = -1;
    }
    LOGI("UDP session %d closed", handle);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_setConnectionParams(
    JNIEnv* env, jobject, jint handle, jbyteArray tokenArr, jbyteArray keyArr) {
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
    env->ReleaseByteArrayElements(tokenArr, tokenBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(keyArr, keyBytes, JNI_ABORT);
    LOGI("Session %d params set (token=%02x%02x%02x%02x)", handle, s->token[0], s->token[1],
         s->token[2], s->token[3]);
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
    jboolean buttonPressed, jint f0TrackingId, jshort f0x, jshort f0y, jint f1TrackingId,
    jshort f1x, jshort f1y, jlong eventTimeMs) {
    auto s = getSession(handle);
    if (!s) return;
    uint8_t payload[16];
    dish_wire::encodeTouchpadPayload(
        payload, (uint8_t)(controllerIndex & 0xFF), f0Active == JNI_TRUE, f1Active == JNI_TRUE,
        buttonPressed == JNI_TRUE, (uint8_t)(f0TrackingId & 0xFF), (int16_t)f0x, (int16_t)f0y,
        (uint8_t)(f1TrackingId & 0xFF), (int16_t)f1x, (int16_t)f1y,
        (uint32_t)(eventTimeMs & 0xFFFFFFFFLL));
    sendEncrypted(s.get(), MSG_TOUCHPAD, payload, sizeof(payload));
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
    uint8_t buf[128];
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

    // Must size with buf[128] not the message-set max: bound holds structurally, not by luck.
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
        hotpath::markAckReceived(); // stage-2: round-trip clock stops here (RTT)
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
        // Android has no controller-LED API; decode and drop (dish-android does not advertise
        // CAP_LIGHTBAR).
        const dish_wire::LightbarPayload lb = dish_wire::decodeLightbarPayload(decrypted + 4);
        LOGI("Session %d lightbar (no LED API on Android, dropping): idx=%d rgb=%02X%02X%02X",
             handle, lb.ctrlIdx, lb.r, lb.g, lb.b);
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
        b.btConnectionId.clear();
    }
    syncSlotBaseline(deviceId);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_bindPhysicalSlotBluetooth(
    JNIEnv* env, jobject, jint deviceId, jstring connectionId) {
    const char* cstr = env->GetStringUTFChars(connectionId, nullptr);
    std::string copy = cstr ? std::string(cstr) : std::string();
    if (cstr) env->ReleaseStringUTFChars(connectionId, cstr);
    {
        std::lock_guard<std::mutex> lock(g_slotsMtx);
        auto& b = g_slots[deviceId];
        b.kind = SLOT_BLUETOOTH;
        b.sessionHandle = -1;
        b.controllerIndex = -1;
        b.btConnectionId = std::move(copy);
    }
    syncSlotBaseline(deviceId);
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
    bool isMappedKey = (keyCode == AKEYCODE_BUTTON_L2 || keyCode == AKEYCODE_BUTTON_R2 ||
                        keyCode == AKEYCODE_BUTTON_7 || keyCode == AKEYCODE_BUTTON_8) ||
                       gamepad::keycodeToXusb(keyCode) != 0;
    if (!isMappedKey) return JNI_FALSE;
    if (action != AKEY_EVENT_ACTION_DOWN && action != AKEY_EVENT_ACTION_UP) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_devicesMtx);
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
    startBtDispatchThread();
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
    jint epInMaxPacket, jint epOut) {
    int dupFd = dup(fd);
    if (dupFd < 0) {
        LOGE("attachUsbDevice: dup(%d) failed: %s", fd, strerror(errno));
        return 0;
    }
    usbhost::AttachResult r = usbhost::attachDevice(
        dupFd, (uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF), interfaceNumber,
        (uint8_t)(epIn & 0xFF), (uint16_t)(epInMaxPacket & 0xFFFF), (uint8_t)(epOut & 0xFF));
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

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_modelHasRumble(
    JNIEnv*, jobject, jint vid, jint pid) {
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF));
    if (!k) return JNI_FALSE;
    return usbparsers::parserHasRumble(k->parser) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_modelHasTouchpad(
    JNIEnv*, jobject, jint vid, jint pid) {
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown((uint16_t)(vid & 0xFFFF), (uint16_t)(pid & 0xFFFF));
    if (!k) return JNI_FALSE;
    return usbparsers::parserHasTouchpad(k->parser) ? JNI_TRUE : JNI_FALSE;
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
