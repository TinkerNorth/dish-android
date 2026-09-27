// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MicCaptureReadTest {
    // A recorder that answers each read with the next count from [chunks], filling what it gives.
    private class ChunkedRecorder(
        private val chunks: List<Int>,
    ) {
        private var call = 0
        val requests = mutableListOf<Pair<Int, Int>>()

        fun read(
            buffer: ShortArray,
            offset: Int,
            count: Int,
        ): Int {
            requests += offset to count
            val n = chunks[call++]
            for (i in 0 until n) buffer[offset + i] = (offset + i + 1).toShort()
            return n
        }
    }

    @Test
    fun `a whole read fills the window in one call`() {
        val recorder = ChunkedRecorder(listOf(4))
        val out = ShortArray(4)

        assertEquals(4, readWholeWindow(recorder::read, out))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), out)
        assertEquals(listOf(0 to 4), recorder.requests)
    }

    @Test
    fun `a short read is retried until the window is whole`() {
        val recorder = ChunkedRecorder(listOf(1, 2, 1))
        val out = ShortArray(4)

        assertEquals(4, readWholeWindow(recorder::read, out))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), out)
        assertEquals(listOf(0 to 4, 1 to 3, 3 to 1), recorder.requests)
    }

    @Test
    fun `a dead recorder returns the partial count`() {
        val recorder = ChunkedRecorder(listOf(2, 0))
        val out = ShortArray(4)

        assertEquals(2, readWholeWindow(recorder::read, out))
        assertEquals(2, recorder.requests.size)
    }

    @Test
    fun `a recorder error returns the partial count`() {
        val recorder = ChunkedRecorder(listOf(3, -1))
        val out = ShortArray(4)

        assertEquals(3, readWholeWindow(recorder::read, out))
    }

    @Test
    fun `an empty window reads nothing`() {
        val recorder = ChunkedRecorder(emptyList())

        assertEquals(0, readWholeWindow(recorder::read, ShortArray(0)))
        assertEquals(emptyList<Pair<Int, Int>>(), recorder.requests)
    }
}
