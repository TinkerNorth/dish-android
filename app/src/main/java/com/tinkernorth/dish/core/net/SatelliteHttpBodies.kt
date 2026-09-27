// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net

import java.net.URLEncoder

// The request bodies, headers and paths of the satellite REST routes (docs/contract.md), kept
// apart from the socket so each shape pins in a unit test. Byte for byte what the client sends.

internal const val HEADER_ACCEPT_LANGUAGE = "Accept-Language"
internal const val HEADER_IF_NONE_MATCH = "If-None-Match"
internal const val HEADER_CONTENT_TYPE = "Content-Type"
internal const val HEADER_DEVICE_ID = "X-Device-Id"
internal const val HEADER_HMAC_PROOF = "X-Hmac-Proof"
internal const val HEADER_ETAG = "ETag"
internal const val MIME_JSON = "application/json"

private const val PAIR_STATUS_PATH = "/api/pair/status"
private const val LOWEST_PRINTABLE = ' '
private const val UNICODE_ESCAPE = "\\u%04x"

// JSON string escaping for the values interpolated into the bodies below (a device name is the
// user's own text): the two-character escapes JSON names, then \u00XX for the rest of C0.
internal fun jsonEscape(s: String): String =
    buildString(s.length) {
        for (c in s) append(jsonEscaped(c))
    }

private fun jsonEscaped(c: Char): String =
    when {
        c == '"' -> "\\\""
        c == '\\' -> "\\\\"
        c == '\n' -> "\\n"
        c == '\r' -> "\\r"
        c == '\t' -> "\\t"
        c < LOWEST_PRINTABLE -> UNICODE_ESCAPE.format(c.code)
        else -> c.toString()
    }

// PUT /api/connections: the declarative session upsert. `descriptorsJson` is the prebuilt
// `[{...}, ...]` controllers array (ControllerDescriptor owns its shape).
internal fun sessionPutBody(
    deviceId: String,
    deviceName: String,
    protocolVersion: Int,
    descriptorsJson: String,
    requestMouseControl: Boolean,
): String =
    """{"deviceId":"${jsonEscape(deviceId)}",""" +
        """"deviceName":"${jsonEscape(deviceName)}",""" +
        """"protocolVersion":$protocolVersion,""" +
        """"controllers":$descriptorsJson,""" +
        """"hostFeatures":{"mouseControl":$requestMouseControl}}"""

// POST /api/pair. `pin` drives Path A (the dish entered the satellite's PIN); `clientPin` drives
// Path B (the dish shows its own PIN for the operator to accept). Both ride in the body; the
// satellite uses a valid `pin` first and only falls back to `clientPin`.
internal fun pairBody(
    deviceId: String,
    deviceName: String,
    protocolVersion: Int,
    pin: String,
    clientPin: String,
): String =
    """{"deviceId":"${jsonEscape(deviceId)}",""" +
        """"deviceName":"${jsonEscape(deviceName)}",""" +
        """"protocolVersion":$protocolVersion,""" +
        """"pin":"${jsonEscape(pin)}",""" +
        """"clientPin":"${jsonEscape(clientPin)}"}"""

// DELETE /api/connections/{id} names the device the session belongs to.
internal fun disconnectBody(deviceId: String): String = """{"deviceId":"${jsonEscape(deviceId)}"}"""

// A transport failure surfaces in the same {error} shape a satellite sends, so callers' decode
// path stays uniform.
internal fun requestFailedBody(cause: String?): String = """{"error":"${jsonEscape("request failed: $cause")}"}"""

// GET /api/catalog revalidates through If-None-Match only once there is an ETag to offer.
internal fun catalogHeaders(
    acceptLanguage: String,
    etag: String?,
): Map<String, String> =
    buildMap {
        put(HEADER_ACCEPT_LANGUAGE, acceptLanguage)
        if (!etag.isNullOrBlank()) put(HEADER_IF_NONE_MATCH, etag)
    }

// GET /api/pair/status?deviceId=...: the device id is free text and rides URL-encoded.
internal fun pairStatusPath(deviceId: String): String = "$PAIR_STATUS_PATH?deviceId=" + URLEncoder.encode(deviceId, Charsets.UTF_8.name())
