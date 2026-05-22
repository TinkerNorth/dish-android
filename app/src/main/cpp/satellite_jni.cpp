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
 * Also handles the LAN broadcast discovery beacon and the heartbeat/ACK loops
 * for the encrypted UDP wire (ChaCha20-Poly1305).
 *
 * The satellite's client-facing API (connection management and PIN pairing) is
 * HTTPS/TLS now; that lives in Kotlin (SatelliteHttpClient) where TLS is a
 * one-liner, rather than pulling an SSL library into this NDK build.
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

#include "gamepad_input.h"
#include "wire_encoders.h"

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
// Mid-session capability update — same payload shape as the caps field
// of MSG_CONTROLLER_ADD (ctrlIdx + caps BE16), but for an already-
// registered controller. Lets the dish push e.g. a user-flipped motion
// toggle without unplugging the controller on the receiver. See
// `MSG_CONTROLLER_CAPS_UPDATE` in satellite/src/core/types.h for the
// authoritative spec and the receiver's silent-drop fallback.
static constexpr uint16_t MSG_CONTROLLER_CAPS_UPDATE = 0x000E;
static constexpr uint16_t MSG_RUMBLE = 0x0009;
static constexpr uint16_t MSG_MOTION = 0x000A;
static constexpr uint16_t MSG_BATTERY = 0x000B;
static constexpr uint16_t MSG_LIGHTBAR = 0x000D;

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
    // No userspace sendMtx: Linux UDP `sendto` is thread-safe per-socket
    // (kernel serialises via the socket's own lock), and our protocol is
    // tolerant of slight reordering across producers (each packet carries
    // its own counter-as-nonce; the host's virtual gamepad applies state
    // idempotently). Adding a userspace lock here would only propagate
    // any one thread's kernel-side stall to every other producer.

    std::thread heartbeatThread;
    std::atomic<bool> heartbeatRunning{false};
    std::atomic<int> missedAcks{0};
    std::atomic<bool> connectionAlive{true};

    // Controller ACK tracking: (requestType << 16) | (ctrlIdx << 8) | result.
    // -1 means no ACK received yet.
    std::atomic<int32_t> lastControllerAck{-1};

    // Motion-status byte from the most recent MSG_CONTROLLER_ACK (only present
    // on MSG_CONTROLLER_ADD acks from a post-extension satellite — msgLen 5
    // instead of 4). Bit 0: receiver's backend supports IMU for this slot's
    // chosen type; bit 1: backend successfully created the per-serial IMU
    // sink. -1 means no extended ACK has been observed for this session —
    // either no ACK at all, or the satellite is a pre-extension build that
    // only sent the legacy 4-byte payload. The dish-side store collapses
    // -1 onto "unknown" so an old satellite doesn't get treated as a
    // permanent failure.
    std::atomic<int32_t> lastControllerAckMotionFlags{-1};

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

// JVM bridge for the rumble return path. Resolved once via
// RumbleBridge.nativeInstall. Unlike the BT bridge there is no dedicated
// dispatcher thread — receiveAck() already runs on a JVM-attached thread
// (Kotlin Dispatchers.IO), so we can call into Java directly from there.
static jclass g_rumbleBridgeClass = nullptr; // global ref, set once
static jmethodID g_rumbleDispatchMethod = nullptr;

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
    float z = axisCur(ev, AMOTION_EVENT_AXIS_Z);
    float rz = axisCur(ev, AMOTION_EVENT_AXIS_RZ);
    float rx = axisCur(ev, AMOTION_EVENT_AXIS_RX);
    float ry = axisCur(ev, AMOTION_EVENT_AXIS_RY);
    // Right-stick axis varies by controller: most use Z/RZ (Xbox-style), but
    // some Bluetooth/HID gamepads use RX/RY. Take whichever pair has larger
    // magnitude so we work for both without per-device config.
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

// dish_wire::encodeMotionPayload / encodeBatteryPayload live in wire_encoders.h
// so app/src/test/cpp/ can include them without dragging in JNI headers.

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
    // MSG_DONTWAIT: make sendto non-blocking. Without this, when the Wi-Fi
    // power-save state transitions or the radio TX buffer fills, sendto
    // could block in the kernel for hundreds of ms (worst observed during
    // diagnosis: 1.56 s, with that latency propagating to every other
    // thread waiting to send). With MSG_DONTWAIT the call returns -1 /
    // EAGAIN immediately instead; the next periodic-resend tick tries
    // again with the latest state. UDP is already lossy by contract, so
    // a buffer-full drop is no worse than any other packet loss.
    ssize_t sent = sendto(s->udpSock, packet, totalLen, MSG_DONTWAIT, (struct sockaddr*)&s->dest,
                          sizeof(s->dest));
    // EAGAIN / EWOULDBLOCK: kernel send buffer momentarily unavailable.
    // Treat as a soft drop, not a hard error — UDP semantics absorb it and
    // the next periodic tick will refresh the state.
    if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) { return true; }
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

/* ── Connection params (called after HTTP POST /api/connections) ───────────── */

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_setConnectionParams(
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

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendReport(
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

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_controllerAdd(
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

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_controllerRemove(
    JNIEnv*, jobject, jint handle, jint controllerIndex) {
    auto s = getSession(handle);
    if (!s) return;
    uint8_t payload[1] = {(uint8_t)(controllerIndex & 0xFF)};
    sendEncrypted(s.get(), MSG_CONTROLLER_REMOVE, payload, 1);
    LOGI("Session %d: sent controller remove idx=%d", handle, controllerIndex);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendControllerType(
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

// Push a new capability word for an already-registered controller. Wire
// payload mirrors the caps field of MSG_CONTROLLER_ADD: ctrlIdx(1) +
// caps(2 BE) = 3 bytes. The Kotlin caller (SatelliteConnectionManager)
// only sends this when the dish-side composer's `toCapBits(slotId)`
// differs from what was last advertised — the receiver no-ops on
// duplicates, so the de-dup is technically redundant, but the wire
// saving + log-line saving is worth the cheap check.
JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendControllerCapsUpdate(
    JNIEnv*, jobject, jint handle, jint controllerIndex, jint capabilities) {
    auto s = getSession(handle);
    if (!s) return;
    uint8_t payload[3];
    payload[0] = (uint8_t)(controllerIndex & 0xFF);
    putBE16(payload + 1, (uint16_t)(capabilities & 0xFFFF));
    sendEncrypted(s.get(), MSG_CONTROLLER_CAPS_UPDATE, payload, 3);
    LOGI("Session %d: sent controller caps update idx=%d caps=0x%04X", handle, controllerIndex,
         capabilities);
}

/* ── Motion (gyro + accel) ────────────────────────────────────────────────── */
//
// Hot path. The Kotlin caller (MotionScaling.gyroRadToWire /
// accelMssToWire) has already done all the unit conversion — rad/s →
// int16 with 1 LSB = 2000/32767 deg/s, and m/s² → int16 with 1 LSB =
// 4/32767 g. This function does NO scaling; it only packs the int16
// values into the 17-byte MSG_MOTION (0x000A) wire payload via
// dish_wire::encodeMotionPayload. Per-controller rate-limiting also
// happens up-stack (MotionRateLimiter.kt); reaching this method means
// the caller has already passed the 250 Hz gate.
//
// The satellite docs §0x000A is the canonical spec for the wire layout.
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

/* ── Battery (level + status) ─────────────────────────────────────────────── */
//
// Low-rate (30 s cadence) — see BatteryValidator.kt for the per-sample
// validation before the Kotlin → JNI call (it validates, it does not dedup).
// `level` is 0..100 inclusive, or 0xFF for unknown. `status` is one of the
// BATTERY_STATUS_* constants documented in satellite/docs/protocol.md §0x000B.
JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_sendBattery(
    JNIEnv*, jobject, jint handle, jint controllerIndex, jint level, jint status) {
    auto s = getSession(handle);
    if (!s) return;
    uint8_t payload[3];
    dish_wire::encodeBatteryPayload(payload, (uint8_t)(controllerIndex & 0xFF),
                                    (uint8_t)(level & 0xFF), (uint8_t)(status & 0xFF));
    sendEncrypted(s.get(), MSG_BATTERY, payload, sizeof(payload));
}

/* ── Heartbeat start/stop ──────────────────────────────────────────────────── */

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

JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_getLastControllerAck(
    JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return -1;
    return s->lastControllerAck.load(std::memory_order_acquire);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_resetControllerAck(
    JNIEnv*, jobject, jint handle) {
    auto s = getSession(handle);
    if (!s) return;
    s->lastControllerAck.store(-1, std::memory_order_release);
    // Reset the motion-flags shadow too so a new registration doesn't read
    // the previous slot's flags. Kept atomic-paired with lastControllerAck
    // (same reset point) — divergence would mean a freshly-reset slot
    // could spuriously read its predecessor's "kernel rejected" flag.
    s->lastControllerAckMotionFlags.store(-1, std::memory_order_release);
}

// Latest MSG_CONTROLLER_ACK motion-flags byte for this session, or -1 if no
// extended ACK has been observed. Bits as per ACK_MOTION_FLAG_* in the
// satellite's core/types.h:
//   bit 0 — receiver's backend supports IMU for the slot's chosen type
//   bit 1 — backend successfully created the per-serial IMU sink
// A pre-extension satellite leaves this at -1; the Kotlin side collapses
// -1 onto "unknown" rather than treating either bit as false (which would
// permanently disable motion against an old satellite).
JNIEXPORT jint JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_getLastControllerMotionFlags(JNIEnv*, jobject,
                                                                                jint handle) {
    auto s = getSession(handle);
    if (!s) return -1;
    return s->lastControllerAckMotionFlags.load(std::memory_order_acquire);
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

/* ── Receive ACK (called from a background thread) ────────────────────────── */

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_receiveAck(JNIEnv* env,
                                                                                     jobject,
                                                                                     jint handle) {
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

    // Sized to match buf[128] so the decrypt destination can never be
    // overflowed: plaintext is (cipherLen - 16) bytes and cipherLen <= n - 8
    // <= 120, so the largest possible plaintext (~104 B) still fits. A 64-byte
    // buffer here was a latent stack overflow — not currently reachable (no
    // server→client message is that large and the Poly1305 tag must verify
    // first), but the bound must hold structurally, not by message-set luck.
    uint8_t decrypted[sizeof(buf)];
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
        // Optional motion-status byte (post-extension satellites). A
        // pre-extension satellite sends only 4 bytes (msgLen == 4) and the
        // extra byte is absent — leave the stored flags at whatever the
        // prior ACK left, or -1 if never written, so a slot with no
        // observation stays "unknown" rather than being misread as
        // "backend broken." A post-extension satellite always sends the
        // byte (zero or not), so msgLen >= 5 is the live-data branch.
        if (msgLen >= 5 && decLen >= 9) {
            s->lastControllerAckMotionFlags.store((int32_t)decrypted[8], std::memory_order_release);
            LOGI("Session %d controller ACK: reqType=0x%04X idx=%d result=0x%02X motion=0x%02X",
                 handle, reqType, ctrlIdx, result, decrypted[8]);
        } else {
            LOGI("Session %d controller ACK: reqType=0x%04X idx=%d result=0x%02X (legacy)", handle,
                 reqType, ctrlIdx, result);
        }
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
    } else if (msgType == MSG_RUMBLE && msgLen == 7 && decLen >= 11) {
        // Wire layout (BE16 magnitudes/duration) — fixed 7-byte payload:
        //   ctrlIdx(1) strong(2) weak(2) durMs(2)
        if (g_rumbleBridgeClass == nullptr || g_rumbleDispatchMethod == nullptr) return;
        const jint ctrlIdx = (jint)decrypted[4];
        const jint strong = ((jint)decrypted[5] << 8) | (jint)decrypted[6];
        const jint weakMag = ((jint)decrypted[7] << 8) | (jint)decrypted[8];
        const jint durMs = ((jint)decrypted[9] << 8) | (jint)decrypted[10];
        env->CallStaticVoidMethod(g_rumbleBridgeClass, g_rumbleDispatchMethod, handle, ctrlIdx,
                                  strong, weakMag, durMs);
        if (env->ExceptionCheck()) env->ExceptionClear();
    } else if (msgType == MSG_LIGHTBAR && msgLen == 4 && decLen >= 8) {
        // Dedicated controller-RGB-LED message (satellite → sender). Android
        // exposes no controller-LED API, so dish-android cannot actuate this
        // and intentionally does not advertise CAP_LIGHTBAR — a capability-
        // aware satellite won't send 0x000D here. We still decode and log any
        // that arrive so the return path is observable, then drop the packet.
        // Wire layout: ctrlIdx(1) R(1) G(1) B(1).
        const dish_wire::LightbarPayload lb = dish_wire::decodeLightbarPayload(decrypted + 4);
        LOGI("Session %d lightbar (no LED API on Android, dropping): idx=%d rgb=%02X%02X%02X",
             handle, lb.ctrlIdx, lb.r, lb.g, lb.b);
    }
}

/* ── LAN Discovery ────────────────────────────────────────────────────────── */

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

/* ── Connection API + PIN pairing ──────────────────────────────────────────
 *
 * Both moved out of native code. The satellite's client-facing API is HTTPS
 * (TLS) now — POST/DELETE /api/connections for connection management and
 * POST /api/pair for PIN pairing (previously a bespoke raw-TCP line protocol).
 * Doing TLS here would mean adding an SSL library to the NDK CMake build and
 * driving a handshake by hand; in Kotlin it is a HttpsURLConnection with a
 * trust-all SSLContext. See SatelliteHttpClient.kt.
 */

/* ── Physical-slot bindings (driven from MainActivity) ─────────────────── */

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_bindPhysicalSlotSatellite(
    JNIEnv*, jobject, jint deviceId, jint sessionHandle, jint controllerIndex) {
    std::lock_guard<std::mutex> lock(g_slotsMtx);
    auto& b = g_slots[deviceId];
    b.kind = SLOT_SATELLITE;
    b.sessionHandle = sessionHandle;
    b.controllerIndex = controllerIndex;
    b.btConnectionId.clear();
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_bindPhysicalSlotBluetooth(
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

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_core_jni_SatelliteNative_setDeviceDeadzones(
    JNIEnv*, jobject, jint deviceId, jfloat flatX, jfloat flatY, jfloat flatZ, jfloat flatRZ) {
    std::lock_guard<std::mutex> lock(g_devicesMtx);
    auto& s = g_devices[deviceId];
    s.flatX = flatX;
    s.flatY = flatY;
    s.flatZ = flatZ;
    s.flatRZ = flatRZ;
}

/* ── Activity-level dispatch entry points ────────────────────────────────
 *
 * GameActivity routes generic motion events through its SurfaceView's
 * OnGenericMotionListener, which sits deep enough in Android's input
 * dispatch chain that for some controllers (notably the Nintendo Switch
 * Pro Controller via USB on Pixel devices) the input system synthesizes
 * DPAD key events from stick movement *before* the event ever reaches the
 * SurfaceView. The result is left-stick movement arriving as
 * DPAD_UP/DOWN/LEFT/RIGHT keycodes and the right stick going dead.
 *
 * Intercepting at the Activity dispatch level (the path the pre-refactor
 * Kotlin code used) and consuming the event there prevents that fallback
 * synthesis from running. These entry points reuse the same g_devices
 * state, applyKey/applyAxes pipeline, and publishIfChanged gate as the
 * GameActivity filter callbacks, so they share locking and the
 * send-on-change behaviour.
 *
 * Returns JNI_TRUE if the event was a gamepad input we recognised. The
 * caller should treat that as "consumed" and not fall through to super.
 */
JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_processGamepadKeyEvent(
    JNIEnv*, jobject, jint deviceId, jint /*source*/, jint action, jint keyCode) {
    // The event's source bits are unreliable as a "is this a gamepad event"
    // discriminator: generic HID joystick adapters dispatch button events
    // with src=AINPUT_SOURCE_KEYBOARD even though the device itself exposes
    // AINPUT_SOURCE_JOYSTICK. The mapped-keycode check below is the real
    // gate — if the keycode resolves to an XUSB button (or one of the
    // four trigger-via-key keycodes), this is a gamepad event regardless of
    // which source flag Android tagged it with. Caller (Activity dispatch)
    // is responsible for not handing us events from devices that aren't
    // gamepads in the first place.
    bool isMappedKey = (keyCode == AKEYCODE_BUTTON_L2 || keyCode == AKEYCODE_BUTTON_R2 ||
                        keyCode == AKEYCODE_BUTTON_7 || keyCode == AKEYCODE_BUTTON_8) ||
                       gamepad::keycodeToXusb(keyCode) != 0;
    if (!isMappedKey) return JNI_FALSE;
    if (action != AKEY_EVENT_ACTION_DOWN && action != AKEY_EVENT_ACTION_UP) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_devicesMtx);
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
    // Right-stick axis varies by controller: most use Z/RZ (Xbox-style), some
    // use RX/RY. Pick whichever pair has larger magnitude so both layouts work
    // without per-device config.
    float rightX = std::fabs(z) >= std::fabs(rx) ? z : rx;
    float rightY = std::fabs(rz) >= std::fabs(ry) ? rz : ry;
    float lt = std::max(lTrigger, brake);
    float rt = std::max(rTrigger, gas);
    gamepad::applyAxes(state, x, y, rightX, rightY, lt, rt, hatX, hatY);
    publishIfChanged(deviceId, state);
    return JNI_TRUE;
}

// Force-zero every device that's bound to a slot and emit a release-all
// report. Used on focus loss so no button stays held server-side.
JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_core_jni_SatelliteNative_releaseAllPhysicalReports(JNIEnv*, jobject) {
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
    // The BT worker thread takes Binder-IPC reports off the lock-free queue
    // populated by gamepadKeyFilter / gamepadMotionFilter on the UI thread.
    // Idempotent — safe if install() is ever called twice.
    startBtDispatchThread();
}

// Mirror of the BT bridge install path, but for the rumble return path. We
// only need the class + method ids — receiveAck() is already invoked from a
// JVM-attached thread (Kotlin Dispatchers.IO), so there's no separate
// dispatcher thread to spawn here.
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

} // extern "C"

/* ── JNI_OnLoad: cache JavaVM only ────────────────────────────────────── */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}
