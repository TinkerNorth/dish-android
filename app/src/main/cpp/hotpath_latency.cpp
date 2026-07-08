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
// Previous reap on the same poller thread, for the inter-arrival (jitter) ring.
thread_local int64_t t_prevReapNs = 0;

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
    void clear() {
        std::lock_guard<std::mutex> lk(mtx);
        us.clear();
    }
};

Ring g_stage1; // URB reap -> gamepad sent
Ring g_rtt;    // heartbeat ping -> ack
Ring g_urbGap; // URB inter-arrival gap (polling jitter)

std::atomic<int64_t> g_lastPingNs{0};

// Gaps above this are stream pauses (idle pad, replug), not polling jitter; recording them
// would drown the tail the metric exists to expose.
constexpr double kUrbGapMaxUs = 100000.0;

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

void setEnabled(bool on) {
    const bool was = g_enabled.exchange(on, std::memory_order_relaxed);
    if (!on || was) return;
    // Fresh window per arm: the flag persists across launches, so without this the
    // percentiles would blend every idle stretch since process start (a dozing Wi-Fi
    // radio between heartbeats reads tens of ms) into the reading the user asked for.
    g_stage1.clear();
    g_rtt.clear();
    g_urbGap.clear();
    g_lastPingNs.store(0, std::memory_order_relaxed);
}
bool enabled() { return g_enabled.load(std::memory_order_relaxed); }

void markInputRead() {
    if (!g_enabled.load(std::memory_order_relaxed)) return;
    const int64_t now = nowNs();
    if (t_prevReapNs != 0) {
        double us = (double)(now - t_prevReapNs) / 1000.0;
        if (us >= 0 && us < kUrbGapMaxUs) g_urbGap.add(us);
    }
    t_prevReapNs = now;
    t_reapNs = now;
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
    appendPctl(out, "urb_gap_us", g_urbGap, reset);
    out += ",";
    // Raw tail of the RTT window BEFORE the pctl block can reset it: feeds the
    // diagnostics sparkline, which wants recent shape rather than an aggregate.
    {
        std::lock_guard<std::mutex> lk(g_rtt.mtx);
        const size_t n = g_rtt.us.size();
        const size_t take = n < 32 ? n : 32;
        out += "\"rtt_recent_us\":[";
        char num[32];
        for (size_t i = n - take; i < n; i++) {
            snprintf(num, sizeof(num), "%.0f", g_rtt.us[i]);
            if (i != n - take) out += ",";
            out += num;
        }
        out += "],";
    }
    appendPctl(out, "rtt_us", g_rtt, reset);
    out += "}";
    return out;
}

} // namespace hotpath
