// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net

import android.util.Log
import java.io.IOException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTPS client for the satellite's client-facing API (port 9443, TLS).
 *
 * The satellite's re-architected client server speaks HTTPS on a single port
 * (9443) for both the connection API (`/api/connections`) and PIN pairing
 * (`/api/pair`). This client replaces the hand-rolled raw-TCP HTTP/1.1 code
 * that previously lived in `satellite_jni.cpp` (`httpRequest()` and the raw-TCP
 * `pair` function).
 *
 * ## Certificate handling — verification deliberately disabled
 *
 * The satellite presents a **self-signed** certificate. By approved project
 * decision the dish accepts it **without verification or pinning** — both TLS
 * certificate-chain validation and hostname validation are disabled, the
 * equivalent of `curl --insecure`. The satellite is only ever reached over the
 * LAN at an IP address the user explicitly discovered/paired with, so there is
 * no CA chain to validate against and no stable hostname to pin.
 *
 * This trades transport authentication for the convenience of a zero-config
 * self-signed setup; the UDP gamepad channel remains independently encrypted
 * and authenticated (ChaCha20-Poly1305 with the pair-derived shared key), so a
 * MITM on the HTTPS channel cannot forge gamepad input.
 *
 * Every method returns the server's raw JSON response body as a string, or a
 * JSON error object (`{"error":...}` / `{"ok":false,...}`) on a transport
 * failure — the exact same string-in / string-out contract the old native
 * functions had, so [DiscoveryRepository] and its callers are unchanged.
 *
 * All methods BLOCK — call from [kotlinx.coroutines.Dispatchers.IO].
 */
internal object SatelliteHttpClient {
    private const val TAG = "SatelliteHttpClient"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /**
     * SSL socket factory that trusts every certificate. Built once and reused
     * — constructing an [SSLContext] per request is needless work and would
     * defeat connection pooling.
     */
    private val insecureSocketFactory by lazy {
        val trustAll =
            arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?,
                    ) = Unit

                    override fun checkServerTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?,
                    ) = Unit

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                },
            )
        SSLContext
            .getInstance("TLS")
            .apply {
                init(null, trustAll, SecureRandom())
            }.socketFactory
    }

    /** Hostname verifier that accepts any hostname (the cert is self-signed). */
    private val allowAllHostnames =
        HostnameVerifier { _: String?, _: SSLSession? -> true }

    /**
     * POST /api/connections — opens a new connection for this device.
     * Returns the server's JSON: `{connectionId, token, maxControllers}` or
     * `{error}` on the satellite side, or a transport-error JSON object.
     */
    fun connect(
        ip: String,
        port: Int,
        deviceId: String,
    ): String =
        request(
            method = "POST",
            ip = ip,
            port = port,
            path = "/api/connections",
            body = """{"deviceId":"${jsonEscape(deviceId)}"}""",
        )

    /**
     * DELETE /api/connections/<id> — closes the connection and removes all
     * controllers. Returns the server's JSON `{ok,...}` or a transport error.
     */
    fun disconnect(
        ip: String,
        port: Int,
        connectionId: String,
        deviceId: String,
    ): String =
        request(
            method = "DELETE",
            ip = ip,
            port = port,
            path = "/api/connections/$connectionId",
            body = """{"deviceId":"${jsonEscape(deviceId)}"}""",
        )

    /**
     * POST /api/devices/touchpad-mode — push the client's touchpad routing
     * choice. Server validates against its supported modes (probed via
     * `/api/server/capabilities`) and hot-applies to the live connection
     * (no re-pair). The mode is also persisted server-side so a re-connect
     * picks up the same routing without another round-trip.
     *
     * `mode` is one of `"off"`, `"ds4"`, `"mouse"`. The server responds
     * `{"ok":true,"hotApplied":bool}` on success, or `{"error":...}` on
     * unknown device / unsupported mode (409) / bad payload (400).
     */
    fun setTouchpadMode(
        ip: String,
        port: Int,
        deviceId: String,
        mode: String,
    ): String =
        request(
            method = "POST",
            ip = ip,
            port = port,
            path = "/api/devices/touchpad-mode",
            body =
                """{"id":"${jsonEscape(deviceId)}",""" +
                    """"mode":"${jsonEscape(mode)}"}""",
        )

    /**
     * POST /api/pair — PIN pairing handshake.
     *
     * Pairing used to be a bespoke raw-TCP JSON line protocol on a separate
     * port; it is now an ordinary HTTPS POST on the client server. The
     * request/response JSON shapes are unchanged — only the transport differs.
     * The satellite always answers HTTP 200 with
     * `{"ok":bool,"error"?,"sharedKey"?,"message"?}`.
     */
    fun pair(
        ip: String,
        port: Int,
        deviceId: String,
        deviceName: String,
        pin: String,
    ): String =
        request(
            method = "POST",
            ip = ip,
            port = port,
            path = "/api/pair",
            body =
                """{"deviceId":"${jsonEscape(deviceId)}",""" +
                    """"deviceName":"${jsonEscape(deviceName)}",""" +
                    """"pin":"${jsonEscape(pin)}"}""",
        )

    /**
     * Perform one HTTPS request against the satellite and return the response
     * body. Never throws — a transport failure is reported as a JSON error
     * object so callers keep their existing `runCatching { decode }` path.
     *
     * Non-2xx responses still have their body returned: the satellite's
     * `/api/pair` always replies 200, and the connection API encodes its own
     * failures as JSON `{error}` bodies, so the body is the useful payload
     * regardless of status code.
     */
    @Suppress("NestedBlockDepth")
    private fun request(
        method: String,
        ip: String,
        port: Int,
        path: String,
        body: String?,
    ): String {
        val url = URL("https", ip, port, path)
        Log.i(TAG, "$method https://$ip:$port$path")
        var conn: HttpsURLConnection? = null
        return try {
            conn =
                (url.openConnection() as HttpsURLConnection).apply {
                    // Disable TLS cert-chain AND hostname validation — the
                    // satellite uses a self-signed cert (approved decision).
                    sslSocketFactory = insecureSocketFactory
                    hostnameVerifier = allowAllHostnames
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Connection", "close")
                    if (body != null) {
                        doOutput = true
                        outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            Log.i(TAG, "$method $path -> HTTP $status (${text.length} bytes)")
            text
        } catch (e: IOException) {
            Log.e(TAG, "$method $path failed: ${e.message}")
            """{"error":"${jsonEscape("request failed: ${e.message}")}"}"""
        } finally {
            conn?.disconnect()
        }
    }

    /** Minimal JSON string escaping for the small set of values we send. */
    private fun jsonEscape(s: String): String =
        buildString(s.length) {
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
}
