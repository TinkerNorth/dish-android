// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

/**
 * The registry's device ids as a sorted int array, and the map they were taken from, so the UI
 * thread asks whether an input event's device is a known pad without boxing its id into a map
 * lookup, and a reader can tell when the map has moved on.
 */
class KnownPadIds internal constructor(
    private val source: Map<Int, *>,
    private val ids: IntArray,
) {
    operator fun contains(deviceId: Int): Boolean = ids.binarySearch(deviceId) >= 0

    fun isTakenFrom(devices: Map<Int, *>): Boolean = devices === source
}

fun knownPadIdsOf(devices: Map<Int, *>): KnownPadIds = KnownPadIds(devices, devices.keys.toIntArray().apply { sort() })
