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
#include <sodium.h>

#define TAG "SatelliteJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ── Constants ─────────────────────────────────────────────────────────────── */
static constexpr int HEARTBEAT_INTERVAL_MS = 2000;
static constexpr int HEARTBEAT_MISS_MAX = 3;
// Message types
static constexpr uint16_t MSG_GAMEPAD_DATA = 0x0001;
static constexpr uint16_t MSG_HEARTBEAT_PING = 0x0002;
static constexpr uint16_t MSG_HEARTBEAT_ACK = 0x0003;
static constexpr uint16_t MSG_CONTROLLER_ADD = 0x0004;
static constexpr uint16_t MSG_CONTROLLER_REMOVE = 0x0005;
static constexpr uint16_t MSG_CONTROLLER_ACK = 0x0006;
static constexpr uint16_t MSG_SERVER_STATUS = 0x0007;
static constexpr uint16_t MSG_CONTROLLER_TYPE = 0x0008;

// ACK result codes
static constexpr uint8_t ACK_OK = 0x00;
static constexpr uint8_t ACK_ERR_VIGEM_UNAVAIL = 0x01;
static constexpr uint8_t ACK_ERR_NO_SLOTS = 0x02;
static constexpr uint8_t ACK_ERR_ALREADY_EXISTS = 0x03;
static constexpr uint8_t ACK_ERR_NOT_FOUND = 0x04;
static constexpr uint8_t ACK_ERR_PLUGIN_FAIL = 0x05;

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

/* ── Connection state ──────────────────────────────────────────────────────── */
static int g_udpSock = -1;
static struct sockaddr_in g_dest = {};
static uint8_t g_token[4] = {};
static uint8_t g_key[32] = {};
static std::atomic<uint32_t> g_counter{0};
static std::mutex g_sendMtx; // protects sendto

// Heartbeat thread
static std::thread g_heartbeatThread;
static std::atomic<bool> g_heartbeatRunning{false};
static std::atomic<int> g_missedAcks{0};
static std::atomic<bool> g_connectionAlive{true};

// Controller ACK tracking
// Packed as: (requestType << 16) | (ctrlIdx << 8) | result
// -1 means no ACK received yet
static std::atomic<int32_t> g_lastControllerAck{-1};

// Server status (from 0x0007)
static std::atomic<int8_t> g_vigemAvailable{-1}; // -1=unknown, 0=idle/unavailable, 1=available
static std::atomic<int8_t> g_activeControllerCount{-1}; // -1=unknown, 0+=count

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
static bool sendEncrypted(uint16_t msgType, const uint8_t* payload, uint16_t payloadLen) {
    if (g_udpSock < 0) return false;

    // Inner message: type(2) + length(2) + payload
    uint16_t innerLen = 4 + payloadLen;
    uint8_t inner[4 + 256]; // max payload ~256 bytes
    if (innerLen > sizeof(inner)) return false;
    putBE16(inner, msgType);
    putBE16(inner + 2, payloadLen);
    if (payloadLen > 0) memcpy(inner + 4, payload, payloadLen);

    // Get next counter
    uint32_t ctr = g_counter.fetch_add(1, std::memory_order_relaxed);

    // Nonce: counter zero-padded to 12 bytes (big-endian, left-padded)
    uint8_t nonce[12] = {};
    putBE32(nonce + 8, ctr);

    // Encrypt: ciphertext = encrypted(inner) + 16-byte auth tag
    uint8_t ciphertext[sizeof(inner) + crypto_aead_chacha20poly1305_ietf_ABYTES];
    unsigned long long cipherLen = 0;
    crypto_aead_chacha20poly1305_ietf_encrypt(ciphertext, &cipherLen, inner, innerLen, g_token,
                                              4,       // AAD = token
                                              nullptr, // nsec (unused)
                                              nonce, g_key);

    // Packet: token(4) + counter(4) + ciphertext
    uint8_t packet[8 + sizeof(ciphertext)];
    memcpy(packet, g_token, 4);
    putBE32(packet + 4, ctr);
    memcpy(packet + 8, ciphertext, (size_t)cipherLen);

    size_t totalLen = 8 + (size_t)cipherLen;
    std::lock_guard<std::mutex> lock(g_sendMtx);
    ssize_t sent =
        sendto(g_udpSock, packet, totalLen, 0, (struct sockaddr*)&g_dest, sizeof(g_dest));
    return sent == (ssize_t)totalLen;
}

/* ── Heartbeat thread ──────────────────────────────────────────────────────── */
static void heartbeatLoop() {
    LOGI("Heartbeat thread started");
    while (g_heartbeatRunning.load(std::memory_order_relaxed)) {
        // Send heartbeat ping
        sendEncrypted(MSG_HEARTBEAT_PING, nullptr, 0);
        g_missedAcks.fetch_add(1, std::memory_order_relaxed);

        // Check if we've missed too many
        if (g_missedAcks.load(std::memory_order_relaxed) >= HEARTBEAT_MISS_MAX) {
            LOGE("Missed %d heartbeat ACKs — connection dead", HEARTBEAT_MISS_MAX);
            g_connectionAlive.store(false, std::memory_order_relaxed);
        }

        // Sleep for heartbeat interval (in 100ms increments for responsiveness)
        for (int i = 0; i < HEARTBEAT_INTERVAL_MS / 100; i++) {
            if (!g_heartbeatRunning.load(std::memory_order_relaxed)) break;
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

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_SatelliteNative_openSocket(JNIEnv* env,
                                                                                jobject, jstring ip,
                                                                                jint port) {
    ensureSodiumInit();
    if (g_udpSock >= 0) {
        close(g_udpSock);
        g_udpSock = -1;
    }
    g_udpSock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (g_udpSock < 0) {
        LOGE("socket() failed");
        return JNI_FALSE;
    }

    int tos = 0xB8;
    if (setsockopt(g_udpSock, IPPROTO_IP, IP_TOS, &tos, sizeof(tos)) < 0)
        LOGI("IP_TOS not supported (non-fatal): %s", strerror(errno));

    int busyPoll = 50;
    if (setsockopt(g_udpSock, SOL_SOCKET, SO_BUSY_POLL, &busyPoll, sizeof(busyPoll)) < 0)
        LOGI("SO_BUSY_POLL not supported (non-fatal): %s", strerror(errno));

    // Set recv timeout for heartbeat ACK reception
    struct timeval rtv = {0, 500000}; // 500ms
    setsockopt(g_udpSock, SOL_SOCKET, SO_RCVTIMEO, &rtv, sizeof(rtv));

    const char* s = env->GetStringUTFChars(ip, nullptr);
    memset(&g_dest, 0, sizeof(g_dest));
    g_dest.sin_family = AF_INET;
    g_dest.sin_port = htons((uint16_t)port);
    inet_pton(AF_INET, s, &g_dest.sin_addr);
    env->ReleaseStringUTFChars(ip, s);
    LOGI("UDP socket opened -> port %d (TOS=0x%02X)", port, tos);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_SatelliteNative_closeSocket(JNIEnv*, jobject) {
    // Stop heartbeat first
    g_heartbeatRunning.store(false);
    if (g_heartbeatThread.joinable()) g_heartbeatThread.join();
    if (g_udpSock >= 0) {
        close(g_udpSock);
        g_udpSock = -1;
    }
    g_counter.store(0);
    memset(g_key, 0, sizeof(g_key));
    memset(g_token, 0, sizeof(g_token));
    g_missedAcks.store(0);
    g_connectionAlive.store(true);
    g_lastControllerAck.store(-1);
    g_vigemAvailable.store(-1);
    g_activeControllerCount.store(-1);
}

/* ── Connection params (called after HTTP POST /api/connections) ───────────── */

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_SatelliteNative_setConnectionParams(
    JNIEnv* env, jobject, jbyteArray tokenArr, jbyteArray keyArr) {
    ensureSodiumInit();
    jbyte* tokenBytes = env->GetByteArrayElements(tokenArr, nullptr);
    jbyte* keyBytes = env->GetByteArrayElements(keyArr, nullptr);
    memcpy(g_token, tokenBytes, 4);
    memcpy(g_key, keyBytes, 32);
    g_counter.store(0);
    g_missedAcks.store(0);
    g_connectionAlive.store(true);
    env->ReleaseByteArrayElements(tokenArr, tokenBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(keyArr, keyBytes, JNI_ABORT);
    LOGI("Connection params set (token=%02x%02x%02x%02x)", g_token[0], g_token[1], g_token[2],
         g_token[3]);
}

/* ── Encrypted gamepad data ────────────────────────────────────────────────── */

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_SatelliteNative_sendReport(
    JNIEnv*, jobject, jint controllerIndex, jint wB, jint bLT, jint bRT, jint sLX, jint sLY,
    jint sRX, jint sRY) {
    if (g_udpSock < 0) return;
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
    sendEncrypted(MSG_GAMEPAD_DATA, payload, 13);
}

/* ── Controller add/remove ─────────────────────────────────────────────────── */

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_SatelliteNative_controllerAdd(JNIEnv*, jobject,
                                                                               jint controllerIndex,
                                                                               jint capabilities) {
    // Payload: controller_index(1B) + capabilities(2B big-endian)
    uint8_t payload[3];
    payload[0] = (uint8_t)(controllerIndex & 0xFF);
    putBE16(payload + 1, (uint16_t)(capabilities & 0xFFFF));
    sendEncrypted(MSG_CONTROLLER_ADD, payload, 3);
    LOGI("Sent controller add: index=%d caps=0x%04X", controllerIndex, capabilities);
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_SatelliteNative_controllerRemove(JNIEnv*, jobject, jint controllerIndex) {
    uint8_t payload[1] = {(uint8_t)(controllerIndex & 0xFF)};
    sendEncrypted(MSG_CONTROLLER_REMOVE, payload, 1);
    LOGI("Sent controller remove: index=%d", controllerIndex);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_SatelliteNative_sendControllerType(
    JNIEnv*, jobject, jint controllerIndex, jint controllerType) {
    // Payload: controller_index(1B) + controller_type(1B)
    uint8_t payload[2];
    payload[0] = (uint8_t)(controllerIndex & 0xFF);
    payload[1] = (uint8_t)(controllerType & 0xFF);
    sendEncrypted(MSG_CONTROLLER_TYPE, payload, 2);
    LOGI("Sent controller type: index=%d type=%d", controllerIndex, controllerType);
}

/* ── Heartbeat start/stop ──────────────────────────────────────────────────── */

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_SatelliteNative_startHeartbeat(JNIEnv*, jobject) {
    if (g_heartbeatRunning.load()) return;
    g_heartbeatRunning.store(true);
    g_missedAcks.store(0);
    g_connectionAlive.store(true);
    g_heartbeatThread = std::thread(heartbeatLoop);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_SatelliteNative_stopHeartbeat(JNIEnv*, jobject) {
    g_heartbeatRunning.store(false);
    if (g_heartbeatThread.joinable()) g_heartbeatThread.join();
}

JNIEXPORT jboolean JNICALL Java_com_tinkernorth_dish_SatelliteNative_isConnectionAlive(JNIEnv*,
                                                                                       jobject) {
    return g_connectionAlive.load() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Returns the last controller ACK as a packed int32:
 *   bits 31-16: requestType (0x0004 or 0x0005)
 *   bits 15-8:  controllerIndex
 *   bits 7-0:   result code (0x00=OK, 0x01=VIGEM_UNAVAIL, etc.)
 * Returns -1 if no ACK has been received yet.
 */
JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_SatelliteNative_getLastControllerAck(JNIEnv*,
                                                                                      jobject) {
    return g_lastControllerAck.load(std::memory_order_acquire);
}

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_SatelliteNative_resetControllerAck(JNIEnv*,
                                                                                    jobject) {
    g_lastControllerAck.store(-1, std::memory_order_release);
}

/**
 * Returns ViGEm availability from the latest 0x0007 Server Status message.
 * -1 = unknown (no status received yet), 0 = idle/unavailable, 1 = available (bus open)
 */
JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_SatelliteNative_getVigemAvailable(JNIEnv*,
                                                                                   jobject) {
    return (jint)g_vigemAvailable.load(std::memory_order_acquire);
}

/**
 * Returns the global active controller count from the latest 0x0007 Server Status.
 * -1 = unknown (no status received yet), 0+ = count across all connections
 */
JNIEXPORT jint JNICALL Java_com_tinkernorth_dish_SatelliteNative_getActiveControllerCount(JNIEnv*,
                                                                                          jobject) {
    return (jint)g_activeControllerCount.load(std::memory_order_acquire);
}

/* ── Receive ACK (called from a background thread) ────────────────────────── */

JNIEXPORT void JNICALL Java_com_tinkernorth_dish_SatelliteNative_receiveAck(JNIEnv*, jobject) {
    if (g_udpSock < 0) return;
    uint8_t buf[128];
    struct sockaddr_in from = {};
    socklen_t fl = sizeof(from);
    ssize_t n = recvfrom(g_udpSock, buf, sizeof(buf), 0, (struct sockaddr*)&from, &fl);
    if (n < 8) return; // too small

    // Verify token matches
    if (memcmp(buf, g_token, 4) != 0) return;

    // Extract counter
    uint32_t ctr = ((uint32_t)buf[4] << 24) | ((uint32_t)buf[5] << 16) | ((uint32_t)buf[6] << 8) |
                   (uint32_t)buf[7];

    // Build nonce
    uint8_t nonce[12] = {};
    putBE32(nonce + 8, ctr);

    // Decrypt
    uint8_t decrypted[64];
    unsigned long long decLen = 0;
    size_t cipherLen = (size_t)n - 8;
    if (crypto_aead_chacha20poly1305_ietf_decrypt(decrypted, &decLen,
                                                  nullptr,                        // nsec
                                                  buf + 8, cipherLen, g_token, 4, // AAD
                                                  nonce, g_key) != 0) {
        return; // decryption failed
    }

    // Parse inner message: type(2B) + length(2B) + payload
    if (decLen < 4) return;
    uint16_t msgType = ((uint16_t)decrypted[0] << 8) | decrypted[1];
    uint16_t msgLen = ((uint16_t)decrypted[2] << 8) | decrypted[3];

    if (msgType == MSG_HEARTBEAT_ACK) {
        g_missedAcks.store(0);
        g_connectionAlive.store(true);
    } else if (msgType == MSG_CONTROLLER_ACK && msgLen >= 4 && decLen >= 8) {
        // Payload: requestType(2B) + controllerIndex(1B) + result(1B)
        uint16_t reqType = ((uint16_t)decrypted[4] << 8) | decrypted[5];
        uint8_t ctrlIdx = decrypted[6];
        uint8_t result = decrypted[7];
        int32_t packed = ((int32_t)reqType << 16) | ((int32_t)ctrlIdx << 8) | (int32_t)result;
        g_lastControllerAck.store(packed, std::memory_order_release);
        LOGI("Controller ACK: reqType=0x%04X idx=%d result=0x%02X", reqType, ctrlIdx, result);
    } else if (msgType == MSG_SERVER_STATUS && msgLen >= 2 && decLen >= 6) {
        // Payload: vigemAvailable(1B) + activeControllerCount(1B)
        uint8_t vigem = decrypted[4];
        uint8_t count = decrypted[5];
        int8_t prevVigem =
            g_vigemAvailable.exchange((int8_t)(vigem ? 1 : 0), std::memory_order_release);
        int8_t prevCount =
            g_activeControllerCount.exchange((int8_t)count, std::memory_order_release);
        if (prevVigem != (int8_t)(vigem ? 1 : 0) || prevCount != (int8_t)count) {
            LOGI("Server status: vigemAvailable=%d activeControllers=%d", vigem, count);
        }
    }
}

/* ── LAN Discovery ────────────────────────────────────────────────────────── */

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_SatelliteNative_discoverServers(
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

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_SatelliteNative_pair(JNIEnv* env, jobject,
                                                                         jstring ip, jint pairPort,
                                                                         jstring deviceId,
                                                                         jstring deviceName,
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

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_SatelliteNative_httpConnect(JNIEnv* env,
                                                                                jobject, jstring ip,
                                                                                jint httpPort,
                                                                                jstring deviceId) {
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

JNIEXPORT jstring JNICALL Java_com_tinkernorth_dish_SatelliteNative_httpDisconnect(
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
