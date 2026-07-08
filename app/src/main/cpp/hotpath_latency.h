// SPDX-License-Identifier: LGPL-3.0-or-later
//
// hotpath_latency - opt-in, on-device latency instrumentation for the USB-direct
// hot path and the heartbeat round trip. Disabled by default; every mark is a
// single relaxed atomic load when off, so the streaming path pays nothing unless
// a benchmark explicitly turns it on (SatelliteNative.setHotPathBench(true)).
//
// Stages (see satellite tools/bench/README.md for the full chain):
//   stage 1  USB-direct hot path : markInputRead() at URB reap, markGamepadSent()
//            right after the gamepad packet leaves sendto() -- delta on one thread.
//   stage 2  network one-way     : shouldArmPing()/addRttSample() time the heartbeat
//            round trip on the device clock (per-session clock); one-way ~= RTT/2.
//
// statsJson() returns microsecond percentiles for the JNI/debug readout.

#pragma once

#include <stdint.h>

#include <string>

namespace hotpath {

void setEnabled(bool on);
bool enabled();

// stage 1 (called on the per-device USB poller thread, in order)
void markInputRead();   // URB reaped with a fresh input report
void markGamepadSent(); // the resulting MSG_GAMEPAD_DATA packet has left sendto()

// stage 2 (heartbeat thread / receive thread). The ping clock lives on the Session so
// concurrent sessions can never pair one session's ping with another's ack; these keep
// the policy (in-flight guard, loss reclaim, sample validity) in one place.
int64_t nowMonotonicNs();
bool shouldArmPing(int64_t outstandingNs, int64_t nowNs);
void addRttSample(int64_t sentNs, int64_t nowNs);

// Drops accumulated RTT samples so a probe-mode window starts fresh.
void resetRttWindow();

// JSON snapshot of the current window (microseconds). reset=true clears samples.
std::string statsJson(bool reset);

} // namespace hotpath
