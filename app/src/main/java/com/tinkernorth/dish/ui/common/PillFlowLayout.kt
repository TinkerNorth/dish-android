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

// Pill row that wraps onto new lines instead of squeezing its last chip. Lines are
// end-aligned to match the right-hugging chip rows it replaces.
class PillFlowLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : ViewGroup(context, attrs) {
        private val gap = resources.getDimensionPixelSize(R.dimen.binding_pill_gap)
        private var lines: List<FlowLine> = emptyList()

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
            val contentWidth = lines.maxOfOrNull { it.width } ?: 0
            val contentHeight = lines.sumOf { it.height } + gap * (lines.size - 1).coerceAtLeast(0)
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
            var y = paddingTop
            lines.forEach { line ->
                var x = (r - l) - paddingRight - line.width
                for (i in line.firstChild until line.firstChild + line.childCount) {
                    val child = children[i]
                    child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                    x += child.measuredWidth + gap
                }
                y += line.height + gap
            }
        }
    }
