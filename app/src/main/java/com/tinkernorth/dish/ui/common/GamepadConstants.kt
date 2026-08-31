// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

internal object GamepadConstants {
    const val SAFE_AREA_CUSHION_DP = 6f

    const val SHOULDER_BAND_HEIGHT_DP = 56f

    const val SHOULDER_CORNER_INSET_FRACTION = 0.04f

    const val SHOULDER_CENTER_HALF_GAP_DP = 80f

    const val TRIGGER_WIDTH_DP = 52f

    const val CONTENT_GAP_DP = 6f

    const val TOP_ROW_Y_FRACTION = 0.28f

    const val BOTTOM_ROW_Y_FRACTION = 0.75f

    const val DPAD_INNER_PAD_DP = 12f

    const val CLUSTER_HEIGHT_FRACTION = 0.42f

    const val CLUSTER_X_FRACTION_OF_QUARTER = 0.55f

    const val ABXY_BTN_RADIUS_FRACTION = 0.18f

    const val ABXY_BTN_SPACING_FACTOR = 1.5f

    const val ABXY_BTN_DRAW_SIZE_FACTOR = 2.2f

    // Centre disc triggers all four ABXY at once; outside it splits into 8 zones like the d-pad.
    const val ABXY_CENTER_ZONE_FRACTION = 0.5f

    const val STICK_RADIUS_QW_FRACTION = 0.42f

    const val STICK_RADIUS_H_FRACTION = 0.2f

    const val L3_STICK_RADIUS_FRACTION = 0.7f

    const val L3_STICK_GAP_DP = 12f

    const val STICK_X_FRACTION_OF_QUARTER = 0.5f

    const val STICK_THUMB_TRAVEL_FRACTION = 0.55f

    const val STICK_THUMB_RADIUS_FRACTION = 0.55f

    const val STICK_DIR_LINE_WIDTH_FRACTION = 0.35f

    const val STICK_RING_STROKE_DP = 2f

    const val STICK_THUMB_RING_STROKE_DP = 1.5f

    const val STICK_LABEL_SIZE_SINGLE = 0.95f

    const val STICK_LABEL_SIZE_MULTI = 0.7f

    const val STICK_LABEL_BASELINE_FRACTION = 0.33f

    const val SMALL_BTN_RADIUS_DP = 14f

    const val CENTER_BTN_TOP_OFFSET_FACTOR = 1.8f

    const val CENTER_BTN_HALF_GAP_DP = 40f

    const val CENTER_BTN_DRAW_SIZE_FACTOR = 2.2f

    const val HOME_VERTICAL_OFFSET_FACTOR = 2.5f

    const val HOME_DRAW_SIZE_FACTOR = 0.8f

    const val PILL_CORNER_RADIUS_FRACTION = 0.35f

    const val PILL_ICON_SIZE_FRACTION = 0.8f

    // >1 so a finger landing just outside the visible disc still latches on.
    const val PICKUP_RADIUS_FACTOR = 1.3f

    const val CENTER_BTN_PICKUP_FACTOR = 1.5f

    // minor/major axis ratio above which a d-pad touch reads as a diagonal instead of snapping to the nearest cardinal.
    const val DPAD_DIAGONAL_THRESHOLD = 0.3f

    const val TRIGGER_MAX = 255

    // Analog trigger rails: the top fraction of each rail is the full/tap zone;
    // below it the rail ramps 0 (bottom edge) to TRIGGER_MAX (zone boundary).
    const val TRIGGER_FULL_ZONE_FRACTION = 0.25f

    const val TRIGGER_ZONE_DIVIDER_STROKE_DP = 1.5f

    const val TRIGGER_ZONE_DIVIDER_INSET_DP = 6f

    const val TRACKPAD_WIDTH_FRACTION = 0.3f

    // The DS4/DualSense trackpad is roughly 2:1.
    const val TRACKPAD_ASPECT = 2.0f

    const val TRACKPAD_MAX_HEIGHT_FRACTION = 0.4f

    const val TRACKPAD_CORNER_RADIUS_FRACTION = 0.12f

    const val TRACKPAD_FLANK_BTN_GAP_DP = 10f

    const val TRACKPAD_HOME_GAP_DP = 8f

    const val TRACKPAD_OUTLINE_STROKE_DP = 1.5f

    const val TRACKPAD_FINGER_DOT_RADIUS_DP = 10f

    const val TRACKPAD_TAP_SLOP_DP = 12f

    const val TRACKPAD_TAP_MAX_MS = 200L

    const val TRACKPAD_CLICK_PULSE_MS = 70L

    // Host-driven feedback rendering (lightbar ring, player-LED row, trigger-effect accents).
    const val LIGHTBAR_STROKE_DP = 3f

    const val PLAYER_LED_COUNT = 5

    const val PLAYER_LED_RADIUS_DP = 2.5f

    const val PLAYER_LED_PITCH_DP = 5f

    const val PLAYER_LED_GAP_DP = 6f

    const val TRIGGER_EFFECT_STROKE_DP = 2f
}
