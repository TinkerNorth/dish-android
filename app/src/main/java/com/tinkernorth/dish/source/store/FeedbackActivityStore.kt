// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import javax.inject.Inject
import javax.inject.Singleton

enum class FeedbackKind { RUMBLE, TRIGGER_RUMBLE, LIGHTBAR, PLAYER_LEDS, TRIGGER_EFFECTS, MIC_LED }

data class FeedbackActivity(
    val lastKind: FeedbackKind,
    val atMs: Long,
    val count: Long,
)

// One lane of one slot: how many arrived, the last one's value as feedbackPair or feedbackRgb
// packed it (or the plain value), and when.
data class LaneActivity(
    val count: Long,
    val value: Long,
    val atMs: Long,
)

const val NO_FEEDBACK_VALUE = 0L

private const val HALF_BITS = 16
private const val HALF_MASK = 0xFFFFL
private const val CHANNEL_BITS = 8
private const val CHANNEL_MASK = 0xFFL
private const val NO_KIND = -1

// Two 16-bit magnitudes in one value: rumble's strong and weak, trigger rumble's left and right.
fun feedbackPair(
    first: Int,
    second: Int,
): Long = ((first.toLong() and HALF_MASK) shl HALF_BITS) or (second.toLong() and HALF_MASK)

fun pairFirst(value: Long): Int = ((value shr HALF_BITS) and HALF_MASK).toInt()

fun pairSecond(value: Long): Int = (value and HALF_MASK).toInt()

fun feedbackRgb(
    r: Int,
    g: Int,
    b: Int,
): Long {
    val red = (r.toLong() and CHANNEL_MASK) shl (2 * CHANNEL_BITS)
    val green = (g.toLong() and CHANNEL_MASK) shl CHANNEL_BITS
    val blue = b.toLong() and CHANNEL_MASK
    return red or green or blue
}

fun rgbRed(value: Long): Int = ((value shr (2 * CHANNEL_BITS)) and CHANNEL_MASK).toInt()

fun rgbGreen(value: Long): Int = ((value shr CHANNEL_BITS) and CHANNEL_MASK).toInt()

fun rgbBlue(value: Long): Int = (value and CHANNEL_MASK).toInt()

// One slot's lanes, written by the receive thread without a lock or an allocation per note.
private class SlotFeedback {
    val counts = AtomicLongArray(FeedbackKind.entries.size)
    val values = AtomicLongArray(FeedbackKind.entries.size)
    val atMs = AtomicLongArray(FeedbackKind.entries.size)
    val lastKind = AtomicInteger(NO_KIND)
}

// Plain map, not a StateFlow: the receive thread notes every host packet and nothing needs to
// react per packet; the diagnostics tick reads a snapshot.
@Singleton
class FeedbackActivityStore
    @Inject
    constructor() {
        private val bySlot = ConcurrentHashMap<String, SlotFeedback>()

        fun note(
            slotId: String,
            kind: FeedbackKind,
            value: Long = NO_FEEDBACK_VALUE,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            if (slotId.isEmpty()) return
            val slot = bySlot.computeIfAbsent(slotId) { SlotFeedback() }
            val lane = kind.ordinal
            slot.values.lazySet(lane, value)
            slot.atMs.lazySet(lane, nowMs)
            slot.counts.incrementAndGet(lane)
            slot.lastKind.lazySet(lane)
        }

        fun snapshot(): Map<String, FeedbackActivity> = bySlot.mapNotNull { (id, slot) -> activityOf(slot)?.let { id to it } }.toMap()

        fun lanes(): Map<String, Map<FeedbackKind, LaneActivity>> = bySlot.mapValues { (_, slot) -> lanesOf(slot) }

        fun forget(slotId: String) {
            bySlot.remove(slotId)
        }

        private fun activityOf(slot: SlotFeedback): FeedbackActivity? {
            val last = FeedbackKind.entries.getOrNull(slot.lastKind.get()) ?: return null
            val total = FeedbackKind.entries.sumOf { slot.counts.get(it.ordinal) }
            return FeedbackActivity(last, slot.atMs.get(last.ordinal), total)
        }

        private fun lanesOf(slot: SlotFeedback): Map<FeedbackKind, LaneActivity> =
            FeedbackKind.entries
                .filter { slot.counts.get(it.ordinal) > 0L }
                .associateWith { LaneActivity(slot.counts.get(it.ordinal), slot.values.get(it.ordinal), slot.atMs.get(it.ordinal)) }
    }
