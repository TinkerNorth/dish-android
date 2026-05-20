// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DishNotificationQueue]. The queue is the single funnel
 * every native-Toast replacement routes through, so its semantics are
 * load-bearing for the whole notification UX:
 *
 *  - `post()` assigns a monotonic id and emits onto `posts` so any
 *    foreground [DishNotificationHost] can render.
 *  - `dismiss(id)` emits on `dismissals` so the host can animate the
 *    matching banner out.
 *  - Convenience builders ([info], [success], [warn], [error]) thread
 *    severity-appropriate defaults — INFO/SUCCESS auto-dismiss, WARN/ERROR
 *    persist until user-dismissed.
 *  - `key`-based de-duplication is the responsibility of the host (not the
 *    queue); the queue itself doesn't drop emissions, but consumer code
 *    relies on `key` being preserved across `post()` calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DishNotificationQueueTest {
    private lateinit var queue: DishNotificationQueue
    private lateinit var scope: TestScope

    @Before
    fun setUp() {
        queue = DishNotificationQueue()
        scope = TestScope(StandardTestDispatcher())
    }

    // ── id allocation ────────────────────────────────────────────────────

    @Test
    fun `post returns a monotonically increasing id`() {
        val first = queue.post(title = "first")
        val second = queue.post(title = "second")
        val third = queue.post(title = "third")
        assertTrue(second > first)
        assertTrue(third > second)
    }

    @Test
    fun `every post gets a distinct id even with identical content`() {
        val a = queue.post(title = "same")
        val b = queue.post(title = "same")
        assertNotEquals(a, b)
    }

    // ── post / posts emission ────────────────────────────────────────────

    @Test
    fun `post emits the notification on the posts flow`() =
        runTest(scope.testScheduler) {
            // Replay = 1, so a single post observable to a later subscriber.
            queue.post(title = "hello", body = "world")
            val received = queue.posts.first()
            assertEquals("hello", received.title)
            assertEquals("world", received.body)
        }

    @Test
    fun `multiple subscribers each see the post emission`() =
        runTest(scope.testScheduler) {
            val received = mutableListOf<String>()
            val a = scope.launch { received += "a:" + queue.posts.first().title }
            val b = scope.launch { received += "b:" + queue.posts.first().title }
            scope.testScheduler.runCurrent()
            queue.post(title = "broadcast")
            scope.testScheduler.advanceUntilIdle()
            a.join()
            b.join()
            assertTrue("both subscribers received: $received", received.contains("a:broadcast"))
            assertTrue(received.contains("b:broadcast"))
        }

    // ── dismiss / dismissals emission ────────────────────────────────────

    @Test
    fun `dismiss emits the id on the dismissals flow`() =
        runTest(scope.testScheduler) {
            val id = queue.post(title = "to-dismiss")
            val collected = mutableListOf<Long>()
            val collector = scope.launch { queue.dismissals.toList(collected) }
            scope.testScheduler.runCurrent()

            queue.dismiss(id)

            scope.testScheduler.runCurrent()
            collector.cancel()
            assertTrue("dismissal for $id should be emitted: $collected", id in collected)
        }

    // ── Convenience builders: severity ───────────────────────────────────

    @Test
    fun `info builder sets severity to INFO`() =
        runTest(scope.testScheduler) {
            queue.info(title = "i")
            assertEquals(DishNotification.Severity.INFO, queue.posts.first().severity)
        }

    @Test
    fun `success builder sets severity to SUCCESS`() =
        runTest(scope.testScheduler) {
            queue.success(title = "s")
            assertEquals(DishNotification.Severity.SUCCESS, queue.posts.first().severity)
        }

    @Test
    fun `warn builder sets severity to WARN`() =
        runTest(scope.testScheduler) {
            queue.warn(title = "w")
            assertEquals(DishNotification.Severity.WARN, queue.posts.first().severity)
        }

    @Test
    fun `error builder sets severity to ERROR`() =
        runTest(scope.testScheduler) {
            queue.error(title = "e")
            assertEquals(DishNotification.Severity.ERROR, queue.posts.first().severity)
        }

    // ── Convenience builders: default duration ───────────────────────────

    @Test
    fun `info auto-dismisses with DURATION_SHORT default`() =
        runTest(scope.testScheduler) {
            queue.info(title = "i")
            assertEquals(DishNotification.DURATION_SHORT, queue.posts.first().durationMs)
        }

    @Test
    fun `success auto-dismisses with DURATION_SHORT default`() =
        runTest(scope.testScheduler) {
            queue.success(title = "s")
            assertEquals(DishNotification.DURATION_SHORT, queue.posts.first().durationMs)
        }

    @Test
    fun `warn is persistent by default`() =
        runTest(scope.testScheduler) {
            queue.warn(title = "w")
            assertEquals(DishNotification.DURATION_PERSISTENT, queue.posts.first().durationMs)
        }

    @Test
    fun `error is persistent by default`() =
        runTest(scope.testScheduler) {
            queue.error(title = "e")
            assertEquals(DishNotification.DURATION_PERSISTENT, queue.posts.first().durationMs)
        }

    // ── Body / glyph / action / key propagation ──────────────────────────

    @Test
    fun `optional fields are propagated through the builder`() =
        runTest(scope.testScheduler) {
            queue.warn(
                title = "T",
                body = "B",
                glyph = 0x123,
                action = DishNotification.Action(label = "RETRY", handler = {}),
                key = "k",
            )
            val n = queue.posts.first()
            assertEquals("B", n.body)
            assertEquals(0x123, n.glyph)
            assertEquals("RETRY", n.action?.label)
            assertEquals("k", n.key)
        }

    @Test
    fun `optional fields default to null when not specified`() =
        runTest(scope.testScheduler) {
            queue.info(title = "T")
            val n = queue.posts.first()
            assertNull(n.body)
            assertNull(n.glyph)
            assertNull(n.action)
            assertNull(n.key)
        }

    // ── Buffer behaviour (DROP_OLDEST, replay=1) ─────────────────────────

    @Test
    fun `late subscriber receives the most recent post via replay`() =
        runTest(scope.testScheduler) {
            queue.post(title = "first")
            queue.post(title = "second")
            queue.post(title = "third")
            // No active subscriber at post time; replay=1 means the LATEST
            // emission survives for the next subscriber to read.
            val replayed = queue.posts.first()
            assertEquals("third", replayed.title)
        }

    // ── Action handler invocation isn't tested here — it's the host's job ─

    @Test
    fun `Action data class carries its label and handler verbatim`() {
        var fired = false
        val action = DishNotification.Action(label = "GO", handler = { fired = true })
        assertEquals("GO", action.label)
        assertFalse(fired)
        action.handler()
        assertTrue(fired)
    }
}
