// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.update

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private val sha = "a".repeat(64)
    private val apkUrl = ASSET_URL_PREFIX + "2.1.0/dish.apk"
    private val notesUrl = "https://github.com/TinkerNorth/dish-android/releases/tag/2.1.0"

    private fun asset(
        url: String = apkUrl,
        sha256: String = sha,
        size: JsonElement = JsonPrimitive(12_345_678),
    ): JsonObject =
        buildJsonObject {
            put("url", url)
            put("sha256", sha256)
            put("size", size)
        }

    private fun body(
        apk: JsonObject? = asset(),
        mutate: (MutableMap<String, JsonElement>) -> Unit = {},
    ): ByteArray {
        val root =
            mutableMapOf<String, JsonElement>(
                "schema" to JsonPrimitive(1),
                "product" to JsonPrimitive("dish-android"),
                "version" to JsonPrimitive("2.1.0"),
                "channel" to JsonPrimitive("stable"),
                "publishedAt" to JsonPrimitive("2026-09-21T00:00:00Z"),
                "minimumSupportedVersion" to JsonPrimitive("0.1.0"),
                "releaseNotesUrl" to JsonPrimitive(notesUrl),
                "assets" to buildJsonObject { if (apk != null) put(APK_ASSET_NAME, apk) },
            )
        mutate(root)
        return JsonObject(root).toString().encodeToByteArray()
    }

    private fun parsed(body: ByteArray): UpdateManifest = (parseUpdateManifest(body) as ParsedManifest.Ok).manifest

    private fun rejection(body: ByteArray): ManifestError = (parseUpdateManifest(body) as ParsedManifest.Rejected).error

    @Test
    fun `a well-formed manifest parses completely`() {
        val m = parsed(body())
        assertEquals("2.1.0", m.version)
        assertEquals("0.1.0", m.minimumSupportedVersion)
        assertEquals("2026-09-21T00:00:00Z", m.publishedAt)
        assertEquals(notesUrl, m.releaseNotesUrl)
        assertEquals(UpdateAsset(apkUrl, sha, 12_345_678L), m.apkAsset)
        assertEquals(notesUrl, m.downloadUrl)
    }

    @Test
    fun `unknown fields are ignored and publishedAt is never validated`() {
        val m =
            parsed(
                body {
                    it["future"] = JsonPrimitive("field")
                    it["publishedAt"] = JsonPrimitive("last Tuesday")
                },
            )
        assertEquals("2.1.0", m.version)
        assertEquals("last Tuesday", m.publishedAt)
    }

    @Test
    fun `the body is size-checked before it is parsed`() {
        val padded = body() + ByteArray(MANIFEST_MAX_BYTES) { ' '.code.toByte() }
        assertEquals(ManifestError.Oversize, rejection(padded))
    }

    @Test
    fun `portal html, arrays and broken json are BadJson`() {
        assertEquals(ManifestError.BadJson, rejection("<html><body>Sign in</body></html>".encodeToByteArray()))
        assertEquals(ManifestError.BadJson, rejection("[1, 2]".encodeToByteArray()))
        assertEquals(ManifestError.BadJson, rejection("{".encodeToByteArray()))
        assertEquals(ManifestError.BadJson, rejection(ByteArray(0)))
    }

    @Test
    fun `the envelope is checked field by field`() {
        assertEquals(ManifestError.UnsupportedSchema, rejection(body { it["schema"] = JsonPrimitive(2) }))
        assertEquals(ManifestError.UnsupportedSchema, rejection(body { it["schema"] = JsonPrimitive("1") }))
        assertEquals(ManifestError.UnsupportedSchema, rejection(body { it.remove("schema") }))
        assertEquals(ManifestError.WrongProduct, rejection(body { it["product"] = JsonPrimitive("dish-windows") }))
        assertEquals(ManifestError.WrongChannel, rejection(body { it["channel"] = JsonPrimitive("beta") }))
        assertEquals(ManifestError.BadVersion, rejection(body { it["version"] = JsonPrimitive("v2.1.0") }))
        assertEquals(ManifestError.BadVersion, rejection(body { it.remove("version") }))
        assertEquals(ManifestError.BadMinimum, rejection(body { it["minimumSupportedVersion"] = JsonPrimitive("9.0.0") }))
        assertEquals(ManifestError.BadMinimum, rejection(body { it.remove("minimumSupportedVersion") }))
    }

    @Test
    fun `the apk asset is required and checked field by field`() {
        assertEquals(ManifestError.MissingApkAsset, rejection(body(apk = null)))
        assertEquals(ManifestError.MissingApkAsset, rejection(body { it.remove("assets") }))
        assertEquals(
            ManifestError.BadAssetUrl,
            rejection(body(asset(url = "http://github.com/TinkerNorth/dish-android/releases/download/2.1.0/dish.apk"))),
        )
        assertEquals(
            ManifestError.BadAssetUrl,
            rejection(body(asset(url = "https://github.com.evil.example/TinkerNorth/dish-android/releases/download/2.1.0/dish.apk"))),
        )
        assertEquals(
            ManifestError.BadAssetUrl,
            rejection(body(asset(url = "https://github.com/Someone/else/releases/download/2.1.0/dish.apk"))),
        )
        assertEquals(ManifestError.BadSha, rejection(body(asset(sha256 = "A".repeat(64)))))
        assertEquals(ManifestError.BadSha, rejection(body(asset(sha256 = "a".repeat(63)))))
        assertEquals(ManifestError.BadSize, rejection(body(asset(size = JsonPrimitive(0)))))
        assertEquals(ManifestError.BadSize, rejection(body(asset(size = JsonPrimitive(ASSET_MAX_BYTES)))))
        assertEquals(ManifestError.BadSize, rejection(body(asset(size = JsonPrimitive("12345")))))
    }

    @Test
    fun `a bad notes link is dropped, never fatal, and the apk stands in`() {
        for (bad in listOf("http://github.com/x", "https://github.com.evil.example/x", "https://example.com/x", "not a url at all")) {
            val m = parsed(body { it["releaseNotesUrl"] = JsonPrimitive(bad) })
            assertEquals(bad, "", m.releaseNotesUrl)
            assertEquals(bad, apkUrl, m.downloadUrl)
        }
        val absent = parsed(body { it.remove("releaseNotesUrl") })
        assertTrue(absent.releaseNotesUrl.isEmpty())
        assertEquals(apkUrl, absent.downloadUrl)
    }
}
