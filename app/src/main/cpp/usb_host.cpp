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
#include "usb_parsers.h"

#define TAG "SatelliteUsbHost"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace usbhost {

namespace {

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

    int64_t lastMotionNs = 0;
    gamepad::TouchpadGate touchGate;

    std::atomic<uint64_t> urbCount{0};
    std::atomic<uint64_t> motionCount{0};

    // Guards rumble writes to epOut against the detach that closes fd; outSeq is the output report
    // counter for protocols that carry one (Xbox One serial, Switch Pro packet number).
    std::mutex outMtx;
    uint8_t outSeq = 0;
    usbparsers::FeedbackState feedback; // guarded by outMtx

    std::atomic<bool> stop{false};
    std::thread poller;
};

std::mutex g_mtx;
std::unordered_map<int32_t, std::shared_ptr<DeviceCtx>> g_devices;
std::atomic<int32_t> g_nextSyntheticId{-1000};

int32_t allocSyntheticId() { return g_nextSyntheticId.fetch_sub(1, std::memory_order_relaxed); }

// Number of URBs kept in flight per device. Two is enough to eliminate the gap between
// REAP and the next SUBMIT for any HID gamepad we target (highest declared bInterval is 1 ms);
// a third URB never gets filled in steady state because by the time it's queued the kernel has
// already redelivered the one we just resubmitted.
static constexpr int kInFlightUrbs = 2;

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

void pollLoop(std::shared_ptr<DeviceCtx> ctx) {
    dish::elevateCurrentThreadToInputPriority();
    std::unique_ptr<UrbSlot> slotStorage[kInFlightUrbs];
    UrbSlot* slots[kInFlightUrbs];
    for (int i = 0; i < kInFlightUrbs; i++) {
        slotStorage[i] = std::make_unique<UrbSlot>();
        slotStorage[i]->urb = std::make_unique<usbdevfs_urb>();
        slotStorage[i]->buf.resize(ctx->epInMaxPacket);
        slots[i] = slotStorage[i].get();
    }

    auto submitSlot = [&](UrbSlot& s) -> bool {
        memset(s.urb.get(), 0, sizeof(*s.urb));
        s.urb->type = USBDEVFS_URB_TYPE_INTERRUPT;
        s.urb->endpoint = ctx->epIn;
        s.urb->buffer = s.buf.data();
        s.urb->buffer_length = (int)s.buf.size();
        if (ioctl(ctx->fd, USBDEVFS_SUBMITURB, s.urb.get()) < 0) {
            LOGE("dev=%d SUBMITURB(ep=0x%02X) failed: %s", ctx->syntheticDeviceId, ctx->epIn,
                 strerror(errno));
            return false;
        }
        s.pending = true;
        return true;
    };

    int initial = 0;
    for (int i = 0; i < kInFlightUrbs; i++) {
        if (!submitSlot(*slots[i])) break;
        initial++;
    }
    if (initial == 0) {
        LOGE("dev=%d initial submit failed, exiting poll loop", ctx->syntheticDeviceId);
        return;
    }

    gamepad::DeviceState scratch{};
    // Last mute state we told Kotlin about. Starts unmuted, which is what a freshly claimed pad's
    // parser state says too, so the first edge is a real press and not a startup echo.
    bool lastMicMuted = false;
    bool running = true;

    while (running && !ctx->stop.load(std::memory_order_relaxed)) {
        struct pollfd pfd = {};
        pfd.fd = ctx->fd;
        pfd.events = POLLOUT;
        int pr = poll(&pfd, 1, 100);
        if (ctx->stop.load(std::memory_order_relaxed)) break;
        if (pr < 0) {
            if (errno == EINTR) continue;
            LOGE("dev=%d poll failed: %s", ctx->syntheticDeviceId, strerror(errno));
            break;
        }
        if (pr == 0) continue;

        while (running) {
            usbdevfs_urb* reaped = nullptr;
            int r = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reaped);
            if (r < 0) {
                if (errno == EAGAIN) break;
                LOGE("dev=%d REAPURB failed: %s", ctx->syntheticDeviceId, strerror(errno));
                running = false;
                break;
            }
            if (reaped == nullptr) break;

            UrbSlot* completed = nullptr;
            for (int i = 0; i < kInFlightUrbs; i++) {
                if (slots[i]->urb.get() == reaped) {
                    completed = slots[i];
                    break;
                }
            }
            if (completed == nullptr) continue;
            completed->pending = false;

            if (reaped->status == -ENODEV) {
                LOGI("dev=%d disappeared (ENODEV), exiting poll loop", ctx->syntheticDeviceId);
                running = false;
                break;
            }

            if (reaped->status == 0 && reaped->actual_length > 0) {
                hotpath::markInputRead(); // stage-1 start: a fresh input report is in hand
                ctx->urbCount.fetch_add(1, std::memory_order_relaxed);
                memset(&scratch, 0, sizeof(scratch));
                usbparsers::WirelessEvent wev = usbparsers::checkWirelessEvent(
                    ctx->parser, completed->buf.data(), (size_t)reaped->actual_length);
                if (wev != usbparsers::WirelessEvent::NONE) {
                    // Both directions publish neutral: a departed pad must not keep its last input
                    // latched, and a returning pad rebooted, so any held stick memory is stale.
                    ctx->stickRange.steamStickX = 0;
                    ctx->stickRange.steamStickY = 0;
                    dispatch::applyUsbReport(ctx->syntheticDeviceId, scratch);
                    if (wev == usbparsers::WirelessEvent::CONNECT) {
                        // The reboot wiped the quiet-mode settings, so re-run the attach init or
                        // the pad streams without motion while its lizard keyboard leaks through.
                        usbparsers::runInit(ctx->fd, ctx->interfaceNumber, ctx->epOut, ctx->parser,
                                            ctx->init);
                    }
                } else if (usbparsers::decodeReport(ctx->parser, completed->buf.data(),
                                                    (size_t)reaped->actual_length, scratch,
                                                    &ctx->stickRange)) {
                    dispatch::applyUsbReport(ctx->syntheticDeviceId, scratch);

                    // The decoder owns the mute latch (the wire bit has to be folded in here, on
                    // this thread, with no JNI in the way); Kotlin gets told only on the edge, so
                    // the mirror costs nothing per report.
                    if (ctx->stickRange.micMuted != lastMicMuted) {
                        lastMicMuted = ctx->stickRange.micMuted;
                        dispatch::applyPadMicMute(ctx->syntheticDeviceId, lastMicMuted);
                    }

                    int64_t nowNs = 0;
                    if (scratch.motionValid || scratch.touchValid) {
                        struct timespec ts;
                        clock_gettime(CLOCK_MONOTONIC, &ts);
                        nowNs = (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
                    }

                    if (scratch.motionValid) {
                        if (ctx->lastMotionNs == 0 ||
                            nowNs - ctx->lastMotionNs >= kMotionMinIntervalNs) {
                            uint32_t deltaUs = ctx->lastMotionNs == 0
                                                   ? 0
                                                   : (uint32_t)((nowNs - ctx->lastMotionNs) / 1000);
                            ctx->lastMotionNs = nowNs;
                            ctx->motionCount.fetch_add(1, std::memory_order_relaxed);
                            dispatch::applyUsbMotion(ctx->syntheticDeviceId, scratch.gyroX,
                                                     scratch.gyroY, scratch.gyroZ, scratch.accelX,
                                                     scratch.accelY, scratch.accelZ, deltaUs);
                        }
                    }

                    if (scratch.touchValid) {
                        gamepad::TouchpadState cur;
                        cur.f0Active = scratch.touch0Active;
                        cur.f1Active = scratch.touch1Active;
                        cur.clickDown = scratch.touchClick;
                        cur.f0Id = scratch.touch0Id;
                        cur.f1Id = scratch.touch1Id;
                        cur.f0X = scratch.touch0X;
                        cur.f0Y = scratch.touch0Y;
                        cur.f1X = scratch.touch1X;
                        cur.f1Y = scratch.touch1Y;
                        if (ctx->touchGate.decide(cur, nowNs) != gamepad::TouchpadSend::SKIP) {
                            dispatch::applyUsbTouchpad(ctx->syntheticDeviceId,
                                                       ctx->touchGate.lastSent(),
                                                       (uint32_t)ctx->touchGate.lastEventTimeMs());
                        }
                    }
                }
            }

            if (!submitSlot(*completed)) {
                running = false;
                break;
            }
        }
    }

    int pendingCount = 0;
    for (int i = 0; i < kInFlightUrbs; i++) {
        if (slots[i]->pending) {
            ioctl(ctx->fd, USBDEVFS_DISCARDURB, slots[i]->urb.get());
            pendingCount++;
        }
    }
    while (pendingCount > 0) {
        usbdevfs_urb* dummy = nullptr;
        if (ioctl(ctx->fd, USBDEVFS_REAPURB, &dummy) < 0) break;
        pendingCount--;
    }
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
        // Joining outside the map lock would race with attach; the poll thread only ever
        // touches ctx + dispatch, never g_mtx, so holding it here is safe.
        ctx->poller.join();
    }
    // outMtx so an in-flight sendRumble finishes before the fd it is writing to is closed.
    std::lock_guard<std::mutex> lock(ctx->outMtx);
    usbparsers::runTeardown(ctx->fd, ctx->interfaceNumber, ctx->parser);
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
    ct.bRequestType = 0x81;            // IN | Standard | Interface
    ct.bRequest = 0x06;                // GET_DESCRIPTOR
    ct.wValue = (uint16_t)(0x22 << 8); // HID report descriptor type, index 0
    ct.wIndex = (uint16_t)interfaceNumber;
    ct.wLength = sizeof(desc);
    ct.timeout = 250;
    ct.data = desc;
    int n = ioctl(fd, USBDEVFS_CONTROL, &ct);
    if (n <= 0) return;
    usbhid::parseReportDescriptor(desc, (size_t)n, out);
}

// Reads the DS4/DualSense calibration feature report so the IMU can be scaled; best-effort, a
// failed read leaves the calibration invalid and motion stays off.
void fetchPsCalibration(int fd, int interfaceNumber, uint8_t reportId,
                        usbparsers::PsImuCalib& out) {
    if (interfaceNumber < 0) return;
    uint8_t buf[64];
    struct usbdevfs_ctrltransfer ct = {};
    ct.bRequestType = 0xA1;                         // IN | Class | Interface
    ct.bRequest = 0x01;                             // GET_REPORT
    ct.wValue = (uint16_t)((0x03 << 8) | reportId); // Feature report
    ct.wIndex = (uint16_t)interfaceNumber;
    ct.wLength = sizeof(buf);
    ct.timeout = 250;
    ct.data = buf;
    int n = ioctl(fd, USBDEVFS_CONTROL, &ct);
    if (n < 35) return;
    usbparsers::parsePsCalibration(buf, (size_t)n, out);
}

} // namespace

AttachResult attachDevice(int fd, uint16_t vid, uint16_t pid, int interfaceNumber, uint8_t epIn,
                          uint16_t epInMaxPacket, uint8_t epOut, uint8_t ifClass,
                          uint8_t ifSubclass, uint8_t ifProtocol) {
    AttachResult out;

    usbparsers::Classification classification =
        usbparsers::classifyDevice(vid, pid, ifClass, ifSubclass, ifProtocol);
    std::string modelName = classification.name != nullptr ? classification.name : "USB controller";
    usbparsers::Parser parser = classification.parser;
    usbparsers::InitKind init = classification.init;

    // We expect Kotlin to have already called UsbDeviceConnection.claimInterface(force=true);
    // CLAIMINTERFACE here is idempotent (returns EBUSY if already held by our process, which is
    // fine). Without claiming, USBDEVFS_SUBMITURB fails with EBUSY because the kernel HID driver
    // holds the interface.
    if (interfaceNumber >= 0) {
        unsigned int iface = (unsigned int)interfaceNumber;
        if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &iface) < 0 && errno != EBUSY) {
            LOGE("CLAIMINTERFACE %d failed: %s", interfaceNumber, strerror(errno));
            releaseAndReattach(fd, interfaceNumber);
            ::close(fd);
            return out;
        }
    }

    // Both bail-outs below run teardown first: a partly-applied init still changed the device, and
    // handing it back that way leaves it useless to its owner outside this app.
    if (!usbparsers::runInit(fd, interfaceNumber, epOut, parser, init)) {
        LOGI("attach %04X:%04X (%s): init failed, falling back to routed", vid, pid,
             modelName.c_str());
        usbparsers::runTeardown(fd, interfaceNumber, parser);
        releaseAndReattach(fd, interfaceNumber);
        ::close(fd);
        return out;
    }

    usbparsers::ProbeOutcome probed = probeEndpoint(fd, epIn, epInMaxPacket, parser);
    if (!usbparsers::probePermitsClaim(probed, usbparsers::isVerifiedFastLane(vid, pid))) {
        LOGI("attach %04X:%04X (%s): %s, releasing to framework", vid, pid, modelName.c_str(),
             probed == usbparsers::ProbeOutcome::SILENT ? "no reports" : "reports did not decode");
        usbparsers::runTeardown(fd, interfaceNumber, parser);
        releaseAndReattach(fd, interfaceNumber);
        ::close(fd);
        return out;
    }
    if (probed == usbparsers::ProbeOutcome::SILENT) {
        LOGI("attach %04X:%04X (%s): silent at rest, claiming verified model", vid, pid,
             modelName.c_str());
    }

    auto ctx = std::make_shared<DeviceCtx>();
    ctx->syntheticDeviceId = allocSyntheticId();
    ctx->fd = fd;
    ctx->vid = vid;
    ctx->pid = pid;
    ctx->epIn = epIn;
    ctx->epInMaxPacket = epInMaxPacket == 0 ? 64 : epInMaxPacket;
    ctx->epOut = epOut;
    ctx->interfaceNumber = interfaceNumber;
    ctx->parser = parser;
    ctx->init = init;
    ctx->modelName = modelName;
    ctx->parserName = usbparsers::parserName(parser);
    if (parser == usbparsers::Parser::GENERIC_HID_GAMEPAD) {
        fetchHidLayout(fd, interfaceNumber, ctx->stickRange.hidLayout);
        ctx->stickRange.hidLayout.switchOrderButtons =
            classification.order == usbparsers::ButtonOrder::SWITCH;
    } else if (parser == usbparsers::Parser::DUALSHOCK4) {
        fetchPsCalibration(fd, interfaceNumber, 0x02, ctx->stickRange.psImu);
    } else if (parser == usbparsers::Parser::DUALSENSE) {
        fetchPsCalibration(fd, interfaceNumber, 0x05, ctx->stickRange.psImu);
    }

    {
        std::lock_guard<std::mutex> lock(g_mtx);
        g_devices[ctx->syntheticDeviceId] = ctx;
    }

    dispatch::prewarmDevice(ctx->syntheticDeviceId);

    ctx->poller = std::thread(pollLoop, ctx);

    LOGI("attach ok: %04X:%04X (%s) dev=%d parser=%s ep=0x%02X max=%u", vid, pid, modelName.c_str(),
         ctx->syntheticDeviceId, ctx->parserName.c_str(), ctx->epIn, (unsigned)ctx->epInMaxPacket);

    out.syntheticDeviceId = ctx->syntheticDeviceId;
    out.ok = true;
    return out;
}

void detachDevice(int32_t syntheticDeviceId) {
    std::shared_ptr<DeviceCtx> ctx;
    {
        std::lock_guard<std::mutex> lock(g_mtx);
        auto it = g_devices.find(syntheticDeviceId);
        if (it == g_devices.end()) return;
        ctx = it->second;
        g_devices.erase(it);
    }
    if (!ctx) return;
    shutdownLocked(ctx);
    dispatch::resetAndPublish(syntheticDeviceId);
    dispatch::forgetDevice(syntheticDeviceId);
    LOGI("detach dev=%d (%s) done", syntheticDeviceId, ctx->modelName.c_str());
}

uint64_t getUrbCount(int32_t deviceId) {
    std::lock_guard<std::mutex> lock(g_mtx);
    auto it = g_devices.find(deviceId);
    if (it == g_devices.end()) return 0;
    return it->second->urbCount.load(std::memory_order_relaxed);
}

uint64_t getMotionCount(int32_t deviceId) {
    std::lock_guard<std::mutex> lock(g_mtx);
    auto it = g_devices.find(deviceId);
    if (it == g_devices.end()) return 0;
    return it->second->motionCount.load(std::memory_order_relaxed);
}

static std::shared_ptr<DeviceCtx> ctxFor(int32_t syntheticDeviceId) {
    std::lock_guard<std::mutex> lock(g_mtx);
    auto it = g_devices.find(syntheticDeviceId);
    return it == g_devices.end() ? nullptr : it->second;
}

void sendRumble(int32_t syntheticDeviceId, uint16_t strong, uint16_t weak) {
    auto ctx = ctxFor(syntheticDeviceId);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->outMtx);
    if (ctx->fd < 0) return;
    ctx->feedback.strong = strong;
    ctx->feedback.weak = weak;
    uint8_t seq = ctx->outSeq++;
    usbparsers::runMergedRumble(ctx->fd, ctx->epOut, ctx->parser, ctx->feedback, seq);
}

void sendTriggerRumble(int32_t syntheticDeviceId, uint16_t left, uint16_t right) {
    auto ctx = ctxFor(syntheticDeviceId);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->outMtx);
    if (ctx->fd < 0) return;
    if (!usbparsers::parserHasTriggerRumble(ctx->parser)) return;
    ctx->feedback.leftTrigger = left;
    ctx->feedback.rightTrigger = right;
    uint8_t seq = ctx->outSeq++;
    usbparsers::runMergedRumble(ctx->fd, ctx->epOut, ctx->parser, ctx->feedback, seq);
}

void sendLightbar(int32_t syntheticDeviceId, uint8_t r, uint8_t g, uint8_t b) {
    auto ctx = ctxFor(syntheticDeviceId);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->outMtx);
    if (ctx->fd < 0) return;
    usbparsers::runLightbar(ctx->fd, ctx->epOut, ctx->parser, ctx->feedback, r, g, b);
}

void sendPlayerLeds(int32_t syntheticDeviceId, uint8_t ledMask) {
    auto ctx = ctxFor(syntheticDeviceId);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->outMtx);
    if (ctx->fd < 0) return;
    uint8_t seq = ctx->outSeq++;
    usbparsers::runPlayerLeds(ctx->fd, ctx->epOut, ctx->parser, ledMask, seq);
}

void sendTriggerEffects(int32_t syntheticDeviceId, const uint8_t* left, const uint8_t* right) {
    auto ctx = ctxFor(syntheticDeviceId);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->outMtx);
    if (ctx->fd < 0) return;
    usbparsers::runTriggerEffects(ctx->fd, ctx->epOut, ctx->parser, left, right);
}

} // namespace usbhost
