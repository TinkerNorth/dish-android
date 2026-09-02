// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.audio.MicIndicatorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The streaming notification's mic surface: which start commands the service recognises, and
 * what the notification says and offers per indicator state. Both are pure mappings so the
 * shade behaviour can be pinned without a running service.
 */
class StreamingServiceMicSurfaceTest {
    @Test
    fun `the stop action maps to stop-all`() {
        assertEquals(StreamingCommand.STOP_ALL, streamingCommandFor(StreamingService.ACTION_STOP_ALL))
    }

    @Test
    fun `the mic action maps to toggle-mic`() {
        assertEquals(StreamingCommand.TOGGLE_MIC, streamingCommandFor(StreamingService.ACTION_TOGGLE_MIC))
    }

    @Test
    fun `anything else is the re-assert no-op, never a toggle`() {
        // A repeat startForegroundService from the controller carries no action; treating an
        // unknown action as a mute toggle would flip the microphone on notification refreshes.
        assertEquals(StreamingCommand.REASSERT, streamingCommandFor(null))
        assertEquals(StreamingCommand.REASSERT, streamingCommandFor(""))
        assertEquals(StreamingCommand.REASSERT, streamingCommandFor("com.tinkernorth.dish.action.SOMETHING_ELSE"))
    }

    @Test
    fun `no armed microphone means no mic line and no mic action`() {
        // A mute control with nothing to mute is exactly the unaccountable-microphone
        // impression the service types work to avoid.
        assertNull(micNotificationUiFor(MicIndicatorState.HIDDEN))
    }

    @Test
    fun `a live microphone offers mute and says it is live`() {
        val ui = micNotificationUiFor(MicIndicatorState.LIVE)
        assertEquals(R.string.mic_state_live, ui?.stateRes)
        assertEquals(R.string.mic_action_mute, ui?.actionRes)
        assertEquals("the icon shows where the tap leads", R.drawable.ic_mic_off, ui?.actionIconRes)
    }

    @Test
    fun `a muted microphone offers unmute and says it is muted`() {
        val ui = micNotificationUiFor(MicIndicatorState.MUTED)
        assertEquals(R.string.mic_state_muted, ui?.stateRes)
        assertEquals(R.string.mic_action_unmute, ui?.actionRes)
        assertEquals(R.drawable.ic_mic, ui?.actionIconRes)
    }
}
