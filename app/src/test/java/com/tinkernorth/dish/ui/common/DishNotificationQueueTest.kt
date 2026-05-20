// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
 *    foreground [DishSnackbarController] can render.
 *  - `dismiss(id)` emits on `dismissals` so the host can animate the
 *    matching banner out.
 *  - Convenience builders ([info], [success], [warn], [error]) thread
 *    severity-appropriate defaults — every severity auto-dismisses by
 *    default; persistent banners are explicit opt-in via `durationMs`.
 *  - One-shot semantics: replay=0, so an activity-switch never re-shows
 *    a banner that has already fired.
 *
 * Test pattern: subscribe BEFORE posting (replay=0 means a post emitted
 * to no subscriber is gone). Each test launches a collector into the
 * TestScope, runs the scheduler, posts, runs again, asserts, cancels.
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

    /**
     * Subscribe, run pending scheduling so the collector is active, post
     * via [emit], run again so the emission lands, then cancel. Returns
     * the list of received notifications.
     */
    private fun TestScope.captureOne(emit: () -> Unit): DishNotification {
        val collected = mutableListOf<DishNotification>()
        val collector: Job = launch { queue.posts.toList(collected) }
        testScheduler.runCurrent()
        emit()
        testScheduler.runCurrent()
        collector.cancel()
        return collected.single()
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
            val received = scope.captureOne { queue.post(title = "hello", body = "world") }
            assertEquals("hello", received.title)
            assertEquals("world", received.body)
        }

    @Test
    fun `multiple subscribers each see the post emission`() =
        runTest(scope.testScheduler) {
            val a = mutableListOf<DishNotification>()
            val b = mutableListOf<DishNotification>()
            val cA = scope.launch { queue.posts.toList(a) }
            val cB = scope.launch { queue.posts.toList(b) }
            scope.testScheduler.runCurrent()
            queue.post(title = "broadcast")
            scope.testScheduler.runCurrent()
            cA.cancel()
            cB.cancel()
            assertEquals("broadcast", a.single().title)
            assertEquals("broadcast", b.single().title)
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
            val n = scope.captureOne { queue.info(title = "i") }
            assertEquals(DishNotification.Severity.INFO, n.severity)
        }

    @Test
    fun `success builder sets severity to SUCCESS`() =
        runTest(scope.testScheduler) {
            val n = scope.captureOne { queue.success(title = "s") }
            assertEquals(DishNotification.Severity.SUCCESS, n.severity)
        }

    @Test
    fun `warn builder sets severity to WARN`() =
        runTest(scope.testScheduler) {
            val n = scope.captureOne { queue.warn(title = "w") }
            assertEquals(DishNotification.Severity.WARN, n.severity)
        }

    @Test
    fun `error builder sets severity to ERROR`() =
        runTest(scope.testScheduler) {
            val n = scope.captureOne { queue.error(title = "e") }
            assertEquals(DishNotification.Severity.ERROR, n.severity)
        }

    // ── Convenience builders: default duration ───────────────────────────

    @Test
    fun `info auto-dismisses with DURATION_SHORT default`() =
        runTest(scope.testScheduler) {
            val n = scope.captureOne { queue.info(title = "i") }
            assertEquals(DishNotification.DURATION_SHORT, n.durationMs)
        }

    @Test
    fun `success auto-dismisses with DURATION_SHORT default`() =
        runTest(scope.testScheduler) {
            val n = scope.captureOne { queue.success(title = "s") }
            assertEquals(DishNotification.DURATION_SHORT, n.durationMs)
        }

    @Test
    fun `warn auto-dismisses with DURATION_LONG default`() =
        runTest(scope.testScheduler) {
            // Persistent banners must be explicit opt-in. The previous
            // "WARN/ERROR are persistent" default caused transient failures
            // like "satellite isn't responding" to stay on screen forever.
            val n = scope.captureOne { queue.warn(title = "w") }
            assertEquals(DishNotification.DURATION_LONG, n.durationMs)
        }

    @Test
    fun `error auto-dismisses with DURATION_LONG default`() =
        runTest(scope.testScheduler) {
            val n = scope.captureOne { queue.error(title = "e") }
            assertEquals(DishNotification.DURATION_LONG, n.durationMs)
        }

    @Test
    fun `warn can opt in to persistent for state-stuck conditions`() =
        runTest(scope.testScheduler) {
            val n =
                scope.captureOne {
                    queue.warn(title = "w", durationMs = DishNotification.DURATION_PERSISTENT)
                }
            assertEquals(DishNotification.DURATION_PERSISTENT, n.durationMs)
        }

    // ── Body / glyph / action / key propagation ──────────────────────────

    @Test
    fun `optional fields are propagated through the builder`() =
        runTest(scope.testScheduler) {
            val n =
                scope.captureOne {
                    queue.warn(
                        title = "T",
                        body = "B",
                        glyph = 0x123,
                        action = DishNotification.Action(label = "RETRY", handler = {}),
                        key = "k",
                    )
                }
            assertEquals("B", n.body)
            assertEquals(0x123, n.glyph)
            assertEquals("RETRY", n.action?.label)
            assertEquals("k", n.key)
        }

    @Test
    fun `optional fields default to null when not specified`() =
        runTest(scope.testScheduler) {
            val n = scope.captureOne { queue.info(title = "T") }
            assertNull(n.body)
            assertNull(n.glyph)
            assertNull(n.action)
            assertNull(n.key)
        }

    // ── Buffer behaviour (DROP_OLDEST, replay=0) ─────────────────────────

    @Test
    fun `late subscriber sees no replay of prior posts`() =
        runTest(scope.testScheduler) {
            // One-shot semantics: posts emitted before a subscriber attaches
            // are NOT replayed when one binds later. This is the fix for the
            // "banner re-shows on every activity switch" bug — replay=1
            // turned every activity transition into a ricochet of the most
            // recent emission.
            queue.post(title = "first")
            queue.post(title = "second")
            queue.post(title = "third")
            val collected = mutableListOf<DishNotification>()
            val collector = scope.launch { queue.posts.toList(collected) }
            scope.testScheduler.runCurrent()
            // Emit one more after subscription — only this one should reach.
            queue.post(title = "fourth")
            scope.testScheduler.runCurrent()
            collector.cancel()
            assertEquals(listOf("fourth"), collected.map { it.title })
        }

    @Test
    fun `posts emitted to an active subscriber are delivered in order`() =
        runTest(scope.testScheduler) {
            val collected = mutableListOf<DishNotification>()
            val collector = scope.launch { queue.posts.toList(collected) }
            scope.testScheduler.runCurrent()
            queue.post(title = "a")
            queue.post(title = "b")
            queue.post(title = "c")
            scope.testScheduler.runCurrent()
            collector.cancel()
            assertEquals(listOf("a", "b", "c"), collected.map { it.title })
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
