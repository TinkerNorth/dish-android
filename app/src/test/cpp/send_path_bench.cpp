// SPDX-License-Identifier: LGPL-3.0-or-later

// bench_send_path: the native input path's latency on a host, from the moment a report is in hand
// to the return of the sendto that carried it, under the threads that share its locks. The method
// and the recorded numbers are in tools/bench/README.md, "Host bench".

#include <errno.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <functional>
#include <iostream>
#include <memory>
#include <numeric>
#include <optional>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <sodium.h>

#include "audio_codec.h"
#include "fake_satellite.h"
#include "gamepad_input.h"
#include "receive_path.h"
#include "send_path.h"
#include "session_table.h"
#include "slot_table.h"
#include "tracked_mutex.h"
#include "udp_socket.h"
#include "wire_encoders.h"

#ifdef _WIN32
#include <windows.h>
#endif

namespace {

using Clock = std::chrono::steady_clock;
using TimePoint = Clock::time_point;
using Nanos = std::chrono::nanoseconds;

constexpr int64_t NANOS_PER_MICRO = 1'000;
constexpr int64_t NANOS_PER_SECOND = 1'000'000'000;
constexpr int64_t ONE_MS_NS = 1'000'000;

constexpr double DEFAULT_SECS = 3.0;
constexpr double MIN_SECS = 0.05;
constexpr double SMOKE_SECS = 0.2;
constexpr int DEFAULT_REPS = 3;
constexpr int MAX_REPS = 99;
constexpr int DEFAULT_RATE_HZ = 1000;
constexpr int MAX_RATE_HZ = 100000;
constexpr int DEFAULT_MIC_HZ = 50;
constexpr int SECOND_POLLER_HZ = 250;
constexpr int UI_INPUT_HZ = 250;
constexpr int RUMBLE_HZ = 100;
constexpr int LIGHTBAR_HZ = 10;
constexpr int HEARTBEAT_INTERVAL_MS = 2000;
constexpr double BATTERY_PERIOD_SECS = 30.0;
constexpr double BATTERY_BURST_SECS = 1.0;
constexpr double BINDING_PERIOD_SECS = 0.2;
constexpr double SMOKE_BATTERY_BURST_SECS = 0.1;
constexpr double SMOKE_BINDING_PERIOD_SECS = 0.05;
constexpr int64_t START_DELAY_MS = 30;
constexpr int SATELLITE_POLL_MS = 1;
constexpr int CONTROLLERS = 3;

// usb_host.cpp's own spacing between MSG_MOTION sends.
constexpr int64_t USB_MOTION_MIN_INTERVAL_NS = 8'000'000;

constexpr size_t MIN_FRAMES_TO_JUDGE_PACING = 8;
constexpr int64_t MAX_MEDIAN_LATENESS_NS = ONE_MS_NS;

constexpr uint64_t BASIS_POINTS = 10'000;
constexpr uint64_t P50_BASIS_POINTS = 5'000;
constexpr uint64_t P99_BASIS_POINTS = 9'900;
constexpr uint64_t P999_BASIS_POINTS = 9'990;

constexpr int32_t DEVICE_A = 1;
constexpr int32_t DEVICE_B = 2;
constexpr int32_t DEVICE_UI = 3;
constexpr int CONTROLLER_A = 0;
constexpr int CONTROLLER_B = 1;
constexpr int CONTROLLER_UI = 2;
constexpr uint8_t TOKEN[4] = {0xB3, 0x7C, 0x01, 0x9E};
constexpr uint8_t KEY_BASE = 0x21;
constexpr double PI = 3.141592653589793;
constexpr double TONE_HZ = 440.0;
constexpr double TONE_AMPLITUDE = 8000.0;

#ifdef _WIN32
// The high-resolution waitable timer fires about half a millisecond late on Windows 11, so the
// producer wakes this far ahead and spins the rest (satellite tools/bench/README.md).
constexpr Nanos SLEEP_LEAD = std::chrono::microseconds(800);
constexpr int64_t NANOS_PER_TIMER_UNIT = 100;
#else
constexpr Nanos SLEEP_LEAD = std::chrono::microseconds(200);
#endif

#ifdef DISH_LOCK_STATS
constexpr bool LOCK_STATS_BUILD = true;
#else
constexpr bool LOCK_STATS_BUILD = false;
#endif

int64_t elapsedNanos(const TimePoint from, const TimePoint to) {
    return std::chrono::duration_cast<Nanos>(to - from).count();
}

Nanos deadlineOffset(const uint64_t k, const int rateHz) {
    return Nanos((int64_t)k * NANOS_PER_SECOND / rateHz);
}

Nanos secondsToNanos(const double seconds) {
    return Nanos((int64_t)(seconds * (double)NANOS_PER_SECOND));
}

double nanosToMicros(const int64_t nanos) { return (double)nanos / (double)NANOS_PER_MICRO; }

void spinUntil(const TimePoint due) {
    while (Clock::now() < due) {}
}

// Waits to an absolute deadline: a sleep that ends SLEEP_LEAD early, then a spin.
class DeadlineWait {
  public:
#ifdef _WIN32
    DeadlineWait()
        : timer_(CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION,
                                        TIMER_ALL_ACCESS)) {}
    ~DeadlineWait() {
        if (timer_ != nullptr) CloseHandle(timer_);
    }
#else
    DeadlineWait() {}
#endif
    DeadlineWait(const DeadlineWait&) = delete;
    DeadlineWait& operator=(const DeadlineWait&) = delete;

    void until(const TimePoint due) const {
        sleepUntil(due - SLEEP_LEAD);
        spinUntil(due);
    }

  private:
#ifdef _WIN32
    void sleepUntil(const TimePoint wake) const {
        const Nanos remaining = wake - Clock::now();
        if (remaining <= Nanos::zero() || timer_ == nullptr) return;
        LARGE_INTEGER relative;
        relative.QuadPart = -(remaining.count() / NANOS_PER_TIMER_UNIT);
        if (SetWaitableTimer(timer_, &relative, 0, nullptr, nullptr, FALSE)) {
            WaitForSingleObject(timer_, INFINITE);
        }
    }

    const HANDLE timer_;
#else
    void sleepUntil(const TimePoint wake) const {
        if (Clock::now() < wake) std::this_thread::sleep_until(wake);
    }
#endif
};

// What the sendto calls of the current report did, on the thread that made them.
struct SendWatch {
    int sends = 0;
    int softDrops = 0;
    int errors = 0;
    TimePoint first{};
    TimePoint last{};
};

thread_local SendWatch t_watch;

// The bench's DatagramPort: the real socket send, with the time it returned noted for the
// calling thread's current report.
ssize_t watchedSend(void*, const int sock, const uint8_t* packet, const size_t len,
                    const sockaddr_in& dest) {
    const ssize_t sent = dish_net::sendDatagram(sock, packet, len, dest);
    const int sendErrno = errno;
    const TimePoint returned = Clock::now();
    SendWatch& watch = t_watch;
    if (watch.sends == 0) watch.first = returned;
    watch.last = returned;
    watch.sends++;
    if (sent < 0 && (sendErrno == EAGAIN || sendErrno == EWOULDBLOCK)) watch.softDrops++;
    if (sent < 0 && sendErrno != EAGAIN && sendErrno != EWOULDBLOCK) watch.errors++;
    errno = sendErrno;
    return sent;
}

struct Options {
    int rateHz = DEFAULT_RATE_HZ;
    double secs = DEFAULT_SECS;
    int reps = DEFAULT_REPS;
    int micHz = DEFAULT_MIC_HZ;
    bool smoke = false;
    bool help = false;
    std::string jsonPath;
    std::string csvPath;
};

bool parseDouble(const char* text, double& out) {
    char* end = nullptr;
    const double value = std::strtod(text, &end);
    if (end == text || *end != '\0' || !std::isfinite(value)) return false;
    out = value;
    return true;
}

bool parseIntInRange(const char* text, const int lowest, const int highest, int& out) {
    char* end = nullptr;
    const long value = std::strtol(text, &end, 10);
    if (end == text || *end != '\0' || value < lowest || value > highest) return false;
    out = (int)value;
    return true;
}

bool applyValuedOption(Options& options, const std::string& name, const char* value) {
    if (name == "--rate") return parseIntInRange(value, 1, MAX_RATE_HZ, options.rateHz);
    if (name == "--mic-hz") return parseIntInRange(value, 1, MAX_RATE_HZ, options.micHz);
    if (name == "--reps") return parseIntInRange(value, 1, MAX_REPS, options.reps);
    if (name == "--secs") return parseDouble(value, options.secs) && options.secs >= MIN_SECS;
    if (name == "--json") {
        options.jsonPath = value;
        return true;
    }
    if (name == "--csv") {
        options.csvPath = value;
        return true;
    }
    return false;
}

std::optional<Options> parseOptions(const int argc, char** argv) {
    Options options;
    for (int i = 1; i < argc; i++) {
        const std::string name = argv[i];
        if (name == "--smoke") {
            options.smoke = true;
            continue;
        }
        if (name == "--help" || name == "-h") {
            options.help = true;
            continue;
        }
        if (i + 1 >= argc || !applyValuedOption(options, name, argv[i + 1])) return std::nullopt;
        i++;
    }
    return options;
}

void printUsage(std::FILE* out) {
    std::fprintf(out,
                 "usage: bench_send_path [options]\n"
                 "  --rate HZ      the first USB poller's rate (default %d)\n"
                 "  --secs S       seconds per scenario run (default %.0f)\n"
                 "  --reps N       measured runs per scenario, after one discarded warm-up "
                 "(default %d)\n"
                 "  --mic-hz HZ    mic frames per second in S4 to S6 (default %d)\n"
                 "  --json PATH    also write the settings, the machine and every row as JSON\n"
                 "  --csv PATH     also write the rows as CSV\n"
                 "  --smoke        every scenario once for %.1f s with the slow schedules "
                 "compressed;\n"
                 "                 the exit code is the harness's own accounting, never a "
                 "latency\n"
                 "  --help         this text\n",
                 DEFAULT_RATE_HZ, DEFAULT_SECS, DEFAULT_REPS, DEFAULT_MIC_HZ, SMOKE_SECS);
}

struct Scenario {
    const char* id;
    const char* what;
    bool secondPoller;
    bool uiInput;
    bool coldSenders;
    bool receive;
    bool binding;
    bool dualSense;
};

constexpr std::array<Scenario, 7> SCENARIOS{{
    {"S1", "one USB poller", false, false, false, false, false, false},
    {"S2", "S1 + a second USB poller at 250 Hz", true, false, false, false, false, false},
    {"S3", "S2 + the UI thread at 250 Hz", true, true, false, false, false, false},
    {"S4", "S3 + heartbeat, battery, mic", true, true, true, false, false, false},
    {"S5", "S4 + the receive loop under rumble and lightbar", true, true, true, true, false, false},
    {"S6", "S5 + a slot rebound every 200 ms", true, true, true, true, true, false},
    {"S7", "a DualSense: input, motion and touch per report", false, false, false, false, false,
     true},
}};

// One producer's frames: when each was due, when it was in hand, and what it sent.
struct Stream {
    std::string name;
    ThreadRole role = ThreadRole::Other;
    int rateHz = 0;
    uint64_t planned = 0;
    bool mustSend = true;
    std::vector<int64_t> latenessNs;
    std::vector<uint32_t> inputNs;
    std::vector<uint32_t> wholeNs;
    uint64_t datagrams = 0;
    uint64_t maxDatagrams = 0;
    uint64_t silent = 0;
    uint64_t softDrops = 0;
    uint64_t errors = 0;

    void reserve() {
        latenessNs.reserve(planned);
        inputNs.reserve(planned);
        wholeNs.reserve(planned);
    }
};

uint32_t clampNs(const int64_t ns) {
    return (uint32_t)std::min<int64_t>(std::max<int64_t>(ns, 0), UINT32_MAX);
}

void recordFrame(Stream& stream, const TimePoint due, const TimePoint inHand,
                 const SendWatch& watch) {
    stream.latenessNs.push_back(elapsedNanos(due, inHand));
    stream.datagrams += (uint64_t)watch.sends;
    stream.maxDatagrams = std::max<uint64_t>(stream.maxDatagrams, (uint64_t)watch.sends);
    stream.softDrops += (uint64_t)watch.softDrops;
    stream.errors += (uint64_t)watch.errors;
    if (watch.sends == 0) {
        stream.silent++;
        return;
    }
    stream.inputNs.push_back(clampNs(elapsedNanos(inHand, watch.first)));
    if (watch.sends >= 3) stream.wholeNs.push_back(clampNs(elapsedNanos(inHand, watch.last)));
}

// A producer at absolute deadlines: frame k is due at start + k / rate, whatever happened to k - 1.
void runProducer(Stream& stream, const TimePoint start,
                 const std::function<void(uint64_t, int64_t)>& publish) {
    const ScopedThreadRole role(stream.role);
    const DeadlineWait wait;
    for (uint64_t k = 0; k < stream.planned; k++) {
        const TimePoint due = start + deadlineOffset(k, stream.rateHz);
        wait.until(due);
        const TimePoint inHand = Clock::now();
        t_watch = SendWatch{};
        publish(k, elapsedNanos(start, inHand));
        recordFrame(stream, due, inHand, t_watch);
    }
}

gamepad::DeviceState padReport(const uint64_t k) {
    gamepad::DeviceState report{};
    report.wButtons = (uint16_t)(k & 0x0F);
    report.bRT = (uint8_t)(k & 0xFF);
    report.sLX = (int16_t)(4000 + (int)(k % 1000) * 20);
    report.sLY = (int16_t)(-4000 - (int)(k % 1000) * 20);
    return report;
}

gamepad::TouchpadState fingerAt(const uint64_t k) {
    gamepad::TouchpadState t{};
    t.f0Active = (k / 256) % 2 == 0;
    t.f0Id = (uint8_t)(k / 512);
    t.f0X = (int16_t)((k * 37) & 0x7FFF);
    t.f0Y = (int16_t)((k * 11) & 0x7FFF);
    return t;
}

gamepad::FrameworkAxes frameworkSample(const uint64_t k) {
    gamepad::FrameworkAxes axes{};
    axes.x = (float)std::sin((double)k / 20.0);
    axes.y = (float)std::cos((double)k / 20.0);
    axes.rTrigger = (float)(k % 100) / 100.0f;
    return axes;
}

std::vector<int16_t> micTone() {
    std::vector<int16_t> pcm(dish_audio::AUDIO_FRAME_SAMPLES);
    for (size_t i = 0; i < pcm.size(); i++) {
        const double t = (double)i / dish_audio::AUDIO_SAMPLE_RATE_HZ;
        pcm[i] = (int16_t)(TONE_AMPLITUDE * std::sin(2.0 * PI * TONE_HZ * t));
    }
    return pcm;
}

// usb_host.cpp's publishDecodedReport for a DualSense report with motion and a moving finger: the
// report, motion no closer than 8 ms and touch through the send-on-change gate, as one URB.
class DualSensePoller {
  public:
    void publish(dish_slots::SlotTable& slots, const uint64_t k, const int64_t nowNs) {
        const gamepad::DeviceState report = padReport(k);
        dish_slots::UsbFrame frame;
        frame.report = &report;
        dish_send::MotionSample motion{};
        const bool firstMotion = lastMotionNs_ < 0;
        if (firstMotion || nowNs - lastMotionNs_ >= USB_MOTION_MIN_INTERVAL_NS) {
            const uint32_t deltaUs = firstMotion ? 0 : (uint32_t)((nowNs - lastMotionNs_) / 1000);
            lastMotionNs_ = nowNs;
            motion =
                dish_send::MotionSample{{(int16_t)k, (int16_t)-k, 3}, {100, 200, 16384}, deltaUs};
            frame.motion = &motion;
        }
        if (gate_.decide(fingerAt(k), nowNs) != gamepad::TouchpadSend::SKIP) {
            frame.touch = &gate_.lastSent();
            frame.touchEventTimeMs = (uint32_t)gate_.lastEventTimeMs();
        }
        slots.applyUsbFrame(DEVICE_A, frame);
    }

  private:
    int64_t lastMotionNs_ = -1;
    gamepad::TouchpadGate gate_;
};

struct SatelliteTally {
    std::atomic<uint64_t> received{0};
    std::atomic<uint64_t> unopened{0};
    std::atomic<uint64_t> outOfOrder{0};
    std::atomic<uint64_t> pings{0};
    std::atomic<uint64_t> mic{0};
    std::atomic<uint64_t> battery{0};
    std::atomic<uint64_t> rumbleSent{0};
    std::atomic<uint64_t> lightbarSent{0};
};

// The satellite's end: drains every client datagram, opening each and holding the counters to the
// replay rule the real one applies, and in S5 and S6 acks pings and sends rumble and lightbar.
void runSatellite(fake_satellite::Peer& peer, const uint8_t key[32], const bool feedback,
                  const TimePoint start, const std::atomic<bool>& stop, SatelliteTally& tally) {
    uint32_t serverCounter = 1;
    uint32_t lastClientCounter = 0;
    bool knowsClient = false;
    uint64_t rumbles = 0;
    uint64_t lightbars = 0;
    const uint8_t rumble[dish_wire::RUMBLE_PAYLOAD_BYTES] = {0, 0x80, 0x00, 0x40, 0x00, 0, 50};
    const uint8_t color[dish_wire::LIGHTBAR_PAYLOAD_BYTES] = {0, 0xFF, 0x00, 0x80};
    while (!stop.load()) {
        const fake_satellite::Bytes datagram = peer.receive(SATELLITE_POLL_MS);
        if (!datagram.empty()) {
            knowsClient = true;
            tally.received++;
            const auto inner =
                fake_satellite::openFromClient(TOKEN, key, datagram.data(), datagram.size());
            if (!inner) {
                tally.unopened++;
            } else {
                if (inner->counter <= lastClientCounter) tally.outOfOrder++;
                lastClientCounter = std::max(lastClientCounter, inner->counter);
                if (inner->msgType == dish_wire::MSG_MIC_AUDIO) tally.mic++;
                if (inner->msgType == dish_wire::MSG_BATTERY) tally.battery++;
                if (inner->msgType == dish_wire::MSG_HEARTBEAT_PING) {
                    tally.pings++;
                    if (feedback) {
                        const uint8_t ack[dish_wire::HEARTBEAT_ACK_ENRICHED_BYTES] = {1, 3, 0,
                                                                                      1, 0, 7};
                        peer.reply(fake_satellite::sealToClient(TOKEN, key, serverCounter++,
                                                                dish_wire::MSG_HEARTBEAT_ACK, ack,
                                                                sizeof(ack)));
                    }
                }
            }
        }
        if (!feedback || !knowsClient) continue;
        const TimePoint now = Clock::now();
        if (now >= start + deadlineOffset(rumbles, RUMBLE_HZ)) {
            peer.reply(fake_satellite::sealToClient(TOKEN, key, serverCounter++,
                                                    dish_wire::MSG_RUMBLE, rumble, sizeof(rumble)));
            rumbles++;
            tally.rumbleSent++;
        }
        if (now >= start + deadlineOffset(lightbars, LIGHTBAR_HZ)) {
            peer.reply(fake_satellite::sealToClient(TOKEN, key, serverCounter++,
                                                    dish_wire::MSG_LIGHTBAR, color, sizeof(color)));
            lightbars++;
            tally.lightbarSent++;
        }
    }
}

// What the JNI layer would have been asked to do with each feedback datagram.
class CountingFeedback final : public dish_receive::FeedbackSink {
  public:
    void rumble(int, uint8_t, uint16_t, uint16_t, uint16_t) override { rumbles++; }
    void lightbar(int, const dish_wire::LightbarPayload&) override { lightbars++; }
    void triggerEffects(int, uint8_t, const uint8_t*) override { others++; }
    void playerLeds(int, const dish_wire::PlayerLedsPayload&) override { others++; }
    void micLed(int, const dish_wire::MicLedPayload&) override { others++; }
    void audio(int, int, const dish_wire::AudioFrameHeader&, const uint8_t*, size_t) override {
        others++;
    }

    std::atomic<uint64_t> rumbles{0};
    std::atomic<uint64_t> lightbars{0};
    std::atomic<uint64_t> others{0};
};

class NoBridge final : public dish_slots::BridgeSink {
  public:
    void push(const dish_slots::BridgeReport&) override {}
};

struct Harness {
    uint64_t satelliteReceived = 0;
    uint64_t unopened = 0;
    uint64_t outOfOrder = 0;
    uint64_t pings = 0;
    uint64_t mic = 0;
    uint64_t battery = 0;
    uint64_t rumbleSent = 0;
    uint64_t lightbarSent = 0;
    uint64_t rumblesHandled = 0;
    uint64_t lightbarsHandled = 0;
    uint64_t rebinds = 0;
};

struct Row {
    std::string scenario;
    std::string stream;
    std::string role;
    int rateHz = 0;
    uint64_t reports = 0;
    uint64_t datagrams = 0;
    double datagramsPerReport = 0;
    uint64_t maxDatagrams = 0;
    double p50Us = 0;
    double p99Us = 0;
    double p999Us = 0;
    double maxUs = 0;
    uint64_t overOneMs = 0;
};

struct LockRow {
    std::string scenario;
    std::string lock;
    std::string role;
    uint64_t acquisitions = 0;
    uint64_t contended = 0;
    uint64_t tryFailures = 0;
    double maxWaitUs = 0;
    double p99WaitUs = 0;
    double maxHoldUs = 0;
};

struct ScenarioResult {
    std::vector<Row> rows;
    std::vector<LockRow> locks;
    Harness harness;
    std::vector<std::string> problems;
};

uint32_t percentileNs(const std::vector<uint32_t>& sorted, const uint64_t basisPoints) {
    if (sorted.empty()) return 0;
    const uint64_t rank = (sorted.size() * basisPoints + BASIS_POINTS - 1) / BASIS_POINTS;
    const uint64_t index = rank == 0 ? 0 : rank - 1;
    return sorted[std::min<uint64_t>(index, sorted.size() - 1)];
}

Row rowOf(const char* scenario, const std::string& name, const ThreadRole role, const int rateHz,
          const uint64_t reports, const uint64_t datagrams, const uint64_t maxDatagrams,
          std::vector<uint32_t> latencies) {
    std::sort(latencies.begin(), latencies.end());
    Row row;
    row.scenario = scenario;
    row.stream = name;
    row.role = threadRoleName(role);
    row.rateHz = rateHz;
    row.reports = reports;
    row.datagrams = datagrams;
    row.datagramsPerReport = reports == 0 ? 0.0 : (double)datagrams / (double)reports;
    row.maxDatagrams = maxDatagrams;
    row.p50Us = nanosToMicros(percentileNs(latencies, P50_BASIS_POINTS));
    row.p99Us = nanosToMicros(percentileNs(latencies, P99_BASIS_POINTS));
    row.p999Us = nanosToMicros(percentileNs(latencies, P999_BASIS_POINTS));
    row.maxUs = latencies.empty() ? 0.0 : nanosToMicros(latencies.back());
    row.overOneMs = (uint64_t)std::count_if(latencies.begin(), latencies.end(),
                                            [](uint32_t ns) { return ns > ONE_MS_NS; });
    return row;
}

std::string micros(const int64_t nanos) {
    std::ostringstream text;
    text.setf(std::ios::fixed);
    text.precision(1);
    text << nanosToMicros(nanos) << " us";
    return text.str();
}

void checkPacing(const char* scenario, const Stream& stream, std::vector<std::string>& problems) {
    if (stream.latenessNs.size() != stream.planned) return;
    if (stream.latenessNs.size() < MIN_FRAMES_TO_JUDGE_PACING) return;
    std::vector<int64_t> late = stream.latenessNs;
    const auto [earliest, latest] = std::minmax_element(late.begin(), late.end());
    const int64_t earliestNs = *earliest;
    const int64_t latestNs = *latest;
    const auto middle = late.begin() + (std::ptrdiff_t)(late.size() / 2);
    std::nth_element(late.begin(), middle, late.end());
    const std::string who = std::string(scenario) + " " + stream.name + ": ";
    if (earliestNs < 0) {
        problems.push_back(who + "a frame was taken " + micros(-earliestNs) +
                           " before its deadline");
    }
    if (*middle > MAX_MEDIAN_LATENESS_NS) {
        problems.push_back(who + "the median frame was taken " + micros(*middle) +
                           " after its deadline (latest " + micros(latestNs) +
                           "), over the 1 ms a working pacer keeps");
    }
}

void checkStream(const char* scenario, const Stream& stream, const bool smoke,
                 std::vector<std::string>& problems) {
    const std::string who = std::string(scenario) + " " + stream.name + ": ";
    if (stream.latenessNs.size() != stream.planned) {
        problems.push_back(who + std::to_string(stream.latenessNs.size()) + " of " +
                           std::to_string(stream.planned) + " frames ran");
    }
    if (stream.mustSend && stream.silent != 0) {
        problems.push_back(who + std::to_string(stream.silent) + " reports sent nothing");
    }
    if (stream.errors != 0) {
        problems.push_back(who + std::to_string(stream.errors) + " sends failed");
    }
    if (smoke) checkPacing(scenario, stream, problems);
}

void checkHarness(const Scenario& scenario, const Harness& h, std::vector<std::string>& problems) {
    const std::string who = std::string(scenario.id) + ": ";
    if (h.satelliteReceived == 0) problems.push_back(who + "the satellite received nothing");
    if (h.unopened != 0) {
        problems.push_back(who + std::to_string(h.unopened) + " datagrams did not open");
    }
    if (h.outOfOrder != 0) {
        problems.push_back(who + std::to_string(h.outOfOrder) +
                           " datagrams reached the satellite with a counter not above the last");
    }
    if (scenario.coldSenders && (h.pings == 0 || h.mic == 0 || h.battery == 0)) {
        problems.push_back(who + "a heartbeat, mic or battery datagram never arrived");
    }
    if (scenario.receive && (h.rumblesHandled == 0 || h.lightbarsHandled == 0)) {
        problems.push_back(who + "the receive loop handed on no rumble or no lightbar");
    }
    if (scenario.binding && h.rebinds == 0) problems.push_back(who + "the slot was never rebound");
}

#ifdef DISH_LOCK_STATS
std::vector<LockRow> lockRowsFor(const char* scenario) {
    std::vector<LockRow> rows;
    const lockstats::Snapshot snap = lockstats::snapshot();
    for (const lockstats::LockStats& lock : snap.locks) {
        for (size_t r = 0; r < THREAD_ROLE_COUNT; r++) {
            const lockstats::RoleStats& stats = lock.roles[r];
            if (!stats.touched()) continue;
            LockRow row;
            row.scenario = scenario;
            row.lock = lock.name;
            row.role = threadRoleName(static_cast<ThreadRole>(r));
            row.acquisitions = stats.acquisitions;
            row.contended = stats.contended;
            row.tryFailures = stats.tryFailures;
            row.maxWaitUs = nanosToMicros((int64_t)stats.waitMaxNs);
            row.p99WaitUs =
                nanosToMicros((int64_t)lockstats::histogramPercentileNs(stats.waitHistogram, 0.99));
            row.maxHoldUs = nanosToMicros((int64_t)stats.holdMaxNs);
            rows.push_back(row);
        }
    }
    return rows;
}
#endif

struct RunPlan {
    double secs = DEFAULT_SECS;
    int rateHz = DEFAULT_RATE_HZ;
    int micHz = DEFAULT_MIC_HZ;
    double batteryBurstSecs = BATTERY_BURST_SECS;
    double bindingPeriodSecs = BINDING_PERIOD_SECS;
    bool smoke = false;
};

uint64_t plannedFrames(const double secs, const int rateHz) {
    return (uint64_t)std::llround(secs * rateHz);
}

Stream makeStream(const std::string& name, const ThreadRole role, const int rateHz,
                  const double secs) {
    Stream stream;
    stream.name = name;
    stream.role = role;
    stream.rateHz = rateHz;
    stream.planned = plannedFrames(secs, rateHz);
    stream.reserve();
    return stream;
}

// Sleeps in 1 ms slices until due, so a stop is seen promptly; false once stopped.
bool sleepToward(const TimePoint due, const std::atomic<bool>& stop) {
    while (!stop.load() && Clock::now() < due) std::this_thread::sleep_for(Nanos(ONE_MS_NS));
    return !stop.load();
}

// Cold senders at their own schedules until stop. Battery: one datagram per controller at the
// start, every 30 s, and once more at the burst, as Kotlin's battery source polls every slot.
void runBattery(const dish_session::SessionRef& session, const TimePoint start,
                const double burstSecs, const std::atomic<bool>& stop) {
    const ScopedThreadRole role(ThreadRole::Battery);
    double periodicSecs = 0.0;
    bool burstSent = false;
    while (true) {
        const bool burstIsNext = !burstSent && burstSecs < periodicSecs;
        const double nextSecs = burstIsNext ? burstSecs : periodicSecs;
        if (!sleepToward(start + secondsToNanos(nextSecs), stop)) return;
        for (int controller = 0; controller < CONTROLLERS; controller++) {
            dish_send::sendBattery(session, controller, 80, 1);
        }
        if (burstIsNext) {
            burstSent = true;
            continue;
        }
        periodicSecs += BATTERY_PERIOD_SECS;
    }
}

void runMic(const dish_session::SessionRef& session, const TimePoint start, const int micHz,
            const std::atomic<bool>& stop) {
    const ScopedThreadRole role(ThreadRole::MicCapture);
    const DeadlineWait wait;
    const std::vector<int16_t> tone = micTone();
    for (uint64_t k = 0; !stop.load(); k++) {
        wait.until(start + deadlineOffset(k, micHz));
        if (stop.load()) return;
        dish_send::sendMicFrame(session, CONTROLLER_A, tone.data());
    }
}

void runBinding(dish_slots::SlotTable& slots, const int handle, const TimePoint start,
                const double periodSecs, const std::atomic<bool>& stop,
                std::atomic<uint64_t>& rebinds) {
    const ScopedThreadRole role(ThreadRole::Binding);
    for (uint64_t k = 1;; k++) {
        if (!sleepToward(start + secondsToNanos(periodSecs * (double)k), stop)) return;
        if (k % 2 == 1) {
            slots.unbind(DEVICE_B);
            continue;
        }
        slots.bindSatellite(DEVICE_B, handle, CONTROLLER_B);
        rebinds++;
    }
}

ScenarioResult runScenario(const Scenario& scenario, const RunPlan& plan) {
    uint8_t key[32];
    for (int i = 0; i < 32; i++) key[i] = (uint8_t)(KEY_BASE + i);
    fake_satellite::Peer peer;
    dish_session::SessionTable table;
    NoBridge bridge;
    CountingFeedback feedback;
    dish_slots::SlotTable slots(table, bridge);

    const int handle = table.open("127.0.0.1", peer.port());
    const dish_session::SessionRef session = table.find(handle);
    dish_session::setConnectionParams(session, TOKEN, key, 3);
    session->port = dish_net::DatagramPort{&watchedSend, nullptr};
    slots.bindSatellite(DEVICE_A, handle, CONTROLLER_A);
    if (scenario.secondPoller) slots.bindSatellite(DEVICE_B, handle, CONTROLLER_B);
    if (scenario.uiInput) slots.bindSatellite(DEVICE_UI, handle, CONTROLLER_UI);

#ifdef DISH_LOCK_STATS
    lockstats::reset();
#endif
    std::vector<Stream> streams;
    streams.push_back(makeStream("A", ThreadRole::UsbPoller, plan.rateHz, plan.secs));
    if (scenario.secondPoller) {
        streams.push_back(makeStream("B", ThreadRole::UsbPoller, SECOND_POLLER_HZ, plan.secs));
        streams.back().mustSend = !scenario.binding;
    }
    if (scenario.uiInput) {
        streams.push_back(makeStream("UI", ThreadRole::UiInput, UI_INPUT_HZ, plan.secs));
    }

    SatelliteTally satellite;
    std::atomic<bool> satelliteStop{false};
    std::atomic<bool> coldStop{false};
    std::atomic<uint64_t> rebinds{0};
    const TimePoint start = Clock::now() + std::chrono::milliseconds(START_DELAY_MS);
    std::thread satelliteThread(runSatellite, std::ref(peer), key, scenario.receive, start,
                                std::cref(satelliteStop), std::ref(satellite));

    std::vector<std::thread> cold;
    const std::atomic<int> heartbeatMs{HEARTBEAT_INTERVAL_MS};
    if (scenario.coldSenders) {
        session->heartbeat.start([session, &heartbeatMs] {
            const ScopedThreadRole role(ThreadRole::Heartbeat);
            dish_send::runHeartbeat(session, heartbeatMs);
        });
        cold.emplace_back(runBattery, session, start, plan.batteryBurstSecs, std::cref(coldStop));
        cold.emplace_back(runMic, session, start, plan.micHz, std::cref(coldStop));
    }
    if (scenario.receive) {
        cold.emplace_back([&table, &feedback, &coldStop, handle] {
            const ScopedThreadRole role(ThreadRole::Receive);
            while (!coldStop.load()) dish_receive::receiveDatagram(table, handle, feedback);
        });
    }
    if (scenario.binding) {
        cold.emplace_back(runBinding, std::ref(slots), handle, start, plan.bindingPeriodSecs,
                          std::cref(coldStop), std::ref(rebinds));
    }

    DualSensePoller dualSense;
    std::vector<std::thread> producers;
    for (Stream& stream : streams) {
        std::function<void(uint64_t, int64_t)> publish;
        if (stream.name == "A" && scenario.dualSense) {
            publish = [&slots, &dualSense](uint64_t k, int64_t nowNs) {
                dualSense.publish(slots, k, nowNs);
            };
        } else if (stream.name == "UI") {
            publish = [&slots](uint64_t k, int64_t) {
                slots.applyFrameworkMotion(DEVICE_UI, gamepad::MOTION_ACTION_MOVE,
                                           frameworkSample(k));
            };
        } else {
            const int32_t device = stream.name == "A" ? DEVICE_A : DEVICE_B;
            publish = [&slots, device](uint64_t k, int64_t) {
                slots.applyUsbReport(device, padReport(k));
            };
        }
        producers.emplace_back(runProducer, std::ref(stream), start, publish);
    }
    for (std::thread& producer : producers) producer.join();
    coldStop = true;
    session->heartbeat.stop();
    for (std::thread& thread : cold) thread.join();
    satelliteStop = true;
    satelliteThread.join();
    table.close(handle);

    ScenarioResult result;
#ifdef DISH_LOCK_STATS
    result.locks = lockRowsFor(scenario.id);
#endif
    for (const Stream& stream : streams) {
        result.rows.push_back(rowOf(scenario.id, stream.name, stream.role, stream.rateHz,
                                    stream.latenessNs.size(), stream.datagrams, stream.maxDatagrams,
                                    stream.inputNs));
        if (scenario.dualSense) {
            result.rows.push_back(rowOf(scenario.id, "A, 3-datagram reports to the last send",
                                        stream.role, stream.rateHz, stream.wholeNs.size(),
                                        stream.wholeNs.size() * 3, 3, stream.wholeNs));
        }
        checkStream(scenario.id, stream, plan.smoke, result.problems);
    }
    Harness& h = result.harness;
    h.satelliteReceived = satellite.received.load();
    h.unopened = satellite.unopened.load();
    h.outOfOrder = satellite.outOfOrder.load();
    h.pings = satellite.pings.load();
    h.mic = satellite.mic.load();
    h.battery = satellite.battery.load();
    h.rumbleSent = satellite.rumbleSent.load();
    h.lightbarSent = satellite.lightbarSent.load();
    h.rumblesHandled = feedback.rumbles.load();
    h.lightbarsHandled = feedback.lightbars.load();
    h.rebinds = rebinds.load();
    checkHarness(scenario, h, result.problems);
    return result;
}

const ScenarioResult& medianRep(const std::vector<ScenarioResult>& reps) {
    std::vector<size_t> order(reps.size());
    std::iota(order.begin(), order.end(), size_t{0});
    std::sort(order.begin(), order.end(), [&reps](size_t a, size_t b) {
        return reps[a].rows.front().p999Us < reps[b].rows.front().p999Us;
    });
    return reps[order[(order.size() - 1) / 2]];
}

std::string fixed(const double value, const int decimals) {
    char text[64];
    std::snprintf(text, sizeof(text), "%.*f", decimals, value);
    return text;
}

std::vector<std::string> rowCells(const Row& row) {
    return {row.scenario,
            row.stream,
            row.role,
            std::to_string(row.rateHz),
            std::to_string(row.reports),
            std::to_string(row.datagrams),
            fixed(row.datagramsPerReport, 3),
            std::to_string(row.maxDatagrams),
            fixed(row.p50Us, 1),
            fixed(row.p99Us, 1),
            fixed(row.p999Us, 1),
            fixed(row.maxUs, 1),
            std::to_string(row.overOneMs)};
}

const std::vector<std::string> ROW_HEADERS = {
    "scenario",       "stream", "role",   "rate Hz",  "reports", "datagrams", "datagrams/report",
    "max per report", "p50 us", "p99 us", "p99.9 us", "max us",  "over 1 ms"};

std::vector<std::string> lockCells(const LockRow& row) {
    return {row.scenario,
            row.lock,
            row.role,
            std::to_string(row.acquisitions),
            std::to_string(row.contended),
            std::to_string(row.tryFailures),
            fixed(row.maxWaitUs, 1),
            fixed(row.p99WaitUs, 1),
            fixed(row.maxHoldUs, 1)};
}

const std::vector<std::string> LOCK_HEADERS = {"scenario",     "lock",        "role",
                                               "acquisitions", "contended",   "failed tries",
                                               "max wait us",  "p99 wait us", "max hold us"};

std::string markdownLine(const std::vector<std::string>& cells) {
    std::string line = "|";
    for (const std::string& cell : cells) line += " " + cell + " |";
    return line;
}

std::string markdownRule(const size_t textColumns, const size_t columns) {
    std::string line = "|";
    for (size_t i = 0; i < columns; i++) line += i < textColumns ? " --- |" : " ---: |";
    return line;
}

void printTables(const std::vector<Row>& rows, const std::vector<LockRow>& locks) {
    std::cout << markdownLine(ROW_HEADERS) << "\n" << markdownRule(3, ROW_HEADERS.size()) << "\n";
    for (const Row& row : rows) std::cout << markdownLine(rowCells(row)) << "\n";
    if (locks.empty()) return;
    std::cout << "\n"
              << markdownLine(LOCK_HEADERS) << "\n"
              << markdownRule(3, LOCK_HEADERS.size()) << "\n";
    for (const LockRow& row : locks) std::cout << markdownLine(lockCells(row)) << "\n";
}

std::string jsonString(const std::string& text) {
    std::string out = "\"";
    for (const char c : text) {
        if (c == '"' || c == '\\') out += '\\';
        if ((unsigned char)c < 0x20) continue;
        out += c;
    }
    return out + "\"";
}

std::string trimmed(const std::string& text) {
    const size_t first = text.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return "";
    const size_t last = text.find_last_not_of(" \t\r\n");
    return text.substr(first, last - first + 1);
}

std::string cpuName() {
#ifdef _WIN32
    char name[256] = {};
    DWORD size = sizeof(name);
    const LSTATUS read =
        RegGetValueA(HKEY_LOCAL_MACHINE, "HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
                     "ProcessorNameString", RRF_RT_REG_SZ, nullptr, name, &size);
    return read == ERROR_SUCCESS ? trimmed(name) : "unknown";
#else
    std::ifstream cpuinfo("/proc/cpuinfo");
    std::string line;
    while (std::getline(cpuinfo, line)) {
        if (line.rfind("model name", 0) == 0) return trimmed(line.substr(line.find(':') + 1));
    }
    return "unknown";
#endif
}

const char* osName() {
#ifdef _WIN32
    return "Windows";
#elif defined(__linux__)
    return "Linux";
#else
    return "other";
#endif
}

#ifdef __OPTIMIZE__
constexpr const char* OPTIMIZED = "yes";
#else
constexpr const char* OPTIMIZED = "no";
#endif

#ifdef __clang__
constexpr const char* COMPILER = "clang " __clang_version__;
#else
constexpr const char* COMPILER = "gcc " __VERSION__;
#endif

std::string machineJson() {
    return "{\"cpu\":" + jsonString(cpuName()) + ",\"os\":" + jsonString(osName()) +
           ",\"hardwareThreads\":" + std::to_string(std::thread::hardware_concurrency()) +
           ",\"compiler\":" + jsonString(COMPILER) + ",\"optimized\":" + jsonString(OPTIMIZED) +
           "}";
}

std::string rowJson(const Row& row) {
    return "{\"scenario\":" + jsonString(row.scenario) + ",\"stream\":" + jsonString(row.stream) +
           ",\"role\":" + jsonString(row.role) + ",\"rateHz\":" + std::to_string(row.rateHz) +
           ",\"reports\":" + std::to_string(row.reports) +
           ",\"datagrams\":" + std::to_string(row.datagrams) +
           ",\"datagramsPerReport\":" + fixed(row.datagramsPerReport, 3) +
           ",\"maxDatagramsPerReport\":" + std::to_string(row.maxDatagrams) +
           ",\"p50Us\":" + fixed(row.p50Us, 2) + ",\"p99Us\":" + fixed(row.p99Us, 2) +
           ",\"p999Us\":" + fixed(row.p999Us, 2) + ",\"maxUs\":" + fixed(row.maxUs, 2) +
           ",\"overOneMs\":" + std::to_string(row.overOneMs) + "}";
}

std::string lockJson(const LockRow& row) {
    return "{\"scenario\":" + jsonString(row.scenario) + ",\"lock\":" + jsonString(row.lock) +
           ",\"role\":" + jsonString(row.role) +
           ",\"acquisitions\":" + std::to_string(row.acquisitions) +
           ",\"contended\":" + std::to_string(row.contended) +
           ",\"tryFailures\":" + std::to_string(row.tryFailures) +
           ",\"maxWaitUs\":" + fixed(row.maxWaitUs, 2) +
           ",\"p99WaitUs\":" + fixed(row.p99WaitUs, 2) +
           ",\"maxHoldUs\":" + fixed(row.maxHoldUs, 2) + "}";
}

std::string harnessJson(const std::string& scenario, const Harness& h) {
    return "{\"scenario\":" + jsonString(scenario) +
           ",\"satelliteReceived\":" + std::to_string(h.satelliteReceived) +
           ",\"pings\":" + std::to_string(h.pings) + ",\"mic\":" + std::to_string(h.mic) +
           ",\"battery\":" + std::to_string(h.battery) +
           ",\"rumbleSent\":" + std::to_string(h.rumbleSent) +
           ",\"lightbarSent\":" + std::to_string(h.lightbarSent) +
           ",\"rumblesHandled\":" + std::to_string(h.rumblesHandled) +
           ",\"lightbarsHandled\":" + std::to_string(h.lightbarsHandled) +
           ",\"rebinds\":" + std::to_string(h.rebinds) + "}";
}

template <typename T, typename ToJson>
std::string jsonArray(const std::vector<T>& items, ToJson toJson) {
    std::string out = "[";
    for (size_t i = 0; i < items.size(); i++) out += (i == 0 ? "" : ",") + toJson(items[i]);
    return out + "]";
}

bool writeJson(const std::string& path, const Options& options, const RunPlan& plan,
               const std::vector<Row>& rows, const std::vector<LockRow>& locks,
               const std::vector<std::pair<std::string, Harness>>& harnesses,
               const std::vector<std::string>& problems) {
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    out << "{\"bench\":\"bench_send_path\",\"lockStats\":" << (LOCK_STATS_BUILD ? "true" : "false")
        << ",\"settings\":{\"rateHz\":" << plan.rateHz << ",\"secs\":" << fixed(plan.secs, 2)
        << ",\"reps\":" << (plan.smoke ? 1 : options.reps) << ",\"micHz\":" << plan.micHz
        << ",\"smoke\":" << (plan.smoke ? "true" : "false") << "},\"machine\":" << machineJson()
        << ",\"rows\":" << jsonArray(rows, rowJson) << ",\"locks\":" << jsonArray(locks, lockJson)
        << ",\"harness\":"
        << jsonArray(harnesses,
                     [](const std::pair<std::string, Harness>& item) {
                         return harnessJson(item.first, item.second);
                     })
        << ",\"problems\":" << jsonArray(problems, jsonString) << "}\n";
    return (bool)out;
}

bool writeCsv(const std::string& path, const std::vector<Row>& rows) {
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    const auto line = [&out](const std::vector<std::string>& cells) {
        for (size_t i = 0; i < cells.size(); i++) out << (i == 0 ? "" : ",") << cells[i];
        out << "\n";
    };
    line(ROW_HEADERS);
    for (const Row& row : rows) line(rowCells(row));
    return (bool)out;
}

void printBanner(const Options& options, const RunPlan& plan) {
    std::cout << "bench_send_path" << (LOCK_STATS_BUILD ? " (lock statistics build)" : "") << "\n"
              << "  machine: " << cpuName() << ", " << std::thread::hardware_concurrency()
              << " hardware threads, " << osName() << ", " << COMPILER
              << ", optimized: " << OPTIMIZED << "\n"
              << "  settings: --rate " << plan.rateHz << " --secs " << fixed(plan.secs, 2)
              << " --reps " << (plan.smoke ? 1 : options.reps) << " --mic-hz " << plan.micHz
              << (plan.smoke ? " --smoke" : "") << "\n"
              << "  latency: a report in hand to the return of the sendto that carried it, per "
                 "report; the reported run of each scenario is the one whose stream A p99.9 is "
                 "the median\n\n";
}

void printHarness(const char* scenario, const Harness& h) {
    std::cout << "  " << scenario << " harness: satellite received " << h.satelliteReceived
              << " (pings " << h.pings << ", mic " << h.mic << ", battery " << h.battery
              << "), sent " << h.rumbleSent << " rumble + " << h.lightbarSent
              << " lightbar; the receive loop handed on " << h.rumblesHandled << " rumble + "
              << h.lightbarsHandled << " lightbar; rebinds " << h.rebinds << "\n";
}

} // namespace

int main(int argc, char** argv) {
    const std::optional<Options> parsed = parseOptions(argc, argv);
    if (!parsed) {
        printUsage(stderr);
        return 2;
    }
    const Options options = *parsed;
    if (options.help) {
        printUsage(stdout);
        return 0;
    }
    if (sodium_init() < 0) {
        std::cerr << "sodium_init failed\n";
        return 1;
    }

    RunPlan plan;
    plan.rateHz = options.rateHz;
    plan.micHz = options.micHz;
    plan.smoke = options.smoke;
    plan.secs = options.smoke ? SMOKE_SECS : options.secs;
    plan.batteryBurstSecs = options.smoke ? SMOKE_BATTERY_BURST_SECS : BATTERY_BURST_SECS;
    plan.bindingPeriodSecs = options.smoke ? SMOKE_BINDING_PERIOD_SECS : BINDING_PERIOD_SECS;
    const int reps = options.smoke ? 1 : options.reps;
    printBanner(options, plan);

    if (!options.smoke) {
        for (const Scenario& scenario : SCENARIOS) runScenario(scenario, plan);
    }
    std::vector<std::vector<ScenarioResult>> results(SCENARIOS.size());
    for (int rep = 0; rep < reps; rep++) {
        for (size_t s = 0; s < SCENARIOS.size(); s++) {
            results[s].push_back(runScenario(SCENARIOS[s], plan));
        }
    }

    std::vector<Row> rows;
    std::vector<LockRow> locks;
    std::vector<std::pair<std::string, Harness>> harnesses;
    std::vector<std::string> problems;
    for (size_t s = 0; s < SCENARIOS.size(); s++) {
        const ScenarioResult& reported = medianRep(results[s]);
        rows.insert(rows.end(), reported.rows.begin(), reported.rows.end());
        locks.insert(locks.end(), reported.locks.begin(), reported.locks.end());
        harnesses.emplace_back(SCENARIOS[s].id, reported.harness);
        printHarness(SCENARIOS[s].id, reported.harness);
        for (const ScenarioResult& rep : results[s]) {
            problems.insert(problems.end(), rep.problems.begin(), rep.problems.end());
        }
    }
    std::cout << "\n";
    printTables(rows, locks);

    if (!options.jsonPath.empty() &&
        !writeJson(options.jsonPath, options, plan, rows, locks, harnesses, problems)) {
        problems.push_back("could not write " + options.jsonPath);
    }
    if (!options.csvPath.empty() && !writeCsv(options.csvPath, rows)) {
        problems.push_back("could not write " + options.csvPath);
    }
    if (problems.empty()) return 0;
    std::cerr << "\nharness problems:\n";
    for (const std::string& problem : problems) std::cerr << "  " << problem << "\n";
    return 1;
}
