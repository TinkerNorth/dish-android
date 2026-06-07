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
    std::string modelName;
    std::string parserName;
    usbparsers::ParserState stickRange;

    int64_t lastMotionNs = 0;

    std::atomic<uint64_t> urbCount{0};

    // Guards rumble writes to epOut against the detach that closes fd; outSeq is the output report
    // counter for protocols that carry one (Xbox One serial, Switch Pro packet number).
    std::mutex outMtx;
    uint8_t outSeq = 0;

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
                ctx->urbCount.fetch_add(1, std::memory_order_relaxed);
                memset(&scratch, 0, sizeof(scratch));
                if (usbparsers::decodeReport(ctx->parser, completed->buf.data(),
                                             (size_t)reaped->actual_length, scratch,
                                             &ctx->stickRange)) {
                    dispatch::applyUsbReport(ctx->syntheticDeviceId, scratch);

                    if (scratch.motionValid) {
                        struct timespec ts;
                        clock_gettime(CLOCK_MONOTONIC, &ts);
                        int64_t nowNs = (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
                        if (ctx->lastMotionNs == 0 ||
                            nowNs - ctx->lastMotionNs >= kMotionMinIntervalNs) {
                            uint32_t deltaUs = ctx->lastMotionNs == 0
                                                   ? 0
                                                   : (uint32_t)((nowNs - ctx->lastMotionNs) / 1000);
                            ctx->lastMotionNs = nowNs;
                            dispatch::applyUsbMotion(ctx->syntheticDeviceId, scratch.gyroX,
                                                     scratch.gyroY, scratch.gyroZ, scratch.accelX,
                                                     scratch.accelY, scratch.accelZ, deltaUs);
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
    releaseAndReattach(ctx->fd, ctx->interfaceNumber);
    if (ctx->fd >= 0) {
        ::close(ctx->fd);
        ctx->fd = -1;
    }
}

constexpr int kProbeMaxReads = 16;
constexpr unsigned kProbeReadTimeoutMs = 80;
constexpr int kProbeMaxTimeouts = 4;

bool probeDecodable(int fd, uint8_t epIn, uint16_t epInMaxPacket, usbparsers::Parser parser) {
    std::vector<uint8_t> buf(epInMaxPacket == 0 ? 64 : epInMaxPacket);
    gamepad::DeviceState scratch{};
    usbparsers::ParserState probeSticks;
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
        memset(&scratch, 0, sizeof(scratch));
        if (usbparsers::decodeReport(parser, buf.data(), (size_t)n, scratch, &probeSticks)) {
            return true;
        }
    }
    return false;
}

} // namespace

AttachResult attachDevice(int fd, uint16_t vid, uint16_t pid, int interfaceNumber, uint8_t epIn,
                          uint16_t epInMaxPacket, uint8_t epOut) {
    AttachResult out;

    const usbparsers::KnownDevice* known = usbparsers::lookupKnown(vid, pid);
    std::string modelName;
    usbparsers::Parser parser = usbparsers::Parser::NONE;
    usbparsers::InitKind init = usbparsers::InitKind::NONE;
    if (known) {
        modelName = known->name;
        parser = known->parser;
        init = known->init;
    } else {
        modelName = "USB controller";
        parser = usbparsers::Parser::GENERIC_HID_GAMEPAD;
    }

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

    if (!usbparsers::runInit(fd, epOut, parser, init)) {
        LOGI("attach %04X:%04X (%s): init failed, falling back to routed", vid, pid,
             modelName.c_str());
        releaseAndReattach(fd, interfaceNumber);
        ::close(fd);
        return out;
    }

    if (!probeDecodable(fd, epIn, epInMaxPacket, parser)) {
        LOGI("attach %04X:%04X (%s): no parseable reports, releasing to framework", vid, pid,
             modelName.c_str());
        releaseAndReattach(fd, interfaceNumber);
        ::close(fd);
        return out;
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
    ctx->modelName = modelName;
    ctx->parserName = usbparsers::parserName(parser);

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

void sendRumble(int32_t syntheticDeviceId, uint16_t strong, uint16_t weak) {
    std::shared_ptr<DeviceCtx> ctx;
    {
        std::lock_guard<std::mutex> lock(g_mtx);
        auto it = g_devices.find(syntheticDeviceId);
        if (it == g_devices.end()) return;
        ctx = it->second;
    }
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->outMtx);
    if (ctx->fd < 0) return;
    uint8_t seq = ctx->outSeq++;
    usbparsers::runRumble(ctx->fd, ctx->epOut, ctx->parser, strong, weak, seq);
}

} // namespace usbhost
