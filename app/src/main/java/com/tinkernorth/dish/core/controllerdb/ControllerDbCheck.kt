// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import kotlinx.serialization.json.Json

// The boundary to dish.tinkernorth.com: a GET of the manifest with its ETag, and a GET of one file
// the manifest names. Nothing else crosses it.
interface ControllerDbGateway {
    suspend fun manifest(etag: String?): ManifestReply

    suspend fun file(path: String): FileReply
}

sealed interface ManifestReply {
    data class Fresh(
        val bytes: ByteArray,
        val etag: String?,
    ) : ManifestReply

    data object NotModified : ManifestReply

    data object Unreachable : ManifestReply
}

sealed interface FileReply {
    data class Body(
        val bytes: ByteArray,
    ) : FileReply

    data object Unreachable : FileReply
}

// A newer database, every file verified and parsed, ready to be written beside the cache and
// renamed over it.
class DownloadedControllerDb(
    val manifestBytes: ByteArray,
    val manifest: ControllerDbManifest,
    val db: ControllerDbFile,
    val files: Map<String, ByteArray>,
    val strings: Map<String, Map<String, String>>,
)

// The result, the ETag to send next time (the old one when the check failed), and the download
// when there is one.
data class CheckOutcome(
    val result: ControllerDbCheckResult,
    val etag: String?,
    val download: DownloadedControllerDb? = null,
)

// docs/controller-db.md section 6.3, to the letter: the manifest with If-None-Match; on 304 or an
// equal dbVersion, done; otherwise every file in files, each checked for its size and SHA-256, then
// parsed, the database checked for schema 1. Any failure leaves the cache alone.
suspend fun runControllerDbCheck(
    gateway: ControllerDbGateway,
    activeVersion: Int,
    etag: String?,
    json: Json,
): CheckOutcome =
    when (val reply = gateway.manifest(etag)) {
        ManifestReply.NotModified -> CheckOutcome(ControllerDbCheckResult.UP_TO_DATE, etag)
        ManifestReply.Unreachable -> CheckOutcome(ControllerDbCheckResult.OFFLINE, etag)
        is ManifestReply.Fresh -> checkFreshManifest(gateway, activeVersion, etag, reply, json)
    }

private suspend fun checkFreshManifest(
    gateway: ControllerDbGateway,
    activeVersion: Int,
    etag: String?,
    reply: ManifestReply.Fresh,
    json: Json,
): CheckOutcome {
    val parsed = parseManifest(reply.bytes, json) as? ParsedManifest.Ok ?: return CheckOutcome(ControllerDbCheckResult.INVALID, etag)
    val manifest = parsed.manifest
    if (!manifestIsNewer(activeVersion, manifest.dbVersion)) return CheckOutcome(ControllerDbCheckResult.UP_TO_DATE, reply.etag)
    return when (val fetched = fetchAll(gateway, manifest)) {
        Fetched.Unreachable -> CheckOutcome(ControllerDbCheckResult.OFFLINE, etag)
        Fetched.Mismatch -> CheckOutcome(ControllerDbCheckResult.INVALID, etag)
        is Fetched.Files ->
            downloadOf(reply, manifest, fetched.files, json)?.let { CheckOutcome(ControllerDbCheckResult.UPDATED, reply.etag, it) }
                ?: CheckOutcome(ControllerDbCheckResult.INVALID, etag)
    }
}

private sealed interface Fetched {
    data class Files(
        val files: Map<String, ByteArray>,
    ) : Fetched

    data object Unreachable : Fetched

    data object Mismatch : Fetched
}

private suspend fun fetchAll(
    gateway: ControllerDbGateway,
    manifest: ControllerDbManifest,
): Fetched {
    val files = LinkedHashMap<String, ByteArray>()
    for ((path, expected) in manifest.files) {
        val body = gateway.file(path) as? FileReply.Body ?: return Fetched.Unreachable
        if (checkFile(body.bytes, expected) != FileCheck.Ok) return Fetched.Mismatch
        files[path] = body.bytes
    }
    return Fetched.Files(files)
}

private fun downloadOf(
    reply: ManifestReply.Fresh,
    manifest: ControllerDbManifest,
    files: Map<String, ByteArray>,
    json: Json,
): DownloadedControllerDb? {
    val db = parseControllerDb(files.getValue(CONTROLLERS_FILE), manifest.dbVersion, json) as? ParsedDb.Ok ?: return null
    val strings = stringsOf(files, json) ?: return null
    return DownloadedControllerDb(reply.bytes, manifest, db.db, files, strings)
}

// Every strings file the manifest named, parsed; null when one is not a flat strings object.
fun stringsOf(
    files: Map<String, ByteArray>,
    json: Json,
): Map<String, Map<String, String>>? {
    val out = LinkedHashMap<String, Map<String, String>>()
    for ((path, bytes) in files) {
        val locale = localeOfStringsPath(path) ?: continue
        out[locale] = parseStrings(bytes, json) ?: return null
    }
    return out
}
