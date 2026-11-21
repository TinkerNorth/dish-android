// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>

#include "wire_encoders.h"

// A session's sent and received counts per wire message kind, as the diagnostics bundle's
// hosts[i].session names them. The send path counts under the session's gate and the receive
// thread after a datagram opens: one relaxed increment each.
namespace dish_tally {

inline constexpr int NO_KIND = -1;

inline constexpr int SENT_INPUT = 0;
inline constexpr int SENT_MOTION = 1;
inline constexpr int SENT_BATTERY = 2;
inline constexpr int SENT_POINTER = 3;
inline constexpr int SENT_MIC = 4;
inline constexpr size_t SENT_KINDS = 5;

inline constexpr int RECEIVED_RUMBLE = 0;
inline constexpr int RECEIVED_LIGHTBAR = 1;
inline constexpr int RECEIVED_TRIGGER_EFFECTS = 2;
inline constexpr int RECEIVED_PLAYER_LEDS = 3;
inline constexpr int RECEIVED_MIC_LED = 4;
inline constexpr int RECEIVED_SPEAKER = 5;
inline constexpr int RECEIVED_HAPTIC = 6;
inline constexpr int RECEIVED_SESSION_CLOSE = 7;
inline constexpr size_t RECEIVED_KINDS = 8;

inline constexpr const char* SENT_NAMES[SENT_KINDS] = {"input", "motion", "battery", "pointer",
                                                       "mic"};
inline constexpr const char* RECEIVED_NAMES[RECEIVED_KINDS] = {
    "rumble", "lightbar", "triggerEffects", "playerLeds",
    "micLed", "speaker",  "haptic",         "sessionClose"};

inline constexpr int sentKindOf(const uint16_t msgType) {
    switch (msgType) {
    case dish_wire::MSG_GAMEPAD_DATA:
        return SENT_INPUT;
    case dish_wire::MSG_MOTION:
        return SENT_MOTION;
    case dish_wire::MSG_BATTERY:
        return SENT_BATTERY;
    case dish_wire::MSG_TOUCHPAD:
        return SENT_POINTER;
    case dish_wire::MSG_MIC_AUDIO:
        return SENT_MIC;
    default:
        return NO_KIND;
    }
}

inline constexpr int receivedKindOf(const uint16_t msgType) {
    switch (msgType) {
    case dish_wire::MSG_RUMBLE:
        return RECEIVED_RUMBLE;
    case dish_wire::MSG_LIGHTBAR:
        return RECEIVED_LIGHTBAR;
    case dish_wire::MSG_TRIGGER_EFFECTS:
        return RECEIVED_TRIGGER_EFFECTS;
    case dish_wire::MSG_PLAYER_LEDS:
        return RECEIVED_PLAYER_LEDS;
    case dish_wire::MSG_MIC_LED:
        return RECEIVED_MIC_LED;
    case dish_wire::MSG_SPEAKER_AUDIO:
        return RECEIVED_SPEAKER;
    case dish_wire::MSG_HAPTIC_AUDIO:
        return RECEIVED_HAPTIC;
    case dish_wire::MSG_SESSION_CLOSE:
        return RECEIVED_SESSION_CLOSE;
    default:
        return NO_KIND;
    }
}

namespace detail {

inline constexpr size_t kCountScratch = 48;

inline void appendCounts(std::string& out, const char* const* names, const uint32_t* counts,
                         const size_t kinds) {
    out += "{";
    char entry[kCountScratch];
    for (size_t i = 0; i < kinds; i++) {
        snprintf(entry, sizeof(entry), "%s\"%s\":%u", i == 0 ? "" : ",", names[i],
                 (unsigned)counts[i]);
        out += entry;
    }
    out += "}";
}

} // namespace detail

// {"sent":{...},"received":{...}}, read off the cold path by the diagnostics bundle.
inline std::string messageCountsJson(const uint32_t (&sent)[SENT_KINDS],
                                     const uint32_t (&received)[RECEIVED_KINDS]) {
    std::string out = "{\"sent\":";
    detail::appendCounts(out, SENT_NAMES, sent, SENT_KINDS);
    out += ",\"received\":";
    detail::appendCounts(out, RECEIVED_NAMES, received, RECEIVED_KINDS);
    out += "}";
    return out;
}

} // namespace dish_tally
