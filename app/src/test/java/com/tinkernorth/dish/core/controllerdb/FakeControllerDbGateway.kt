// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

// A served database in memory: a manifest over the files it holds, and a count of every request.
internal class FakeControllerDbGateway(
    var files: Map<String, ByteArray> = emptyMap(),
    var version: Int = 2,
    var manifestReply: ManifestReply? = null,
    var unreachablePaths: Set<String> = emptySet(),
    var tamperedPaths: Set<String> = emptySet(),
) : ControllerDbGateway {
    var manifestRequests = 0
    var fileRequests = 0
    val etagsSent = mutableListOf<String?>()

    fun manifestBytes(): ByteArray {
        val entries =
            files.entries.joinToString(",") { (path, bytes) ->
                "\"$path\":{\"sha256\":\"${sha256Hex(bytes)}\",\"bytes\":${bytes.size}}"
            }
        return "{\"schema\":1,\"dbVersion\":$version,\"files\":{$entries}}".toByteArray()
    }

    override suspend fun manifest(etag: String?): ManifestReply {
        manifestRequests++
        etagsSent += etag
        return manifestReply ?: ManifestReply.Fresh(manifestBytes(), "\"etag-$version\"")
    }

    override suspend fun file(path: String): FileReply {
        fileRequests++
        if (path in unreachablePaths) return FileReply.Unreachable
        val bytes = files.getValue(path)
        return FileReply.Body(if (path in tamperedPaths) bytes.copyOf().also { it[0] = 'X'.code.toByte() } else bytes)
    }
}

internal fun servedFiles(
    version: Int,
    vararg ids: String,
): Map<String, ByteArray> {
    val records =
        ids.joinToString(",") {
            "{\"id\":\"$it\",\"identity\":{\"usb\":[\"1d79:03${it.length.toString().padStart(2, '0')}\"]}," +
                "\"input\":{\"protocol\":{\"ref\":\"xinput360\"}}}"
        }
    val names = ids.joinToString(",") { "\"controller.$it.name\":\"$it\"" }
    return mapOf(
        CONTROLLERS_FILE to "{\"schema\":1,\"dbVersion\":$version,\"records\":[$records]}".toByteArray(),
        ENGLISH_STRINGS_FILE to "{$names}".toByteArray(),
        "strings/de.json" to "{}".toByteArray(),
    )
}
