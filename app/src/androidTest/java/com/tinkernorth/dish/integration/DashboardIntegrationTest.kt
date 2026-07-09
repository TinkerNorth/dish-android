// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.integration

import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tinkernorth.dish.R
import com.tinkernorth.dish.integration.AppSingletons.await
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.ui.main.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end through the real MainActivity + ViewModel + connection stack:
 * pair against an in-process satellite, then assert the dashboard's own
 * views reflect the live session. Verifies the app's state actually flows
 * to the UI, not just that the manager reports Live.
 */
@RunWith(AndroidJUnit4::class)
class DashboardIntegrationTest {
    private val manager get() = AppSingletons.satellite
    private var fake: FakeSatellite? = null

    @Before
    fun setUp() {
        AppSingletons.resetConnections()
    }

    @After
    fun tearDown() {
        AppSingletons.resetConnections()
        fake?.close()
        fake = null
    }

    @Test
    fun liveSession_showsOneOfOneOnline_onTheDashboard() {
        val satellite = FakeSatellite().also { fake = it }
        val server = satellite.server(name = "Fake Satellite")
        manager.pairWithPin(server, "1234")
        assertTrue(
            await { manager.get(SatelliteConnection.idFor(server))?.state?.value == SatelliteSessionState.Live },
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val reflected =
                await(timeoutMs = 25_000) { scenario.summaryText().contains("1 of 1", ignoreCase = true) }
            assertTrue(
                "connections summary should report the live link, was \"${scenario.summaryText()}\"",
                reflected,
            )
            scenario.onActivity { activity ->
                val controllers = activity.findViewById<android.view.View>(R.id.rvControllers)
                assertTrue("controller list must be present", controllers != null)
            }
        }
    }

    @Test
    fun noConnections_showsTheEmptyPrompt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val prompt =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext
                    .getString(R.string.status_tap_manage)
            assertTrue(
                "empty dashboard should invite adding a connection",
                await { scenario.summaryText() == prompt },
            )
        }
    }

    private fun ActivityScenario<MainActivity>.summaryText(): String {
        var text = ""
        onActivity { activity ->
            text =
                activity
                    .findViewById<TextView>(R.id.tvConnectionsSummary)
                    ?.text
                    ?.toString()
                    .orEmpty()
        }
        return text
    }
}
