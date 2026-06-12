// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.notification

import com.tinkernorth.dish.architecture.testing.TestLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DishNotificationsTransitionTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun attach(
        notifications: DishNotifications,
        owner: TestLifecycleOwner,
        renderer: RecordingRenderer,
        scope: TestScope,
    ): DishNotifications.Attachment =
        notifications.attachWithRenderer(
            owner = owner,
            renderer = renderer,
            scope = scope.backgroundScope,
        )

    @Test
    fun `two STARTED attachments both render the same post`() =
        runTest {
            val notifications = DishNotifications()
            val ownerA = TestLifecycleOwner()
            val rendererA = RecordingRenderer()
            attach(notifications, ownerA, rendererA, this)
            val ownerB = TestLifecycleOwner()
            val rendererB = RecordingRenderer()
            attach(notifications, ownerB, rendererB, this)

            ownerA.start()
            ownerB.start()
            testScheduler.runCurrent()

            notifications.info(title = "fanout")
            testScheduler.runCurrent()

            assertEquals(1, rendererA.shown.size)
            assertEquals(1, rendererB.shown.size)
        }

    @Test
    fun `after A stops and B starts, posts route only to B`() =
        runTest {
            val notifications = DishNotifications()
            val ownerA = TestLifecycleOwner()
            val rendererA = RecordingRenderer()
            attach(notifications, ownerA, rendererA, this)

            ownerA.start()
            testScheduler.runCurrent()
            notifications.info(title = "on-a")
            testScheduler.runCurrent()
            assertEquals(1, rendererA.shown.size)

            ownerA.stop()
            testScheduler.runCurrent()

            val ownerB = TestLifecycleOwner()
            val rendererB = RecordingRenderer()
            attach(notifications, ownerB, rendererB, this)
            ownerB.start()
            testScheduler.runCurrent()

            notifications.error(title = "on-b")
            testScheduler.runCurrent()

            assertEquals(1, rendererA.shown.size)
            assertEquals("on-a", rendererA.shown[0].notification.title)
            assertEquals(1, rendererB.shown.size)
            assertEquals("on-b", rendererB.shown[0].notification.title)
        }

    @Test
    fun `posts in the transition gap are dropped (replay=0)`() =
        runTest {
            val notifications = DishNotifications()
            val ownerA = TestLifecycleOwner()
            val rendererA = RecordingRenderer()
            attach(notifications, ownerA, rendererA, this)
            ownerA.start()
            testScheduler.runCurrent()

            ownerA.stop()
            testScheduler.runCurrent()

            notifications.error(title = "gap")
            testScheduler.runCurrent()

            val ownerB = TestLifecycleOwner()
            val rendererB = RecordingRenderer()
            attach(notifications, ownerB, rendererB, this)
            ownerB.start()
            testScheduler.runCurrent()

            assertTrue(
                rendererA.shown.isEmpty(),
            )
            assertTrue(
                rendererB.shown.isEmpty(),
            )
        }

    @Test
    fun `destroying A does not dismiss handles owned by B`() =
        runTest {
            val notifications = DishNotifications()
            val ownerA = TestLifecycleOwner()
            val rendererA = RecordingRenderer()
            val attachmentA = attach(notifications, ownerA, rendererA, this)
            val ownerB = TestLifecycleOwner()
            val rendererB = RecordingRenderer()
            val attachmentB = attach(notifications, ownerB, rendererB, this)

            ownerA.start()
            ownerB.start()
            testScheduler.runCurrent()
            notifications.info(title = "shared")
            testScheduler.runCurrent()

            assertEquals(1, attachmentA.liveById())
            assertEquals(1, attachmentB.liveById())
            val handleA = rendererA.handles.last()
            val handleB = rendererB.handles.last()

            ownerA.destroy()
            testScheduler.runCurrent()

            assertTrue(handleA.dismissed)
            assertFalse(handleB.dismissed)
            assertEquals(0, attachmentA.liveById())
            assertEquals(1, attachmentB.liveById())
        }

    @Test
    fun `same-key dedup is per-attachment - A's key does not affect B`() =
        runTest {
            val notifications = DishNotifications()
            val ownerA = TestLifecycleOwner()
            val rendererA = RecordingRenderer()
            val attachmentA = attach(notifications, ownerA, rendererA, this)
            val ownerB = TestLifecycleOwner()
            val rendererB = RecordingRenderer()
            val attachmentB = attach(notifications, ownerB, rendererB, this)

            ownerA.start()
            ownerB.start()
            testScheduler.runCurrent()

            notifications.warn(title = "v1", key = "shared-key")
            testScheduler.runCurrent()
            val a1 = rendererA.handles.last()
            val b1 = rendererB.handles.last()

            notifications.warn(title = "v2", key = "shared-key")
            testScheduler.runCurrent()

            assertTrue(a1.dismissed)
            assertTrue(b1.dismissed)
            assertEquals(1, attachmentA.liveById())
            assertEquals(1, attachmentB.liveByKey())
        }

    @Test
    fun `dismiss(id) propagates to every attachment that has the live handle`() =
        runTest {
            val notifications = DishNotifications()
            val ownerA = TestLifecycleOwner()
            val rendererA = RecordingRenderer()
            val attachmentA = attach(notifications, ownerA, rendererA, this)
            val ownerB = TestLifecycleOwner()
            val rendererB = RecordingRenderer()
            val attachmentB = attach(notifications, ownerB, rendererB, this)

            ownerA.start()
            ownerB.start()
            testScheduler.runCurrent()

            val id = notifications.info(title = "x")
            testScheduler.runCurrent()
            assertEquals(1, attachmentA.liveById())
            assertEquals(1, attachmentB.liveById())

            notifications.dismiss(id)
            testScheduler.runCurrent()

            assertEquals(0, attachmentA.liveById())
            assertEquals(0, attachmentB.liveById())
            assertTrue(rendererA.handles.last().dismissed)
            assertTrue(rendererB.handles.last().dismissed)
        }

    @Test
    fun `re-attaching to a fresh owner after destroy starts a clean slate`() =
        runTest {
            val notifications = DishNotifications()
            val ownerA1 = TestLifecycleOwner()
            val rendererA1 = RecordingRenderer()
            val attachmentA1 = attach(notifications, ownerA1, rendererA1, this)

            ownerA1.start()
            testScheduler.runCurrent()
            notifications.info(title = "a")
            testScheduler.runCurrent()
            assertEquals(1, attachmentA1.liveById())
            ownerA1.destroy()
            testScheduler.runCurrent()

            val ownerA2 = TestLifecycleOwner()
            val rendererA2 = RecordingRenderer()
            val attachmentA2 = attach(notifications, ownerA2, rendererA2, this)
            ownerA2.start()
            testScheduler.runCurrent()

            assertEquals(0, attachmentA2.liveById())
            assertTrue(rendererA2.shown.isEmpty())

            notifications.info(title = "b")
            testScheduler.runCurrent()
            assertEquals(1, attachmentA2.liveById())
            assertEquals(
                "b",
                rendererA2.shown
                    .last()
                    .notification.title,
            )
        }
}
