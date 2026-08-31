// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.graphics.Rect
import android.graphics.RectF
import com.tinkernorth.dish.ui.common.GamepadConstants.ABXY_BTN_RADIUS_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.BOTTOM_ROW_Y_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.CENTER_BTN_HALF_GAP_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.CENTER_BTN_TOP_OFFSET_FACTOR
import com.tinkernorth.dish.ui.common.GamepadConstants.CLUSTER_HEIGHT_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.CLUSTER_X_FRACTION_OF_QUARTER
import com.tinkernorth.dish.ui.common.GamepadConstants.CONTENT_GAP_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.DPAD_INNER_PAD_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.HOME_VERTICAL_OFFSET_FACTOR
import com.tinkernorth.dish.ui.common.GamepadConstants.L3_STICK_GAP_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.L3_STICK_RADIUS_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.SAFE_AREA_CUSHION_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.SHOULDER_BAND_HEIGHT_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.SHOULDER_CENTER_HALF_GAP_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.SHOULDER_CORNER_INSET_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.SMALL_BTN_RADIUS_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_RADIUS_H_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_RADIUS_QW_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.STICK_X_FRACTION_OF_QUARTER
import com.tinkernorth.dish.ui.common.GamepadConstants.TOP_ROW_Y_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.TRACKPAD_ASPECT
import com.tinkernorth.dish.ui.common.GamepadConstants.TRACKPAD_FLANK_BTN_GAP_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.TRACKPAD_HOME_GAP_DP
import com.tinkernorth.dish.ui.common.GamepadConstants.TRACKPAD_MAX_HEIGHT_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.TRACKPAD_WIDTH_FRACTION
import com.tinkernorth.dish.ui.common.GamepadConstants.TRIGGER_WIDTH_DP
import kotlin.math.min

internal data class GamepadLayout(
    val dpadRect: RectF,
    val abxyRect: RectF,
    val lbRect: RectF,
    val rbRect: RectF,
    val ltRect: RectF,
    val rtRect: RectF,
    val leftStickCx: Float,
    val leftStickCy: Float,
    val rightStickCx: Float,
    val rightStickCy: Float,
    val l3StickCx: Float,
    val l3StickCy: Float,
    val r3StickCx: Float,
    val r3StickCy: Float,
    val stickRadius: Float,
    val l3StickRadius: Float,
    val btnRadius: Float,
    val smallBtnRadius: Float,
    val selectCx: Float,
    val startCx: Float,
    val homeCx: Float,
    val homeCy: Float,
    val centerBtnCy: Float,
    val trackpadRect: RectF? = null,
)

@Suppress("LongMethod")
internal fun computeGamepadLayout(
    width: Int,
    height: Int,
    density: Float,
    safeInsets: Rect,
    skin: GamepadSkin,
    trackpad: Boolean = false,
): GamepadLayout {
    // Only the PlayStation family flips the d-pad above the left stick; Switch keeps the Xbox arrangement.
    val psLayout = skin == GamepadSkin.PlayStation || skin == GamepadSkin.DualSense
    val cushion = SAFE_AREA_CUSHION_DP * density
    val safeTop = safeInsets.top + cushion
    val safeLeft = safeInsets.left + cushion
    val safeRight = width - safeInsets.right - cushion
    val safeBottom = height - safeInsets.bottom - cushion

    val sbH = SHOULDER_BAND_HEIGHT_DP * density
    val sbCornerInset = (safeRight - safeLeft) * SHOULDER_CORNER_INSET_FRACTION
    val tW = TRIGGER_WIDTH_DP * density
    val gap = CONTENT_GAP_DP * density
    val centreX = (safeLeft + safeRight) / 2f
    val sbHalfGap = SHOULDER_CENTER_HALF_GAP_DP * density

    val lbRect = RectF(safeLeft + sbCornerInset, safeTop, centreX - sbHalfGap, safeTop + sbH)
    val rbRect = RectF(centreX + sbHalfGap, safeTop, safeRight - sbCornerInset, safeTop + sbH)

    val contentTop = safeTop + sbH + gap
    val contentBottom = safeBottom
    val ltRect = RectF(safeLeft, contentTop, safeLeft + tW, contentBottom)
    val rtRect = RectF(safeRight - tW, contentTop, safeRight, contentBottom)

    val contentLeft = ltRect.right + gap
    val contentRight = rtRect.left - gap
    val contentW = contentRight - contentLeft
    val contentH = contentBottom - contentTop
    val qw = contentW / 4f

    val topRowCy = contentTop + contentH * TOP_ROW_Y_FRACTION
    val bottomRowCy = contentTop + contentH * BOTTOM_ROW_Y_FRACTION

    val pad = DPAD_INNER_PAD_DP * density
    val clusterSize = min(qw * 2f - pad * 2, contentH * CLUSTER_HEIGHT_FRACTION)

    val dpadCx = contentLeft + qw * CLUSTER_X_FRACTION_OF_QUARTER
    val dpadCy = if (psLayout) topRowCy else bottomRowCy
    val dpadRect =
        RectF(dpadCx - clusterSize / 2, dpadCy - clusterSize / 2, dpadCx + clusterSize / 2, dpadCy + clusterSize / 2)

    val stickRadius = min(qw * STICK_RADIUS_QW_FRACTION, contentH * STICK_RADIUS_H_FRACTION)
    val l3StickRadius = stickRadius * L3_STICK_RADIUS_FRACTION
    val l3Gap = L3_STICK_GAP_DP * density
    val leftStickCx = contentLeft + qw * STICK_X_FRACTION_OF_QUARTER
    val leftStickCy = if (psLayout) bottomRowCy else topRowCy
    val l3StickCx = leftStickCx + stickRadius + l3StickRadius + l3Gap
    val l3StickCy = leftStickCy

    val abxyCx = contentRight - qw * CLUSTER_X_FRACTION_OF_QUARTER
    val abxyRect =
        RectF(abxyCx - clusterSize / 2, topRowCy - clusterSize / 2, abxyCx + clusterSize / 2, topRowCy + clusterSize / 2)
    val btnRadius = clusterSize * ABXY_BTN_RADIUS_FRACTION

    val rightStickCx = contentRight - qw * STICK_X_FRACTION_OF_QUARTER
    val rightStickCy = bottomRowCy
    val r3StickCx = rightStickCx - stickRadius - l3StickRadius - l3Gap
    val r3StickCy = rightStickCy

    val smallBtnRadius = SMALL_BTN_RADIUS_DP * density
    val centerBtnCy = contentTop + smallBtnRadius * CENTER_BTN_TOP_OFFSET_FACTOR
    val centerCx = (contentLeft + contentRight) / 2f
    val centerHalfGap = CENTER_BTN_HALF_GAP_DP * density

    // The trackpad sits where the real DS4/DualSense carries it: top centre, with the
    // Share/Create and Options buttons flanking it and the PS button dropping below.
    val trackpadRect =
        if (psLayout && trackpad) {
            val padH = min(contentW * TRACKPAD_WIDTH_FRACTION / TRACKPAD_ASPECT, contentH * TRACKPAD_MAX_HEIGHT_FRACTION)
            val padW = padH * TRACKPAD_ASPECT
            RectF(centerCx - padW / 2, contentTop, centerCx + padW / 2, contentTop + padH)
        } else {
            null
        }
    val flankGap = TRACKPAD_FLANK_BTN_GAP_DP * density
    val selectCx = trackpadRect?.let { it.left - flankGap - smallBtnRadius } ?: (centerCx - centerHalfGap)
    val startCx = trackpadRect?.let { it.right + flankGap + smallBtnRadius } ?: (centerCx + centerHalfGap)
    val homeCy =
        trackpadRect?.let { it.bottom + TRACKPAD_HOME_GAP_DP * density + smallBtnRadius }
            ?: (centerBtnCy + smallBtnRadius * HOME_VERTICAL_OFFSET_FACTOR)

    return GamepadLayout(
        dpadRect = dpadRect,
        abxyRect = abxyRect,
        lbRect = lbRect,
        rbRect = rbRect,
        ltRect = ltRect,
        rtRect = rtRect,
        leftStickCx = leftStickCx,
        leftStickCy = leftStickCy,
        rightStickCx = rightStickCx,
        rightStickCy = rightStickCy,
        l3StickCx = l3StickCx,
        l3StickCy = l3StickCy,
        r3StickCx = r3StickCx,
        r3StickCy = r3StickCy,
        stickRadius = stickRadius,
        l3StickRadius = l3StickRadius,
        btnRadius = btnRadius,
        smallBtnRadius = smallBtnRadius,
        selectCx = selectCx,
        startCx = startCx,
        homeCx = centerCx,
        homeCy = homeCy,
        centerBtnCy = centerBtnCy,
        trackpadRect = trackpadRect,
    )
}
