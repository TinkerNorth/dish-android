// SPDX-License-Identifier: LGPL-3.0-or-later

#include "bridge_queue.h"

namespace dish_bridge {

void BridgeQueue::push(const dish_slots::BridgeReport& report) {
    const uint32_t dropped = reports_.pushDroppingOldest(report);
    if (dropped > 0) dropped_.fetch_add(dropped, std::memory_order_relaxed);
    wake_.notify();
}

void BridgeQueue::pushMicMute(const int32_t deviceId, const bool muted) {
    mutes_.pushWaiting(MicMuteEdge{deviceId, muted});
    wake_.notify();
}

bool BridgeQueue::hasWork() const {
    return stopping_.load(std::memory_order_seq_cst) || !mutes_.empty() || !reports_.empty();
}

bool BridgeQueue::waitForWork() {
    wake_.sleepUnless([this] { return hasWork(); });
    const bool drained = mutes_.empty() && reports_.empty();
    return !(stopping_.load(std::memory_order_seq_cst) && drained);
}

size_t BridgeQueue::drain(BridgeConsumer& consumer) {
    size_t handed = 0;
    for (;;) {
        MicMuteEdge edge;
        if (mutes_.tryPop(edge)) {
            consumer.micMute(edge.deviceId, edge.muted);
            handed++;
            continue;
        }
        dish_slots::BridgeReport report;
        if (!reports_.tryPop(report)) return handed;
        consumer.report(report);
        handed++;
    }
}

void BridgeQueue::stop() {
    stopping_.store(true, std::memory_order_seq_cst);
    wake_.notify();
}

} // namespace dish_bridge
