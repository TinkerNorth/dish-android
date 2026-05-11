// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

/*
 * satellite_jni.cpp — Native bridge for the Dish Android client.
 *
 * Physical-gamepad input is handled inline in the GameActivity input filter
 * callbacks (UI thread), which is the lowest-latency path Android exposes:
 * Android InputDispatcher → GameActivity dispatch → filter callback → encrypted
 * UDP sendto, all on the same thread with no queue/looper hop. For Bluetooth-
 * bound slots the filter pushes the report onto an internal queue drained by
 * a dedicated worker thread (BluetoothHidDevice.sendReport is Binder IPC and
 * would jank the UI thread otherwise).
 *
 * Also handles LAN discovery, TCP pairing, HTTP connection API, and the
 * heartbeat/ACK loops for the encrypted UDP wire (ChaCha20-Poly1305).
 */
#include <jni.h>
#include <android/log.h>
#include <android/input.h>
#include <android/keycodes.h>
#include <android/looper.h>
#include <game-activity/GameActivity.h>
#include <game-activity/GameActivityEvents.h>
#include <game-activity/native_app_glue/android_native_app_glue.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
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

#include "gamepad_input.h"

#define TAG "SatelliteJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ── Constants ─────────────────────────────────────────────────────────────── */
static constexpr int HEARTBEAT_INTERVAL_MS = 2000;
static constexpr int HEARTBEAT_MISS_MAX = 5;
// Message types
static constexpr uint16_t MSG_GAMEPAD_DATA = 0x0001;
static constexpr uint16_t MSG_HEARTBEAT_PING = 0x0002;
static constexpr uint16_t MSG_HEARTBEAT_ACK = 0x0003;
static constexpr uint16_t MSG_CONTROLLER_ADD = 0x0004;
static constexpr uint16_t MSG_CONTROLLER_REMOVE = 0x0005;
static constexpr uint16_t MSG_CONTROLLER_ACK = 0x0006;
static constexpr uint16_t MSG_SERVER_STATUS = 0x0007;
static constexpr uint16_t MSG_CONTROLLER_TYPE = 0x0008;

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

/* ── Per-session connection state ──────────────────────────────────────────── */
// The JNI layer supports multiple concurrent UDP sessions, each keyed by a
// positive integer handle returned from openSocket(). All subsequent calls take
// the handle so independent WiFi servers can run side by side.
struct Session {
    int udpSock = -1;
    struct sockaddr_in dest = {};
    uint8_t token[4] = {};
    uint8_t key[32] = {};
    std::atomic<uint32_t> counter{0};
    std::mutex sendMtx; // protects sendto for this session

    std::thread heartbeatThread;
    std::atomic<bool> heartbeatRunning{false};
    std::atomic<int> missedAcks{0};
    std::atomic<bool> connectionAlive{true};

    // Controller ACK tracking: (requestType << 16) | (ctrlIdx << 8) | result.
    // -1 means no ACK received yet.
    std::atomic<int32_t> lastControllerAck{-1};

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

/* ── Native gamepad input processor ────────────────────────────────────────
 *
 * Per-physical-device state and slot bindings live entirely in C++. Events
 * are processed inline in gamepadKeyFilter / gamepadMotionFilter (UI thread)
 * — see the filter callbacks further down. For satellite-bound slots the
 * resulting XUSB report is encrypted and sent inline. For Bluetooth-bound
 * slots the filter pushes the report onto a queue drained by btDispatchLoop
 * (BluetoothHidDevice.sendReport is Binder IPC; doing it inline would block
 * the UI thread).
 */

// Forward-declare — defined further down with the rest of the wire helpers.
static bool sendEncrypted(Session* s, uint16_t msgType, const uint8_t* payload,
                          uint16_t payloadLen);
static uint64_t nowMs();

// Pure XUSB constants, DeviceState, and the keycode/axes/state helpers live
// in gamepad_input.h so they can be exercised by the host-build googletest
// target (app/src/test/cpp). The JNI glue below converts GameActivity events
// into primitive args and delegates to that pure layer.
using gamepad::DeviceState;

enum SlotKind : uint8_t { SLOT_NONE = 0, SLOT_SATELLITE = 1, SLOT_BLUETOOTH = 2 };

struct SlotBinding {
    SlotKind kind = SLOT_NONE;
    int sessionHandle = -1;     // SATELLITE only
    int controllerIndex = -1;   // SATELLITE only
    std::string btConnectionId; // BLUETOOTH only (UTF-8). Empty otherwise.
};

static std::mutex g_devicesMtx;
static std::unordered_map<int32_t, DeviceState> g_devices;

static std::mutex g_slotsMtx;
static std::unordered_map<int32_t, SlotBinding> g_slots;

// JVM bridge for Bluetooth callback path. The bridge class + method id are
// resolved once via BluetoothGamepadBridge.nativeInstall (see below).
static JavaVM* g_jvm = nullptr;
static jclass g_btBridgeClass = nullptr; // global ref, set once
static jmethodID g_btDispatchMethod = nullptr;

/* ── BT dispatch queue ───────────────────────────────────────────────────
 *
 * Filter callbacks run on the UI thread for lowest input-to-wire latency.
 * The satellite path (encrypted UDP send) is fast enough to do inline. The
 * Bluetooth HID path is not — BluetoothHidDevice.sendReport is Binder IPC
 * and can block for hundreds of µs to several ms, which would jank the UI.
 *
 * So BT reports are pushed onto this queue from the filter and drained by
 * a dedicated worker thread that owns the JNI dispatch into Kotlin.
 *
 * Queue depth is bounded; if the BT thread can't keep up we drop the oldest
 * report (latest-wins semantics — old reports are stale anyway).
 */
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

// Read the primary pointer's current value for the given axis. We only deal
// with physical gamepads here, which always present pointer 0.
static inline float axisCur(const GameActivityMotionEvent* ev, int axis) {
    if (ev->pointerCount == 0) return 0.f;
    return ev->pointers[0].axisValues[axis];
}

// Look up the slot binding for a device and dispatch the report if state
// changed since the last publish. Holds g_slotsMtx briefly. Lock order:
// devices < slots < (sessions | btQueue).
//
// Satellite slots: encrypt + sendto inline. ChaCha20-Poly1305 of 16 bytes
// plus a non-blocking sendto is ~30 µs total — fine on the UI thread.
//
// Bluetooth slots: enqueue a copy of the report; a dedicated worker thread
// owns the Binder IPC. We don't hold any JVM ref across threads — the
// connection-id string is copied into the queue entry and a fresh local
// jstring is built on the dispatch side.
static void publishIfChanged(int32_t deviceId, DeviceState& s) {
    if (!gamepad::consumePublishIfChanged(s)) return;

    std::lock_guard<std::mutex> lock(g_slotsMtx);
    auto it = g_slots.find(deviceId);
    if (it == g_slots.end()) return;
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

/* ── Filter callbacks (UI thread) ────────────────────────────────────────
 *
 * Process events inline rather than just predicating + queueing. This is
 * the lowest-latency path Android exposes for gamepad input — the native
 * input buffer + android_main looper-wake are skipped entirely, so the
 * encrypted UDP packet leaves the device on the same thread that received
 * the event.
 *
 * Both filters return true on every gamepad/joystick event — this consumes
 * the event so it doesn't bubble back into the View hierarchy and trigger
 * incidental focus navigation. android_main still drains-and-clears the
 * native input buffer to keep memory bounded; it just doesn't re-process.
 */
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
        auto& state = g_devices[deviceId];
        if (gamepad::applyKey(state, kc, action == AKEY_EVENT_ACTION_DOWN)) {
            publishIfChanged(deviceId, state);
        }
    }
    return true; // consumed — do not propagate to View dispatch
}

// Per-device throttle for the diagnostic axis dump (DEBUG_GAMEPAD). Holds
// the last log timestamp keyed by deviceId. Accessed under g_devicesMtx.
static std::unordered_map<int32_t, uint64_t> g_axisLogLastMs;

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

    // "Latest sample wins" — historical samples are intermediate states the
    // next apply would overwrite anyway. Faster *and* fewer wire packets.
    float x = axisCur(ev, AMOTION_EVENT_AXIS_X);
    float y = axisCur(ev, AMOTION_EVENT_AXIS_Y);
    float z = axisCur(ev, AMOTION_EVENT_AXIS_Z);
    float rz = axisCur(ev, AMOTION_EVENT_AXIS_RZ);
    float rx = axisCur(ev, AMOTION_EVENT_AXIS_RX);
    float ry = axisCur(ev, AMOTION_EVENT_AXIS_RY);
    float hatX = axisCur(ev, AMOTION_EVENT_AXIS_HAT_X);
    float hatY = axisCur(ev, AMOTION_EVENT_AXIS_HAT_Y);
    float ltAxis = axisCur(ev, AMOTION_EVENT_AXIS_LTRIGGER);
    float rtAxis = axisCur(ev, AMOTION_EVENT_AXIS_RTRIGGER);
    float brake = axisCur(ev, AMOTION_EVENT_AXIS_BRAKE);
    float gas = axisCur(ev, AMOTION_EVENT_AXIS_GAS);

    // Right-stick axis varies by controller: most use Z/RZ (Xbox-style), but
    // some Bluetooth/HID gamepads use RX/RY. Take whichever pair has larger
    // magnitude so we work for both without per-device config.
    float rightX = std::fabs(z) >= std::fabs(rx) ? z : rx;
    float rightY = std::fabs(rz) >= std::fabs(ry) ? rz : ry;
    float lt = std::max(ltAxis, brake);
    float rt = std::max(rtAxis, gas);

    // Throttled diagnostic dump: once every 250 ms per device, only when at
    // least one axis is off-rest. Lets us see exactly what each physical
    // controller delivers without spamming logcat at idle.
    uint64_t now = nowMs();
    uint64_t& last = g_axisLogLastMs[deviceId];
    bool anyActive = std::fabs(x) > 0.05f || std::fabs(y) > 0.05f || std::fabs(z) > 0.05f ||
                     std::fabs(rz) > 0.05f || std::fabs(rx) > 0.05f || std::fabs(ry) > 0.05f ||
                     std::fabs(hatX) > 0.5f || std::fabs(hatY) > 0.5f || lt > 0.05f || rt > 0.05f;
    if (anyActive && now - last >= 250) {
        last = now;
        LOGI("AXES dev=%d src=0x%x X=%.2f Y=%.2f Z=%.2f RZ=%.2f RX=%.2f RY=%.2f "
             "HAT=(%.2f,%.2f) LT=%.2f(L=%.2f,B=%.2f) RT=%.2f(R=%.2f,G=%.2f)",
             deviceId, ev->source, x, y, z, rz, rx, ry, hatX, hatY, lt, ltAxis, brake, rt, rtAxis,
             gas);
    }

    gamepad::applyAxes(state, x, y, rightX, rightY, lt, rt, hatX, hatY);
    publishIfChanged(deviceId, state);
    return true;
}

static uint64_t nowMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

/* ── Helpers: big-endian encoding ──────────────────────────────────────────── */
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

/* ── Encrypt and send a message over the UDP channel ───────────────────────── */
static bool sendEncrypted(Session* s, uint16_t msgType, const uint8_t* payload,
                          uint16_t payloadLen) {
    if (!s || s->udpSock < 0) return false;

    // Inner message: type(2) + length(2) + payload
    uint16_t innerLen = 4 + payloadLen;
    uint8_t inner[4 + 256]; // max payload ~256 bytes
    if (innerLen > sizeof(inner)) return false;
    putBE16(inner, msgType);
    putBE16(inner + 2, payloadLen);
    if (payloadLen > 0) memcpy(inner + 4, payload, payloadLen);

    uint32_t ctr = s->counter.fetch_add(1, std::memory_order_relaxed);

    // Nonce: counter zero-padded to 12 bytes (big-endian, left-padded)
    uint8_t nonce[12] = {};
    putBE32(nonce + 8, ctr);

    // Encrypt: ciphertext = encrypted(inner) + 16-byte auth tag
    uint8_t ciphertext[sizeof(inner) + crypto_aead_chacha20poly1305_ietf_ABYTES];
    unsigned long long cipherLen = 0;
    crypto_aead_chacha20poly1305_ietf_encrypt(ciphertext, &cipherLen, inner, innerLen, s->token,
                                              4,       // AAD = token
                                              nullptr, // nsec (unused)
                                              nonce, s->key);

    // Packet: token(4) + counter(4) + ciphertext
    uint8_t packet[8 + sizeof(ciphertext)];
    memcpy(packet, s->token, 4);
    putBE32(packet + 4, ctr);
    memcpy(packet + 8, ciphertext, (size_t)cipherLen);

    size_t totalLen = 8 + (size_t)cipherLen;
    std::lock_guard<std::mutex> lock(s->sendMtx);
    ssize_t sent =
        sendto(s->udpSock, packet, totalLen, 0, (struct sockaddr*)&s->dest, sizeof(s->dest));
    return sent == (ssize_t)totalLen;
}

/* ── Heartbeat thread (one per session) ────────────────────────────────────── */
static void heartbeatLoop(std::shared_ptr<Session> s) {
    LOGI("Heartbeat thread started (sock=%d)", s->udpSock);
    while (s->heartbeatRunning.load(std::memory_order_relaxed)) {
        sendEncrypted(s.get(), MSG_HEARTBEAT_PING, nullptr, 0);
        s->missedAcks.fetch_add(1, std::memory_order_relaxed);

        if (s->missedAcks.load(std::memory_order_relaxed) >= HEARTBEAT_MISS_MAX) {
            LOGE("Missed %d heartbeat ACKs — connection dead", HEARTBEAT_MISS_MAX);
            s->connectionAlive.store(false, std::memory_order_relaxed);
        }

        for (int i = 0; i < HEARTBEAT_INTERVAL_MS / 100; i++) {
            if (!s->heartbeatRunning.load(std::memory_order_relaxed)) break;
            usleep(100000); // 100ms
        }
    }
    LOGI("Heartbeat thread stopped");
}
/* ── android_main — GameActivity native entry point ──────────────────────
 *
 * Input is processed inline by gamepadKeyFilter / gamepadMotionFilter on
 * the UI thread (lowest possible latency — see Filter callbacks above).
 * This loop is therefore lifecycle-only: it drives source->process for
 * onStart/onStop/onDestroy and drains the native input buffer so it doesn't
 * grow unbounded. The events themselves were already processed in the
 * filter; we just clear them here.
 */
void android_main(struct android_app* app) {
    LOGI("android_main started (filter-inline input mode)");

    // GameActivity populates only AXIS_X and AXIS_Y in pointers[].axisValues
    // by default; every other axis reads 0 until explicitly enabled. The
    // filter below reads right stick (Z/RZ), triggers (LT/RT + BRAKE/GAS),
    // and hat (HAT_X/HAT_Y) — opt them in here. If you ever read a new axis
    // in gamepadMotionFilter, add a matching enableAxis call.
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

/* ── libsodium init ────────────────────────────────────────────────────────── */
static std::once_flag g_sodiumInit;
static void ensureSodiumInit() {
    std::call_once(g_sodiumInit, []() {
        if (sodium_init() < 0)
            LOGE("sodium_init() failed!");
        else
            LOGI("libsodium initialized");
    });
}

/* ── UDP Socket ────────────────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_openSocket(
    JNIEnv* env, jobject, jstring ip, jint port) {
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

    struct timeval rtv = {0, 500000}; // 500ms recv timeout
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &rtv, sizeof(rtv));

    auto session = std::make_shared<Session>();
    session->udpSock = sock;
    const char* s = env->GetStringUTFChars(ip, nullptr);
    session->dest.sin_family = AF_INET;
    session->dest.sin_port = htons((uint16_t)port);
    inet_pton(AF_INET, s, &session->dest.sin_addr);
    env->ReleaseStringUTFChars(ip, s);

    int handle = g_nextHandle.fetch_add(1, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(g_sessionsMtx);
        g_sessions[handle] = session;
    }
    LOGI("UDP session %d opened -> port %d (TOS=0x%02X)", handle, port, tos);
    return handle;
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_data_network_SatelliteNative_closeSocket(JNIEnv*, jobject, jint handle) {
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

/* ── Connection params (called after HTTP POST /api/connections) ───────────── */

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_setConnectionParams(
    JNIEnv* env, jobject, jint handle, jbyteArray tokenArr, jbyteArray keyArr) {
    ensureSodiumInit();
    auto s = getSession(handle);
    if (!s) return;
    jbyte* tokenBytes = env->GetByteArrayElements(tokenArr, nullptr);
    jbyte* keyBytes = env->GetByteArrayElements(keyArr, nullptr);
    memcpy(s->token, tokenBytes, 4);
    memcpy(s->key, keyBytes, 32);
    s->counter.store(0);
    s->missedAcks.store(0);
    s->connectionAlive.store(true);
    env->ReleaseByteArrayElements(tokenArr, tokenBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(keyArr, keyBytes, JNI_ABORT);
    LOGI("Session %d params set (token=%02x%02x%02x%02x)", handle, s->token[0], s->token[1],
         s->token[2], s->token[3]);
}

/* ── Encrypted gamepad data ────────────────────────────────────────────────── */

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_sendReport(
    JNIEnv*, jobject, jint handle, jint controllerIndex, jint wB, jint bLT, jint bRT, jint sLX,
    jint sLY, jint sRX, jint sRY) {
    auto s = getSession(handle);
    if (!s) return;
    // Payload: controller_index(1B) + XUSB_REPORT(12B) = 13 bytes
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

/* ── Controller add/remove ─────────────────────────────────────────────────── */

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_controllerAdd(
    JNIEnv*, jobject, jint handle, jint controllerIndex, jint capabilities) {
    auto s = getSession(handle);
    if (!s) return;
    uint8_t payload[3];
    payload[0] = (uint8_t)(controllerIndex & 0xFF);
    putBE16(payload + 1, (uint16_t)(capabilities & 0xFFFF));
    sendEncrypted(s.get(), MSG_CONTROLLER_ADD, payload, 3);
    LOGI("Session %d: sent controller add idx=%d caps=0x%04X", handle, controllerIndex,
         capabilities);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_controllerRemove(
    JNIEnv*, jobject, jint handle, jint controllerIndex) {
    auto s = getSession(handle);
    if (!s) return;
    uint8_t payload[1] = {(uint8_t)(controllerIndex & 0xFF)};
    sendEncrypted(s.get(), MSG_CONTROLLER_REMOVE, payload, 1);
    LOGI("Session %d: sent controller remove idx=%d", handle, controllerIndex);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_sendControllerType(
    JNIEnv*, jobject, jint handle, jint controllerIndex, jint controllerType) {
    auto s = getSession(handle);
    if (!s) return;
    uint8_t payload[2];
    payload[0] = (uint8_t)(controllerIndex & 0xFF);
    payload[1] = (uint8_t)(controllerType & 0xFF);
    sendEncrypted(s.get(), MSG_CONTROLLER_TYPE, payload, 2);
    LOGI("Session %d: sent controller type idx=%d type=%d", handle, controllerIndex,
         controllerType);
}

/* ── Heartbeat start/stop ──────────────────────────────────────────────────── */

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_startHeartbeat(
    JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return;
    if (s->heartbeatRunning.load()) return;
    s->heartbeatRunning.store(true);
    s->missedAcks.store(0);
    s->connectionAlive.store(true);
    s->heartbeatThread = std::thread(heartbeatLoop, s);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_stopHeartbeat(
    JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return;
    s->heartbeatRunning.store(false);
    if (s->heartbeatThread.joinable()) s->heartbeatThread.join();
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_isConnectionAlive(
    JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return JNI_FALSE;
    return s->connectionAlive.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_getLastControllerAck(
    JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return -1;
    return s->lastControllerAck.load(std::memory_order_acquire);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_resetControllerAck(
    JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return;
    s->lastControllerAck.store(-1, std::memory_order_release);
}

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_getVigemAvailable(
    JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return -1;
    return (jint)s->vigemAvailable.load(std::memory_order_acquire);
}

JNIEXPORT jint JNICALL
Java_com_tinkernorth_dish_data_network_SatelliteNative_getActiveControllerCount(JNIEnv*, jobject,
                                                                                jint handle) {
    auto s = getSession(handle);
    if (!s) return -1;
    return (jint)s->activeControllerCount.load(std::memory_order_acquire);
}

/* ── Receive ACK (called from a background thread) ────────────────────────── */

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_data_network_SatelliteNative_receiveAck(JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s || s->udpSock < 0) return;
    uint8_t buf[128];
    struct sockaddr_in from = {};
    socklen_t fl = sizeof(from);
    ssize_t n = recvfrom(s->udpSock, buf, sizeof(buf), 0, (struct sockaddr*)&from, &fl);
    if (n < 8) return;

    if (memcmp(buf, s->token, 4) != 0) return;

    uint32_t ctr = ((uint32_t)buf[4] << 24) | ((uint32_t)buf[5] << 16) | ((uint32_t)buf[6] << 8) |
                   (uint32_t)buf[7];

    uint8_t nonce[12] = {};
    putBE32(nonce + 8, ctr);

    uint8_t decrypted[64];
    unsigned long long decLen = 0;
    size_t cipherLen = (size_t)n - 8;
    if (crypto_aead_chacha20poly1305_ietf_decrypt(decrypted, &decLen, nullptr, buf + 8, cipherLen,
                                                  s->token, 4, nonce, s->key) != 0) {
        return;
    }

    if (decLen < 4) return;
    uint16_t msgType = ((uint16_t)decrypted[0] << 8) | decrypted[1];
    uint16_t msgLen = ((uint16_t)decrypted[2] << 8) | decrypted[3];

    if (msgType == MSG_HEARTBEAT_ACK) {
        s->missedAcks.store(0);
        s->connectionAlive.store(true);
    } else if (msgType == MSG_CONTROLLER_ACK && msgLen >= 4 && decLen >= 8) {
        uint16_t reqType = ((uint16_t)decrypted[4] << 8) | decrypted[5];
        uint8_t ctrlIdx = decrypted[6];
        uint8_t result = decrypted[7];
        int32_t packed = ((int32_t)reqType << 16) | ((int32_t)ctrlIdx << 8) | (int32_t)result;
        s->lastControllerAck.store(packed, std::memory_order_release);
        LOGI("Session %d controller ACK: reqType=0x%04X idx=%d result=0x%02X", handle, reqType,
             ctrlIdx, result);
    } else if (msgType == MSG_SERVER_STATUS && msgLen >= 2 && decLen >= 6) {
        uint8_t vigem = decrypted[4];
        uint8_t count = decrypted[5];
        int8_t prevVigem =
            s->vigemAvailable.exchange((int8_t)(vigem ? 1 : 0), std::memory_order_release);
        int8_t prevCount =
            s->activeControllerCount.exchange((int8_t)count, std::memory_order_release);
        if (prevVigem != (int8_t)(vigem ? 1 : 0) || prevCount != (int8_t)count) {
            LOGI("Session %d server status: vigemAvailable=%d activeControllers=%d", handle, vigem,
                 count);
        }
    }
}

/* ── LAN Discovery ────────────────────────────────────────────────────────── */

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_discoverServers(
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

/* ── TCP Pairing ──────────────────────────────────────────────────────────── */

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_pair(
    JNIEnv* env, jobject, jstring ip, jint pairPort, jstring deviceId, jstring deviceName,
    jstring pin) {
    const char* ipStr = env->GetStringUTFChars(ip, nullptr);
    const char* idStr = env->GetStringUTFChars(deviceId, nullptr);
    const char* nameStr = env->GetStringUTFChars(deviceName, nullptr);
    const char* pinStr = env->GetStringUTFChars(pin, nullptr);
    std::string result = "{\"ok\":false,\"error\":\"connection failed\"}";
    int sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock >= 0) {
        int flags = fcntl(sock, F_GETFL, 0);
        fcntl(sock, F_SETFL, flags | O_NONBLOCK);
        struct sockaddr_in addr = {};
        addr.sin_family = AF_INET;
        addr.sin_port = htons((uint16_t)pairPort);
        inet_pton(AF_INET, ipStr, &addr.sin_addr);
        int ret = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
        if (ret < 0 && errno == EINPROGRESS) {
            struct timeval tv = {4, 0};
            fd_set wset;
            FD_ZERO(&wset);
            FD_SET(sock, &wset);
            ret = select(sock + 1, nullptr, &wset, nullptr, &tv);
        }
        if (ret > 0) {
            fcntl(sock, F_SETFL, flags & ~O_NONBLOCK);
            std::string msg = "{\"deviceId\":\"";
            msg += idStr;
            msg += "\",\"deviceName\":\"";
            msg += nameStr;
            msg += "\",\"pin\":\"";
            msg += pinStr;
            msg += "\"}";
            LOGI("pair: sending %s", msg.c_str());
            send(sock, msg.c_str(), (int)msg.size(), 0);
            struct timeval rtv = {5, 0};
            setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &rtv, sizeof(rtv));
            char buf[512] = {};
            int n = (int)recv(sock, buf, sizeof(buf) - 1, 0);
            if (n > 0) {
                buf[n] = '\0';
                result = std::string(buf, (size_t)n);
                LOGI("pair: received %d bytes: %s", n, buf);
            } else {
                LOGE("pair: recv failed (n=%d, errno=%d: %s)", n, errno, strerror(errno));
                result = "{\"ok\":false,\"error\":\"no response\"}";
            }
        } else {
            LOGE("pair: connect failed (ret=%d, errno=%d: %s)", ret, errno, strerror(errno));
        }
        close(sock);
    }
    env->ReleaseStringUTFChars(ip, ipStr);
    env->ReleaseStringUTFChars(deviceId, idStr);
    env->ReleaseStringUTFChars(deviceName, nameStr);
    env->ReleaseStringUTFChars(pin, pinStr);
    LOGI("pair: result = %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

/* ── HTTP helpers (minimal, no external deps) ──────────────────────────────── */

static std::string httpRequest(const char* method, const char* ip, int port, const char* path,
                               const char* body) {
    LOGI("httpRequest: %s %s:%d%s", method, ip, port, path);
    int sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock < 0) {
        LOGE("httpRequest: socket() failed: %s", strerror(errno));
        return "{\"error\":\"socket failed\"}";
    }

    int flags = fcntl(sock, F_GETFL, 0);
    fcntl(sock, F_SETFL, flags | O_NONBLOCK);
    struct sockaddr_in addr = {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    inet_pton(AF_INET, ip, &addr.sin_addr);

    int ret = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
    if (ret == 0) {
        // Immediate success (rare but valid)
    } else if (ret < 0 && errno == EINPROGRESS) {
        struct timeval tv = {5, 0};
        fd_set wset;
        FD_ZERO(&wset);
        FD_SET(sock, &wset);
        ret = select(sock + 1, nullptr, &wset, nullptr, &tv);
        if (ret <= 0) {
            LOGE("httpRequest: select() timeout/error connecting to %s:%d", ip, port);
            close(sock);
            return "{\"error\":\"connect timeout\"}";
        }
        // Check if the connection actually succeeded
        int sockerr = 0;
        socklen_t sl = sizeof(sockerr);
        getsockopt(sock, SOL_SOCKET, SO_ERROR, &sockerr, &sl);
        if (sockerr != 0) {
            LOGE("httpRequest: connect to %s:%d failed: %s", ip, port, strerror(sockerr));
            close(sock);
            return std::string("{\"error\":\"connect refused: ") + strerror(sockerr) + "\"}";
        }
    } else {
        LOGE("httpRequest: connect() to %s:%d failed immediately: %s", ip, port, strerror(errno));
        close(sock);
        return std::string("{\"error\":\"connect failed: ") + strerror(errno) + "\"}";
    }
    fcntl(sock, F_SETFL, flags & ~O_NONBLOCK);

    // Build HTTP request
    std::string req = std::string(method) + " " + path + " HTTP/1.1\r\n";
    req += "Host: ";
    req += ip;
    req += "\r\n";
    req += "Content-Type: application/json\r\n";
    req += "Connection: close\r\n";
    if (body && strlen(body) > 0) {
        req += "Content-Length: " + std::to_string(strlen(body)) + "\r\n";
    } else {
        req += "Content-Length: 0\r\n";
    }
    req += "\r\n";
    if (body && strlen(body) > 0) req += body;

    send(sock, req.c_str(), (int)req.size(), 0);

    struct timeval rtv = {5, 0};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &rtv, sizeof(rtv));

    // Read response
    std::string response;
    char buf[2048];
    while (true) {
        int n = (int)recv(sock, buf, sizeof(buf) - 1, 0);
        if (n <= 0) break;
        buf[n] = '\0';
        response += buf;
    }
    close(sock);

    // Extract body (after \r\n\r\n)
    size_t bodyStart = response.find("\r\n\r\n");
    if (bodyStart != std::string::npos) { return response.substr(bodyStart + 4); }
    return response;
}

/* ── POST /api/connections ─────────────────────────────────────────────────── */

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_httpConnect(
    JNIEnv* env, jobject, jstring ip, jint httpPort, jstring deviceId) {
    const char* ipStr = env->GetStringUTFChars(ip, nullptr);
    const char* idStr = env->GetStringUTFChars(deviceId, nullptr);

    std::string body = "{\"deviceId\":\"";
    body += idStr;
    body += "\"}";

    std::string result =
        httpRequest("POST", ipStr, (int)httpPort, "/api/connections", body.c_str());

    env->ReleaseStringUTFChars(ip, ipStr);
    env->ReleaseStringUTFChars(deviceId, idStr);
    LOGI("POST /api/connections -> %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

/* ── DELETE /api/connections/:id ───────────────────────────────────────────── */

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_httpDisconnect(
    JNIEnv* env, jobject, jstring ip, jint httpPort, jstring connectionId, jstring deviceId) {
    const char* ipStr = env->GetStringUTFChars(ip, nullptr);
    const char* connStr = env->GetStringUTFChars(connectionId, nullptr);
    const char* idStr = env->GetStringUTFChars(deviceId, nullptr);

    std::string path = "/api/connections/";
    path += connStr;

    std::string body = "{\"deviceId\":\"";
    body += idStr;
    body += "\"}";

    std::string result = httpRequest("DELETE", ipStr, (int)httpPort, path.c_str(), body.c_str());

    env->ReleaseStringUTFChars(ip, ipStr);
    env->ReleaseStringUTFChars(connectionId, connStr);
    env->ReleaseStringUTFChars(deviceId, idStr);
    LOGI("DELETE %s -> %s", path.c_str(), result.c_str());
    return env->NewStringUTF(result.c_str());
}

/* ── Physical-slot bindings (driven from MainActivity) ─────────────────── */

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_data_network_SatelliteNative_bindPhysicalSlotSatellite(
    JNIEnv*, jobject, jint deviceId, jint sessionHandle, jint controllerIndex) {
    std::lock_guard<std::mutex> lock(g_slotsMtx);
    auto& b = g_slots[deviceId];
    b.kind = SLOT_SATELLITE;
    b.sessionHandle = sessionHandle;
    b.controllerIndex = controllerIndex;
    b.btConnectionId.clear();
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_data_network_SatelliteNative_bindPhysicalSlotBluetooth(
    JNIEnv* env, jobject, jint deviceId, jstring connectionId) {
    const char* cstr = env->GetStringUTFChars(connectionId, nullptr);
    std::string copy = cstr ? std::string(cstr) : std::string();
    if (cstr) env->ReleaseStringUTFChars(connectionId, cstr);
    std::lock_guard<std::mutex> lock(g_slotsMtx);
    auto& b = g_slots[deviceId];
    b.kind = SLOT_BLUETOOTH;
    b.sessionHandle = -1;
    b.controllerIndex = -1;
    b.btConnectionId = std::move(copy);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_unbindPhysicalSlot(
    JNIEnv*, jobject, jint deviceId) {
    std::lock_guard<std::mutex> lock(g_slotsMtx);
    g_slots.erase(deviceId);
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_data_network_SatelliteNative_clearAllPhysicalSlots(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_slotsMtx);
    g_slots.clear();
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_data_network_SatelliteNative_setDeviceDeadzones(
    JNIEnv*, jobject, jint deviceId, jfloat flatX, jfloat flatY, jfloat flatZ, jfloat flatRZ) {
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    auto& s = g_devices[deviceId];
    s.flatX = flatX;
    s.flatY = flatY;
    s.flatZ = flatZ;
    s.flatRZ = flatRZ;
}

// Force-zero every device that's bound to a slot and emit a release-all
// report. Used on focus loss so no button stays held server-side.
JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_data_network_SatelliteNative_releaseAllPhysicalReports(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    for (auto& kv : g_devices) {
        gamepad::resetState(kv.second);
        publishIfChanged(kv.first, kv.second);
    }
}

/* ── Bluetooth bridge wiring ──────────────────────────────────────────── */
// Called once from BluetoothGamepadBridge.install(). We register the bridge
// class here, not in JNI_OnLoad, because FindClass() in JNI_OnLoad uses the
// system classloader (which doesn't see app classes); from a regular JNI
// call the app classloader is on the call stack so the lookup succeeds.
JNIEXPORT void JNICALL Java_com_tinkernorth_dish_data_network_BluetoothGamepadBridge_nativeInstall(
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
    // The BT worker thread takes Binder-IPC reports off the lock-free queue
    // populated by gamepadKeyFilter / gamepadMotionFilter on the UI thread.
    // Idempotent — safe if install() is ever called twice.
    startBtDispatchThread();
}

} // extern "C"

/* ── JNI_OnLoad: cache JavaVM only ────────────────────────────────────── */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}
