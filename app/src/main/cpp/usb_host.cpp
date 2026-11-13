// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_host.h"

#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <time.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#include "dispatch.h"
#include "gamepad_input.h"
#include "hotpath_latency.h"
#include "thread_priority.h"
#include "tracked_mutex.h"
#include "usb_device_table.h"
#include "usb_parsers.h"

#define TAG "SatelliteUsbHost"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace usbhost {

namespace {

// A pad's OUT endpoint as its output worker writes it. The worker is the only thread that touches
// the feedback state and the output report counter, from its start at attach to its stop at
// detach, which comes before the fd is closed.
class UsbOutput final : public dish_usb::OutputDevice {
  public:
    void rumble(const uint16_t strong, const uint16_t weak) override {
        feedback.strong = strong;
        feedback.weak = weak;
        writeRumble(dish_usb::outputClockMs());
    }

    void triggerRumble(const uint16_t left, const uint16_t right) override {
        if (!usbparsers::parserHasTriggerRumble(parser)) return;
        feedback.leftTrigger = left;
        feedback.rightTrigger = right;
        writeRumble(dish_usb::outputClockMs());
    }

    void lightbar(const uint8_t r, const uint8_t g, const uint8_t b) override {
        usbparsers::runLightbar(fd, epOut, parser, feedback, r, g, b);
    }

    void playerLeds(const uint8_t ledMask) override {
        usbparsers::runPlayerLeds(fd, epOut, parser, feedback, ledMask, outSeq++);
    }

    void triggerEffects(const uint8_t* left, const uint8_t* right) override {
        usbparsers::runTriggerEffects(fd, epOut, parser, feedback, left, right);
    }

    void micLed(const uint8_t state) override {
        usbparsers::runMicMuteLed(fd, epOut, parser, feedback, state);
    }

    void ack(const uint8_t* bytes, const size_t len) override {
        usbparsers::writeOutReport(fd, epOut, bytes, len);
    }

    // The reboot wiped the attach-time settings, so they go again or the pad streams without
    // motion while its lizard keyboard leaks into the phone.
    void reconnected() override {
        feedback.controllerAway = false;
        usbparsers::runInit(fd, interfaceNumber, epOut, init);
        feedback.settingsSentMs = dish_usb::outputClockMs();
    }

    // Nothing is refreshed for a slot with no controller, and the one that next connects starts
    // still: the rumble it was playing belonged to the controller that left.
    void left() override {
        feedback.controllerAway = true;
        feedback.strong = 0;
        feedback.weak = 0;
    }

    // A write is stamped when it is attempted, so a pad that refuses one is not retried at once.
    uint32_t refresh(const int64_t nowMs) override {
        const usbparsers::RefreshDue due = usbparsers::refreshDue(parser, feedback, nowMs);
        if (due.settings) {
            usbparsers::runSettingsRefresh(fd, interfaceNumber, parser);
            feedback.settingsSentMs = nowMs;
        }
        if (due.rumble) writeRumble(nowMs);
        return usbparsers::nextRefreshInMs(parser, feedback, nowMs);
    }

    int fd = -1;
    int interfaceNumber = -1;
    uint8_t epOut = 0;
    usbparsers::Parser parser = usbparsers::Parser::NONE;
    usbparsers::InitKind init = usbparsers::InitKind::NONE;
    usbparsers::FeedbackState feedback;
    // The output report counter for protocols that carry one (Xbox One serial, Switch Pro packet
    // number).
    uint8_t outSeq = 0;

  private:
    void writeRumble(const int64_t nowMs) {
        usbparsers::runMergedRumble(fd, interfaceNumber, epOut, parser, feedback, outSeq++);
        feedback.rumbleSentMs = nowMs;
    }
};

struct DeviceCtx {
    int32_t syntheticDeviceId = 0;
    int fd = -1;
    uint16_t vid = 0;
    uint16_t pid = 0;
    uint8_t epIn = 0;
    uint16_t epInMaxPacket = 64;
    uint8_t epOut = 0;
    int interfaceNumber = 0;
    usbparsers::Parser parser = usbparsers::Parser::NONE;
    usbparsers::InitKind init = usbparsers::InitKind::NONE;
    std::string modelName;
    std::string parserName;
    usbparsers::ParserState stickRange;

    // One slot of a wireless receiver, and whether its controller is connected; the poller's own,
    // mirrored to entry->stats.linked for the JVM.
    bool receiverSlot = false;
    bool linked = true;
    // The poller's: set once the kernel reports the device gone, read after the poller is joined.
    bool unplugged = false;

    int64_t lastMotionNs = 0;
    gamepad::TouchpadGate touchGate;
    // The slot's trackpad pick, written by the JVM and read by the poller before each decode.
    std::atomic<uint8_t> trackpadPick{static_cast<uint8_t>(gamepad::TrackpadPick::STICK)};

    // The pad's entry in g_usbDevices: its poller's counters and charge, which the JVM reads
    // without a lock, and the worker that owns its OUT endpoint.
    dish_usb::UsbDevice* entry = nullptr;

    // The writes the pad's output worker performs.
    UsbOutput output;

    std::atomic<bool> stop{false};
    std::thread poller;
};

// The diagnostics the Kotlin pollers read every 500 ms and the output workers the feedback paths
// post to, kept out of g_mtx.
dish_usb::UsbDeviceTable g_usbDevices;

TrackedMutex g_mtx{"usb.devices"};
std::unordered_map<int32_t, std::shared_ptr<DeviceCtx>> g_devices;
std::atomic<int32_t> g_nextSyntheticId{-1000};

int32_t allocSyntheticId() { return g_nextSyntheticId.fetch_sub(1, std::memory_order_relaxed); }

// Number of URBs kept in flight per device. Two is enough to eliminate the gap between
// REAP and the next SUBMIT for any HID gamepad we target (highest declared bInterval is 1 ms);
// a third URB never gets filled in steady state because by the time it's queued the kernel has
// already redelivered the one we just resubmitted.
static constexpr int kInFlightUrbs = 2;

// Short enough that a stop request is observed promptly, long enough that an idle pad does not
// spin the poll thread.
static constexpr int kUrbPollTimeoutMs = 100;

// Minimum spacing between MSG_MOTION sends per device (~125 Hz). Caps the motion stream so a
// high-rate IMU (DS4/DualSense report far above the Switch's rate) can't flood the UDP queue;
// the input reports themselves are never throttled.
static constexpr int64_t kMotionMinIntervalNs = 8000000;

// usbdevfs_urb carries a trailing flexible array (iso_frame_desc[]), so it cannot be embedded as
// a value inside another struct. We hold a separate heap-allocated urb per slot via unique_ptr;
// HID interrupt URBs never populate iso_frame_desc, so the default-constructed zero-sized tail
// is what the kernel expects.
struct UrbSlot {
    std::unique_ptr<usbdevfs_urb> urb;
    std::vector<uint8_t> buf;
    bool pending = false;
};

bool submitUrb(DeviceCtx& ctx, UrbSlot& s) {
    memset(s.urb.get(), 0, sizeof(*s.urb));
    s.urb->type = USBDEVFS_URB_TYPE_INTERRUPT;
    s.urb->endpoint = ctx.epIn;
    s.urb->buffer = s.buf.data();
    s.urb->buffer_length = (int)s.buf.size();
    if (ioctl(ctx.fd, USBDEVFS_SUBMITURB, s.urb.get()) < 0) {
        LOGE("dev=%d SUBMITURB(ep=0x%02X) failed: %s", ctx.syntheticDeviceId, ctx.epIn,
             strerror(errno));
        return false;
    }
    s.pending = true;
    return true;
}

// Fills the storage and the pointer table, then primes the ring. Answers how many URBs are in
// flight; zero means the endpoint never accepted one and there is nothing to poll.
int setUpUrbSlots(DeviceCtx& ctx, std::unique_ptr<UrbSlot> (&storage)[kInFlightUrbs],
                  UrbSlot* (&slots)[kInFlightUrbs]) {
    for (int i = 0; i < kInFlightUrbs; i++) {
        storage[i] = std::make_unique<UrbSlot>();
        storage[i]->urb = std::make_unique<usbdevfs_urb>();
        storage[i]->buf.resize(ctx.epInMaxPacket);
        slots[i] = storage[i].get();
    }
    int submitted = 0;
    for (int i = 0; i < kInFlightUrbs; i++) {
        if (!submitUrb(ctx, *slots[i])) break;
        submitted++;
    }
    return submitted;
}

UrbSlot* findCompletedSlot(UrbSlot* (&slots)[kInFlightUrbs], const usbdevfs_urb* reaped) {
    for (int i = 0; i < kInFlightUrbs; i++) {
        if (slots[i]->urb.get() == reaped) return slots[i];
    }
    return nullptr;
}

// A level in tenths moves minutes apart and Kotlin polls it, so a store is all the mirror costs.
void publishBattery(DeviceCtx& ctx, const gamepad::DeviceState& scratch) {
    if (!scratch.batteryValid) return;
    const uint32_t packed = ((uint32_t)scratch.batteryLevel << 8) | scratch.batteryStatus;
    ctx.entry->stats.lastBattery.store((int32_t)packed, std::memory_order_relaxed);
}

// The decoder owns the mute latch (the wire bit has to be folded in here, on this thread, with no
// JNI in the way); Kotlin gets told only on the edge, so the mirror costs nothing per report.
void publishMicMuteEdge(DeviceCtx& ctx, bool& lastMicMuted) {
    const bool changed = ctx.stickRange.micMuted != lastMicMuted;
    if (!changed) return;
    lastMicMuted = ctx.stickRange.micMuted;
    dispatch::applyPadMicMute(ctx.syntheticDeviceId, lastMicMuted);
}

// The motion sample this report is due to carry, no closer than kMotionMinIntervalNs to the last.
bool dueMotion(DeviceCtx& ctx, const gamepad::DeviceState& scratch, const int64_t nowNs,
               dish_send::MotionSample& out) {
    const bool isFirstSample = ctx.lastMotionNs == 0;
    const bool intervalElapsed = nowNs - ctx.lastMotionNs >= kMotionMinIntervalNs;
    if (!isFirstSample && !intervalElapsed) return false;

    const uint32_t deltaUs = isFirstSample ? 0 : (uint32_t)((nowNs - ctx.lastMotionNs) / 1000);
    ctx.lastMotionNs = nowNs;
    dish_usb::bump(ctx.entry->stats.motionCount);
    out = dish_send::MotionSample{{scratch.gyroX, scratch.gyroY, scratch.gyroZ},
                                  {scratch.accelX, scratch.accelY, scratch.accelZ},
                                  deltaUs};
    return true;
}

// Whether the send-on-change gate lets this report's touch frame (its lastSent) through. A wheel
// turn is an event, not a state, so it goes out whether or not the fingers moved.
bool dueTouch(DeviceCtx& ctx, const gamepad::DeviceState& scratch, const int64_t nowNs) {
    gamepad::TouchpadState cur;
    cur.f0Active = scratch.touch0Active;
    cur.f1Active = scratch.touch1Active;
    cur.clickDown = scratch.touchClick;
    cur.rightDown = scratch.touchRightDown;
    cur.f0Id = scratch.touch0Id;
    cur.f1Id = scratch.touch1Id;
    cur.f0X = scratch.touch0X;
    cur.f0Y = scratch.touch0Y;
    cur.f1X = scratch.touch1X;
    cur.f1Y = scratch.touch1Y;
    const bool stateDue = ctx.touchGate.decide(cur, nowNs) != gamepad::TouchpadSend::SKIP;
    return stateDue || scratch.touchScrollV != 0;
}

int64_t monotonicNowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// One URB, one hold of the session's gate: the report, then the motion and touch it carried.
void publishDecodedReport(DeviceCtx& ctx, const gamepad::DeviceState& scratch, bool& lastMicMuted) {
    const bool needsTimestamp = scratch.motionValid || scratch.touchValid;
    const int64_t nowNs = needsTimestamp ? monotonicNowNs() : 0;
    dish_send::MotionSample motion{};
    const bool hasMotion = scratch.motionValid && dueMotion(ctx, scratch, nowNs, motion);
    const bool hasTouch = scratch.touchValid && dueTouch(ctx, scratch, nowNs);

    dish_slots::UsbFrame frame;
    frame.report = &scratch;
    frame.motion = hasMotion ? &motion : nullptr;
    frame.touch = hasTouch ? &ctx.touchGate.lastSent() : nullptr;
    frame.touchEventTimeMs = (uint32_t)ctx.touchGate.lastEventTimeMs();
    frame.touchScrollV = hasTouch ? scratch.touchScrollV : 0;
    dispatch::applyUsbFrame(ctx.syntheticDeviceId, frame);
    publishMicMuteEdge(ctx, lastMicMuted);
    publishBattery(ctx, scratch);
}

// A store only on the edge: the JVM reads it on its own cadence.
void markLinked(DeviceCtx& ctx, const bool linked) {
    ctx.linked = linked;
    ctx.entry->stats.linked.store(linked, std::memory_order_relaxed);
}

// The arriving controller's set-up and the leaving one's are the output worker's to run, so the
// poller never waits on a control transfer.
void followSlotLink(DeviceCtx& ctx, const usbparsers::WirelessEvent wev, const bool decodedState) {
    switch (usbparsers::slotLinkStep(ctx.linked, wev, decodedState)) {
    case usbparsers::SlotLinkStep::STAYS:
        return;
    case usbparsers::SlotLinkStep::ARRIVES:
        markLinked(ctx, true);
        ctx.entry->output.postReconnected(ctx.syntheticDeviceId);
        return;
    case usbparsers::SlotLinkStep::LEAVES:
        ctx.linked = false;
        dish_usb::controllerLeft(ctx.entry->stats);
        ctx.entry->output.postLeft(ctx.syntheticDeviceId);
        return;
    }
}

void followWirelessEvent(DeviceCtx& ctx, const usbparsers::WirelessEvent wev) {
    if (ctx.receiverSlot) {
        followSlotLink(ctx, wev, false);
        return;
    }
    if (wev == usbparsers::WirelessEvent::CONNECT) {
        ctx.entry->output.postReconnected(ctx.syntheticDeviceId);
    }
}

// Both directions publish neutral: a departed pad must not keep its last input latched, and a
// returning pad rebooted, so any held stick memory is stale.
void publishWirelessEvent(DeviceCtx& ctx, const usbparsers::WirelessEvent wev,
                          gamepad::DeviceState& scratch) {
    ctx.stickRange.steamStickX = 0;
    ctx.stickRange.steamStickY = 0;
    dispatch::applyUsbReport(ctx.syntheticDeviceId, scratch);
    followWirelessEvent(ctx, wev);
}

void ackGipReport(DeviceCtx& ctx, const uint8_t* report, const size_t len) {
    if (ctx.parser != usbparsers::Parser::XBOX_ONE_GIP) return;
    uint8_t ack[usbparsers::kGipAckBytes];
    const size_t n = usbparsers::buildGipAck(report, len, ack, sizeof(ack));
    if (n == 0) return;
    ctx.entry->output.postAck(ctx.syntheticDeviceId, ack, n);
}

void publishCompletedUrb(DeviceCtx& ctx, const UrbSlot& completed, const int actualLength,
                         gamepad::DeviceState& scratch, bool& lastMicMuted) {
    hotpath::markInputRead(ctx.syntheticDeviceId); // stage-1 start: a fresh input report is in hand
    dish_usb::bump(ctx.entry->stats.urbCount);
    ackGipReport(ctx, completed.buf.data(), (size_t)actualLength);
    memset(&scratch, 0, sizeof(scratch));

    const usbparsers::WirelessEvent wev =
        usbparsers::checkWirelessEvent(ctx.parser, completed.buf.data(), (size_t)actualLength);
    if (wev != usbparsers::WirelessEvent::NONE) {
        publishWirelessEvent(ctx, wev, scratch);
        return;
    }
    // A report that carries only the charge must not publish as input: that would be a neutral pad.
    const bool isABatteryReport = usbparsers::decodePadBatteryReport(
        ctx.parser, completed.buf.data(), (size_t)actualLength, scratch);
    if (isABatteryReport) {
        publishBattery(ctx, scratch);
        return;
    }
    ctx.stickRange.trackpadPick =
        static_cast<gamepad::TrackpadPick>(ctx.trackpadPick.load(std::memory_order_relaxed));
    const bool decoded = usbparsers::decodeReport(ctx.parser, completed.buf.data(),
                                                  (size_t)actualLength, scratch, &ctx.stickRange);
    if (!decoded) return;
    if (ctx.receiverSlot) followSlotLink(ctx, usbparsers::WirelessEvent::NONE, decoded);
    publishDecodedReport(ctx, scratch, lastMicMuted);
}

void discardPendingUrbs(DeviceCtx& ctx, UrbSlot* (&slots)[kInFlightUrbs]) {
    int pendingCount = 0;
    for (int i = 0; i < kInFlightUrbs; i++) {
        if (!slots[i]->pending) continue;
        ioctl(ctx.fd, USBDEVFS_DISCARDURB, slots[i]->urb.get());
        pendingCount++;
    }
    while (pendingCount > 0) {
        usbdevfs_urb* dummy = nullptr;
        if (ioctl(ctx.fd, USBDEVFS_REAPURB, &dummy) < 0) break;
        pendingCount--;
    }
}

// Reaps everything the kernel has ready and resubmits each slot. Answers false when the loop
// should stop: the device went away, an ioctl failed, or a resubmit was refused.
bool reapReadyUrbs(DeviceCtx& ctx, UrbSlot* (&slots)[kInFlightUrbs], gamepad::DeviceState& scratch,
                   bool& lastMicMuted) {
    while (true) {
        usbdevfs_urb* reaped = nullptr;
        const int r = ioctl(ctx.fd, USBDEVFS_REAPURBNDELAY, &reaped);
        if (r < 0) {
            if (errno == EAGAIN) return true;
            LOGE("dev=%d REAPURB failed: %s", ctx.syntheticDeviceId, strerror(errno));
            dish_usb::bump(ctx.entry->stats.urbErrorCount);
            return false;
        }
        if (reaped == nullptr) return true;

        UrbSlot* completed = findCompletedSlot(slots, reaped);
        if (completed == nullptr) continue;
        completed->pending = false;

        if (reaped->status == -ENODEV) {
            LOGI("dev=%d disappeared (ENODEV), exiting poll loop", ctx.syntheticDeviceId);
            ctx.unplugged = true;
            return false;
        }
        if (reaped->status != 0) {
            dish_usb::bump(ctx.entry->stats.urbErrorCount);
            ctx.entry->stats.lastUrbStatus.store(reaped->status, std::memory_order_relaxed);
        }
        const bool carriesAReport = reaped->status == 0 && reaped->actual_length > 0;
        if (carriesAReport) {
            publishCompletedUrb(ctx, *completed, reaped->actual_length, scratch, lastMicMuted);
        }
        if (!submitUrb(ctx, *completed)) {
            dish_usb::bump(ctx.entry->stats.urbErrorCount);
            return false;
        }
    }
}

// What one wait on the URB fd came back with. Idle covers both a timeout and an EINTR: neither
// is an error and neither has a completion to reap.
enum class PollOutcome { Ready, Idle, Stop };

PollOutcome waitForUrbCompletion(DeviceCtx& ctx) {
    struct pollfd pfd = {};
    pfd.fd = ctx.fd;
    pfd.events = POLLOUT;
    const int pr = poll(&pfd, 1, kUrbPollTimeoutMs);
    if (ctx.stop.load(std::memory_order_relaxed)) return PollOutcome::Stop;
    if (pr > 0) return PollOutcome::Ready;
    if (pr == 0) return PollOutcome::Idle;
    if (errno == EINTR) return PollOutcome::Idle;
    LOGE("dev=%d poll failed: %s", ctx.syntheticDeviceId, strerror(errno));
    return PollOutcome::Stop;
}

void pollLoop(std::shared_ptr<DeviceCtx> ctx) {
    const ScopedThreadRole role(ThreadRole::UsbPoller);
    dish::elevateCurrentThreadToInputPriority();
    std::unique_ptr<UrbSlot> slotStorage[kInFlightUrbs];
    UrbSlot* slots[kInFlightUrbs];
    const int submitted = setUpUrbSlots(*ctx, slotStorage, slots);
    if (submitted == 0) {
        LOGE("dev=%d initial submit failed, exiting poll loop", ctx->syntheticDeviceId);
        return;
    }

    gamepad::DeviceState scratch{};
    // Last mute state we told Kotlin about. Starts unmuted, which is what a freshly claimed pad's
    // parser state says too, so the first edge is a real press and not a startup echo.
    bool lastMicMuted = false;
    bool running = true;

    while (running && !ctx->stop.load(std::memory_order_relaxed)) {
        const PollOutcome outcome = waitForUrbCompletion(*ctx);
        if (outcome == PollOutcome::Stop) break;
        if (outcome == PollOutcome::Idle) continue;
        running = reapReadyUrbs(*ctx, slots, scratch, lastMicMuted);
    }

    discardPendingUrbs(*ctx, slots);
    LOGI("dev=%d poll loop exited", ctx->syntheticDeviceId);
}

// Release our claim and re-bind the kernel HID driver the force-claim detached, so Android
// re-enumerates the framework InputDevice and a fall back to Standard needs no physical replug.
// Best effort; logs if the kernel refuses CONNECT.
void releaseAndReattach(int fd, int interfaceNumber) {
    if (interfaceNumber < 0) return;
    unsigned int iface = (unsigned int)interfaceNumber;
    ioctl(fd, USBDEVFS_RELEASEINTERFACE, &iface);
    usbdevfs_ioctl reattach{};
    reattach.ifno = interfaceNumber;
    reattach.ioctl_code = USBDEVFS_CONNECT;
    reattach.data = nullptr;
    if (ioctl(fd, USBDEVFS_IOCTL, &reattach) < 0) {
        LOGE("USBDEVFS_CONNECT re-attach iface %d failed: %s", interfaceNumber, strerror(errno));
    }
}

void shutdownLocked(const std::shared_ptr<DeviceCtx>& ctx) {
    ctx->stop.store(true, std::memory_order_relaxed);
    if (ctx->poller.joinable()) {
        // No lock is held here: detachDevice has already taken ctx out of the map and released
        // g_mtx.
        ctx->poller.join();
    }
    // The worker's last write finishes before the fd it is writing to is closed.
    ctx->entry->output.stop();
    // A device the kernel reported gone has nothing to restore: the write would go to a dead
    // handle.
    const bool restorable =
        !ctx->unplugged && usbparsers::controllerIsThere(ctx->receiverSlot, ctx->linked);
    if (restorable) usbparsers::runTeardown(ctx->fd, ctx->interfaceNumber, ctx->parser);
    releaseAndReattach(ctx->fd, ctx->interfaceNumber);
    if (ctx->fd >= 0) {
        ::close(ctx->fd);
        ctx->fd = -1;
    }
}

constexpr int kProbeMaxReads = 16;
constexpr unsigned kProbeReadTimeoutMs = 80;
constexpr int kProbeMaxTimeouts = 4;

usbparsers::ProbeOutcome probeEndpoint(int fd, uint8_t epIn, uint16_t epInMaxPacket,
                                       usbparsers::Parser parser) {
    std::vector<uint8_t> buf(epInMaxPacket == 0 ? 64 : epInMaxPacket);
    gamepad::DeviceState scratch{};
    usbparsers::ParserState probeSticks;
    bool sawTraffic = false;
    int consecutiveTimeouts = 0;
    for (int i = 0; i < kProbeMaxReads; i++) {
        struct usbdevfs_bulktransfer xfer = {};
        xfer.ep = epIn;
        xfer.len = (unsigned int)buf.size();
        xfer.timeout = kProbeReadTimeoutMs;
        xfer.data = buf.data();
        int n = ioctl(fd, USBDEVFS_BULK, &xfer);
        if (n <= 0) {
            if (++consecutiveTimeouts >= kProbeMaxTimeouts) break;
            continue;
        }
        consecutiveTimeouts = 0;
        sawTraffic = true;
        if (usbparsers::checkWirelessEvent(parser, buf.data(), (size_t)n) !=
            usbparsers::WirelessEvent::NONE) {
            return usbparsers::ProbeOutcome::DECODED;
        }
        memset(&scratch, 0, sizeof(scratch));
        if (usbparsers::decodeReport(parser, buf.data(), (size_t)n, scratch, &probeSticks)) {
            return usbparsers::ProbeOutcome::DECODED;
        }
    }
    return sawTraffic ? usbparsers::ProbeOutcome::UNDECODED : usbparsers::ProbeOutcome::SILENT;
}

// Best-effort: a failed transfer or unparseable descriptor leaves the layout invalid, so the
// generic decoder falls back to its fixed-offset guess.
void fetchHidLayout(int fd, int interfaceNumber, usbhid::HidLayout& out) {
    if (interfaceNumber < 0) return;
    uint8_t desc[512];
    struct usbdevfs_ctrltransfer ct = {};
    ct.bRequestType = usbparsers::USB_REQUEST_TYPE_IN_STANDARD_INTERFACE;
    ct.bRequest = usbparsers::USB_REQUEST_GET_DESCRIPTOR;
    ct.wValue = (uint16_t)(usbparsers::HID_DESCRIPTOR_TYPE_REPORT << 8); // index 0
    ct.wIndex = (uint16_t)interfaceNumber;
    ct.wLength = sizeof(desc);
    ct.timeout = usbparsers::USB_CONTROL_TIMEOUT_MS;
    ct.data = desc;
    int n = ioctl(fd, USBDEVFS_CONTROL, &ct);
    if (n <= 0) return;
    usbhid::parseReportDescriptor(desc, (size_t)n, out);
}

// One HID GET_REPORT of a feature report over the claimed interface: the bytes read, or -1.
int readFeatureReport(int fd, int interfaceNumber, uint8_t reportId, uint8_t* buf, size_t cap) {
    struct usbdevfs_ctrltransfer ct = {};
    ct.bRequestType = usbparsers::USB_REQUEST_TYPE_IN_CLASS_INTERFACE;
    ct.bRequest = usbparsers::USB_REQUEST_GET_REPORT;
    ct.wValue = (uint16_t)((usbparsers::HID_REPORT_TYPE_FEATURE << 8) | reportId);
    ct.wIndex = (uint16_t)interfaceNumber;
    ct.wLength = (uint16_t)cap;
    ct.timeout = usbparsers::USB_CONTROL_TIMEOUT_MS;
    ct.data = buf;
    return ioctl(fd, USBDEVFS_CONTROL, &ct);
}

// Reads the DS4/DualSense calibration feature report so the IMU can be scaled; best-effort, a
// failed read leaves the calibration invalid and motion stays off.
void fetchPsCalibration(int fd, int interfaceNumber, uint8_t reportId,
                        usbparsers::PsImuCalib& out) {
    if (interfaceNumber < 0) return;
    uint8_t buf[64];
    const int n = readFeatureReport(fd, interfaceNumber, reportId, buf, sizeof(buf));
    if (n < 0 || static_cast<size_t>(n) < usbparsers::PS_CALIBRATION_REPORT_BYTES) return;
    usbparsers::parsePsCalibration(buf, (size_t)n, out);
}

// Reads the DualSense firmware-info report that picks its rumble mode; best-effort, an unread
// report leaves the pad on the original mode.
void fetchDs5FirmwareInfo(int fd, int interfaceNumber, usbparsers::Ds5FirmwareInfo& out) {
    if (interfaceNumber < 0) return;
    uint8_t buf[usbparsers::DS5_FIRMWARE_INFO_REPORT_BYTES];
    const int n = readFeatureReport(fd, interfaceNumber, usbparsers::DS5_FIRMWARE_INFO_REPORT_ID,
                                    buf, sizeof(buf));
    if (n < 0) return;
    usbparsers::parseDs5FirmwareInfo(buf, (size_t)n, out);
}

} // namespace

namespace {

// The interface a claim names, and what usb_parsers made of its model.
struct AttachTarget {
    int fd = -1;
    uint16_t vid = 0;
    uint16_t pid = 0;
    int interfaceNumber = -1;
    uint8_t epIn = 0;
    uint16_t epInMaxPacket = 0;
    uint8_t epOut = 0;
    usbparsers::Classification classification;
    std::string modelName;
};

// We expect Kotlin to have already called UsbDeviceConnection.claimInterface(force=true);
// CLAIMINTERFACE here is idempotent (returns EBUSY if already held by our process, which is
// fine). Without claiming, USBDEVFS_SUBMITURB fails with EBUSY because the kernel HID driver
// holds the interface.
bool claimInterface(const int fd, const int interfaceNumber) {
    if (interfaceNumber < 0) return true;
    unsigned int iface = (unsigned int)interfaceNumber;
    if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &iface) >= 0 || errno == EBUSY) return true;
    LOGE("CLAIMINTERFACE %d failed: %s", interfaceNumber, strerror(errno));
    releaseAndReattach(fd, interfaceNumber);
    ::close(fd);
    return false;
}

// A partly-applied init still changed the device, and handing it back that way leaves it useless
// to its owner outside this app.
void giveBackPad(const AttachTarget& t) {
    usbparsers::runTeardown(t.fd, t.interfaceNumber, t.classification.parser);
    releaseAndReattach(t.fd, t.interfaceNumber);
    ::close(t.fd);
}

// A receiver slot's controller is set up only once it is there, so there is nothing to restore.
void giveBackSlot(const AttachTarget& t) {
    releaseAndReattach(t.fd, t.interfaceNumber);
    ::close(t.fd);
}

void giveBack(const AttachTarget& t, const bool receiverSlot) {
    if (receiverSlot) {
        giveBackSlot(t);
        return;
    }
    giveBackPad(t);
}

// Whether a pad came up, and when its init went out: the Steam Controller (2026)'s first refresh
// is owed from then.
struct PadReady {
    bool ok = false;
    int64_t initSentMs = usbparsers::NEVER_SENT_MS;
};

// A pad of its own: its init, then a probe that its reports decode. Either refusal gives it back.
PadReady readyPad(const AttachTarget& t) {
    const usbparsers::Parser parser = t.classification.parser;
    if (!usbparsers::runInit(t.fd, t.interfaceNumber, t.epOut, t.classification.init)) {
        LOGI("attach %04X:%04X (%s): init failed, falling back to routed", t.vid, t.pid,
             t.modelName.c_str());
        giveBackPad(t);
        return PadReady{};
    }
    const int64_t initSentMs = dish_usb::outputClockMs();
    const usbparsers::ProbeOutcome probed = probeEndpoint(t.fd, t.epIn, t.epInMaxPacket, parser);
    if (!usbparsers::probePermitsClaim(probed, usbparsers::isVerifiedFastLane(t.vid, t.pid),
                                       parser)) {
        LOGI("attach %04X:%04X (%s): %s, releasing to framework", t.vid, t.pid, t.modelName.c_str(),
             probed == usbparsers::ProbeOutcome::SILENT ? "no reports" : "reports did not decode");
        giveBackPad(t);
        return PadReady{};
    }
    if (probed == usbparsers::ProbeOutcome::SILENT) {
        LOGI("attach %04X:%04X (%s): silent at rest, claiming verified model", t.vid, t.pid,
             t.modelName.c_str());
    }
    return PadReady{true, initSentMs};
}

// The 2015 receiver answers with a wireless event; a 2026 one's slot says what it holds with its
// first report.
void askReceiverSlot(const AttachTarget& t) {
    if (usbparsers::runReceiverStateRequest(t.fd, t.interfaceNumber, t.classification.parser)) {
        return;
    }
    LOGI("attach %04X:%04X (%s): slot %d not asked, its first report says what it holds", t.vid,
         t.pid, t.modelName.c_str(), t.interfaceNumber);
}

void readModelFeatures(const AttachTarget& t, DeviceCtx& ctx) {
    const usbparsers::Parser parser = t.classification.parser;
    ctx.stickRange.productId = t.pid;
    if (parser == usbparsers::Parser::GENERIC_HID_GAMEPAD) {
        fetchHidLayout(t.fd, t.interfaceNumber, ctx.stickRange.hidLayout);
        ctx.stickRange.hidLayout.order = t.classification.order;
    } else if (parser == usbparsers::Parser::DUALSHOCK4) {
        fetchPsCalibration(t.fd, t.interfaceNumber, 0x02, ctx.stickRange.psImu);
    } else if (parser == usbparsers::Parser::DUALSENSE) {
        fetchPsCalibration(t.fd, t.interfaceNumber, 0x05, ctx.stickRange.psImu);
        usbparsers::Ds5FirmwareInfo firmware;
        fetchDs5FirmwareInfo(t.fd, t.interfaceNumber, firmware);
        ctx.output.feedback.ds5VibrationV2 = usbparsers::ds5UsesVibrationV2(t.pid, firmware);
        LOGI("attach %04X:%04X: DualSense feature version %04X (%s), rumble mode %s", t.vid, t.pid,
             firmware.featureVersion, firmware.valid ? "read" : "unread",
             ctx.output.feedback.ds5VibrationV2 ? "v2" : "v1");
    }
}

// A receiver slot starts with no controller: it is set up, and refreshed, once one is there.
void markSlotEmpty(DeviceCtx& ctx) {
    ctx.receiverSlot = true;
    markLinked(ctx, false);
    ctx.output.feedback.controllerAway = true;
}

AttachResult startDevice(const AttachTarget& t, const bool receiverSlot,
                         const int64_t settingsSentMs) {
    AttachResult out;
    const usbparsers::Parser parser = t.classification.parser;
    auto ctx = std::make_shared<DeviceCtx>();
    ctx->syntheticDeviceId = allocSyntheticId();
    ctx->entry = g_usbDevices.claim(ctx->syntheticDeviceId);
    if (ctx->entry == nullptr) {
        LOGE("attach %04X:%04X (%s): %u pads already claimed, releasing to framework", t.vid, t.pid,
             t.modelName.c_str(), (unsigned)dish_usb::MAX_USB_DEVICES);
        giveBack(t, receiverSlot);
        return out;
    }
    ctx->fd = t.fd;
    ctx->vid = t.vid;
    ctx->pid = t.pid;
    ctx->epIn = t.epIn;
    ctx->epInMaxPacket = t.epInMaxPacket == 0 ? 64 : t.epInMaxPacket;
    ctx->epOut = t.epOut;
    ctx->interfaceNumber = t.interfaceNumber;
    ctx->parser = parser;
    ctx->init = t.classification.init;
    ctx->modelName = t.modelName;
    ctx->parserName = usbparsers::parserName(parser);
    readModelFeatures(t, *ctx);
    if (receiverSlot) markSlotEmpty(*ctx);

    {
        std::lock_guard<TrackedMutex> lock(g_mtx);
        g_devices[ctx->syntheticDeviceId] = ctx;
    }

    dispatch::prewarmDevice(ctx->syntheticDeviceId);

    ctx->output.fd = t.fd;
    ctx->output.interfaceNumber = t.interfaceNumber;
    ctx->output.epOut = t.epOut;
    ctx->output.parser = parser;
    ctx->output.init = t.classification.init;
    ctx->output.feedback.settingsSentMs = settingsSentMs;
    ctx->entry->output.start(ctx->syntheticDeviceId, ctx->output);
    ctx->poller = std::thread(pollLoop, ctx);

    LOGI("attach ok: %04X:%04X (%s) dev=%d parser=%s ep=0x%02X max=%u%s", t.vid, t.pid,
         t.modelName.c_str(), ctx->syntheticDeviceId, ctx->parserName.c_str(), ctx->epIn,
         (unsigned)ctx->epInMaxPacket, receiverSlot ? " (receiver slot)" : "");

    out.syntheticDeviceId = ctx->syntheticDeviceId;
    out.ok = true;
    return out;
}

AttachResult attachPad(const AttachTarget& t) {
    const PadReady ready = readyPad(t);
    if (!ready.ok) return AttachResult{};
    return startDevice(t, false, ready.initSentMs);
}

AttachResult attachReceiverSlot(const AttachTarget& t) {
    askReceiverSlot(t);
    return startDevice(t, true, usbparsers::NEVER_SENT_MS);
}

} // namespace

AttachResult attachDevice(int fd, uint16_t vid, uint16_t pid, int interfaceNumber, uint8_t epIn,
                          uint16_t epInMaxPacket, uint8_t epOut, uint8_t ifClass,
                          uint8_t ifSubclass, uint8_t ifProtocol, bool receiverSlot) {
    const usbparsers::Classification classification =
        usbparsers::classifyDevice(vid, pid, ifClass, ifSubclass, ifProtocol);
    const bool isAKnownModel = classification.name != nullptr;
    const std::string modelName = isAKnownModel ? classification.name : "USB controller";
    const AttachTarget t{
        fd, vid, pid, interfaceNumber, epIn, epInMaxPacket, epOut, classification, modelName};
    if (!claimInterface(fd, interfaceNumber)) return AttachResult{};
    if (receiverSlot) return attachReceiverSlot(t);
    return attachPad(t);
}

void detachDevice(int32_t syntheticDeviceId) {
    std::shared_ptr<DeviceCtx> ctx;
    {
        std::lock_guard<TrackedMutex> lock(g_mtx);
        auto it = g_devices.find(syntheticDeviceId);
        if (it == g_devices.end()) return;
        ctx = it->second;
        g_devices.erase(it);
    }
    if (!ctx) return;
    shutdownLocked(ctx);
    g_usbDevices.release(syntheticDeviceId);
    dispatch::resetAndPublish(syntheticDeviceId);
    dispatch::forgetDevice(syntheticDeviceId);
    hotpath::forgetDevice(syntheticDeviceId);
    LOGI("detach dev=%d (%s) done", syntheticDeviceId, ctx->modelName.c_str());
}

uint64_t getUrbCount(int32_t deviceId) {
    return g_usbDevices.read<uint64_t>(deviceId, 0, [](const dish_usb::UsbDeviceStats& s) {
        return s.urbCount.load(std::memory_order_relaxed);
    });
}

uint64_t getUrbErrorCount(int32_t deviceId) {
    return g_usbDevices.read<uint64_t>(deviceId, 0, [](const dish_usb::UsbDeviceStats& s) {
        return s.urbErrorCount.load(std::memory_order_relaxed);
    });
}

bool isPadLinked(int32_t deviceId) {
    return g_usbDevices.read<bool>(deviceId, false, [](const dish_usb::UsbDeviceStats& s) {
        return s.linked.load(std::memory_order_relaxed);
    });
}

int32_t getPadBattery(int32_t deviceId) {
    return g_usbDevices.read<int32_t>(deviceId, -1, [](const dish_usb::UsbDeviceStats& s) {
        return s.lastBattery.load(std::memory_order_relaxed);
    });
}

static const char* initKindName(usbparsers::InitKind init) {
    switch (init) {
    case usbparsers::InitKind::XBOX_ONE_POWERON:
        return "Xbox One power-on";
    case usbparsers::InitKind::XBOX_ONE_S:
        return "Xbox One S set-mode";
    case usbparsers::InitKind::SWITCH_PRO_HANDSHAKE:
        return "Switch Pro handshake";
    case usbparsers::InitKind::STEAM_QUIET:
        return "Steam quiet mode";
    case usbparsers::InitKind::STEAM_TRITON_QUIET:
        return "Steam (2026) quiet mode";
    case usbparsers::InitKind::NONE:
    default:
        return "";
    }
}

static void appendJsonString(std::string& out, const std::string& value) {
    out += '"';
    for (char c : value) {
        if (c == '"' || c == '\\') out += '\\';
        if ((unsigned char)c < 0x20) continue;
        out += c;
    }
    out += '"';
}

std::string deviceInfoJson(int32_t deviceId) {
    std::shared_ptr<DeviceCtx> ctx;
    {
        std::lock_guard<TrackedMutex> lock(g_mtx);
        auto it = g_devices.find(deviceId);
        if (it == g_devices.end()) return "";
        ctx = it->second;
    }
    std::string out = "{\"model\":";
    appendJsonString(out, ctx->modelName);
    out += ",\"parser\":";
    appendJsonString(out, ctx->parserName);
    out += ",\"init\":";
    appendJsonString(out, initKindName(ctx->init));
    char buf[96];
    snprintf(buf, sizeof(buf), ",\"reportBytes\":%u,\"endpointOut\":%s,\"lastUrbStatus\":%d}",
             (unsigned)ctx->epInMaxPacket, ctx->epOut != 0 ? "true" : "false",
             (int)g_usbDevices.read<int32_t>(deviceId, 0, [](const dish_usb::UsbDeviceStats& s) {
                 return s.lastUrbStatus.load(std::memory_order_relaxed);
             }));
    out += buf;
    return out;
}

uint64_t getMotionCount(int32_t deviceId) {
    return g_usbDevices.read<uint64_t>(deviceId, 0, [](const dish_usb::UsbDeviceStats& s) {
        return s.motionCount.load(std::memory_order_relaxed);
    });
}

// The feedback paths only post to the pad's output worker, which performs the OUT transfer.
void sendRumble(int32_t syntheticDeviceId, uint16_t strong, uint16_t weak) {
    g_usbDevices.post(syntheticDeviceId, [&](dish_usb::OutputWorker& output) {
        output.postRumble(syntheticDeviceId, strong, weak);
    });
}

void sendTriggerRumble(int32_t syntheticDeviceId, uint16_t left, uint16_t right) {
    g_usbDevices.post(syntheticDeviceId, [&](dish_usb::OutputWorker& output) {
        output.postTriggerRumble(syntheticDeviceId, left, right);
    });
}

void sendLightbar(int32_t syntheticDeviceId, uint8_t r, uint8_t g, uint8_t b) {
    g_usbDevices.post(syntheticDeviceId, [&](dish_usb::OutputWorker& output) {
        output.postLightbar(syntheticDeviceId, r, g, b);
    });
}

void sendPlayerLeds(int32_t syntheticDeviceId, uint8_t ledMask) {
    g_usbDevices.post(syntheticDeviceId, [&](dish_usb::OutputWorker& output) {
        output.postPlayerLeds(syntheticDeviceId, ledMask);
    });
}

void sendTriggerEffects(int32_t syntheticDeviceId, const uint8_t* left, const uint8_t* right) {
    g_usbDevices.post(syntheticDeviceId, [&](dish_usb::OutputWorker& output) {
        output.postTriggerEffects(syntheticDeviceId, left, right);
    });
}

void sendMicMuteLed(int32_t syntheticDeviceId, uint8_t state) {
    g_usbDevices.post(syntheticDeviceId, [&](dish_usb::OutputWorker& output) {
        output.postMicLed(syntheticDeviceId, state);
    });
}

void setTrackpadPick(const int32_t syntheticDeviceId, const gamepad::TrackpadPick pick) {
    std::lock_guard<TrackedMutex> lock(g_mtx);
    auto it = g_devices.find(syntheticDeviceId);
    if (it == g_devices.end()) return;
    it->second->trackpadPick.store(static_cast<uint8_t>(pick), std::memory_order_relaxed);
}

} // namespace usbhost
