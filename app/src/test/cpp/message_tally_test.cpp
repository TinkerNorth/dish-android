// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

#include "message_tally.h"

#include <gtest/gtest.h>

#include <cstdint>
#include <string>

#include "wire_encoders.h"

namespace {

using dish_tally::RECEIVED_KINDS;
using dish_tally::SENT_KINDS;

} // namespace

TEST(MessageTally, EachStreamTheClientSendsCountsUnderItsOwnKind) {
    EXPECT_EQ(dish_tally::sentKindOf(dish_wire::MSG_GAMEPAD_DATA), dish_tally::SENT_INPUT);
    EXPECT_EQ(dish_tally::sentKindOf(dish_wire::MSG_MOTION), dish_tally::SENT_MOTION);
    EXPECT_EQ(dish_tally::sentKindOf(dish_wire::MSG_BATTERY), dish_tally::SENT_BATTERY);
    EXPECT_EQ(dish_tally::sentKindOf(dish_wire::MSG_TOUCHPAD), dish_tally::SENT_POINTER);
    EXPECT_EQ(dish_tally::sentKindOf(dish_wire::MSG_MIC_AUDIO), dish_tally::SENT_MIC);
}

TEST(MessageTally, AHeartbeatPingIsNoStreamAndCountsUnderNoKind) {
    EXPECT_EQ(dish_tally::sentKindOf(dish_wire::MSG_HEARTBEAT_PING), dish_tally::NO_KIND);
}

TEST(MessageTally, EachMessageTheSatelliteSendsBackCountsUnderItsOwnKind) {
    EXPECT_EQ(dish_tally::receivedKindOf(dish_wire::MSG_RUMBLE), dish_tally::RECEIVED_RUMBLE);
    EXPECT_EQ(dish_tally::receivedKindOf(dish_wire::MSG_LIGHTBAR), dish_tally::RECEIVED_LIGHTBAR);
    EXPECT_EQ(dish_tally::receivedKindOf(dish_wire::MSG_TRIGGER_EFFECTS),
              dish_tally::RECEIVED_TRIGGER_EFFECTS);
    EXPECT_EQ(dish_tally::receivedKindOf(dish_wire::MSG_PLAYER_LEDS),
              dish_tally::RECEIVED_PLAYER_LEDS);
    EXPECT_EQ(dish_tally::receivedKindOf(dish_wire::MSG_MIC_LED), dish_tally::RECEIVED_MIC_LED);
    EXPECT_EQ(dish_tally::receivedKindOf(dish_wire::MSG_SPEAKER_AUDIO),
              dish_tally::RECEIVED_SPEAKER);
    EXPECT_EQ(dish_tally::receivedKindOf(dish_wire::MSG_HAPTIC_AUDIO), dish_tally::RECEIVED_HAPTIC);
    EXPECT_EQ(dish_tally::receivedKindOf(dish_wire::MSG_SESSION_CLOSE),
              dish_tally::RECEIVED_SESSION_CLOSE);
}

TEST(MessageTally, AHeartbeatAckAndAnUnknownTypeCountUnderNoKind) {
    EXPECT_EQ(dish_tally::receivedKindOf(dish_wire::MSG_HEARTBEAT_ACK), dish_tally::NO_KIND);
    EXPECT_EQ(dish_tally::receivedKindOf(0x7FFF), dish_tally::NO_KIND);
}

TEST(MessageTally, TheCountsAreOneObjectPerDirectionKeyedByTheBundlesNames) {
    const uint32_t sent[SENT_KINDS] = {12345, 1, 3, 0, 7};
    const uint32_t received[RECEIVED_KINDS] = {12, 2, 0, 1, 0, 40, 41, 1};
    EXPECT_EQ(dish_tally::messageCountsJson(sent, received),
              std::string("{\"sent\":{\"input\":12345,\"motion\":1,\"battery\":3,\"pointer\":0,"
                          "\"mic\":7},\"received\":{\"rumble\":12,\"lightbar\":2,"
                          "\"triggerEffects\":0,\"playerLeds\":1,\"micLed\":0,\"speaker\":40,"
                          "\"haptic\":41,\"sessionClose\":1}}"));
}
