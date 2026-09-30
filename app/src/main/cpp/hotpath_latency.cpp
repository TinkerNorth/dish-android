// SPDX-License-Identifier: LGPL-3.0-or-later

#include "hotpath_latency.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <ctime>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace hotpath {
namespace {

constexpr int64_t kNsPerSec = 1000000000LL;
constexpr double kNsPerUs = 1000.0;

// A ping every 2 s in steady state, 4/s in probe mode: 64 samples spans ~2 min
// idle and ~16 s while the diagnostics panel probes.
constexpr size_t kRttWindow = 64;
// A ping unanswered past this is lost, not in flight (matches the ack-side cap).
constexpr int64_t kRttMaxNs = 5 * kNsPerSec;
constexpr double kRttMaxUs = (double)kRttMaxNs / kNsPerUs;
// A stage-1 reading past this is a stalled poller, not a hot-path measurement.
constexpr double kStage1MaxUs = 1e6;
// Gaps above this are stream pauses (idle pad, replug), not polling jitter; recording them
// would drown the tail the metric exists to expose.
constexpr double kUrbGapMaxUs = 100000.0;
constexpr size_t kDeviceWindow = 512;
// How many raw samples the diagnostics sparkline plots.
constexpr size_t kRecentSamples = 32;

constexpr double kP50 = 0.50;
constexpr double kP90 = 0.90;
constexpr double kP99 = 0.99;
constexpr double kRoundHalfUp = 0.5;
constexpr size_t kNumberScratch = 32;
constexpr size_t kPctlScratch = 256;
constexpr size_t kTallyScratch = 64;

std::atomic<bool> g_enabled{false};

int64_t nowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * kNsPerSec + ts.tv_nsec;
}

double elapsedUs(const int64_t fromNs, const int64_t toNs) {
    return (double)(toNs - fromNs) / kNsPerUs;
}

// The URB-reap timestamp lives on the poller thread: reap -> parse -> dedupe ->
// encrypt -> sendto all run synchronously on that one thread, so a thread_local
// needs no synchronisation and never crosses sessions.
thread_local int64_t t_reapNs = 0;
// Previous reap on the same poller thread, for the inter-arrival (jitter) ring.
thread_local int64_t t_prevReapNs = 0;
thread_local int32_t t_deviceId = 0;

// One bounded ring per metric. A mutex per sample is cheap next to the syscall
// the send already does, and only taken while benchmarking.
class Ring {
  public:
    // ~262k samples (~2 MB) so an offline "arm, unplug cable, play, replug, dump"
    // session at 1 kHz captures minutes, not seconds, before it stops growing.
    static constexpr size_t kCap = 262144;

    // 0 = accumulate to kCap (bench-dump semantics); >0 = sliding window of the
    // last N samples so percentiles answer "now", not "since arming".
    explicit Ring(const size_t window = 0) : window_(window) {}

    void add(const double v) {
        std::lock_guard<std::mutex> lk(mtx_);
        const bool slides = window_ > 0;
        if (slides) {
            addSliding(v);
        } else {
            addUntilCap(v);
        }
    }

    void clear() {
        std::lock_guard<std::mutex> lk(mtx_);
        us_.clear();
    }

    // A copy of the window, emptied under the same lock when asked.
    std::vector<double> snapshot(const bool clearAfter) {
        std::lock_guard<std::mutex> lk(mtx_);
        std::vector<double> copy = us_;
        if (clearAfter) us_.clear();
        return copy;
    }

  private:
    void addSliding(const double v) {
        if (us_.size() >= window_) us_.erase(us_.begin());
        us_.push_back(v);
    }

    void addUntilCap(const double v) {
        if (us_.size() < kCap) us_.push_back(v);
    }

    std::mutex mtx_;
    std::vector<double> us_; // sample window, microseconds
    const size_t window_;
};

Ring g_stage1;          // URB reap -> gamepad sent
Ring g_rtt{kRttWindow}; // heartbeat ping -> ack, sliding
Ring g_urbGap;          // URB inter-arrival gap (polling jitter)

struct DeviceRings {
    Ring stage1{kDeviceWindow};
    Ring urbGap{kDeviceWindow};
};

std::mutex g_devMtx;
std::unordered_map<int32_t, std::unique_ptr<DeviceRings>> g_dev;

DeviceRings& ringsFor(const int32_t deviceId) {
    std::lock_guard<std::mutex> lk(g_devMtx);
    auto& slot = g_dev[deviceId];
    if (!slot) slot = std::make_unique<DeviceRings>();
    return *slot;
}

// Both rings are copied under g_devMtx so the JSON is built with no lock held at all. An unknown
// device leaves both empty, which reads as a device with no samples yet.
void copyDeviceRings(const int32_t deviceId, std::vector<double>& stage1,
                     std::vector<double>& gap) {
    std::lock_guard<std::mutex> lk(g_devMtx);
    const auto it = g_dev.find(deviceId);
    if (it == g_dev.end()) return;
    stage1 = it->second->stage1.snapshot(false);
    gap = it->second->urbGap.snapshot(false);
}

std::vector<double> rttWindowInOrder(RttStats& session) {
    std::lock_guard<std::mutex> lk(session.mtx);
    std::vector<double> ordered;
    ordered.reserve(session.count);
    const bool hasWrapped = session.count >= RttStats::kWindow;
    const size_t oldest = hasWrapped ? session.next : 0;
    for (size_t i = 0; i < session.count; i++) {
        ordered.push_back(session.us[(oldest + i) % RttStats::kWindow]);
    }
    return ordered;
}

// Appends the newest samples as a JSON array, oldest first. The sparkline wants recent shape
// rather than an aggregate, so these go out raw.
void appendRecentSamples(std::string& out, const char* name, const double* us, const size_t n) {
    const size_t take = std::min(n, kRecentSamples);
    const size_t first = n - take;
    out += "\"";
    out += name;
    out += "\":[";
    char num[kNumberScratch];
    for (size_t i = first; i < n; i++) {
        snprintf(num, sizeof(num), "%.0f", us[i]);
        if (i != first) out += ",";
        out += num;
    }
    out += "]";
}

// The sample at rank p of a sorted window, the rank rounded half up.
double percentileAt(const std::vector<double>& sorted, const double p) {
    const size_t last = sorted.size() - 1;
    const size_t rank = (size_t)(p * (double)last + kRoundHalfUp);
    return sorted[std::min(rank, last)];
}

void appendPctlValues(std::string& out, const char* name, std::vector<double> v) {
    char buf[kPctlScratch];
    if (v.empty()) {
        snprintf(buf, sizeof(buf), "\"%s\":{\"n\":0}", name);
        out += buf;
        return;
    }
    std::sort(v.begin(), v.end());
    double sum = 0;
    for (const double x : v) sum += x;
    const double mean = sum / (double)v.size();
    snprintf(buf, sizeof(buf),
             "\"%s\":{\"n\":%zu,\"min\":%.2f,\"p50\":%.2f,\"p90\":%.2f,\"p99\":%.2f,"
             "\"max\":%.2f,\"mean\":%.2f}",
             name, v.size(), v.front(), percentileAt(v, kP50), percentileAt(v, kP90),
             percentileAt(v, kP99), v.back(), mean);
    out += buf;
}

void appendPctl(std::string& out, const char* name, Ring& r, const bool reset) {
    appendPctlValues(out, name, r.snapshot(reset));
}

} // namespace

void setEnabled(const bool on) {
    const bool was = g_enabled.exchange(on, std::memory_order_relaxed);
    const bool isAFreshArm = on && !was;
    if (!isAFreshArm) return;
    g_stage1.clear();
    g_rtt.clear();
    g_urbGap.clear();
    std::lock_guard<std::mutex> lk(g_devMtx);
    g_dev.clear();
}

bool enabled() { return g_enabled.load(std::memory_order_relaxed); }

void markInputRead(const int32_t deviceId) {
    if (!g_enabled.load(std::memory_order_relaxed)) return;
    const int64_t now = nowNs();
    const bool hasAPreviousReap = t_prevReapNs != 0;
    const bool isTheSameDevice = t_deviceId == deviceId;
    if (hasAPreviousReap && isTheSameDevice) {
        const double us = elapsedUs(t_prevReapNs, now);
        const bool isPollingJitter = us >= 0 && us < kUrbGapMaxUs;
        if (isPollingJitter) {
            g_urbGap.add(us);
            ringsFor(deviceId).urbGap.add(us);
        }
    }
    t_deviceId = deviceId;
    t_prevReapNs = now;
    t_reapNs = now;
}

void markGamepadSent() {
    if (!g_enabled.load(std::memory_order_relaxed)) return;
    const bool isUrbDriven = t_reapNs != 0;
    if (!isUrbDriven) return;
    const double us = elapsedUs(t_reapNs, nowNs());
    t_reapNs = 0;
    const bool isAHotPathReading = us >= 0 && us < kStage1MaxUs;
    if (isAHotPathReading) {
        g_stage1.add(us);
        ringsFor(t_deviceId).stage1.add(us);
    }
}

std::string deviceLatencyJson(const int32_t deviceId) {
    std::vector<double> stage1;
    std::vector<double> gap;
    copyDeviceRings(deviceId, stage1, gap);

    std::string out = "{";
    appendPctlValues(out, "stage1_hotpath_us", std::move(stage1));
    out += ",";
    appendPctlValues(out, "urb_gap_us", std::move(gap));
    out += "}";
    return out;
}

void forgetDevice(const int32_t deviceId) {
    std::lock_guard<std::mutex> lk(g_devMtx);
    g_dev.erase(deviceId);
}

int64_t nowMonotonicNs() { return nowNs(); }

bool shouldArmPing(const int64_t outstandingNs, const int64_t atNs) {
    const bool nothingInFlight = outstandingNs == 0;
    const bool theFlightIsLost = atNs - outstandingNs >= kRttMaxNs;
    return nothingInFlight || theFlightIsLost;
}

void addRttSample(RttStats* session, const int64_t sentNs, const int64_t atNs) {
    if (!g_enabled.load(std::memory_order_relaxed)) return;
    const double us = elapsedUs(sentNs, atNs);
    const bool isAValidRoundTrip = us >= 0 && us < kRttMaxUs;
    if (!isAValidRoundTrip) return;
    g_rtt.add(us);
    if (session == nullptr) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->us[session->next] = us;
    session->next = (session->next + 1) % RttStats::kWindow;
    if (session->count < RttStats::kWindow) session->count++;
}

void clearRtt(RttStats& session) {
    std::lock_guard<std::mutex> lk(session.mtx);
    session.count = 0;
    session.next = 0;
}

std::string sessionStatsJson(RttStats& session, const int missedAcks) {
    std::vector<double> ordered = rttWindowInOrder(session);
    std::string out = "{";
    appendRecentSamples(out, "rtt_recent_us", ordered.data(), ordered.size());
    out += ",";
    appendPctlValues(out, "rtt_us", std::move(ordered));
    char num[kTallyScratch];
    snprintf(num, sizeof(num), ",\"pings\":%u,\"acks\":%u,\"missed\":%d}",
             session.pings.load(std::memory_order_relaxed),
             session.acks.load(std::memory_order_relaxed), missedAcks);
    out += num;
    return out;
}

void resetRttWindow() { g_rtt.clear(); }

std::string statsJson(const bool reset) {
    std::string out = "{\"enabled\":";
    out += enabled() ? "true" : "false";
    out += ",";
    appendPctl(out, "stage1_hotpath_us", g_stage1, reset);
    out += ",";
    appendPctl(out, "urb_gap_us", g_urbGap, reset);
    out += ",";
    const std::vector<double> recent = g_rtt.snapshot(false);
    appendRecentSamples(out, "rtt_recent_us", recent.data(), recent.size());
    out += ",";
    appendPctl(out, "rtt_us", g_rtt, reset);
    out += "}";
    return out;
}

} // namespace hotpath
