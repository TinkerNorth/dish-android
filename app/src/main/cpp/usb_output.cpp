// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_output.h"

#include <algorithm>

namespace dish_usb {

void OutputWorker::start(const int32_t deviceId, OutputDevice& device) {
    stop();
    deviceId_ = deviceId;
    device_ = &device;
    pending_.store(0, std::memory_order_relaxed);
    AckOut stale;
    while (acks_.tryPop(stale)) {}
    running_.store(true, std::memory_order_seq_cst);
    thread_ = std::thread([this] { run(); });
}

void OutputWorker::stop() {
    if (!thread_.joinable()) return;
    running_.store(false, std::memory_order_seq_cst);
    wake_.notify();
    thread_.join();
}

void OutputWorker::announce(const uint32_t kind) {
    pending_.fetch_or(kind, std::memory_order_seq_cst);
    wake_.notify();
}

void OutputWorker::postRumble(const int32_t deviceId, const uint16_t strong, const uint16_t weak) {
    rumble_.post(RumbleOut{deviceId, strong, weak});
    announce(RUMBLE);
}

void OutputWorker::postTriggerRumble(const int32_t deviceId, const uint16_t left,
                                     const uint16_t right) {
    triggerRumble_.post(TriggerRumbleOut{deviceId, left, right});
    announce(TRIGGER_RUMBLE);
}

void OutputWorker::postLightbar(const int32_t deviceId, const uint8_t r, const uint8_t g,
                                const uint8_t b) {
    lightbar_.post(LightbarOut{deviceId, r, g, b});
    announce(LIGHTBAR);
}

void OutputWorker::postPlayerLeds(const int32_t deviceId, const uint8_t ledMask) {
    playerLeds_.post(PlayerLedsOut{deviceId, ledMask});
    announce(PLAYER_LEDS);
}

void OutputWorker::postTriggerEffects(const int32_t deviceId, const uint8_t* left,
                                      const uint8_t* right) {
    TriggerEffectsOut effects;
    effects.deviceId = deviceId;
    memcpy(effects.left, left, TRIGGER_EFFECT_BYTES);
    memcpy(effects.right, right, TRIGGER_EFFECT_BYTES);
    triggerEffects_.post(effects);
    announce(TRIGGER_EFFECTS);
}

void OutputWorker::postMicLed(const int32_t deviceId, const uint8_t state) {
    micLed_.post(MicLedOut{deviceId, state});
    announce(MIC_LED);
}

void OutputWorker::postReconnected(const int32_t deviceId) {
    reconnected_.post(ReconnectedOut{deviceId});
    announce(RECONNECTED);
}

void OutputWorker::postAck(const int32_t deviceId, const uint8_t* bytes, const size_t len) {
    AckOut ack;
    ack.deviceId = deviceId;
    ack.len = static_cast<uint8_t>(std::min(len, sizeof(ack.bytes)));
    memcpy(ack.bytes, bytes, ack.len);
    const uint32_t dropped = acks_.pushDroppingOldest(ack);
    if (dropped > 0) droppedAcks_.fetch_add(dropped, std::memory_order_relaxed);
    wake_.notify();
}

void OutputWorker::writeAcks() {
    AckOut ack;
    while (acks_.tryPop(ack)) {
        if (ack.deviceId == deviceId_) device_->ack(ack.bytes, ack.len);
    }
}

// Each kind's newest value, written only for the pad this worker serves. A reconnect goes first:
// the pad rebooted, and whatever else is pending is meant for it once it is set up again.
void OutputWorker::writePending(const uint32_t kinds) {
    if ((kinds & RECONNECTED) != 0) {
        const ReconnectedOut v = reconnected_.read();
        if (v.deviceId == deviceId_) device_->reconnected();
    }
    if ((kinds & RUMBLE) != 0) {
        const RumbleOut v = rumble_.read();
        if (v.deviceId == deviceId_) device_->rumble(v.strong, v.weak);
    }
    if ((kinds & TRIGGER_RUMBLE) != 0) {
        const TriggerRumbleOut v = triggerRumble_.read();
        if (v.deviceId == deviceId_) device_->triggerRumble(v.left, v.right);
    }
    if ((kinds & LIGHTBAR) != 0) {
        const LightbarOut v = lightbar_.read();
        if (v.deviceId == deviceId_) device_->lightbar(v.r, v.g, v.b);
    }
    if ((kinds & PLAYER_LEDS) != 0) {
        const PlayerLedsOut v = playerLeds_.read();
        if (v.deviceId == deviceId_) device_->playerLeds(v.ledMask);
    }
    if ((kinds & TRIGGER_EFFECTS) != 0) {
        const TriggerEffectsOut v = triggerEffects_.read();
        if (v.deviceId == deviceId_) device_->triggerEffects(v.left, v.right);
    }
    if ((kinds & MIC_LED) != 0) {
        const MicLedOut v = micLed_.read();
        if (v.deviceId == deviceId_) device_->micLed(v.state);
    }
}

bool OutputWorker::hasWork() {
    return !running_.load(std::memory_order_seq_cst) ||
           pending_.load(std::memory_order_seq_cst) != 0 || !acks_.empty();
}

// Untimed while the device owes nothing on a timer, so a pad that needs no refresh only ever
// wakes for what is posted.
void OutputWorker::sleepUntilWorkOrRefresh(const uint32_t refreshInMs) {
    const auto workArrived = [this] { return hasWork(); };
    if (refreshInMs == usbparsers::NO_REFRESH) return wake_.sleepUnless(workArrived);
    wake_.sleepUnlessFor(workArrived, refreshInMs);
}

// Acks first: each one answers a packet the pad is waiting on. The refresh comes last, after
// whatever was posted, so a posted write is never held for it.
void OutputWorker::run() {
    uint32_t refreshInMs = device_->refresh(outputClockMs());
    while (running_.load(std::memory_order_seq_cst)) {
        sleepUntilWorkOrRefresh(refreshInMs);
        writeAcks();
        const uint32_t kinds = pending_.exchange(0, std::memory_order_acq_rel);
        if (kinds != 0) writePending(kinds);
        refreshInMs = device_->refresh(outputClockMs());
    }
}

} // namespace dish_usb
