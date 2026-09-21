// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URI

// latest.json (schema 1) parsing and validation: the ONLY reader of update
// metadata, with every rule enforced here once, so the reducer downstream may
// trust a parsed manifest completely. The file is the same shape dish-windows
// and dish-linux read, emitted by release.yml for every tagged release, with
// `dish.apk` as the asset instead of an installer. Unknown extra fields are
// ignored (additive-only policy for schema 1).

// The permalink the app fetches (GitHub's Latest pointer; no api.github.com)
// and the only prefix a manifest asset URL may carry.
const val LATEST_MANIFEST_URL = "https://github.com/TinkerNorth/dish-android/releases/latest/download/latest.json"
const val ASSET_URL_PREFIX = "https://github.com/TinkerNorth/dish-android/releases/download/"
const val APK_ASSET_NAME = "dish.apk"
const val MANIFEST_PRODUCT = "dish-android"
const val MANIFEST_CHANNEL = "stable"

// Caps: the body is size-checked BEFORE the JSON parse (a captive portal's
// HTML splash can be arbitrarily large), and no plausible APK is half a
// gigabyte.
const val MANIFEST_MAX_BYTES = 64 * 1024
const val ASSET_MAX_BYTES = 500L * 1024 * 1024

enum class ManifestError {
    Oversize, // body > 64 KiB before parsing
    BadJson, // unparseable, or the root is not an object (portal HTML)
    UnsupportedSchema, // schema != 1 (greater = newer client required)
    WrongProduct, // product != "dish-android"
    WrongChannel, // channel != "stable"
    BadVersion, // version not strict M.m.p
    BadMinimum, // minimumSupportedVersion malformed or > version
    MissingApkAsset, // no assets["dish.apk"] object
    BadAssetUrl, // http://, lookalike host, or wrong path prefix
    BadSha, // not 64 lowercase hex
    BadSize, // outside (0, 500 MB)
}

data class UpdateAsset(
    val url: String,
    val sha256: String, // 64 lowercase hex
    val size: Long,
)

data class UpdateManifest(
    val version: String,
    val minimumSupportedVersion: String,
    val publishedAt: String, // display only, NEVER ordering
    val releaseNotesUrl: String, // "" when absent or dropped (non-https / non-github.com)
    val apkAsset: UpdateAsset, // the dish.apk entry (required)
) {
    // The page the notice opens: the release notes when the manifest carries a
    // usable link, the APK itself otherwise. Both are on github.com.
    val downloadUrl: String get() = releaseNotesUrl.ifEmpty { apkAsset.url }
}

sealed interface ParsedManifest {
    data class Ok(
        val manifest: UpdateManifest,
    ) : ParsedManifest

    data class Rejected(
        val error: ManifestError,
    ) : ParsedManifest
}

fun parseUpdateManifest(body: ByteArray): ParsedManifest {
    if (body.size > MANIFEST_MAX_BYTES) return ParsedManifest.Rejected(ManifestError.Oversize)
    val root = parseObject(body) ?: return ParsedManifest.Rejected(ManifestError.BadJson)
    val version = stringOf(root["version"]).orEmpty()
    val minimum = stringOf(root["minimumSupportedVersion"]).orEmpty()
    val apk = (root["assets"] as? JsonObject)?.get(APK_ASSET_NAME) as? JsonObject
    val asset =
        UpdateAsset(
            url = stringOf(apk?.get("url")).orEmpty(),
            sha256 = stringOf(apk?.get("sha256")).orEmpty(),
            size = numberOf(apk?.get("size"))?.toLong() ?: 0L,
        )
    val error = envelopeError(root, version, minimum) ?: assetError(apk, asset)
    return if (error != null) {
        ParsedManifest.Rejected(error)
    } else {
        ParsedManifest.Ok(
            UpdateManifest(
                version = version,
                minimumSupportedVersion = minimum,
                publishedAt = stringOf(root["publishedAt"]).orEmpty(),
                releaseNotesUrl = sanitizedNotesUrl(stringOf(root["releaseNotesUrl"])),
                apkAsset = asset,
            ),
        )
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

private fun parseObject(body: ByteArray): JsonObject? =
    runCatching {
        lenientJson.parseToJsonElement(body.decodeToString())
    }.getOrNull() as? JsonObject

// A JSON string, or null for anything else: a number where a string belongs
// fails validation the same way a missing field does.
private fun stringOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun numberOf(element: JsonElement?): Double? = (element as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

private fun envelopeError(
    root: JsonObject,
    version: String,
    minimum: String,
): ManifestError? =
    when {
        numberOf(root["schema"]) != 1.0 -> ManifestError.UnsupportedSchema
        stringOf(root["product"]) != MANIFEST_PRODUCT -> ManifestError.WrongProduct
        stringOf(root["channel"]) != MANIFEST_CHANNEL -> ManifestError.WrongChannel
        !isValidVersion(version) -> ManifestError.BadVersion
        !isValidVersion(minimum) || isStrictlyNewer(minimum, version) -> ManifestError.BadMinimum
        else -> null
    }

private fun assetError(
    apk: JsonObject?,
    asset: UpdateAsset,
): ManifestError? =
    when {
        apk == null -> ManifestError.MissingApkAsset
        // Prefix compare on the raw string: URL normalization could mask a
        // lookalike ("github.com.evil.example") that plain startsWith rejects.
        !asset.url.startsWith(ASSET_URL_PREFIX) -> ManifestError.BadAssetUrl
        !isLowercaseHex64(asset.sha256) -> ManifestError.BadSha
        asset.size <= 0L || asset.size >= ASSET_MAX_BYTES -> ManifestError.BadSize
        else -> null
    }

private fun isLowercaseHex64(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

// releaseNotesUrl is advisory: a bad value is DROPPED, not fatal, because the
// notes link must never be able to take the whole update check down.
private fun sanitizedNotesUrl(value: String?): String {
    if (value == null) return ""
    val uri = runCatching { URI(value) }.getOrNull() ?: return ""
    return if (uri.scheme == "https" && uri.host == "github.com") value else ""
}
