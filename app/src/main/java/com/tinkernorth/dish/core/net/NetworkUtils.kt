// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import com.tinkernorth.dish.core.model.DiscoveredServer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

fun jsonGet(
    jsonString: String,
    key: String,
): String? =
    try {
        val element = json.parseToJsonElement(jsonString)
        element.jsonObject[key]?.jsonPrimitive?.content
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

fun hexToBytes(hex: String): ByteArray {
    // Validate up front: an odd length would read past the end mid-loop, and a
    // non-hex char makes Character.digit return -1 and silently corrupt a byte.
    require(hex.length % 2 == 0) { "hex string must have even length" }
    require(hex.all { it.isHexDigit() }) { "hex string contains a non-hex character" }
    val data = ByteArray(hex.length / 2)
    var i = 0
    while (i < hex.length) {
        data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        i += 2
    }
    return data
}

internal fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

fun parseServers(jsonString: String): List<DiscoveredServer> =
    try {
        json
            .decodeFromString<List<DiscoveredServer>>(jsonString)
            .filter { it.name.isNotEmpty() && it.ip.isNotEmpty() }
    } catch (e: SerializationException) {
        emptyList()
    } catch (e: IllegalArgumentException) {
        emptyList()
    }
