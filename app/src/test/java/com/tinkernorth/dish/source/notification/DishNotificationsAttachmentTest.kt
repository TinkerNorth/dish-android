// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.notification

import android.view.View
import com.tinkernorth.dish.architecture.testing.TestLifecycleOwner
import io.mockk.mockk
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DishNotificationsAttachmentTest {
    // repeatOnLifecycle needs Dispatchers.Main wired or attach calls fail immediately.
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
    fun `posts before the owner is resumed are not rendered`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            attach(notifications, owner, renderer, this)

            notifications.info(title = "early")
            testScheduler.runCurrent()

            assertTrue("Renderer should not be called before STARTED", renderer.shown.isEmpty())
        }

    @Test
    fun `posts while the owner is resumed are rendered`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            notifications.error(title = "boom")
            testScheduler.runCurrent()

            assertEquals(1, renderer.shown.size)
            assertEquals("boom", renderer.shown[0].notification.title)
        }

    @Test
    fun `posts after STOPPED are not rendered`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            attach(notifications, owner, renderer, this)

            owner.resume()
            testScheduler.runCurrent()
            notifications.info(title = "while-started")
            testScheduler.runCurrent()

            owner.stop()
            testScheduler.runCurrent()
            notifications.info(title = "after-stopped")
            testScheduler.runCurrent()

            assertEquals(
                listOf("while-started"),
                renderer.shown.map { it.notification.title },
            )
        }

    @Test
    fun `restart after stop does not replay the missed posts (replay=0)`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            attach(notifications, owner, renderer, this)

            owner.resume()
            testScheduler.runCurrent()
            owner.stop()
            testScheduler.runCurrent()
            notifications.info(title = "between")
            testScheduler.runCurrent()

            owner.resume()
            testScheduler.runCurrent()
            notifications.info(title = "after-restart")
            testScheduler.runCurrent()

            assertEquals(
                listOf("after-restart"),
                renderer.shown.map { it.notification.title },
            )
        }

    @Test
    fun `default anchor is null`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            val attachment = attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            notifications.info(title = "x")
            testScheduler.runCurrent()

            assertNull(renderer.shown[0].anchor)
            assertNull(attachment.anchorView)
        }

    @Test
    fun `setting anchorView affects subsequent posts but not prior ones`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            val attachment = attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            notifications.info(title = "first")
            testScheduler.runCurrent()

            val anchor = mockk<View>()
            attachment.anchorView = anchor
            notifications.info(title = "second")
            testScheduler.runCurrent()

            assertNull(renderer.shown[0].anchor)
            assertSame(anchor, renderer.shown[1].anchor)
        }

    @Test
    fun `clearing anchorView reverts subsequent posts to no-anchor`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            val attachment = attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            val anchor = mockk<View>()
            attachment.anchorView = anchor
            notifications.info(title = "anchored")
            testScheduler.runCurrent()

            attachment.anchorView = null
            notifications.info(title = "free")
            testScheduler.runCurrent()

            assertSame(anchor, renderer.shown[0].anchor)
            assertNull(renderer.shown[1].anchor)
        }

    @Test
    fun `dismiss dismisses the handle for that id`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            val attachment = attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            val id = notifications.info(title = "x")
            testScheduler.runCurrent()
            val handle = renderer.handles.first { it.id == id }
            assertFalse(handle.dismissed)

            notifications.dismiss(id)
            testScheduler.runCurrent()

            assertTrue(handle.dismissed)
            assertEquals(0, attachment.liveById())
        }

    @Test
    fun `dismiss of unknown id is a no-op`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            val attachment = attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            notifications.info(title = "live")
            testScheduler.runCurrent()
            val liveCountBefore = attachment.liveById()

            notifications.dismiss(9999L)
            testScheduler.runCurrent()

            assertEquals(
                liveCountBefore,
                attachment.liveById(),
            )
        }

    @Test
    fun `dismiss after handle already gone via same-key replace is a no-op`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            val firstId = notifications.info(title = "v1", key = "k")
            testScheduler.runCurrent()
            notifications.info(title = "v2", key = "k")
            testScheduler.runCurrent()
            val firstHandle = renderer.handles.first { it.id == firstId }
            assertTrue(firstHandle.dismissed)

            notifications.dismiss(firstId)
            testScheduler.runCurrent()

            val secondHandle = renderer.handles.last()
            assertFalse(secondHandle.dismissed)
        }

    @Test
    fun `same-key post dismisses the prior handle before showing the new one`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            val attachment = attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            notifications.warn(title = "v1", key = "bt-off")
            testScheduler.runCurrent()
            val first = renderer.handles.last()
            assertFalse(first.dismissed)

            notifications.warn(title = "v2", key = "bt-off")
            testScheduler.runCurrent()

            assertTrue(first.dismissed)
            assertEquals(
                1,
                attachment.liveById(),
            )
            assertEquals(1, attachment.liveByKey())
        }

    @Test
    fun `different-key posts do not dismiss each other`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            val attachment = attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            notifications.warn(title = "x", key = "a")
            notifications.warn(title = "y", key = "b")
            testScheduler.runCurrent()

            assertEquals(2, attachment.liveById())
            assertEquals(2, attachment.liveByKey())
            assertTrue(renderer.handles.none { it.dismissed })
        }

    @Test
    fun `null-key posts never dedupe each other`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            val attachment = attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            notifications.info(title = "a")
            notifications.info(title = "b")
            notifications.info(title = "c")
            testScheduler.runCurrent()

            assertEquals(3, attachment.liveById())
            assertEquals(
                0,
                attachment.liveByKey(),
            )
        }

    @Test
    fun `dismiss removes the byKey entry too`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            val attachment = attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            val id = notifications.info(title = "x", key = "the-key")
            testScheduler.runCurrent()
            assertEquals(1, attachment.liveByKey())

            notifications.dismiss(id)
            testScheduler.runCurrent()

            assertEquals(0, attachment.liveByKey())
            assertEquals(0, attachment.liveById())
        }

    @Test
    fun `internal handle ids are distinct across posts`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            notifications.info(title = "a")
            notifications.info(title = "b")
            notifications.info(title = "c")
            testScheduler.runCurrent()

            val ids = renderer.handles.map { it.id }
            assertEquals(3, ids.size)
            assertEquals(3, ids.distinct().size)
        }

    @Test
    fun `onDestroy dismisses every live handle`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            val attachment = attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            notifications.info(title = "a")
            notifications.info(title = "b", key = "kk")
            notifications.info(title = "c")
            testScheduler.runCurrent()
            assertEquals(3, attachment.liveById())

            owner.destroy()
            testScheduler.runCurrent()

            assertEquals(0, attachment.liveById())
            assertEquals(0, attachment.liveByKey())
            assertTrue(
                renderer.handles.all { it.dismissed },
            )
        }

    @Test
    fun `post after onDestroy does not render`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            owner.destroy()
            testScheduler.runCurrent()
            val priorShown = renderer.shown.size

            notifications.error(title = "post-destroy")
            testScheduler.runCurrent()

            assertEquals(
                priorShown,
                renderer.shown.size,
            )
        }

    @Test
    fun `posts render in the order they were posted`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            val ids =
                (1..5).map {
                    notifications.info(title = "n$it")
                }
            testScheduler.runCurrent()

            assertEquals(ids, renderer.shownIds)
        }

    @Test
    fun `dismiss does not change other handles' lifecycle`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            val a = notifications.info(title = "a")
            val b = notifications.info(title = "b")
            val c = notifications.info(title = "c")
            testScheduler.runCurrent()

            notifications.dismiss(b)
            testScheduler.runCurrent()

            val handleA = renderer.handles.first { it.id == a }
            val handleB = renderer.handles.first { it.id == b }
            val handleC = renderer.handles.first { it.id == c }
            assertFalse(handleA.dismissed)
            assertTrue(handleB.dismissed)
            assertFalse(handleC.dismissed)
        }

    @Test
    fun `same-key replace assigns a new id (does not reuse the prior one)`() =
        runTest {
            val notifications = DishNotifications()
            val owner = TestLifecycleOwner()
            val renderer = RecordingRenderer()
            attach(notifications, owner, renderer, this)
            owner.resume()
            testScheduler.runCurrent()

            val first = notifications.warn(title = "x", key = "k")
            val second = notifications.warn(title = "y", key = "k")
            assertNotEquals(first, second)
        }
}
