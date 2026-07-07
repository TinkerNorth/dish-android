// SPDX-License-Identifier: LGPL-3.0-or-later
#include "hotpath_latency.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <ctime>
#include <mutex>
#include <vector>

namespace hotpath {
namespace {

std::atomic<bool> g_enabled{false};

inline int64_t nowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// The URB-reap timestamp lives on the poller thread: reap -> parse -> dedupe ->
// encrypt -> sendto all run synchronously on that one thread, so a thread_local
// needs no synchronisation and never crosses sessions.
thread_local int64_t t_reapNs = 0;

// One bounded ring per metric. A mutex per sample is cheap next to the syscall
// the send already does, and only taken while benchmarking.
struct Ring {
    std::mutex mtx;
    std::vector<double> us; // sample window, microseconds
    // ~262k samples (~2 MB) so an offline "arm, unplug cable, play, replug, dump"
    // session at 1 kHz captures minutes, not seconds, before it stops growing.
    static constexpr size_t kCap = 262144;
    void add(double v) {
        std::lock_guard<std::mutex> lk(mtx);
        if (us.size() < kCap) us.push_back(v);
    }
};

Ring g_stage1; // URB reap -> gamepad sent
Ring g_rtt;    // heartbeat ping -> ack

std::atomic<int64_t> g_lastPingNs{0};

void appendPctl(std::string& out, const char* name, Ring& r, bool reset) {
    std::vector<double> v;
    {
        std::lock_guard<std::mutex> lk(r.mtx);
        v = r.us;
        if (reset) r.us.clear();
    }
    char buf[256];
    if (v.empty()) {
        snprintf(buf, sizeof(buf), "\"%s\":{\"n\":0}", name);
        out += buf;
        return;
    }
    std::sort(v.begin(), v.end());
    auto q = [&](double p) {
        size_t i = (size_t)(p * (v.size() - 1) + 0.5);
        return v[std::min(i, v.size() - 1)];
    };
    double sum = 0;
    for (double x : v) sum += x;
    snprintf(buf, sizeof(buf),
             "\"%s\":{\"n\":%zu,\"min\":%.2f,\"p50\":%.2f,\"p90\":%.2f,\"p99\":%.2f,"
             "\"max\":%.2f,\"mean\":%.2f}",
             name, v.size(), v.front(), q(0.50), q(0.90), q(0.99), v.back(), sum / v.size());
    out += buf;
}

} // namespace

void setEnabled(bool on) { g_enabled.store(on, std::memory_order_relaxed); }
bool enabled() { return g_enabled.load(std::memory_order_relaxed); }

void markInputRead() {
    if (!g_enabled.load(std::memory_order_relaxed)) return;
    t_reapNs = nowNs();
}

void markGamepadSent() {
    if (!g_enabled.load(std::memory_order_relaxed)) return;
    if (t_reapNs == 0) return; // not a URB-driven send (framework path, motion, ...)
    double us = (double)(nowNs() - t_reapNs) / 1000.0;
    t_reapNs = 0;
    if (us >= 0 && us < 1e6) g_stage1.add(us);
}

void markPingSent() {
    if (!g_enabled.load(std::memory_order_relaxed)) return;
    g_lastPingNs.store(nowNs(), std::memory_order_relaxed);
}

void markAckReceived() {
    if (!g_enabled.load(std::memory_order_relaxed)) return;
    int64_t sent = g_lastPingNs.exchange(0, std::memory_order_relaxed);
    if (sent == 0) return;
    double us = (double)(nowNs() - sent) / 1000.0;
    if (us >= 0 && us < 5e6) g_rtt.add(us);
}

std::string statsJson(bool reset) {
    std::string out = "{\"enabled\":";
    out += enabled() ? "true" : "false";
    out += ",";
    appendPctl(out, "stage1_hotpath_us", g_stage1, reset);
    out += ",";
    appendPctl(out, "rtt_us", g_rtt, reset);
    out += "}";
    return out;
}

} // namespace hotpath
