// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

/*
 * gamepad_input_test.cpp — host unit tests for the pure gamepad-input layer.
 *
 * These tests replace the deleted Kotlin GamepadInputProcessorTest. Scope is
 * intentionally the same:
 *   - scaleAxis / scaleTrigger / deadzone arithmetic (incl. Y-inversion +
 *     clamping)
 *   - keycodeToXusb mapping
 *   - applyKey state transitions (incl. L2/R2 trigger-via-key path)
 *   - applyAxes (deadzone, Y-inversion, trigger-pair max, hat → DPAD bits)
 *   - resetState (ACTION_CANCEL semantics)
 *   - consumePublishIfChanged send-on-change gate
 *   - per-device isolation
 */
#include "gamepad_input.h"

#include <gtest/gtest.h>

using namespace gamepad;

// ── scaleAxis ───────────────────────────────────────────────────────────────

TEST(ScaleAxis, CenterReturnsZero) { EXPECT_EQ(0, scaleAxis(0.f, 32767.f)); }

TEST(ScaleAxis, FullPositiveReturnsMax) { EXPECT_EQ(32767, scaleAxis(1.f, 32767.f)); }

TEST(ScaleAxis, FullNegativeReturnsNegativeMax) { EXPECT_EQ(-32767, scaleAxis(-1.f, 32767.f)); }

TEST(ScaleAxis, YInversionFlipsSign) {
    // Android reports AXIS_Y = -1.0 for "stick up"; the wire wants +Short_MAX.
    // The pipeline encodes that by scaling AXIS_Y with a negative max.
    EXPECT_EQ(32767, scaleAxis(-1.f, -32767.f));
    EXPECT_EQ(-32767, scaleAxis(1.f, -32767.f));
}

TEST(ScaleAxis, ClampsAbovePositiveMax) { EXPECT_EQ(32767, scaleAxis(2.f, 32767.f)); }

TEST(ScaleAxis, ClampsBelowNegativeMax) {
    // -32768 is the int16 floor; verify the impl honours it instead of -32767.
    EXPECT_EQ(-32768, scaleAxis(-2.f, 32767.f));
}

// ── scaleTrigger ────────────────────────────────────────────────────────────

TEST(ScaleTrigger, ZeroReturnsZero) { EXPECT_EQ(0, scaleTrigger(0.f, 255.f)); }

TEST(ScaleTrigger, FullReturnsMax) { EXPECT_EQ(255, scaleTrigger(1.f, 255.f)); }

TEST(ScaleTrigger, NegativeClampsToZero) {
    // Triggers are unsigned on the wire; a malformed negative input must not
    // wrap around to 255 via a signed→unsigned cast.
    EXPECT_EQ(0, scaleTrigger(-0.5f, 255.f));
}

TEST(ScaleTrigger, OverOneClampsToMax) { EXPECT_EQ(255, scaleTrigger(1.5f, 255.f)); }

// ── deadzone ────────────────────────────────────────────────────────────────

TEST(Deadzone, InsideZoneReturnsZero) {
    // |0.05| <= 0.1 → return 0 (gate is strict-greater-than, so 0.1 itself
    // is also inside).
    EXPECT_EQ(0.f, deadzone(0.05f, 0.1f));
    EXPECT_EQ(0.f, deadzone(-0.1f, 0.1f));
}

TEST(Deadzone, OutsideZonePassesThrough) {
    EXPECT_FLOAT_EQ(0.5f, deadzone(0.5f, 0.1f));
    EXPECT_FLOAT_EQ(-0.5f, deadzone(-0.5f, 0.1f));
}

TEST(Deadzone, ZeroFlatPassesEverything) { EXPECT_FLOAT_EQ(0.001f, deadzone(0.001f, 0.f)); }

// ── keycodeToXusb ───────────────────────────────────────────────────────────

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
    // L2/R2 are handled separately by applyKey (trigger-via-key path) — the
    // pure mapping here intentionally returns 0 so the JNI filter doesn't
    // route them via the wButtons path.
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_L2));
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_R2));
}

TEST(KeycodeToXusb, UnknownKeycodeReturnsZero) {
    EXPECT_EQ(0, keycodeToXusb(0));
    EXPECT_EQ(0, keycodeToXusb(/* AKEYCODE_VOLUME_UP */ 24));
    EXPECT_EQ(0, keycodeToXusb(/* AKEYCODE_BACK */ 4));
}

// ── Numeric BUTTON_1..16 fallback (cheap HID joystick adapters) ─────────────
// These adapters don't ship a key-layout file that relabels their buttons to
// the named BUTTON_A/B/X/Y/Start/Select set, so their buttons surface as
// BUTTON_1..16 instead. The mapping follows the DirectInput numbering most
// adapters use: 1-4 face, 5-6 shoulders, 7-8 triggers (handled in applyKey),
// 9-10 Back/Start, 11-12 stick clicks, 13-16 unmapped.

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
    // BUTTON_7/8 are routed via the trigger-via-key path (applyKey) — same
    // as L2/R2 — so they intentionally return 0 here. Test that contract so
    // a future "just complete the table" PR doesn't accidentally double-fire
    // a trigger as both a wButtons bit and a trigger axis.
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
    // Physical assignment varies too much between devices to guess at;
    // these stay zero so the activity-side dispatch can still consume them
    // (preventing Android's fallback DPAD_CENTER hijack) without claiming
    // they're a particular logical button.
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_13));
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_14));
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_15));
    EXPECT_EQ(0, keycodeToXusb(KC_BUTTON_16));
}

// ── applyKey for BUTTON_7/8 trigger-via-key path ────────────────────────────

TEST(ApplyKey, Button7DownSetsLtTriggerLikeL2) {
    DeviceState s;
    EXPECT_TRUE(applyKey(s, KC_BUTTON_7, true));
    EXPECT_EQ(255, s.bLT);
    EXPECT_TRUE(s.ltFromKey);
    EXPECT_EQ(0, s.wButtons); // must NOT touch wButtons
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
    // Some hybrid devices fire BOTH the named L2 and the numeric BUTTON_7 on
    // the same press. Both paths must agree — last-action wins, no leakage.
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
    // Spot-check that the numeric buttons walk through applyKey end-to-end
    // (not just keycodeToXusb in isolation).
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

// ── applyKey ────────────────────────────────────────────────────────────────

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
    EXPECT_EQ(0, s.wButtons); // L2 must NOT touch wButtons
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
    EXPECT_FALSE(applyKey(s, /* AKEYCODE_BACK */ 4, true));
    EXPECT_EQ(XUSB_A, s.wButtons); // untouched
}

// ── applyAxes ───────────────────────────────────────────────────────────────

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
    // Android's AXIS_Y = -1.0 means "stick up" — wire expects +Short_MAX.
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
    // The trigger key path latches state.ltFromKey. While that flag is set,
    // a subsequent motion event with a 0-valued LTRIGGER axis must not drop
    // the trigger back to zero — that was a bug in an earlier iteration.
    DeviceState s;
    applyKey(s, KC_BUTTON_L2, true);
    EXPECT_EQ(255, s.bLT);
    applyAxes(s, 0.f, 0.f, 0.f, 0.f, /*lt=*/0.f, 0.f, 0.f, 0.f);
    EXPECT_EQ(255, s.bLT); // axis read suppressed
}

TEST(ApplyAxes, HatXFoldsIntoDpadBits) {
    DeviceState s;
    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, /*hatX=*/-1.f, 0.f);
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_LEFT);
    EXPECT_FALSE(s.wButtons & XUSB_DPAD_RIGHT);

    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, /*hatX=*/1.f, 0.f);
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_RIGHT);
    EXPECT_FALSE(s.wButtons & XUSB_DPAD_LEFT);
}

TEST(ApplyAxes, HatYFoldsIntoDpadBits) {
    DeviceState s;
    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, /*hatY=*/-1.f);
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_UP);

    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, /*hatY=*/1.f);
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_DOWN);
}

TEST(ApplyAxes, HatNeutralClearsDpadButPreservesOtherButtons) {
    DeviceState s;
    applyKey(s, KC_BUTTON_A, true);
    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, /*hatX=*/-1.f, 0.f);
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_LEFT);
    EXPECT_TRUE(s.wButtons & XUSB_A);
    applyAxes(s, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    EXPECT_FALSE(s.wButtons & XUSB_DPAD_MASK);
    EXPECT_TRUE(s.wButtons & XUSB_A); // non-DPAD buttons untouched
}

// ── resetState ──────────────────────────────────────────────────────────────

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
    // Reset is per-event (ACTION_CANCEL / focus-loss), not per-device. The
    // deadzones are configured once on InputDevice add and must survive.
    DeviceState s;
    s.flatX = 0.1f;
    s.everPublished = true;
    s.lastWButtons = XUSB_A;

    resetState(s);

    EXPECT_FLOAT_EQ(0.1f, s.flatX);
    EXPECT_TRUE(s.everPublished);
    EXPECT_EQ(XUSB_A, s.lastWButtons);
}

// ── consumePublishIfChanged ─────────────────────────────────────────────────

TEST(ConsumePublishIfChanged, FirstCallAlwaysPublishes) {
    DeviceState s;
    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_TRUE(s.everPublished);
}

TEST(ConsumePublishIfChanged, IdempotentAfterFirstPublish) {
    DeviceState s;
    consumePublishIfChanged(s);
    EXPECT_FALSE(consumePublishIfChanged(s)); // nothing changed
    EXPECT_FALSE(consumePublishIfChanged(s));
}

TEST(ConsumePublishIfChanged, RepublishesAfterStateChange) {
    DeviceState s;
    consumePublishIfChanged(s);
    applyKey(s, KC_BUTTON_A, true);
    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_FALSE(consumePublishIfChanged(s)); // settled
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

// ── Per-device isolation ────────────────────────────────────────────────────

TEST(MultiDevice, StatesAreIndependent) {
    // The JNI layer keys DeviceStates by InputDevice id in an unordered_map;
    // here we just verify two separate instances don't share state.
    DeviceState a, b;
    applyKey(a, KC_BUTTON_A, true);
    applyAxes(a, 0.5f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);

    EXPECT_EQ(XUSB_A, a.wButtons);
    EXPECT_NE(0, a.sLX);
    EXPECT_EQ(0, b.wButtons);
    EXPECT_EQ(0, b.sLX);
}

// ── Integration: a realistic event sequence ────────────────────────────────

TEST(Integration, KeyDownAxisMoveCancelSequence) {
    // Smoke test pinning the full lifecycle: button held, stick moved,
    // ACTION_CANCEL releases everything, send-on-change gate behaves.
    DeviceState s;
    s.flatX = 0.1f;
    s.flatY = 0.1f;

    EXPECT_TRUE(applyKey(s, KC_BUTTON_A, true));
    EXPECT_TRUE(consumePublishIfChanged(s));

    applyAxes(s, 0.7f, -0.7f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_NE(0, s.sLX);
    EXPECT_GT(s.sLY, 0); // -0.7 input × -32767 scale → positive (Xbox up)

    // Repeat axis read with same values: nothing to publish.
    applyAxes(s, 0.7f, -0.7f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    EXPECT_FALSE(consumePublishIfChanged(s));

    resetState(s);
    EXPECT_TRUE(consumePublishIfChanged(s));
    EXPECT_EQ(0, s.wButtons);
    EXPECT_EQ(0, s.sLX);
    EXPECT_EQ(0, s.sLY);
}
