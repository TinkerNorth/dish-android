// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.pm.ServiceInfo
import com.tinkernorth.dish.source.audio.MicCapturePlan
import com.tinkernorth.dish.source.audio.MicCaptureTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Which foreground-service types the streaming session is entitled to hold.
 *
 * The microphone type is the interesting half: it is a while-in-use type, so it grants access to
 * the microphone for as long as it is held, and a session holding it without capturing is a
 * microphone the user can see in the status bar and cannot account for.
 */
class StreamingServiceTypesTest {
    @Test
    fun `a plain session is a connected-device session and nothing else`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            foregroundServiceTypes(micArmed = false),
        )
    }

    @Test
    fun `a mic-enabled binding adds the microphone type on top`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            foregroundServiceTypes(micArmed = true),
        )
    }

    @Test
    fun `the microphone type is never held on its own`() {
        // The manifest declares connectedDevice|microphone; a service that declared only the
        // microphone would be claiming the session is a recording, which it is not.
        val armed = foregroundServiceTypes(micArmed = true)
        assertNotEquals(0, armed and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        assertEquals(0, foregroundServiceTypes(micArmed = false) and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    @Test
    fun `the type follows arming, not delivery, so a mute does not drop it`() {
        // Re-taking a while-in-use type needs the app in the foreground; a mute pressed on the
        // overlay and released a second later would otherwise be a coin flip on getting it back.
        // Zero packets while muted is enforced in the capture engine instead.
        val target = MicCaptureTarget("virtual", "satellite:abc")
        val muted = MicCapturePlan(armed = setOf(target), delivering = emptySet())
        val live = MicCapturePlan(armed = setOf(target), delivering = setOf(target))
        assertEquals(
            foregroundServiceTypes(micArmed = live.arming),
            foregroundServiceTypes(micArmed = muted.arming),
        )
        assertEquals(false, muted.capturing)
    }

    @Test
    fun `an idle plan asks for no microphone type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            foregroundServiceTypes(micArmed = MicCapturePlan.IDLE.arming),
        )
    }
}
