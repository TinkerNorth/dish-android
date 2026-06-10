// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

import android.util.Log
import com.tinkernorth.dish.repository.SatellitePinRepository
import com.tinkernorth.dish.repository.TofuVerdict
import com.tinkernorth.dish.repository.sha256FingerprintHex
import com.tinkernorth.dish.repository.tofuVerdict
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

// One HTTP exchange's outcome. Public (unlike the client itself) because the
// gateway surfaces it to callers that need status/ETag — the catalog's 304
// revalidation can't ride a body-only return.
data class HttpReply(
    val status: Int,
    val body: String,
    val etag: String?,
) {
    val notModified: Boolean get() = status == 304
}

// Satellite presents a self-signed cert on a LAN IP (no CA to validate against), so the
// X509TrustManager stays permissive to let the handshake complete. MITM protection instead
// comes from trust-on-first-use cert PINNING in the hostname verifier below.
// UDP gamepad channel has its own ChaCha20-Poly1305 auth.
// All methods BLOCK — call from Dispatchers.IO.
internal object SatelliteHttpClient {
    private const val TAG = "SatelliteHttpClient"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    // docs/contract.md §Versioning — rides in every pairing/session request.
    const val PROTOCOL_VERSION = 1

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

    // Per-call TOFU verifier: the X509TrustManager accepts any (self-signed) cert, so this
    // is the sole MITM gate. It fingerprints the negotiated peer cert and pins it to this
    // satellite id on first contact, then rejects any future cert that doesn't match.
    // TRADEOFF: pairing's FIRST contact has no prior pin, so that one handshake is
    // unauthenticated (an attacker already on-path at pairing time can still impersonate).
    // internal (not private) so the verifier decision is unit-testable with a mocked session.
    internal fun tofuHostnameVerifier(
        satelliteId: String,
        pins: SatellitePinRepository,
    ): HostnameVerifier =
        HostnameVerifier { _: String?, session: SSLSession? ->
            val cert = session?.peerCertificates?.firstOrNull() ?: return@HostnameVerifier false
            val presented = sha256FingerprintHex(cert.encoded)
            when (tofuVerdict(pins.pinnedFingerprint(satelliteId), presented)) {
                TofuVerdict.TRUST_FIRST_USE -> {
                    pins.pin(satelliteId, presented)
                    Log.i(TAG, "pinned cert for $satelliteId on first use")
                    true
                }
                TofuVerdict.MATCH -> true
                TofuVerdict.MISMATCH -> {
                    Log.e(TAG, "cert pin MISMATCH for $satelliteId — aborting (possible MITM)")
                    false
                }
            }
        }

    // PUT /api/connections — the declarative session upsert. `descriptorsJson`
    // is the prebuilt `[{...}, ...]` controllers array (ControllerDescriptor
    // owns its shape so it stays unit-testable without a socket).
    fun putSession(
        ip: String,
        port: Int,
        deviceId: String,
        deviceName: String,
        hmacProof: String,
        descriptorsJson: String,
        requestMouseControl: Boolean,
        satelliteId: String,
        pins: SatellitePinRepository,
    ): String =
        request(
            method = "PUT",
            ip = ip,
            port = port,
            path = "/api/connections",
            deviceId = deviceId,
            hmacProof = hmacProof,
            body =
                """{"deviceId":"${jsonEscape(deviceId)}",""" +
                    """"deviceName":"${jsonEscape(deviceName)}",""" +
                    """"protocolVersion":$PROTOCOL_VERSION,""" +
                    """"controllers":$descriptorsJson,""" +
                    """"hostFeatures":{"mouseControl":$requestMouseControl}}""",
            satelliteId = satelliteId,
            pins = pins,
        )

    // GET /api/connections/{id} — the reconcile endpoint (applied state + epoch).
    fun getSession(
        ip: String,
        port: Int,
        connectionId: String,
        deviceId: String,
        hmacProof: String,
        satelliteId: String,
        pins: SatellitePinRepository,
    ): String =
        request(
            method = "GET",
            ip = ip,
            port = port,
            path = "/api/connections/$connectionId",
            deviceId = deviceId,
            hmacProof = hmacProof,
            body = null,
            satelliteId = satelliteId,
            pins = pins,
        )

    // PUT /api/connections/{id}/controllers/{idx} — single-slot descriptor
    // upsert. Converges type/caps/touchpadMode without touching the session
    // (no token rotation), so toggles never churn the UDP channel.
    fun putController(
        ip: String,
        port: Int,
        connectionId: String,
        ctrlIdx: Int,
        deviceId: String,
        hmacProof: String,
        descriptorJson: String,
        satelliteId: String,
        pins: SatellitePinRepository,
    ): String =
        request(
            method = "PUT",
            ip = ip,
            port = port,
            path = "/api/connections/$connectionId/controllers/$ctrlIdx",
            deviceId = deviceId,
            hmacProof = hmacProof,
            body = descriptorJson,
            satelliteId = satelliteId,
            pins = pins,
        )

    // DELETE .../controllers/{idx} — removes the SLOT only; the session lives on.
    fun deleteController(
        ip: String,
        port: Int,
        connectionId: String,
        ctrlIdx: Int,
        deviceId: String,
        hmacProof: String,
        satelliteId: String,
        pins: SatellitePinRepository,
    ): String =
        request(
            method = "DELETE",
            ip = ip,
            port = port,
            path = "/api/connections/$connectionId/controllers/$ctrlIdx",
            deviceId = deviceId,
            hmacProof = hmacProof,
            body = null,
            satelliteId = satelliteId,
            pins = pins,
        )

    fun disconnect(
        ip: String,
        port: Int,
        connectionId: String,
        deviceId: String,
        hmacProof: String,
        satelliteId: String,
        pins: SatellitePinRepository,
    ): String =
        request(
            method = "DELETE",
            ip = ip,
            port = port,
            path = "/api/connections/$connectionId",
            deviceId = deviceId,
            hmacProof = hmacProof,
            body = """{"deviceId":"${jsonEscape(deviceId)}"}""",
            satelliteId = satelliteId,
            pins = pins,
        )

    // DELETE /api/pair — self-unpair (forget on the dish also forgets us on
    // the satellite, closing any live session server-side).
    fun unpair(
        ip: String,
        port: Int,
        deviceId: String,
        hmacProof: String,
        satelliteId: String,
        pins: SatellitePinRepository,
    ): String =
        request(
            method = "DELETE",
            ip = ip,
            port = port,
            path = "/api/pair",
            deviceId = deviceId,
            hmacProof = hmacProof,
            body = null,
            satelliteId = satelliteId,
            pins = pins,
        )

    // GET /api/catalog — localized controller-type catalog. Unauthenticated by
    // design (the picker renders before pairing). `acceptLanguage` is the
    // device locale chain; ETag revalidation rides If-None-Match (304 → empty
    // body, caller serves its cache).
    fun getCatalog(
        ip: String,
        port: Int,
        acceptLanguage: String,
        etag: String?,
        satelliteId: String,
        pins: SatellitePinRepository,
    ): HttpReply {
        val headers = mutableMapOf("Accept-Language" to acceptLanguage)
        if (!etag.isNullOrBlank()) headers["If-None-Match"] = etag
        return requestWithMeta(
            method = "GET",
            ip = ip,
            port = port,
            path = "/api/catalog",
            deviceId = null,
            hmacProof = null,
            body = null,
            satelliteId = satelliteId,
            pins = pins,
            extraHeaders = headers,
        )
    }

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
        satelliteId: String,
        pins: SatellitePinRepository,
        clientPin: String = "",
    ): String =
        request(
            method = "POST",
            ip = ip,
            port = port,
            path = "/api/pair",
            deviceId = null,
            hmacProof = null,
            body =
                """{"deviceId":"${jsonEscape(deviceId)}",""" +
                    """"deviceName":"${jsonEscape(deviceName)}",""" +
                    """"protocolVersion":$PROTOCOL_VERSION,""" +
                    """"pin":"${jsonEscape(pin)}",""" +
                    """"clientPin":"${jsonEscape(clientPin)}"}""",
            satelliteId = satelliteId,
            pins = pins,
        )

    // Poll for the operator's accept/deny decision on a Path-B request.
    fun pairStatus(
        ip: String,
        port: Int,
        deviceId: String,
        satelliteId: String,
        pins: SatellitePinRepository,
    ): String =
        request(
            method = "GET",
            ip = ip,
            port = port,
            path = "/api/pair/status?deviceId=" + java.net.URLEncoder.encode(deviceId, "UTF-8"),
            deviceId = null,
            hmacProof = null,
            body = null,
            satelliteId = satelliteId,
            pins = pins,
        )

    private fun request(
        method: String,
        ip: String,
        port: Int,
        path: String,
        deviceId: String?,
        hmacProof: String?,
        body: String?,
        satelliteId: String,
        pins: SatellitePinRepository,
    ): String = requestWithMeta(method, ip, port, path, deviceId, hmacProof, body, satelliteId, pins).body

    // Never throws — transport failure surfaces as a JSON {error} body (status 0)
    // so callers' decode path stays uniform.
    @Suppress("NestedBlockDepth", "LongParameterList")
    private fun requestWithMeta(
        method: String,
        ip: String,
        port: Int,
        path: String,
        deviceId: String?,
        hmacProof: String?,
        body: String?,
        satelliteId: String,
        pins: SatellitePinRepository,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpReply {
        val url = URL("https", ip, port, path)
        Log.i(TAG, "$method https://$ip:$port$path")
        var conn: HttpsURLConnection? = null
        var pooled = false
        return try {
            conn =
                (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = insecureSocketFactory
                    hostnameVerifier = tofuHostnameVerifier(satelliteId, pins)
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    if (deviceId != null) {
                        setRequestProperty("X-Device-Id", deviceId)
                    }
                    if (hmacProof != null) {
                        setRequestProperty("X-Hmac-Proof", hmacProof)
                    }
                    for ((k, v) in extraHeaders) setRequestProperty(k, v)
                    if (body != null) {
                        doOutput = true
                        outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            Log.i(TAG, "$method $path -> HTTP $status (${text.length} bytes)")
            // Fully drained: leave the socket in the keep-alive pool so the approval poll reuses the TLS session.
            pooled = true
            HttpReply(status, text, conn.getHeaderField("ETag"))
        } catch (e: IOException) {
            Log.e(TAG, "$method $path failed: ${e.message}")
            HttpReply(0, """{"error":"${jsonEscape("request failed: ${e.message}")}"}""", null)
        } finally {
            if (!pooled) conn?.disconnect()
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
