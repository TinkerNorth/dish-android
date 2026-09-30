// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.input

import android.os.Build

// Pointer capture, the only way to read a pad's touch surface as fingers, arrived in this release.
internal const val POINTER_CAPTURE_SDK = Build.VERSION_CODES.O

// What decides whether another InputDevice is a pad's separately enumerated touch surface.
internal data class SiblingDevice(
    val id: Int,
    val sources: Int,
    val isGamepad: Boolean,
    val vendorId: Int,
    val productId: Int,
)

// What a re-probe of a framework pad found, compared against the card the registry holds.
internal data class ProbedCapabilities(
    val hasGyro: Boolean,
    val hasRumble: Boolean,
    val hasLightbar: Boolean,
    val touchpadDeviceId: Int?,
)

// The pad's own surface: the merged device first (the common case), then a sibling device of the
// same identity, enumerated through [siblings] only when the first two answers are not enough.
// Before pointer capture the surface stays a system mouse and the app reports none, so nothing
// offers what it cannot read.
internal fun resolveTouchpadSurface(
    sdkInt: Int,
    deviceId: Int,
    sources: Int,
    vendorId: Int,
    productId: Int,
    siblings: () -> List<SiblingDevice>,
): Int? {
    if (sdkInt < POINTER_CAPTURE_SDK) return null
    if (hasPointerSource(sources)) return deviceId
    val hasNoIdentity = vendorId == 0 && productId == 0
    if (hasNoIdentity) return null
    return siblings().firstOrNull { isSiblingSurfaceOf(it, deviceId, vendorId, productId) }?.id
}

private fun isSiblingSurfaceOf(
    other: SiblingDevice,
    deviceId: Int,
    vendorId: Int,
    productId: Int,
): Boolean {
    val isAnotherDevice = other.id != deviceId
    val hasPointer = hasPointerSource(other.sources)
    val isNotAPad = !other.isGamepad
    val sameIdentity = other.vendorId == vendorId && other.productId == productId
    return isAnotherDevice && hasPointer && isNotAPad && sameIdentity
}

// Whether a re-probe warrants republishing the pad's card: a pad the registry does not hold yet, a
// renamed one, one mid-way through a disconnect countdown, or a capability that enumerated late.
internal fun frameworkPadChanged(
    current: PhysicalGamepadRegistry.Device?,
    name: String,
    probed: ProbedCapabilities,
): Boolean {
    if (current == null) return true
    val renamed = current.name != name
    val gyroChanged = current.hasGyro != probed.hasGyro
    val rumbleChanged = current.hasRumble != probed.hasRumble
    val lightbarChanged = current.hasLightbar != probed.hasLightbar
    val surfaceChanged = current.touchpadDeviceId != probed.touchpadDeviceId
    val probeChanged = gyroChanged || rumbleChanged || lightbarChanged || surfaceChanged
    return renamed || current.isDisconnecting || probeChanged
}
