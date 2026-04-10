package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.data.model.DiscoveredServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

fun jsonGet(jsonString: String, key: String): String? {
    return try {
        val element = json.parseToJsonElement(jsonString)
        element.jsonObject[key]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }
}

fun hexToBytes(hex: String): ByteArray {
    val data = ByteArray(hex.length / 2)
    var i = 0
    while (i < hex.length) {
        data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        i += 2
    }
    return data
}

fun parseServers(jsonString: String): List<DiscoveredServer> {
    return try {
        json.decodeFromString<List<DiscoveredServer>>(jsonString)
            .filter { it.name.isNotEmpty() && it.ip.isNotEmpty() }
    } catch (e: Exception) {
        emptyList()
    }
}
