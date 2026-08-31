// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PillFlowLayoutTest {
    private val gap = 8

    @Test
    fun `no children means no lines`() {
        assertEquals(emptyList<FlowLine>(), buildFlowLines(emptyList(), 100, gap))
    }

    @Test
    fun `a single child fills one line`() {
        val lines = buildFlowLines(listOf(40 to 20), 100, gap)
        assertEquals(listOf(FlowLine(firstChild = 0, childCount = 1, width = 40, height = 20)), lines)
    }

    @Test
    fun `children that fit stay on one line with gaps between them`() {
        val lines = buildFlowLines(listOf(30 to 20, 30 to 20), 100, gap)
        assertEquals(listOf(FlowLine(firstChild = 0, childCount = 2, width = 68, height = 20)), lines)
    }

    @Test
    fun `an exact fit does not wrap`() {
        val lines = buildFlowLines(listOf(46 to 20, 46 to 20), 100, gap)
        assertEquals(1, lines.size)
        assertEquals(100, lines[0].width)
    }

    @Test
    fun `one pixel too wide wraps the last child to a new line`() {
        val lines = buildFlowLines(listOf(47 to 20, 46 to 20), 100, gap)
        assertEquals(2, lines.size)
        assertEquals(FlowLine(firstChild = 0, childCount = 1, width = 47, height = 20), lines[0])
        assertEquals(FlowLine(firstChild = 1, childCount = 1, width = 46, height = 20), lines[1])
    }

    @Test
    fun `the leading child never pays a gap`() {
        val lines = buildFlowLines(listOf(100 to 20), 100, gap)
        assertEquals(1, lines.size)
        assertEquals(100, lines[0].width)
    }

    @Test
    fun `a child wider than the row still gets its own full line`() {
        val lines = buildFlowLines(listOf(30 to 20, 500 to 20, 30 to 20), 100, gap)
        assertEquals(3, lines.size)
        assertEquals(FlowLine(firstChild = 1, childCount = 1, width = 500, height = 20), lines[1])
    }

    @Test
    fun `line height is the tallest child on that line`() {
        val lines = buildFlowLines(listOf(30 to 10, 30 to 24, 90 to 16), 100, gap)
        assertEquals(2, lines.size)
        assertEquals(24, lines[0].height)
        assertEquals(16, lines[1].height)
    }

    @Test
    fun `wrapping keeps every child exactly once and in order`() {
        val sizes = List(9) { 35 to 20 }
        val lines = buildFlowLines(sizes, 100, gap)
        assertEquals(sizes.size, lines.sumOf { it.childCount })
        var next = 0
        lines.forEach { line ->
            assertEquals(next, line.firstChild)
            next += line.childCount
        }
        assertEquals(sizes.size, next)
    }

    @Test
    fun `every line respects the max width unless a single child forces it`() {
        val sizes = listOf(60 to 20, 50 to 20, 40 to 20, 70 to 20, 20 to 20)
        val lines = buildFlowLines(sizes, 120, gap)
        lines.forEach { line ->
            assertTrue(line.width <= 120 || line.childCount == 1)
        }
    }

    @Test
    fun `zero available width puts each child on its own line`() {
        val lines = buildFlowLines(listOf(10 to 5, 10 to 5, 10 to 5), 0, gap)
        assertEquals(3, lines.size)
        lines.forEach { assertEquals(1, it.childCount) }
    }

    @Test
    fun `four destination-sized chips wrap into two lines on a narrow dialog`() {
        val chip = 96 to 28
        val lines = buildFlowLines(listOf(chip, chip, chip, chip), 220, gap)
        assertEquals(2, lines.size)
        assertEquals(2, lines[0].childCount)
        assertEquals(2, lines[1].childCount)
        assertEquals(200, lines[0].width)
    }

    @Test
    fun `renderable line count is unlimited at zero and clamped by a budget`() {
        assertEquals(3, renderableLineCount(lineCount = 3, fixedLineCount = 0))
        assertEquals(2, renderableLineCount(lineCount = 3, fixedLineCount = 2))
        assertEquals(1, renderableLineCount(lineCount = 1, fixedLineCount = 2))
    }

    @Test
    fun `a fixed budget reserves its full height once anything is visible`() {
        val oneLine = buildFlowLines(listOf(40 to 20), 100, gap)
        // One line of content in a two-line budget still measures two lines tall,
        // so the row's height never follows how many chips happen to show.
        assertEquals(2 * 20 + gap, reservedContentHeight(oneLine, fixedLineCount = 2, gap = gap))
        val threeLines = buildFlowLines(listOf(90 to 20, 90 to 20, 90 to 20), 100, gap)
        assertEquals(2 * 20 + gap, reservedContentHeight(threeLines, fixedLineCount = 2, gap = gap))
    }

    @Test
    fun `an empty row still collapses under a fixed budget`() {
        assertEquals(0, reservedContentHeight(emptyList(), fixedLineCount = 2, gap = gap))
    }

    @Test
    fun `natural height sums the lines like before`() {
        val lines = buildFlowLines(listOf(90 to 20, 90 to 24), 100, gap)
        assertEquals(20 + 24 + gap, reservedContentHeight(lines, fixedLineCount = 0, gap = gap))
    }
}
