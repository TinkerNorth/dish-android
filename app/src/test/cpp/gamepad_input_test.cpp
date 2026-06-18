// SPDX-License-Identifier: LGPL-3.0-or-later

#include "gamepad_input.h"

#include <gtest/gtest.h>

using namespace gamepad;

TEST(ScaleAxis, CenterReturnsZero) { EXPECT_EQ(0, scaleAxis(0.f, 32767.f)); }

TEST(ScaleAxis, FullPositiveReturnsMax) { EXPECT_EQ(32767, scaleAxis(1.f, 32767.f)); }

TEST(ScaleAxis, FullNegativeReturnsNegativeMax) { EXPECT_EQ(-32767, scaleAxis(-1.f, 32767.f)); }

TEST(ScaleAxis, YInversionFlipsSign) {
    // Android AXIS_Y = -1 means "up"; wire wants +Short_MAX → encode via negative max.
    EXPECT_EQ(32767, scaleAxis(-1.f, -32767.f));
    EXPECT_EQ(-32767, scaleAxis(1.f, -32767.f));
}

TEST(ScaleAxis, ClampsAbovePositiveMax) { EXPECT_EQ(32767, scaleAxis(2.f, 32767.f)); }

TEST(ScaleAxis, ClampsBelowNegativeMax) {
    // -32768 is the int16 floor.
    EXPECT_EQ(-32768, scaleAxis(-2.f, 32767.f));
}

TEST(ScaleTrigger, ZeroReturnsZero) { EXPECT_EQ(0, scaleTrigger(0.f, 255.f)); }

TEST(ScaleTrigger, FullReturnsMax) { EXPECT_EQ(255, scaleTrigger(1.f, 255.f)); }

TEST(ScaleTrigger, NegativeClampsToZero) {
    // Negative input must not wrap to 255 via signed→unsigned cast.
    EXPECT_EQ(0, scaleTrigger(-0.5f, 255.f));
}

TEST(ScaleTrigger, OverOneClampsToMax) { EXPECT_EQ(255, scaleTrigger(1.5f, 255.f)); }

TEST(Deadzone, InsideZoneReturnsZero) {
    // Gate is strict-greater-than, so 0.1 with flat=0.1 is inside.
    EXPECT_EQ(0.f, deadzone(0.05f, 0.1f));
    EXPECT_EQ(0.f, deadzone(-0.1f, 0.1f));
}

TEST(Deadzone, OutsideZonePassesThrough) {
    EXPECT_FLOAT_EQ(0.5f, deadzone(0.5f, 0.1f));
    EXPECT_FLOAT_EQ(-0.5f, deadzone(-0.5f, 0.1f));
}

TEST(Deadzone, ZeroFlatPassesEverything) { EXPECT_FLOAT_EQ(0.001f, deadzone(0.001f, 0.f)); }

TEST(KeycodeToXusb, FaceButtonsMapToXusbBits) {
    EXPECT_EQ(XUSB_A, keycodeToXusb(KC_BUTTON_A));
    EXPECT_EQ(XUSB_B, keycodeToXusb(KC_BUTTON_B));
    EXPECT_EQ(XUSB_X, keycodeToXusb(KC_BUTTON_X));
    EXPECT_EQ(XUSB_Y, keycodeToXusb(KC_BUTTON_Y));
}

TEST(KeycodeToXusb, ShouldersAndThumbsMap) {
    EXPECT_EQ(XUSB_LB, keycodeToXusb(KC_BUTTON_L1));
    EXPECT_EQ(XUSB_RB, keycodeToXusb(KC_BUTTON_R1));
    EXPECT_EQ(XUSB_THUMB_L, keycodeToXusb(KC_BUTTON_THUMBL));
    EXPECT_EQ(XUSB_THUMB_R, keycodeToXusb(KC_BUTTON_THUMBR));
}

TEST(KeycodeToXusb, StartSelectMap) {
    EXPECT_EQ(XUSB_START, keycodeToXusb(KC_BUTTON_START));
    EXPECT_EQ(XUSB_BACK, keycodeToXusb(KC_BUTTON_SELECT));
}

TEST(KeycodeToXusb, DpadKeycodesMap) {
    EXPECT_EQ(XUSB_DPAD_UP, keycodeToXusb(KC_DPAD_UP));
    EXPECT_EQ(XUSB_DPAD_DOWN, keycodeToXusb(KC_DPAD_DOWN));
    EXPECT_EQ(XUSB_DPAD_LEFT, keycodeToXusb(KC_DPAD_LEFT));
    EXPECT_EQ(XUSB_DPAD_RIGHT, keycodeToXusb(KC_DPAD_RIGHT));
}

TEST(KeycodeToXusb, L2R2NotInButtonMap) {
    // L2/R2 take the trigger-via-key path in applyKey; mapping must return 0 here.
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_L2));
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_R2));
}

TEST(KeycodeToXusb, UnknownKeycodeReturnsZero) {
    EXPECT_EQ(0, keycodeToXusb(0));
    EXPECT_EQ(0, keycodeToXusb(24));
    EXPECT_EQ(0, keycodeToXusb(4));
}

TEST(KeycodeToXusb, ButtonNumeric1Through4MapToFace) {
    EXPECT_EQ(XUSB_A, keycodeToXusb(KC_BUTTON_1));
    EXPECT_EQ(XUSB_B, keycodeToXusb(KC_BUTTON_2));
    EXPECT_EQ(XUSB_X, keycodeToXusb(KC_BUTTON_3));
    EXPECT_EQ(XUSB_Y, keycodeToXusb(KC_BUTTON_4));
}

TEST(KeycodeToXusb, ButtonNumeric5And6MapToShoulders) {
    EXPECT_EQ(XUSB_LB, keycodeToXusb(KC_BUTTON_5));
    EXPECT_EQ(XUSB_RB, keycodeToXusb(KC_BUTTON_6));
}

TEST(KeycodeToXusb, ButtonNumeric7And8AreNotInButtonMap) {
    // BUTTON_7/8 take the trigger-via-key path (like L2/R2); must not double-fire as wButtons bit.
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_7));
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_8));
}

TEST(KeycodeToXusb, ButtonNumeric9And10MapToBackStart) {
    EXPECT_EQ(XUSB_BACK, keycodeToXusb(KC_BUTTON_9));
    EXPECT_EQ(XUSB_START, keycodeToXusb(KC_BUTTON_10));
}

TEST(KeycodeToXusb, ButtonNumeric11And12MapToStickClicks) {
    EXPECT_EQ(XUSB_THUMB_L, keycodeToXusb(KC_BUTTON_11));
    EXPECT_EQ(XUSB_THUMB_R, keycodeToXusb(KC_BUTTON_12));
}

TEST(KeycodeToXusb, ButtonNumeric13Through16AreUnmapped) {
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_13));
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_14));
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_15));
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_16));
}

TEST(ApplyKey, Button7DownSetsLtTriggerLikeL2) {
    DeviceState s;
    EXPECT_TRUE(applyKey(s, KC_BUTTON_7, true));
    EXPECT_EQ(255, s.bLT);
    EXPECT_TRUE(s.ltFromKey);
    EXPECT_EQ(0, s.wButtons);
}

TEST(ApplyKey, Button7UpClearsLtTrigger) {
    DeviceState s;
    applyKey(s, KC_BUTTON_7, true);
    applyKey(s, KC_BUTTON_7, false);
    EXPECT_EQ(0, s.bLT);
    EXPECT_FALSE(s.ltFromKey);
}

TEST(ApplyKey, Button8DownSetsRtTriggerLikeR2) {
    DeviceState s;
    EXPECT_TRUE(applyKey(s, KC_BUTTON_8, true));
    EXPECT_EQ(255, s.bRT);
    EXPECT_TRUE(s.rtFromKey);
    EXPECT_EQ(0, s.wButtons);
}

TEST(ApplyKey, Button8UpClearsRtTrigger) {
    DeviceState s;
    applyKey(s, KC_BUTTON_8, true);
    applyKey(s, KC_BUTTON_8, false);
    EXPECT_EQ(0, s.bRT);
    EXPECT_FALSE(s.rtFromKey);
}

TEST(ApplyKey, Button7AndL2BothSetLt) {
    // Hybrid devices may fire both named L2 and numeric BUTTON_7; last-action wins.
    DeviceState s;
    applyKey(s, KC_BUTTON_L2, true);
    applyKey(s, KC_BUTTON_7, true);
    EXPECT_EQ(255, s.bLT);
    EXPECT_TRUE(s.ltFromKey);
    applyKey(s, KC_BUTTON_7, false);
    EXPECT_EQ(0, s.bLT);
    EXPECT_FALSE(s.ltFromKey);
}

TEST(ApplyKey, NumericButtonFollowsKeycodeToXusbMapping) {
    DeviceState s;
    applyKey(s, KC_BUTTON_1, true);
    EXPECT_EQ(XUSB_A, s.wButtons);
    applyKey(s, KC_BUTTON_4, true);
    EXPECT_EQ(XUSB_A | XUSB_Y, s.wButtons);
    applyKey(s, KC_BUTTON_9, true);
    EXPECT_EQ(XUSB_A | XUSB_Y | XUSB_BACK, s.wButtons);
    applyKey(s, KC_BUTTON_10, true);
    EXPECT_EQ(XUSB_A | XUSB_Y | XUSB_BACK | XUSB_START, s.wButtons);
    applyKey(s, KC_BUTTON_11, true);
    EXPECT_EQ(XUSB_A | XUSB_Y | XUSB_BACK | XUSB_START | XUSB_THUMB_L, s.wButtons);
}

TEST(ApplyKey, NumericButton13ThroughIs16ReturnsFalseAndNoStateChange) {
    DeviceState s;
    s.wButtons = XUSB_A;
    EXPECT_FALSE(applyKey(s, KC_BUTTON_13, true));
    EXPECT_FALSE(applyKey(s, KC_BUTTON_16, true));
    EXPECT_EQ(XUSB_A, s.wButtons);
}

TEST(ApplyKey, FaceButtonDownSetsBitUpClearsBit) {
    DeviceState s;
    EXPECT_TRUE(applyKey(s, KC_BUTTON_A, true));
    EXPECT_EQ(XUSB_A, s.wButtons);
    EXPECT_TRUE(applyKey(s, KC_BUTTON_A, false));
    EXPECT_EQ(0, s.wButtons);
}

TEST(ApplyKey, MultipleButtonsAccumulate) {
    DeviceState s;
    applyKey(s, KC_BUTTON_A, true);
    applyKey(s, KC_BUTTON_Y, true);
    EXPECT_EQ(XUSB_A | XUSB_Y, s.wButtons);
    applyKey(s, KC_BUTTON_A, false);
    EXPECT_EQ(XUSB_Y, s.wButtons);
}

TEST(ApplyKey, L2DownSetsTriggerAndKeyFlag) {
    DeviceState s;
    EXPECT_TRUE(applyKey(s, KC_BUTTON_L2, true));
    EXPECT_EQ(255, s.bLT);
    EXPECT_TRUE(s.ltFromKey);
    EXPECT_EQ(0, s.wButtons);
}

TEST(ApplyKey, L2UpClearsTriggerAndKeyFlag) {
    DeviceState s;
    applyKey(s, KC_BUTTON_L2, true);
    applyKey(s, KC_BUTTON_L2, false);
    EXPECT_EQ(0, s.bLT);
    EXPECT_FALSE(s.ltFromKey);
}

TEST(ApplyKey, R2DownSetsTriggerAndKeyFlag) {
    DeviceState s;
    EXPECT_TRUE(applyKey(s, KC_BUTTON_R2, true));
    EXPECT_EQ(255, s.bRT);
    EXPECT_TRUE(s.rtFromKey);
}

TEST(ApplyKey, UnknownKeycodeReturnsFalseAndNoStateChange) {
    DeviceState s;
    s.wButtons = XUSB_A;
    EXPECT_FALSE(applyKey(s, 4, true));
    EXPECT_EQ(XUSB_A, s.wButtons);
}

TEST(ApplyAxes, NeutralStaysZero) {
    DeviceState s;
    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    EXPECT_EQ(0, s.sLX);
    EXPECT_EQ(0, s.sLY);
    EXPECT_EQ(0, s.sRX);
    EXPECT_EQ(0, s.sRY);
    EXPECT_EQ(0, s.bLT);
    EXPECT_EQ(0, s.bRT);
    EXPECT_EQ(0, s.wButtons & XUSB_DPAD_MASK);
}

TEST(ApplyAxes, YIsInverted) {
    DeviceState s;
    applyAxes(s, 0.f, -1.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    EXPECT_EQ(32767, s.sLY);
    applyAxes(s, 0.f, 1.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    EXPECT_EQ(-32767, s.sLY);
}

TEST(ApplyAxes, RightStickYInverted) {
    DeviceState s;
    applyAxes(s, 0.f, 0.f, 0.f, -1.f, 0.f, 0.f, 0.f, 0.f);
    EXPECT_EQ(32767, s.sRY);
}

TEST(ApplyAxes, DeadzoneSuppressesNearRest) {
    DeviceState s;
    s.flatX = 0.1f;
    applyAxes(s, 0.05f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    EXPECT_EQ(0, s.sLX);
    applyAxes(s, 0.5f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    EXPECT_NE(0, s.sLX);
}

TEST(ApplyAxes, TriggersScaleToUint8) {
    DeviceState s;
    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.5f, 1.f, 0.f, 0.f);
    EXPECT_GE(s.bLT, 127);
    EXPECT_LE(s.bLT, 128);
    EXPECT_EQ(255, s.bRT);
}

TEST(ApplyAxes, LtFromKeySuppressesAxisTrigger) {
    // While ltFromKey is latched, a 0-valued LTRIGGER axis must not clear the trigger.
    DeviceState s;
    applyKey(s, KC_BUTTON_L2, true);
    EXPECT_EQ(255, s.bLT);
    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    EXPECT_EQ(255, s.bLT);
}

TEST(ApplyAxes, HatXFoldsIntoDpadBits) {
    DeviceState s;
    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, -1.f, 0.f);
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_LEFT);
    EXPECT_FALSE(s.wButtons & XUSB_DPAD_RIGHT);

    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 1.f, 0.f);
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_RIGHT);
    EXPECT_FALSE(s.wButtons & XUSB_DPAD_LEFT);
}

TEST(ApplyAxes, HatYFoldsIntoDpadBits) {
    DeviceState s;
    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, -1.f);
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_UP);

    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 1.f);
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_DOWN);
}

TEST(ApplyAxes, HatNeutralClearsDpadButPreservesOtherButtons) {
    DeviceState s;
    applyKey(s, KC_BUTTON_A, true);
    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, -1.f, 0.f);
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_LEFT);
    EXPECT_TRUE(s.wButtons & XUSB_A);
    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    EXPECT_FALSE(s.wButtons & XUSB_DPAD_MASK);
    EXPECT_TRUE(s.wButtons & XUSB_A);
}

TEST(ResetState, ZerosAllLiveFields) {
    DeviceState s;
    applyKey(s, KC_BUTTON_A, true);
    applyKey(s, KC_BUTTON_L2, true);
    applyAxes(s, 0.5f, -0.5f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);

    resetState(s);

    EXPECT_EQ(0, s.wButtons);
    EXPECT_EQ(0, s.bLT);
    EXPECT_EQ(0, s.bRT);
    EXPECT_EQ(0, s.sLX);
    EXPECT_EQ(0, s.sLY);
    EXPECT_EQ(0, s.sRX);
    EXPECT_EQ(0, s.sRY);
    EXPECT_FALSE(s.ltFromKey);
    EXPECT_FALSE(s.rtFromKey);
}

TEST(ResetState, DoesNotResetDeadzonesOrPublishHistory) {
    // Reset is per-event (ACTION_CANCEL / focus-loss); deadzones configured once must survive.
    DeviceState s;
    s.flatX = 0.1f;
    s.everPublished = true;
    s.lastWButtons = XUSB_A;

    resetState(s);

    EXPECT_FLOAT_EQ(0.1f, s.flatX);
    EXPECT_TRUE(s.everPublished);
    EXPECT_EQ(XUSB_A, s.lastWButtons);
}

TEST(ConsumePublishIfChanged, FirstCallAlwaysPublishes) {
    DeviceState s;
    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_TRUE(s.everPublished);
}

TEST(ConsumePublishIfChanged, IdempotentAfterFirstPublish) {
    DeviceState s;
    consumePublishIfChanged(s);
    EXPECT_FALSE(consumePublishIfChanged(s));
    EXPECT_FALSE(consumePublishIfChanged(s));
}

TEST(ConsumePublishIfChanged, RepublishesAfterStateChange) {
    DeviceState s;
    consumePublishIfChanged(s);
    applyKey(s, KC_BUTTON_A, true);
    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_FALSE(consumePublishIfChanged(s));
}

TEST(ConsumePublishIfChanged, EachAxisIndependentlyTriggersPublish) {
    DeviceState s;
    consumePublishIfChanged(s);
    s.sLX = 100;
    EXPECT_TRUE(consumePublishIfChanged(s));
    s.sLY = 100;
    EXPECT_TRUE(consumePublishIfChanged(s));
    s.bLT = 50;
    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_FALSE(consumePublishIfChanged(s));
}

TEST(MultiDevice, StatesAreIndependent) {
    DeviceState a, b;
    applyKey(a, KC_BUTTON_A, true);
    applyAxes(a, 0.5f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);

    EXPECT_EQ(XUSB_A, a.wButtons);
    EXPECT_NE(0, a.sLX);
    EXPECT_EQ(0, b.wButtons);
    EXPECT_EQ(0, b.sLX);
}

TEST(Integration, KeyDownAxisMoveCancelSequence) {
    DeviceState s;
    s.flatX = 0.1f;
    s.flatY = 0.1f;

    EXPECT_TRUE(applyKey(s, KC_BUTTON_A, true));
    EXPECT_TRUE(consumePublishIfChanged(s));

    applyAxes(s, 0.7f, -0.7f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_NE(0, s.sLX);
    EXPECT_GT(s.sLY, 0);

    applyAxes(s, 0.7f, -0.7f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    EXPECT_FALSE(consumePublishIfChanged(s));

    resetState(s);
    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_EQ(0, s.wButtons);
    EXPECT_EQ(0, s.sLX);
    EXPECT_EQ(0, s.sLY);
}

TEST(ResetPublishLatch, RepublishesUnchangedState) {
    DeviceState s;
    applyKey(s, KC_BUTTON_A, true);
    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_FALSE(consumePublishIfChanged(s));

    resetPublishLatch(s);

    EXPECT_FALSE(s.everPublished);
    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_FALSE(consumePublishIfChanged(s));
}

TEST(ResetPublishLatch, PreservesLiveStateAndDeadzones) {
    DeviceState s;
    s.flatX = 0.1f;
    applyKey(s, KC_BUTTON_A, true);
    applyAxes(s, 0.5f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    consumePublishIfChanged(s);
    int16_t liveSLX = s.sLX;

    resetPublishLatch(s);

    EXPECT_EQ(XUSB_A, s.wButtons);
    EXPECT_EQ(liveSLX, s.sLX);
    EXPECT_FLOAT_EQ(0.1f, s.flatX);
}

TEST(ResetPublishLatch, ForcesBaselineEvenWhenLiveMatchesLastPublished) {
    DeviceState s;
    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_FALSE(consumePublishIfChanged(s));

    resetPublishLatch(s);

    EXPECT_TRUE(consumePublishIfChanged(s));
}

TEST(BindBaselineSync, ResetPlusRearmClearsStalePhantomAndPublishesNeutral) {
    DeviceState s;
    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.6f, 0.f, -1.f, 0.f);
    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_GT(s.bLT, 0);
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_LEFT);

    resetState(s);
    resetPublishLatch(s);

    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_EQ(0, s.bLT);
    EXPECT_EQ(0, s.wButtons & XUSB_DPAD_MASK);
    EXPECT_EQ(0, s.lastBLT);
    EXPECT_EQ(0, s.lastWButtons);
}

TEST(BindBaselineSync, StaleHeldStateDoesNotSurviveRebind) {
    DeviceState s;
    applyKey(s, KC_BUTTON_B, true);
    consumePublishIfChanged(s);

    resetState(s);
    resetPublishLatch(s);
    consumePublishIfChanged(s);

    EXPECT_EQ(0, s.wButtons);
    EXPECT_FALSE(consumePublishIfChanged(s));
}
