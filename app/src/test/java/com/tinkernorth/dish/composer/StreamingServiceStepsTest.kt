// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingServiceStepsTest {
    @Test
    fun `every plain start intent is answered with a promotion`() {
        assertEquals(listOf(ServiceStep.PROMOTE), serviceStepsFor(StreamingCommand.REASSERT))
    }

    @Test
    fun `an unknown or missing action still promotes`() {
        for (action in listOf(null, "", "com.tinkernorth.dish.action.SOMETHING_ELSE")) {
            assertTrue(action.toString(), ServiceStep.PROMOTE in serviceStepsFor(streamingCommandFor(action)))
        }
    }

    @Test
    fun `stop-all tears sessions and claims down before the service itself`() {
        assertEquals(
            listOf(ServiceStep.STOP_SESSIONS, ServiceStep.RELEASE_DIRECT, ServiceStep.STOP_SELF),
            serviceStepsFor(StreamingCommand.STOP_ALL),
        )
    }

    @Test
    fun `the mic toggle neither stops nor re-promotes`() {
        assertEquals(listOf(ServiceStep.TOGGLE_MIC), serviceStepsFor(StreamingCommand.TOGGLE_MIC))
    }

    @Test
    fun `no command both promotes and stops`() {
        for (command in StreamingCommand.entries) {
            val steps = serviceStepsFor(command)
            assertFalse(command.name, ServiceStep.PROMOTE in steps && ServiceStep.STOP_SELF in steps)
        }
    }
}
