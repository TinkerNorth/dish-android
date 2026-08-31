// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.tinkernorth.dish.R

internal data class FlowLine(
    val firstChild: Int,
    val childCount: Int,
    val width: Int,
    val height: Int,
)

// Greedy line breaking: a child starts a new line when it no longer fits beside the
// previous ones. A child wider than the row still gets its own full line.
internal fun buildFlowLines(
    sizes: List<Pair<Int, Int>>,
    maxWidth: Int,
    gap: Int,
): List<FlowLine> {
    val lines = mutableListOf<FlowLine>()
    var first = 0
    var count = 0
    var width = 0
    var height = 0
    sizes.forEachIndexed { i, (w, h) ->
        if (count > 0 && width + gap + w > maxWidth) {
            lines.add(FlowLine(first, count, width, height))
            first = i
            count = 0
            width = 0
            height = 0
        }
        width += (if (count > 0) gap else 0) + w
        height = maxOf(height, h)
        count++
    }
    if (count > 0) lines.add(FlowLine(first, count, width, height))
    return lines
}

// How many of the computed lines actually render under a fixed budget (0 = all).
internal fun renderableLineCount(
    lineCount: Int,
    fixedLineCount: Int,
): Int = if (fixedLineCount <= 0) lineCount else minOf(lineCount, fixedLineCount)

// The content height a fixed budget reserves. The full budget is claimed the
// moment there is ANYTHING to show, so a card's row never grows or shrinks with
// how many chips happen to be visible; an empty row still collapses (matching
// the pre-flow rows, whose chips gone meant the row folded to its padding).
internal fun reservedContentHeight(
    lines: List<FlowLine>,
    fixedLineCount: Int,
    gap: Int,
): Int {
    if (lines.isEmpty()) return 0
    if (fixedLineCount <= 0) {
        return lines.sumOf { it.height } + gap * (lines.size - 1)
    }
    val lineHeight = lines.maxOf { it.height }
    return fixedLineCount * lineHeight + gap * (fixedLineCount - 1)
}

// Pill row that wraps onto new lines instead of squeezing its last chip. Lines are
// end-aligned by default to match the right-hugging chip rows it replaces;
// [startAligned] flips that for label-column rows. [fixedLineCount] reserves an
// exact number of lines (dashboard cards must not change height with content);
// lines past the budget are parked off-canvas where clipping hides them whole,
// instead of the old LinearLayout squeeze that ellipsized chips mid-word.
class PillFlowLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : ViewGroup(context, attrs) {
        private val gap = resources.getDimensionPixelSize(R.dimen.binding_pill_gap)
        private var lines: List<FlowLine> = emptyList()

        var fixedLineCount: Int = 0
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        var startAligned: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        private fun visibleChildren(): List<View> =
            (0 until childCount)
                .map { getChildAt(it) }
                .filter { it.visibility != GONE }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val maxWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            val childWidthSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
            val childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val children = visibleChildren()
            children.forEach { it.measure(childWidthSpec, childHeightSpec) }
            lines = buildFlowLines(children.map { it.measuredWidth to it.measuredHeight }, maxWidth, gap)
            val rendered = lines.take(renderableLineCount(lines.size, fixedLineCount))
            val contentWidth = rendered.maxOfOrNull { it.width } ?: 0
            val contentHeight = reservedContentHeight(lines, fixedLineCount, gap)
            setMeasuredDimension(
                resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec),
                resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
            )
        }

        override fun onLayout(
            changed: Boolean,
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ) {
            val children = visibleChildren()
            val rendered = renderableLineCount(lines.size, fixedLineCount)
            var y = paddingTop
            lines.forEachIndexed { index, line ->
                // Overflow lines park beyond the right edge; clipChildren hides
                // them whole rather than half-drawing a squeezed chip.
                val overflow = if (index < rendered) 0 else (r - l) + OVERFLOW_PARK_PX
                var x =
                    overflow +
                        if (startAligned) paddingLeft else (r - l) - paddingRight - line.width
                for (i in line.firstChild until line.firstChild + line.childCount) {
                    val child = children[i]
                    child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                    x += child.measuredWidth + gap
                }
                if (index < rendered) y += line.height + gap
            }
        }

        private companion object {
            const val OVERFLOW_PARK_PX = 10_000
        }
    }
