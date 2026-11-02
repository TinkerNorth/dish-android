// SPDX-License-Identifier: LGPL-3.0-or-later

// The lock laws: which named locks each thread role takes on the hot path, which locks are held
// across a blocking call and which nest. Every list holds exactly what the code does today, so the
// test is green now and fails both ways: a lock a role starts taking that is not listed, and a
// listed entry the code no longer needs, which is then deleted. The lists only shrink.

#include <gtest/gtest.h>
#include <sodium.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <functional>
#include <iomanip>
#include <iostream>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "audio_codec.h"
#include "bridge_queue.h"
#include "fake_satellite.h"
#include "gamepad_input.h"
#include "receive_path.h"
#include "recording_sinks.h"
#include "send_path.h"
#include "session_table.h"
#include "slot_table.h"
#include "speaker_queue.h"
#include "tracked_mutex.h"
#include "wire_encoders.h"

#ifndef DISH_LOCK_STATS
#error "lock_laws_test reads lock statistics: link it against dish_core_lockstats"
#endif

namespace {

enum class Allowance { ByDesign, Debt };

const char* allowanceName(const Allowance why) {
    return why == Allowance::ByDesign ? "by design" : "debt";
}

struct LockName {
    const char* lock;
    Allowance why;
};

struct LockPair {
    const char* first;
    const char* second;
    Allowance why;
};

// A report is published under the device lock, staged under the slot lock, and draws its counter
// under the session's send turn after looking the session up in the table.
const std::vector<LockName> USB_POLLER_MAY_ACQUIRE = {
    {"input.devices", Allowance::Debt},
    {"input.slots", Allowance::Debt},
    {"session.table", Allowance::Debt},
    {"session.turn", Allowance::Debt},
};

const std::vector<LockName> UI_INPUT_MAY_ACQUIRE = {
    {"input.devices", Allowance::Debt},
    {"input.slots", Allowance::Debt},
    {"session.table", Allowance::Debt},
    {"session.turn", Allowance::Debt},
};

// Every pass of the receive loop looks its session up.
const std::vector<LockName> RECEIVE_MAY_ACQUIRE = {
    {"session.table", Allowance::Debt},
};

const std::vector<LockPair> BLOCKING_MAY_HOLD = {
    {"net.close", "session.turn", Allowance::ByDesign},
    {"net.sendto", "session.turn", Allowance::Debt},
};

const std::vector<LockPair> ORDER_MAY_NEST = {
    {"input.devices", "input.slots", Allowance::Debt},
    {"input.devices", "session.table", Allowance::Debt},
    {"input.devices", "session.turn", Allowance::Debt},
    {"input.slots", "session.table", Allowance::Debt},
    {"input.slots", "session.turn", Allowance::Debt},
};

using Clock = std::chrono::steady_clock;
using NameSet = std::set<std::string>;
using PairSet = std::set<std::pair<std::string, std::string>>;

constexpr auto RUN_TIME = std::chrono::milliseconds(1500);
constexpr int POLLER_A_HZ = 1000;
constexpr int POLLER_B_HZ = 250;
constexpr int POLLER_MOONLIGHT_HZ = 250;
constexpr int UI_INPUT_HZ = 250;
constexpr int MIC_HZ = 50;
constexpr int BATTERY_HZ = 10;
constexpr int BINDING_HZ = 5;
constexpr int FEEDBACK_HZ = 100;
constexpr int SPEAKER_HZ = 50;
constexpr uint64_t MUTE_EVERY = 50;
constexpr uint64_t LIGHTBAR_EVERY = 10;
constexpr int HEARTBEAT_INTERVAL_MS = 50;
constexpr uint64_t MOTION_EVERY = 8;
constexpr uint64_t KEY_EVERY = 50;
constexpr int SATELLITE_POLL_MS = 1;
constexpr int32_t DEVICE_A = 1;
constexpr int32_t DEVICE_B = 2;
constexpr int32_t DEVICE_UI = 3;
constexpr int32_t DEVICE_MOONLIGHT = 4;
constexpr uint8_t SPEAKER_CONTROLLER = 0;
constexpr int32_t KEYCODE_BUTTON_A = 96;
constexpr double PI = 3.141592653589793;
constexpr double TONE_HZ = 440.0;
constexpr double TONE_AMPLITUDE = 8000.0;
constexpr uint8_t TOKEN[4] = {0x5A, 0x11, 0x22, 0x33};

Clock::duration periodOf(const int hz) {
    return std::chrono::duration_cast<Clock::duration>(std::chrono::nanoseconds(1000000000LL / hz));
}

// Runs tick(k) at start + k periods until stop, whatever happened to tick k - 1.
void paced(const std::atomic<bool>& stop, const int hz, const std::function<void(uint64_t)>& tick) {
    const Clock::time_point start = Clock::now();
    for (uint64_t k = 0; !stop.load(); ++k) {
        std::this_thread::sleep_until(start + periodOf(hz) * (int64_t)k);
        if (stop.load()) return;
        tick(k);
    }
}

gamepad::DeviceState usbReport(const uint64_t k) {
    gamepad::DeviceState report{};
    report.wButtons = (uint16_t)(k & 0x0F);
    report.sLX = (int16_t)(4000 + (int)(k % 1000) * 20);
    report.sLY = (int16_t)(-4000 - (int)(k % 1000) * 20);
    return report;
}

gamepad::TouchpadState movingFinger(const uint64_t k) {
    gamepad::TouchpadState t{};
    t.f0Active = (k / 64) % 2 == 0;
    t.f0Id = (uint8_t)(k / 128);
    t.f0X = (int16_t)(k * 37);
    t.f0Y = (int16_t)(k * 11);
    return t;
}

int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now().time_since_epoch())
        .count();
}

// What usb_host.cpp's poller does with one decoded report: the report, then motion at its own
// cadence, then touch through the send-on-change gate.
void publishLikeThePoller(dish_slots::SlotTable& slots, const int32_t device, const uint64_t k,
                          gamepad::TouchpadGate& gate) {
    slots.applyUsbReport(device, usbReport(k));
    if (k % MOTION_EVERY == 0) {
        const dish_send::MotionSample motion{{(int16_t)k, 2, 3}, {4, 5, 6}, 8000};
        slots.applyUsbMotion(device, motion);
    }
    if (gate.decide(movingFinger(k), nowNs()) == gamepad::TouchpadSend::SKIP) return;
    slots.applyUsbTouchpad(device, gate.lastSent(), (uint32_t)gate.lastEventTimeMs());
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

struct SatelliteTally {
    std::atomic<uint64_t> received{0};
    std::atomic<uint64_t> acked{0};
    std::atomic<uint64_t> feedbackSent{0};
    std::atomic<uint64_t> speakerSent{0};
};

// One 20 ms frame of silence as the satellite encodes speaker audio, behind its wire header.
fake_satellite::Bytes speakerPayload(const uint16_t seq) {
    const std::unique_ptr<dish_audio::OpusStreamEncoder> encoder =
        dish_audio::OpusStreamEncoder::create(dish_audio::Stream::Speaker);
    const std::vector<int16_t> stereo(dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES, 0);
    fake_satellite::Bytes payload(dish_wire::AUDIO_WIRE_HEADER_BYTES +
                                  dish_wire::AUDIO_WIRE_MAX_OPUS_BYTES);
    const size_t len = encoder->encode(stereo.data(), dish_audio::AUDIO_FRAME_SAMPLES,
                                       payload.data() + dish_wire::AUDIO_WIRE_HEADER_BYTES,
                                       dish_wire::AUDIO_WIRE_MAX_OPUS_BYTES);
    payload.resize(dish_wire::AUDIO_WIRE_HEADER_BYTES + len);
    dish_wire::encodeAudioFrameHeader(payload.data(), SPEAKER_CONTROLLER, seq);
    return payload;
}

// The satellite's side: drains the client's datagrams, answers each ping with an enriched ack,
// and once it knows the client sends rumble with a lightbar every tenth datagram at FEEDBACK_HZ
// and speaker audio at SPEAKER_HZ.
void runSatellite(fake_satellite::Peer& peer, const uint8_t key[32], const std::atomic<bool>& stop,
                  SatelliteTally& tally) {
    uint32_t counter = 1;
    bool knowsClient = false;
    const Clock::time_point start = Clock::now();
    uint64_t nextFeedback = 0;
    uint16_t nextSpeaker = 0;
    while (!stop.load()) {
        const fake_satellite::Bytes datagram = peer.receive(SATELLITE_POLL_MS);
        if (!datagram.empty()) {
            knowsClient = true;
            tally.received++;
            const auto inner =
                fake_satellite::openFromClient(TOKEN, key, datagram.data(), datagram.size());
            if (inner && inner->msgType == dish_wire::MSG_HEARTBEAT_PING) {
                const uint8_t ack[dish_wire::HEARTBEAT_ACK_ENRICHED_BYTES] = {1, 3, 0, 1, 0, 7};
                peer.reply(fake_satellite::sealToClient(
                    TOKEN, key, counter++, dish_wire::MSG_HEARTBEAT_ACK, ack, sizeof(ack)));
                tally.acked++;
            }
        }
        const bool speakerDue = Clock::now() >= start + periodOf(SPEAKER_HZ) * (int64_t)nextSpeaker;
        if (knowsClient && speakerDue) {
            const fake_satellite::Bytes audio = speakerPayload(nextSpeaker++);
            peer.reply(fake_satellite::sealToClient(
                TOKEN, key, counter++, dish_wire::MSG_SPEAKER_AUDIO, audio.data(), audio.size()));
            tally.speakerSent++;
        }
        const bool feedbackDue =
            Clock::now() >= start + periodOf(FEEDBACK_HZ) * (int64_t)nextFeedback;
        if (!knowsClient || !feedbackDue) continue;
        const bool lightbar = nextFeedback % LIGHTBAR_EVERY == LIGHTBAR_EVERY - 1;
        const uint8_t rumble[dish_wire::RUMBLE_PAYLOAD_BYTES] = {0, 0x80, 0x00, 0x40, 0x00, 0, 50};
        const uint8_t color[dish_wire::LIGHTBAR_PAYLOAD_BYTES] = {0, 0xFF, 0x00, 0x80};
        peer.reply(lightbar
                       ? fake_satellite::sealToClient(TOKEN, key, counter++,
                                                      dish_wire::MSG_LIGHTBAR, color, sizeof(color))
                       : fake_satellite::sealToClient(TOKEN, key, counter++, dish_wire::MSG_RUMBLE,
                                                      rumble, sizeof(rumble)));
        tally.feedbackSent++;
        nextFeedback++;
    }
}

struct RunTally {
    std::atomic<uint64_t> received{0};
    std::atomic<uint64_t> micFrames{0};
    std::atomic<uint64_t> binds{0};
    std::atomic<uint64_t> unbinds{0};
    std::atomic<uint64_t> bridgeReports{0};
    std::atomic<uint64_t> muteEdges{0};
    std::atomic<uint64_t> resolvedConnections{0};
    std::atomic<uint64_t> speakerFrames{0};
};

struct LawRun {
    lockstats::Snapshot snap;
    SatelliteTally satellite;
    RunTally tally;
    size_t rumbles = 0;
    size_t lightbars = 0;
};

// The receive thread's feedback as the JNI layer hands it on: the upcalls recorded, speaker audio
// onto the queue the audio dispatch thread drains.
class QueueingFeedback final : public dish_receive::FeedbackSink {
  public:
    explicit QueueingFeedback(dish_receive::SpeakerQueue& speaker) : speaker_(speaker) {}

    void rumble(const int handle, const uint8_t ctrlIdx, const uint16_t strong, const uint16_t weak,
                const uint16_t durationMs) override {
        recorded.rumble(handle, ctrlIdx, strong, weak, durationMs);
    }
    void lightbar(const int handle, const dish_wire::LightbarPayload& lb) override {
        recorded.lightbar(handle, lb);
    }
    void triggerEffects(const int handle, const uint8_t ctrlIdx, const uint8_t* blocks) override {
        recorded.triggerEffects(handle, ctrlIdx, blocks);
    }
    void playerLeds(const int handle, const dish_wire::PlayerLedsPayload& leds) override {
        recorded.playerLeds(handle, leds);
    }
    void micLed(const int handle, const dish_wire::MicLedPayload& led) override {
        recorded.micLed(handle, led);
    }
    void audio(const int handle, const int lane, const dish_wire::AudioFrameHeader& header,
               const uint8_t* opus, const size_t opusLen) override {
        speaker_.push(handle, lane, header, opus, opusLen);
    }

    recording::Feedback recorded;

  private:
    dish_receive::SpeakerQueue& speaker_;
};

// The bridge thread's side of the JNI layer: each new connection number resolved once, as its
// connection-id cache does, and every item counted.
class CountingBridge final : public dish_bridge::BridgeConsumer {
  public:
    CountingBridge(dish_slots::SlotTable& slots, RunTally& tally) : slots_(slots), tally_(tally) {}

    void report(const dish_slots::BridgeReport& r) override {
        tally_.bridgeReports++;
        if (r.connection == lastResolved_) return;
        if (slots_.bridgeConnectionId(r.connection)) tally_.resolvedConnections++;
        lastResolved_ = r.connection;
    }
    void micMute(int32_t, bool) override { tally_.muteEdges++; }

  private:
    dish_slots::SlotTable& slots_;
    RunTally& tally_;
    uint32_t lastResolved_ = 0;
};

class CountingSpeaker final : public dish_receive::SpeakerSink {
  public:
    explicit CountingSpeaker(RunTally& tally) : tally_(tally) {}
    void pcm(int, uint8_t, int, const int16_t*, bool) override { tally_.speakerFrames++; }

  private:
    RunTally& tally_;
};

// Every role on its own thread, each driving the extracted units the way production does, for
// RUN_TIME on one session over loopback; the session is closed before the snapshot.
void runEveryRole(LawRun& run) {
    uint8_t key[32];
    for (int i = 0; i < 32; i++) key[i] = (uint8_t)(0xC0 + i);
    fake_satellite::Peer peer;
    dish_session::SessionTable table;
    dish_bridge::BridgeQueue bridge;
    dish_receive::SpeakerQueue speaker;
    QueueingFeedback feedback(speaker);
    dish_slots::SlotTable slots(table, bridge);

    lockstats::reset();
    const int handle = table.open("127.0.0.1", peer.port());
    ASSERT_GT(handle, 0);
    const std::shared_ptr<dish_session::Session> session = table.get(handle);
    dish_session::setConnectionParams(*session, TOKEN, key, 3);
    slots.bindSatellite(DEVICE_A, handle, 0);
    slots.bindSatellite(DEVICE_B, handle, 1);
    slots.bindSatellite(DEVICE_UI, handle, 2);
    slots.bindBridge(DEVICE_MOONLIGHT, std::make_shared<const std::string>("moonlight:laws"),
                     dish_slots::SLOT_MOONLIGHT, 0);

    std::atomic<bool> satelliteStop{false};
    std::atomic<bool> stop{false};
    std::thread satellite(runSatellite, std::ref(peer), key, std::cref(satelliteStop),
                          std::ref(run.satellite));
    const std::atomic<int> heartbeatMs{HEARTBEAT_INTERVAL_MS};
    session->heartbeat.start([session, &heartbeatMs] {
        const ScopedThreadRole role(ThreadRole::Heartbeat);
        dish_send::runHeartbeat(session, heartbeatMs);
    });

    CountingBridge bridgeConsumer(slots, run.tally);
    std::thread bridgeThread([&] {
        const ScopedThreadRole role(ThreadRole::Bridge);
        while (bridge.waitForWork()) bridge.drain(bridgeConsumer);
    });
    CountingSpeaker speakerSink(run.tally);
    std::thread audioDispatch([&] {
        const ScopedThreadRole role(ThreadRole::AudioDispatch);
        while (speaker.waitForWork()) speaker.drain(table, speakerSink);
    });

    std::vector<std::thread> roles;
    roles.emplace_back([&] {
        const ScopedThreadRole role(ThreadRole::UsbPoller);
        gamepad::TouchpadGate gate;
        paced(stop, POLLER_A_HZ,
              [&](uint64_t k) { publishLikeThePoller(slots, DEVICE_A, k, gate); });
    });
    roles.emplace_back([&] {
        const ScopedThreadRole role(ThreadRole::UsbPoller);
        gamepad::TouchpadGate gate;
        paced(stop, POLLER_MOONLIGHT_HZ, [&](uint64_t k) {
            publishLikeThePoller(slots, DEVICE_MOONLIGHT, k, gate);
            if (k % MUTE_EVERY == 0)
                bridge.pushMicMute(DEVICE_MOONLIGHT, (k / MUTE_EVERY) % 2 == 0);
        });
    });
    roles.emplace_back([&] {
        const ScopedThreadRole role(ThreadRole::UsbPoller);
        gamepad::TouchpadGate gate;
        paced(stop, POLLER_B_HZ,
              [&](uint64_t k) { publishLikeThePoller(slots, DEVICE_B, k, gate); });
    });
    roles.emplace_back([&] {
        const ScopedThreadRole role(ThreadRole::UiInput);
        paced(stop, UI_INPUT_HZ, [&](uint64_t k) {
            slots.applyFrameworkMotion(DEVICE_UI, gamepad::MOTION_ACTION_MOVE, frameworkSample(k));
            if (k % KEY_EVERY != 0) return;
            const int32_t action =
                (k / KEY_EVERY) % 2 == 0 ? gamepad::KEY_ACTION_DOWN : gamepad::KEY_ACTION_UP;
            slots.applyFrameworkKey(DEVICE_UI, KEYCODE_BUTTON_A, action);
        });
    });
    roles.emplace_back([&] {
        const ScopedThreadRole role(ThreadRole::Battery);
        paced(stop, BATTERY_HZ, [&](uint64_t) { dish_send::sendBattery(session, 0, 80, 1); });
    });
    roles.emplace_back([&] {
        const ScopedThreadRole role(ThreadRole::MicCapture);
        const std::vector<int16_t> tone = micTone();
        paced(stop, MIC_HZ, [&](uint64_t) {
            if (dish_send::sendMicFrame(session, 0, tone.data())) run.tally.micFrames++;
        });
    });
    roles.emplace_back([&] {
        const ScopedThreadRole role(ThreadRole::Binding);
        paced(stop, BINDING_HZ, [&](uint64_t k) {
            if (k % 2 == 0) {
                slots.unbind(DEVICE_B);
                run.tally.unbinds++;
                return;
            }
            slots.bindSatellite(DEVICE_B, handle, 1);
            run.tally.binds++;
        });
    });
    std::thread receive([&] {
        const ScopedThreadRole role(ThreadRole::Receive);
        while (!stop.load()) {
            if (dish_receive::receiveDatagram(table, handle, feedback) == 1) run.tally.received++;
        }
    });

    std::this_thread::sleep_for(RUN_TIME);
    stop = true;
    for (std::thread& role : roles) role.join();
    session->heartbeat.stop();
    receive.join();
    satelliteStop = true;
    satellite.join();
    bridge.stop();
    bridgeThread.join();
    speaker.stop();
    audioDispatch.join();
    table.close(handle);

    run.snap = lockstats::snapshot();
    run.rumbles = feedback.recorded.rumbles.size();
    run.lightbars = feedback.recorded.lightbars.size();
}

NameSet locksTakenBy(const lockstats::Snapshot& snap, const ThreadRole role) {
    NameSet names;
    for (const lockstats::LockStats& lock : snap.locks) {
        if (lock.role(role).touched()) names.insert(lock.name);
    }
    return names;
}

PairSet pairsOf(const std::vector<lockstats::NamePair>& pairs) {
    PairSet out;
    for (const lockstats::NamePair& pair : pairs) out.insert({pair.first, pair.second});
    return out;
}

uint64_t acquisitions(const lockstats::Snapshot& snap, const char* lock, const ThreadRole role) {
    const lockstats::LockStats* found = snap.find(lock);
    return found == nullptr ? 0 : found->role(role).acquisitions;
}

uint64_t regionHits(const lockstats::Snapshot& snap, const std::string& region) {
    for (const lockstats::RegionHit& hit : snap.regions) {
        if (hit.region == region) return hit.hits;
    }
    return 0;
}

void expectExactly(const std::string& law, const NameSet& observed,
                   const std::vector<LockName>& listed) {
    NameSet names;
    for (const LockName& entry : listed) names.insert(entry.lock);
    for (const std::string& lock : observed) {
        EXPECT_EQ(names.count(lock), 1u) << law << ": " << lock << " is not on the list";
    }
    for (const LockName& entry : listed) {
        EXPECT_EQ(observed.count(entry.lock), 1u)
            << law << ": " << entry.lock << " (" << allowanceName(entry.why)
            << ") is listed but no longer observed; delete it";
    }
}

void expectExactly(const std::string& law, const PairSet& observed,
                   const std::vector<LockPair>& listed) {
    PairSet pairs;
    for (const LockPair& entry : listed) pairs.insert({entry.first, entry.second});
    for (const auto& [first, second] : observed) {
        EXPECT_EQ(pairs.count({first, second}), 1u)
            << law << ": (" << first << ", " << second << ") is not on the list";
    }
    for (const LockPair& entry : listed) {
        EXPECT_EQ(observed.count({entry.first, entry.second}), 1u)
            << law << ": (" << entry.first << ", " << entry.second << ") ("
            << allowanceName(entry.why) << ") is listed but no longer observed; delete it";
    }
}

bool findCycle(const PairSet& edges, std::vector<std::string>& cycle) {
    std::map<std::string, std::vector<std::string>> next;
    for (const auto& [from, to] : edges) next[from].push_back(to);
    std::map<std::string, int> state;
    std::vector<std::string> path;
    std::function<bool(const std::string&)> visit = [&](const std::string& node) {
        state[node] = 1;
        path.push_back(node);
        for (const std::string& to : next[node]) {
            if (state[to] == 1) {
                cycle.assign(std::find(path.begin(), path.end(), to), path.end());
                cycle.push_back(to);
                return true;
            }
            if (state[to] == 0 && visit(to)) return true;
        }
        path.pop_back();
        state[node] = 2;
        return false;
    };
    for (const auto& [from, to] : edges) {
        if (state[from] == 0 && visit(from)) return true;
    }
    return false;
}

double micros(const uint64_t nanos) { return (double)nanos / 1000.0; }

void report(const lockstats::Snapshot& snap) {
    std::cout << "  lock stats (role: acquisitions, contended, failed tries, max wait us, p99 "
                 "wait us, max hold us)\n"
              << std::fixed << std::setprecision(1);
    for (const lockstats::LockStats& lock : snap.locks) {
        for (size_t r = 0; r < THREAD_ROLE_COUNT; ++r) {
            const lockstats::RoleStats& stats = lock.roles[r];
            if (!stats.touched()) continue;
            std::cout << "    " << lock.name << " / " << threadRoleName(static_cast<ThreadRole>(r))
                      << ": " << stats.acquisitions << ", " << stats.contended << ", "
                      << stats.tryFailures << ", " << micros(stats.waitMaxNs) << ", "
                      << micros(lockstats::histogramPercentileNs(stats.waitHistogram, 0.99)) << ", "
                      << micros(stats.holdMaxNs) << "\n";
        }
    }
    std::cout << "  nesting edges:";
    for (const lockstats::NamePair& edge : snap.edges) {
        std::cout << " (" << edge.first << " -> " << edge.second << ")";
    }
    std::cout << "\n  blocking regions:";
    for (const lockstats::NamePair& pair : snap.blocking) {
        std::cout << " (" << pair.first << " under " << pair.second << ")";
    }
    std::cout << "\n";
}

class LockLaws : public ::testing::Test {
  protected:
    static void SetUpTestSuite() {
        ASSERT_GE(sodium_init(), 0);
        run = std::make_unique<LawRun>();
        runEveryRole(*run);
        report(run->snap);
    }

    static void TearDownTestSuite() { run.reset(); }

    static std::unique_ptr<LawRun> run;
};

std::unique_ptr<LawRun> LockLaws::run;

} // namespace

TEST_F(LockLaws, TheRunReachedEveryRoleAndEveryPath) {
    const lockstats::Snapshot& snap = run->snap;
    EXPECT_GT(acquisitions(snap, "session.turn", ThreadRole::UsbPoller), 0u);
    EXPECT_GT(acquisitions(snap, "session.turn", ThreadRole::UiInput), 0u);
    EXPECT_GT(acquisitions(snap, "session.turn", ThreadRole::Heartbeat), 0u);
    EXPECT_GT(acquisitions(snap, "session.turn", ThreadRole::Battery), 0u);
    EXPECT_GT(acquisitions(snap, "session.audio", ThreadRole::MicCapture), 0u);
    EXPECT_GT(acquisitions(snap, "input.slots", ThreadRole::Binding), 0u);
    EXPECT_GT(acquisitions(snap, "session.table", ThreadRole::Receive), 0u);
    EXPECT_GT(regionHits(snap, "net.sendto"), 0u);
    EXPECT_GT(run->tally.micFrames.load(), 0u);
    EXPECT_GT(run->tally.binds.load(), 0u);
    EXPECT_GT(run->tally.unbinds.load(), 0u);
    EXPECT_GT(run->satellite.acked.load(), 0u);
    EXPECT_GT(run->tally.received.load(), run->satellite.acked.load());
    EXPECT_GT(run->rumbles, 0u);
    EXPECT_GT(run->lightbars, 0u);
    EXPECT_GT(run->tally.bridgeReports.load(), 0u);
    EXPECT_GT(run->tally.muteEdges.load(), 0u);
    EXPECT_EQ(run->tally.resolvedConnections.load(), 1u);
    EXPECT_GT(run->satellite.speakerSent.load(), 0u);
    EXPECT_GT(run->tally.speakerFrames.load(), 0u);
    EXPECT_GT(acquisitions(snap, "session.audio", ThreadRole::AudioDispatch), 0u);
    EXPECT_EQ(snap.overflows, 0u);
}

TEST_F(LockLaws, TheUsbPollersTakeOnlyTheLocksOnTheirList) {
    expectExactly("usb poller law", locksTakenBy(run->snap, ThreadRole::UsbPoller),
                  USB_POLLER_MAY_ACQUIRE);
}

TEST_F(LockLaws, TheUiInputThreadTakesOnlyTheLocksOnItsList) {
    expectExactly("ui input law", locksTakenBy(run->snap, ThreadRole::UiInput),
                  UI_INPUT_MAY_ACQUIRE);
}

TEST_F(LockLaws, TheReceiveThreadTakesOnlyTheLocksOnItsList) {
    expectExactly("receive law", locksTakenBy(run->snap, ThreadRole::Receive), RECEIVE_MAY_ACQUIRE);
}

TEST_F(LockLaws, OnlyTheListedLocksAreHeldAcrossABlockingCall) {
    expectExactly("blocking law", pairsOf(run->snap.blocking), BLOCKING_MAY_HOLD);
}

TEST_F(LockLaws, LocksNestOnlyInTodaysOrderAndNeverInACycle) {
    const PairSet edges = pairsOf(run->snap.edges);
    std::vector<std::string> cycle;
    EXPECT_FALSE(findCycle(edges, cycle)) << "a cycle through " << cycle.front();
    expectExactly("order law", edges, ORDER_MAY_NEST);
}
