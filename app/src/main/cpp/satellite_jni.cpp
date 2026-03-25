/*
 * satellite_jni.cpp — Native bridge for the Dish Android client.
 * GameActivity provides the native thread (android_main) for lifecycle.
 * Controller input is handled via direct JNI calls from Kotlin for
 * lowest latency (Java dispatchGenericMotionEvent → JNI sendReport → sendto).
 * Also handles LAN discovery and TCP pairing via JNI.
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

#define TAG "SatelliteJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#pragma pack(push, 1)
struct XUSB_REPORT {
    uint16_t wButtons;
    uint8_t  bLeftTrigger;
    uint8_t  bRightTrigger;
    int16_t  sThumbLX;
    int16_t  sThumbLY;
    int16_t  sThumbRX;
    int16_t  sThumbRY;
};
#pragma pack(pop)
static_assert(sizeof(XUSB_REPORT) == 12, "XUSB_REPORT must be 12 bytes");

static int               g_udpSock = -1;
static struct sockaddr_in g_dest   = {};

static uint64_t nowMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
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
            if (source != nullptr) {
                source->process(source->app, source);
            }
            if (app->destroyRequested) {
                LOGI("android_main: destroy requested");
                return;
            }
        }
    }
}

extern "C" {

/* ── UDP Streaming ────────────────────────────────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_tinkernorth_dish_SatelliteNative_openSocket(
        JNIEnv* env, jobject, jstring ip, jint port) {
    if (g_udpSock >= 0) { close(g_udpSock); g_udpSock = -1; }
    g_udpSock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (g_udpSock < 0) { LOGE("socket() failed"); return JNI_FALSE; }
    const char* s = env->GetStringUTFChars(ip, nullptr);
    memset(&g_dest, 0, sizeof(g_dest));
    g_dest.sin_family = AF_INET;
    g_dest.sin_port   = htons((uint16_t)port);
    inet_pton(AF_INET, s, &g_dest.sin_addr);
    env->ReleaseStringUTFChars(ip, s);
    LOGI("UDP socket opened -> port %d", port);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_SatelliteNative_closeSocket(JNIEnv*, jobject) {
    if (g_udpSock >= 0) { close(g_udpSock); g_udpSock = -1; }
}

JNIEXPORT void JNICALL
Java_com_tinkernorth_dish_SatelliteNative_sendReport(
        JNIEnv*, jobject,
        jint wB, jint bLT, jint bRT, jint sLX, jint sLY, jint sRX, jint sRY) {
    if (g_udpSock < 0) return;
    XUSB_REPORT r;
    r.wButtons      = (uint16_t)(wB  & 0xFFFF);
    r.bLeftTrigger  = (uint8_t) (bLT & 0xFF);
    r.bRightTrigger = (uint8_t) (bRT & 0xFF);
    r.sThumbLX = (int16_t)sLX; r.sThumbLY = (int16_t)sLY;
    r.sThumbRX = (int16_t)sRX; r.sThumbRY = (int16_t)sRY;
    sendto(g_udpSock, &r, sizeof(r), 0,
           (struct sockaddr*)&g_dest, sizeof(g_dest));
}

/* ── LAN Discovery ────────────────────────────────────────────────────────── */

JNIEXPORT jstring JNICALL
Java_com_tinkernorth_dish_SatelliteNative_discoverServers(
        JNIEnv* env, jobject, jint discPort, jint timeoutMs) {
    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) return env->NewStringUTF("[]");
    int reuse = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    struct sockaddr_in ba = {};
    ba.sin_family = AF_INET; ba.sin_port = htons((uint16_t)discPort);
    ba.sin_addr.s_addr = INADDR_ANY;
    if (bind(sock, (struct sockaddr*)&ba, sizeof(ba)) < 0) {
        LOGE("discovery bind() failed: %s", strerror(errno));
        close(sock); return env->NewStringUTF("[]");
    }
    struct timeval tv = { 0, 300000 };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    std::string result = "[";
    bool first = true;
    std::vector<std::string> seen;
    const uint64_t deadline = nowMs() + (uint64_t)timeoutMs;
    while (nowMs() < deadline) {
        char buf[1024];
        struct sockaddr_in from = {};
        socklen_t fl = sizeof(from);
        int n = (int)recvfrom(sock, buf, sizeof(buf)-1, 0,
                              (struct sockaddr*)&from, &fl);
        if (n <= 0) continue;
        buf[n] = '\0';
        if (!strstr(buf, "\"service\":\"satellite\"")) continue;
        char ip[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &from.sin_addr, ip, sizeof(ip));
        bool dup = false;
        for (auto& s : seen) { if (s == ip) { dup = true; break; } }
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

JNIEXPORT jstring JNICALL
Java_com_tinkernorth_dish_SatelliteNative_pair(
        JNIEnv* env, jobject,
        jstring ip, jint pairPort,
        jstring deviceId, jstring deviceName, jstring pin) {
    const char* ipStr   = env->GetStringUTFChars(ip,         nullptr);
    const char* idStr   = env->GetStringUTFChars(deviceId,   nullptr);
    const char* nameStr = env->GetStringUTFChars(deviceName, nullptr);
    const char* pinStr  = env->GetStringUTFChars(pin,        nullptr);
    std::string result = "{\"ok\":false,\"error\":\"connection failed\"}";
    int sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock >= 0) {
        int flags = fcntl(sock, F_GETFL, 0);
        fcntl(sock, F_SETFL, flags | O_NONBLOCK);
        struct sockaddr_in addr = {};
        addr.sin_family = AF_INET;
        addr.sin_port   = htons((uint16_t)pairPort);
        inet_pton(AF_INET, ipStr, &addr.sin_addr);
        int ret = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
        if (ret < 0 && errno == EINPROGRESS) {
            struct timeval tv = { 4, 0 };
            fd_set wset; FD_ZERO(&wset); FD_SET(sock, &wset);
            ret = select(sock + 1, nullptr, &wset, nullptr, &tv);
        }
        if (ret > 0) {
            fcntl(sock, F_SETFL, flags & ~O_NONBLOCK);
            std::string msg = "{\"deviceId\":\"";
            msg += idStr; msg += "\",\"deviceName\":\"";
            msg += nameStr; msg += "\",\"pin\":\"";
            msg += pinStr; msg += "\"}";
            send(sock, msg.c_str(), (int)msg.size(), 0);
            struct timeval rtv = { 5, 0 };
            setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &rtv, sizeof(rtv));
            char buf[512] = {};
            int n = (int)recv(sock, buf, sizeof(buf) - 1, 0);
            if (n > 0) { buf[n] = '\0'; result = std::string(buf, (size_t)n); }
            else        { result = "{\"ok\":false,\"error\":\"no response\"}"; }
        }
        close(sock);
    }
    env->ReleaseStringUTFChars(ip,         ipStr);
    env->ReleaseStringUTFChars(deviceId,   idStr);
    env->ReleaseStringUTFChars(deviceName, nameStr);
    env->ReleaseStringUTFChars(pin,        pinStr);
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
