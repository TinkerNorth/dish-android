// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

/**
 * Layout, drawing and gesture-recognition tuning for [GamepadTouchView].
 *
 * Everything here is dimensionless: dp / fraction-of-region / multiplier, so
 * the values can be reasoned about without thinking in pixels. Pixel-space
 * conversion happens at the call site in `computeGamepadLayout`.
 */
internal object GamepadConstants {
    // ── Safe-area envelope ────────────────────────────────────────────────
    const val SAFE_AREA_CUSHION_DP = 6f

    // ── Shoulder band (LB / RB) ──────────────────────────────────────────
    const val SHOULDER_BAND_HEIGHT_DP = 56f

    /** Inset from each side as a fraction of the safe horizontal width. */
    const val SHOULDER_CORNER_INSET_FRACTION = 0.04f

    /** Half-gap (dp) between LB and RB at the centre of the shoulder band. */
    const val SHOULDER_CENTER_HALF_GAP_DP = 80f

    // ── Trigger columns (LT / RT) ────────────────────────────────────────
    const val TRIGGER_WIDTH_DP = 52f

    /** Vertical gap between shoulder band and the content rect. */
    const val CONTENT_GAP_DP = 6f

    // ── Content rect: top / bottom row centres ───────────────────────────

    /** Top-row centre Y as a fraction of the content rect's height. */
    const val TOP_ROW_Y_FRACTION = 0.28f

    /** Bottom-row centre Y as a fraction of the content rect's height. */
    const val BOTTOM_ROW_Y_FRACTION = 0.75f

    // ── D-Pad / ABXY cluster ─────────────────────────────────────────────
    const val DPAD_INNER_PAD_DP = 12f

    /** Cluster height as a fraction of the content rect's height (cap). */
    const val CLUSTER_HEIGHT_FRACTION = 0.42f

    /** Cluster centre X as a fraction-of-quarter from the content edge. */
    const val CLUSTER_X_FRACTION_OF_QUARTER = 0.55f

    /** ABXY individual button radius as a fraction of cluster size. */
    const val ABXY_BTN_RADIUS_FRACTION = 0.18f

    /** Centre-to-button spacing for ABXY (multiples of [btnRadius]). */
    const val ABXY_BTN_SPACING_FACTOR = 1.5f

    /** Drawn-icon size for ABXY (multiples of [btnRadius]). */
    const val ABXY_BTN_DRAW_SIZE_FACTOR = 2.2f

    /**
     * Radius of the centre "all four buttons" zone, expressed as a fraction
     * of the cluster centre → button-centre distance. A touch inside this
     * disc triggers A + B + X + Y at once.
     *
     * Outside the disc the cluster is split into the same 8-direction
     * sweet-spot map as the d-pad (see [DPAD_DIAGONAL_THRESHOLD]): the
     * cardinal sectors map to a single button, the diagonal sectors to the
     * two adjacent buttons. Live-tracking — the held bits follow the finger
     * across zones, so sliding from B into A drops B and picks up A.
     */
    const val ABXY_CENTER_ZONE_FRACTION = 0.5f

    // ── Sticks ───────────────────────────────────────────────────────────

    /** Main-stick radius cap as a fraction of a content-quarter width. */
    const val STICK_RADIUS_QW_FRACTION = 0.42f

    /** Main-stick radius cap as a fraction of the content rect's height. */
    const val STICK_RADIUS_H_FRACTION = 0.2f

    /** L3/R3 secondary-stick radius as a fraction of the main stick's. */
    const val L3_STICK_RADIUS_FRACTION = 0.7f

    /** Gap (dp) between the main stick and its L3/R3 secondary. */
    const val L3_STICK_GAP_DP = 12f

    /** Main-stick centre X as a fraction-of-quarter from content edge. */
    const val STICK_X_FRACTION_OF_QUARTER = 0.5f

    /** How far the visual thumb cap travels (fraction of stick radius). */
    const val STICK_THUMB_TRAVEL_FRACTION = 0.55f

    /** Stick thumb-cap radius as a fraction of stick radius. */
    const val STICK_THUMB_RADIUS_FRACTION = 0.55f

    /** Direction-line width as a fraction of the thumb-cap radius. */
    const val STICK_DIR_LINE_WIDTH_FRACTION = 0.35f

    /** Outer ring stroke width (dp). */
    const val STICK_RING_STROKE_DP = 2f

    /** Thumb-cap ring stroke width (dp). */
    const val STICK_THUMB_RING_STROKE_DP = 1.5f

    /** Stick-label text size for single-char labels (× thumb radius). */
    const val STICK_LABEL_SIZE_SINGLE = 0.95f

    /** Stick-label text size for multi-char labels (× thumb radius). */
    const val STICK_LABEL_SIZE_MULTI = 0.7f

    /** Baseline drop for stick labels (× thumb radius). */
    const val STICK_LABEL_BASELINE_FRACTION = 0.33f

    // ── Centre cluster (Select / Start / Home) ───────────────────────────
    const val SMALL_BTN_RADIUS_DP = 14f

    /** Centre-button Y from content top, in multiples of [smallBtnRadius]. */
    const val CENTER_BTN_TOP_OFFSET_FACTOR = 1.8f

    /** Half-gap between Select and Start (dp). */
    const val CENTER_BTN_HALF_GAP_DP = 40f

    /** Drawn-icon size for centre buttons (× smallBtnRadius). */
    const val CENTER_BTN_DRAW_SIZE_FACTOR = 2.2f

    /** Vertical offset of the Home button below Select/Start, × small radius. */
    const val HOME_VERTICAL_OFFSET_FACTOR = 2.5f

    /** Home button is drawn slightly smaller than Select/Start. */
    const val HOME_DRAW_SIZE_FACTOR = 0.8f

    // ── Pill buttons (shoulders + triggers) ──────────────────────────────

    /** Corner-radius as a fraction of the pill's shorter side. */
    const val PILL_CORNER_RADIUS_FRACTION = 0.35f

    /** Icon size as a fraction of the pill's shorter side. */
    const val PILL_ICON_SIZE_FRACTION = 0.8f

    // ── Touch hit-test factors ───────────────────────────────────────────

    /**
     * Pickup radius around sticks and individual ABXY buttons, as a multiple
     * of the visual radius. >1 so a finger landing just outside the visible
     * disc still latches onto the control.
     */
    const val PICKUP_RADIUS_FACTOR = 1.3f

    /** Pickup radius around centre buttons (× smallBtnRadius). */
    const val CENTER_BTN_PICKUP_FACTOR = 1.5f

    // ── D-pad diagonal sweet-spot ────────────────────────────────────────

    /**
     * Minor-axis / major-axis threshold for a d-pad touch to register as a
     * diagonal (NE/SE/SW/NW) instead of snapping to the nearest cardinal.
     *
     * Below this ratio the touch falls back to the dominant axis. With
     * 0.3 each diagonal sweet-spot spans ~57° (`2·(45°−atan(0.3))`) and
     * each cardinal narrows to ~33°. The bias favours diagonals because
     * thumb-driven d-pads rarely land precisely on the 45° line — the
     * old 8-octant geometric split gave each direction the same 45° band
     * and in practice "up + left" almost always fell into pure up or
     * pure left.
     */
    const val DPAD_DIAGONAL_THRESHOLD = 0.3f

    // ── Triggers ─────────────────────────────────────────────────────────
    const val TRIGGER_MAX = 255
}
