// SPDX-License-Identifier: LGPL-3.0-or-later

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

// Satellite presents a self-signed cert on a LAN IP; approved decision to skip TLS validation
// (curl --insecure equivalent). UDP gamepad channel has its own ChaCha20-Poly1305 auth.
// All methods BLOCK — call from Dispatchers.IO.
internal object SatelliteHttpClient {
    private const val TAG = "SatelliteHttpClient"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    @Suppress("CustomX509TrustManager")
    private val insecureSocketFactory by lazy {
        val trustAll =
            arrayOf<TrustManager>(
                @Suppress("TrustAllX509TrustManager")
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

    private val allowAllHostnames =
        HostnameVerifier { _: String?, _: SSLSession? -> true }

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
            deviceId = deviceId,
            body = """{"deviceId":"${jsonEscape(deviceId)}"}""",
        )

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
            deviceId = deviceId,
            body = """{"deviceId":"${jsonEscape(deviceId)}"}""",
        )

    // Body field is "id" (matches handler route-param); device id for auth goes in X-Device-Id header.
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
            deviceId = deviceId,
            body =
                """{"id":"${jsonEscape(deviceId)}",""" +
                    """"mode":"${jsonEscape(mode)}"}""",
        )

    // No X-Device-Id — /api/pair is the only client route that bypasses clientAuthorized.
    // `pin` drives Path A (the dish entered the satellite's PIN); `clientPin` drives
    // Path B (the dish shows its own PIN for the operator to accept). Both ride in the
    // body; the satellite uses a valid `pin` first and only falls back to `clientPin`.
    fun pair(
        ip: String,
        port: Int,
        deviceId: String,
        deviceName: String,
        pin: String,
        clientPin: String = "",
    ): String =
        request(
            method = "POST",
            ip = ip,
            port = port,
            path = "/api/pair",
            deviceId = null,
            body =
                """{"deviceId":"${jsonEscape(deviceId)}",""" +
                    """"deviceName":"${jsonEscape(deviceName)}",""" +
                    """"pin":"${jsonEscape(pin)}",""" +
                    """"clientPin":"${jsonEscape(clientPin)}"}""",
        )

    // Poll for the operator's accept/deny decision on a Path-B request.
    fun pairStatus(
        ip: String,
        port: Int,
        deviceId: String,
    ): String =
        request(
            method = "GET",
            ip = ip,
            port = port,
            path = "/api/pair/status?deviceId=" + java.net.URLEncoder.encode(deviceId, "UTF-8"),
            deviceId = null,
            body = null,
        )

    // Never throws — transport failure surfaces as a JSON {error} body so callers' decode path stays unchanged.
    @Suppress("NestedBlockDepth")
    private fun request(
        method: String,
        ip: String,
        port: Int,
        path: String,
        deviceId: String?,
        body: String?,
    ): String {
        val url = URL("https", ip, port, path)
        Log.i(TAG, "$method https://$ip:$port$path")
        var conn: HttpsURLConnection? = null
        return try {
            conn =
                (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = insecureSocketFactory
                    hostnameVerifier = allowAllHostnames
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Connection", "close")
                    if (deviceId != null) {
                        setRequestProperty("X-Device-Id", deviceId)
                    }
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
