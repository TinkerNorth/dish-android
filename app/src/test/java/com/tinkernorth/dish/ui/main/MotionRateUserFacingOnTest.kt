// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.composer.MotionCapability
import com.tinkernorth.dish.source.store.SatelliteMotionBackendStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionRateUserFacingOnTest {
    private fun cap(
        hasGyro: Boolean = true,
        carriesOnConnection: Boolean = true,
        userEnabled: Boolean = true,
        hostHasSinkForType: Boolean = true,
        backendOk: Boolean? = null,
    ) = MotionCapability(
        hasGyro = hasGyro,
        carriesOnConnection = carriesOnConnection,
        userEnabled = userEnabled,
        hostHasSinkForType = hostHasSinkForType,
        satelliteBackendStatus =
            backendOk?.let { SatelliteMotionBackendStatus(sinkSupportedForType = true, backendOk = it) },
    )

    @Test
    fun `shown when motion is fully on`() {
        assertTrue(motionRateUserFacingOn(cap()))
        assertTrue(motionRateUserFacingOn(cap(backendOk = true)))
    }

    @Test
    fun `hidden for an emulated type with no host sink`() {
        assertFalse(motionRateUserFacingOn(cap(hostHasSinkForType = false)))
    }

    @Test
    fun `hidden when the user disabled motion`() {
        assertFalse(motionRateUserFacingOn(cap(userEnabled = false)))
    }

    @Test
    fun `hidden when the connection does not carry motion`() {
        assertFalse(motionRateUserFacingOn(cap(carriesOnConnection = false)))
    }

    @Test
    fun `hidden when the host backend is broken`() {
        assertFalse(motionRateUserFacingOn(cap(backendOk = false)))
    }

    @Test
    fun `hidden without a gyro`() {
        assertFalse(motionRateUserFacingOn(cap(hasGyro = false)))
    }
}
