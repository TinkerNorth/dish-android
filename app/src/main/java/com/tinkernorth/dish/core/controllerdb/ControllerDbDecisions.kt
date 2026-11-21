// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

// The result of the last check, by the names docs/controller-db.md and the bundle use.
enum class ControllerDbCheckResult(
    val wire: String,
) {
    UP_TO_DATE("upToDate"),
    UPDATED("updated"),
    OFFLINE("offline"),
    INVALID("invalid"),
    OFF("off"),
}

sealed interface FileCheck {
    data object Ok : FileCheck

    data object WrongSize : FileCheck

    data object WrongHash : FileCheck
}

sealed interface ParsedManifest {
    data class Ok(
        val manifest: ControllerDbManifest,
    ) : ParsedManifest

    data object Invalid : ParsedManifest
}

sealed interface ParsedDb {
    data class Ok(
        val db: ControllerDbFile,
    ) : ParsedDb

    data object Invalid : ParsedDb
}

const val CONTROLLERS_FILE = "controllers.json"
const val MANIFEST_FILE = "manifest.json"
const val ENGLISH = "en"
const val ENGLISH_STRINGS_FILE = "strings/en.json"

// A served file is never larger than this; today's controllers.json is about 370 KB.
const val CONTROLLER_DB_FILE_MAX_BYTES = 4L * 1024 * 1024
const val MANIFEST_MAX_BYTES = 64 * 1024

private const val STRINGS_PREFIX = "strings/"
private const val JSON_SUFFIX = ".json"
private const val HEX_DIGITS = "0123456789abcdef"
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F
private const val BYTE_MASK = 0xFF
private val STRINGS_PATH = Regex("^strings/[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?\\.json$")

fun manifestIsNewer(
    activeVersion: Int,
    remoteVersion: Int,
): Boolean = remoteVersion > activeVersion

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val out = StringBuilder(digest.size * 2)
    for (b in digest) {
        val value = b.toInt() and BYTE_MASK
        out.append(HEX_DIGITS[value shr NIBBLE_BITS])
        out.append(HEX_DIGITS[value and NIBBLE_MASK])
    }
    return out.toString()
}

fun checkFile(
    bytes: ByteArray,
    expected: ManifestFile,
): FileCheck {
    val isTheSize = bytes.size.toLong() == expected.bytes
    if (!isTheSize) return FileCheck.WrongSize
    val isTheContent = sha256Hex(bytes) == expected.sha256.lowercase()
    return if (isTheContent) FileCheck.Ok else FileCheck.WrongHash
}

// Only the database and a locale's strings: a manifest names the paths the cache is written under,
// so nothing it names may reach outside it.
fun isDatabaseFilePath(path: String): Boolean = path == CONTROLLERS_FILE || STRINGS_PATH.matches(path)

fun localeOfStringsPath(path: String): String? {
    if (!STRINGS_PATH.matches(path)) return null
    return path.removePrefix(STRINGS_PREFIX).removeSuffix(JSON_SUFFIX)
}

fun parseManifest(
    bytes: ByteArray,
    json: Json,
): ParsedManifest {
    val manifest = decodeOrNull(json) { decodeFromString(ControllerDbManifest.serializer(), bytes.decodeToString()) }
    manifest ?: return ParsedManifest.Invalid
    val isSchemaOne = manifest.schema == CONTROLLER_DB_SCHEMA
    val hasAVersion = manifest.dbVersion >= 1
    val namesTheDatabase = CONTROLLERS_FILE in manifest.files && ENGLISH_STRINGS_FILE in manifest.files
    val namesOnlyDatabaseFiles = manifest.files.keys.all(::isDatabaseFilePath)
    val fitsTheCap = manifest.files.values.all { it.bytes in 0..CONTROLLER_DB_FILE_MAX_BYTES }
    val isValid = isSchemaOne && hasAVersion && namesTheDatabase && namesOnlyDatabaseFiles && fitsTheCap
    return if (isValid) ParsedManifest.Ok(manifest) else ParsedManifest.Invalid
}

fun parseControllerDb(
    bytes: ByteArray,
    expectedVersion: Int,
    json: Json,
): ParsedDb {
    val db = decodeOrNull(json) { decodeFromString(ControllerDbFile.serializer(), bytes.decodeToString()) }
    db ?: return ParsedDb.Invalid
    val isSchemaOne = db.schema == CONTROLLER_DB_SCHEMA
    val isTheManifestsVersion = db.dbVersion == expectedVersion
    return if (isSchemaOne && isTheManifestsVersion) ParsedDb.Ok(db) else ParsedDb.Invalid
}

// A flat object of string to string, or null for anything else.
fun parseStrings(
    bytes: ByteArray,
    json: Json,
): Map<String, String>? {
    val element = decodeOrNull(json) { parseToJsonElement(bytes.decodeToString()) } ?: return null
    val obj = element as? JsonObject ?: return null
    val allStrings = obj.values.all { it is JsonPrimitive && it.isString }
    if (!allStrings) return null
    return obj.jsonObject.mapValues { (it.value as JsonPrimitive).content }
}

private inline fun <T> decodeOrNull(
    json: Json,
    decode: Json.() -> T,
): T? =
    try {
        json.decode()
    } catch (e: SerializationException) {
        null
    }
