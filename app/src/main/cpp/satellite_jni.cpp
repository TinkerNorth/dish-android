// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

/*
 * satellite_jni.cpp — Native bridge for the Dish Android client.
 * GameActivity provides the native thread (android_main) for lifecycle.
 * Controller input is handled via direct JNI calls from Kotlin for
 * lowest latency (Java dispatchGenericMotionEvent → JNI sendReport → sendto).
 * Also handles LAN discovery, TCP pairing, HTTP connection API, and
 * encrypted UDP streaming (ChaCha20-Poly1305) via JNI.
 */
#include <jni.h>
#include <android/log.h>
#include <game-activity/GameActivity.h>
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
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include <memory>
#include <unordered_map>
#include <sodium.h>

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
/* ── android_main — GameActivity native entry point (lifecycle only) ───── */
void android_main(struct android_app* app) {
    LOGI("android_main started (lifecycle-only mode)");

    // Minimal lifecycle loop — input is handled via direct JNI calls from Kotlin
    while (true) {
        int events;
        struct android_poll_source* source;

        // Block indefinitely waiting for lifecycle events
        while (ALooper_pollOnce(-1, nullptr, &events, (void**)&source) >= 0) {
            if (source != nullptr) { source->process(source->app, source); }
            if (app->destroyRequested) {
                LOGI("android_main: destroy requested");
                return;
            }
        }
    }
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

} // extern "C"
