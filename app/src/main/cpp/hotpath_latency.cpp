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
    explicit Ring(size_t window = 0) : window(window) {}
    std::mutex mtx;
    std::vector<double> us; // sample window, microseconds
    // 0 = accumulate to kCap (bench-dump semantics); >0 = sliding window of the
    // last N samples so percentiles answer "now", not "since arming".
    const size_t window;
    // ~262k samples (~2 MB) so an offline "arm, unplug cable, play, replug, dump"
    // session at 1 kHz captures minutes, not seconds, before it stops growing.
    static constexpr size_t kCap = 262144;
    void add(double v) {
        std::lock_guard<std::mutex> lk(mtx);
        if (window > 0) {
            if (us.size() >= window) us.erase(us.begin());
            us.push_back(v);
        } else if (us.size() < kCap) {
            us.push_back(v);
        }
    }
    void clear() {
        std::lock_guard<std::mutex> lk(mtx);
        us.clear();
    }
};

// A ping every 2 s in steady state, 4/s in probe mode: 64 samples spans ~2 min
// idle and ~16 s while the diagnostics panel probes.
constexpr size_t kRttWindow = 64;
// A ping unanswered past this is lost, not in flight (matches the ack-side cap).
constexpr int64_t kRttMaxNs = 5000000000LL;

Ring g_stage1;          // URB reap -> gamepad sent
Ring g_rtt{kRttWindow}; // heartbeat ping -> ack, sliding
Ring g_urbGap;          // URB inter-arrival gap (polling jitter)

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

int64_t nowMonotonicNs() { return nowNs(); }

// Keep an in-flight ping's clock: overwriting would pair its late ack with a newer
// ping and read low. Past the validity window the ping is lost; reclaim.
bool shouldArmPing(int64_t outstandingNs, int64_t nowNs) {
    return outstandingNs == 0 || nowNs - outstandingNs >= kRttMaxNs;
}

void addRttSample(int64_t sentNs, int64_t nowNs) {
    if (!g_enabled.load(std::memory_order_relaxed)) return;
    double us = (double)(nowNs - sentNs) / 1000.0;
    if (us >= 0 && us < (double)kRttMaxNs / 1000.0) g_rtt.add(us);
}

void resetRttWindow() { g_rtt.clear(); }

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
