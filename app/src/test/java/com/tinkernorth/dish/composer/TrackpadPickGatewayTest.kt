// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.repository.TrackpadPick
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrackpadPickGatewayTest {
    @Test
    fun `every pick the composer derives reaches its device's native router, and again when it moves`() =
        runTest {
            val picks = MutableStateFlow(mapOf(-1000 to TrackpadPick.STICK, -1001 to TrackpadPick.PAD))
            val composer: CapabilityComposer = mockk { every { trackpadPicks } returns picks }
            val native: PhysicalInputNative = mockk { justRun { setTrackpadPick(any(), any()) } }
            TrackpadPickGateway(composer, native, backgroundScope)
            testScheduler.runCurrent()
            verify(exactly = 1) { native.setTrackpadPick(-1000, TrackpadPick.STICK) }
            verify(exactly = 1) { native.setTrackpadPick(-1001, TrackpadPick.PAD) }

            picks.value = mapOf(-1000 to TrackpadPick.MOUSE)
            testScheduler.runCurrent()
            verify(exactly = 1) { native.setTrackpadPick(-1000, TrackpadPick.MOUSE) }
        }
}
