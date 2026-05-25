// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.notification

import app.cash.turbine.test
import com.tinkernorth.dish.core.model.DishNotification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DishNotificationsApiTest {
    @Test
    fun `post returns monotonically increasing ids starting from 1`() {
        val notifications = DishNotifications()
        val id1 = notifications.post(title = "a")
        val id2 = notifications.post(title = "b")
        val id3 = notifications.post(title = "c")
        assertEquals(1L, id1)
        assertEquals(2L, id2)
        assertEquals(3L, id3)
    }

    @Test
    fun `distinct posts get distinct ids even when same title`() {
        val notifications = DishNotifications()
        val a = notifications.post(title = "same")
        val b = notifications.post(title = "same")
        assertNotEquals(a, b)
    }

    @Test
    fun `posts emits the notification with all fields populated`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                val id =
                    notifications.post(
                        severity = DishNotification.Severity.ERROR,
                        title = "Server unreachable",
                        body = "192.168.1.5",
                        key = "net:err",
                        durationMs = DishNotification.DURATION_PERSISTENT,
                    )
                val emitted = awaitItem()
                assertEquals(id, emitted.id)
                assertEquals(DishNotification.Severity.ERROR, emitted.severity)
                assertEquals("Server unreachable", emitted.title)
                assertEquals("192.168.1.5", emitted.body)
                assertEquals("net:err", emitted.key)
                assertEquals(DishNotification.DURATION_PERSISTENT, emitted.durationMs)
                assertNull(emitted.action)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `posts emits action when supplied`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                notifications.post(
                    title = "Reconnect?",
                    action =
                        DishNotification.Action(
                            label = "RETRY",
                            handler = { },
                        ),
                )
                val emitted = awaitItem()
                assertEquals("RETRY", emitted.action?.label)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `posts has replay=0 — late subscriber sees only future emissions`() =
        runTest {
            val notifications = DishNotifications()
            notifications.post(title = "early")
            notifications.posts.test {
                val id = notifications.post(title = "late")
                val seen = awaitItem()
                assertEquals(id, seen.id)
                assertEquals("late", seen.title)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dismiss emits the id on the dismissals flow`() =
        runTest {
            val notifications = DishNotifications()
            notifications.dismissals.test {
                notifications.dismiss(id = 42L)
                assertEquals(42L, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dismiss of an unknown id does not throw and still emits`() =
        runTest {
            val notifications = DishNotifications()
            notifications.dismissals.test {
                notifications.dismiss(id = 9999L)
                assertEquals(9999L, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dismissals has replay=0 — late subscriber misses prior dismiss calls`() =
        runTest {
            val notifications = DishNotifications()
            notifications.dismiss(id = 1L)
            notifications.dismissals.test {
                notifications.dismiss(id = 2L)
                assertEquals(2L, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `info builder posts INFO severity`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                notifications.info(title = "x")
                assertEquals(DishNotification.Severity.INFO, awaitItem().severity)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `success builder posts SUCCESS severity`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                notifications.success(title = "x")
                assertEquals(DishNotification.Severity.SUCCESS, awaitItem().severity)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `warn builder posts WARN severity`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                notifications.warn(title = "x")
                assertEquals(DishNotification.Severity.WARN, awaitItem().severity)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `error builder posts ERROR severity`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                notifications.error(title = "x")
                assertEquals(DishNotification.Severity.ERROR, awaitItem().severity)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `info defaults to DURATION_SHORT`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                notifications.info(title = "x")
                assertEquals(DishNotification.DURATION_SHORT, awaitItem().durationMs)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `success defaults to DURATION_SHORT`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                notifications.success(title = "x")
                assertEquals(DishNotification.DURATION_SHORT, awaitItem().durationMs)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `warn defaults to DURATION_LONG — regression for prior persistent-by-default bug`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                notifications.warn(title = "x")
                val n = awaitItem()
                assertEquals(DishNotification.DURATION_LONG, n.durationMs)
                assertNotEquals(DishNotification.DURATION_PERSISTENT, n.durationMs)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `error defaults to DURATION_LONG`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                notifications.error(title = "x")
                assertEquals(DishNotification.DURATION_LONG, awaitItem().durationMs)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `explicit durationMs overrides severity default`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                notifications.info(title = "x", durationMs = DishNotification.DURATION_PERSISTENT)
                assertEquals(DishNotification.DURATION_PERSISTENT, awaitItem().durationMs)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `post with custom durationMs is respected`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                notifications.post(title = "x", durationMs = 7_500L)
                assertEquals(7_500L, awaitItem().durationMs)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `post propagates body and glyph and key`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                notifications.post(
                    title = "Hello",
                    body = "world",
                    glyph = 0x123,
                    key = "hello-key",
                )
                val n = awaitItem()
                assertEquals("world", n.body)
                assertEquals(0x123, n.glyph)
                assertEquals("hello-key", n.key)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `null key is allowed and propagated`() =
        runTest {
            val notifications = DishNotifications()
            notifications.posts.test {
                notifications.error(title = "no-key")
                assertNull(awaitItem().key)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `burst posts do not block — buffer absorbs them`() {
        val notifications = DishNotifications()
        val ids = (1..100).map { notifications.post(title = "burst-$it") }
        assertEquals(100, ids.size)
        assertEquals(100, ids.distinct().size)
        assertTrue("ids monotonic", ids == ids.sorted())
    }
}
