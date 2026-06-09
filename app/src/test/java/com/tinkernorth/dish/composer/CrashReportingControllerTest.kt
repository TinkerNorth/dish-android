// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.tinkernorth.dish.architecture.testing.probe
import com.tinkernorth.dish.source.store.CrashReportingStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrashReportingControllerTest {
    private lateinit var context: Context
    private lateinit var store: CrashReportingStore
    private lateinit var scope: TestScope

    private val enabledFlow = MutableStateFlow(false)

    @Before
    fun setUp() {
        mockkStatic(Log::class, FirebaseApp::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        // Drives apply down the "Firebase not initialised" branch: no real Crashlytics on the JVM
        // classpath, yet getApps(context) still proves apply ran for each emission.
        every { FirebaseApp.getApps(any()) } returns emptyList()

        context = mockk(relaxed = true)
        enabledFlow.value = false
        store = mockk(relaxed = true) { every { state } returns enabledFlow }
        scope = TestScope(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class, FirebaseApp::class)
    }

    private fun controller() = CrashReportingController(context, store, scope)

    @Test
    fun `opt-in flips propagate to apply on each emission`() {
        val probe = controller().probe()
        probe.start()
        scope.testScheduler.runCurrent()
        verify(exactly = 1) { FirebaseApp.getApps(context) }

        enabledFlow.value = true
        scope.testScheduler.runCurrent()
        verify(exactly = 2) { FirebaseApp.getApps(context) }
    }

    @Test
    fun `onStop does not cancel the collection so a later emission still applies`() {
        val probe = controller().probe()
        probe.start()
        scope.testScheduler.runCurrent()
        verify(exactly = 1) { FirebaseApp.getApps(context) }

        probe.stop()
        scope.testScheduler.runCurrent()

        // Process-scoped controller keeps collecting after ON_STOP, so the flip must still reach apply.
        enabledFlow.value = true
        scope.testScheduler.runCurrent()
        verify(exactly = 2) { FirebaseApp.getApps(context) }
    }
}
