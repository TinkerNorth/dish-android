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
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

// One HTTP exchange's outcome. Public (unlike the client itself) because the
// gateway surfaces it to callers that need status/ETag: the catalog's 304
// revalidation can't ride a body-only return.
data class HttpReply(
    val status: Int,
    val body: String,
    val etag: String?,
    val pinMismatch: Boolean = false,
) {
    val notModified: Boolean get() = status == 304

    val unreachable: Boolean get() = status == 0 || body.isBlank()
}

// Satellite presents a self-signed cert on a LAN IP (no CA to validate against), so the
// handshake is validated by trust-on-first-use cert PINNING ([TofuTrustManager]): the first
// contact pins, every later one must match. TRADEOFF: pairing's FIRST contact has no prior
// pin, so that one handshake is unauthenticated (an attacker already on-path at pairing time
// can still impersonate). UDP gamepad channel has its own ChaCha20-Poly1305 auth.
// All methods BLOCK. Call from Dispatchers.IO.
@Singleton
class SatelliteHttpClient
    @Inject
    constructor(
        private val pins: SatellitePinRepository,
    ) {
        // One context per request: the trust manager is bound to the satellite id it pins for.
        private fun pinningSocketFactory(
            satelliteId: String,
            onMismatch: () -> Unit,
        ): SSLSocketFactory =
            SSLContext
                .getInstance("TLS")
                .apply {
                    init(null, arrayOf<TrustManager>(TofuTrustManager(satelliteId, pins, onMismatch)), SecureRandom())
                }.socketFactory

        // The cert names no host the URL stack could match (a self-signed cert for a LAN IP), so
        // the platform verifier is replaced by the one check that means something here: the
        // session the handshake negotiated carries the certificate pinned for this satellite.
        // internal (not private) so the decision is unit-testable with a mocked session.
        internal fun pinnedSessionVerifier(satelliteId: String): HostnameVerifier =
            HostnameVerifier { _: String?, session: SSLSession? ->
                val cert = session?.peerCertificates?.firstOrNull() ?: return@HostnameVerifier false
                tofuVerdict(pins.pinnedFingerprint(satelliteId), sha256FingerprintHex(cert.encoded)) == TofuVerdict.MATCH
            }

        // PUT /api/connections: the declarative session upsert. `descriptorsJson`
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
            protocolVersion: Int,
            satelliteId: String,
        ): HttpReply =
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
                        """"protocolVersion":$protocolVersion,""" +
                        """"controllers":$descriptorsJson,""" +
                        """"hostFeatures":{"mouseControl":$requestMouseControl}}""",
                satelliteId = satelliteId,
            )

        // GET /api/connections/{id}: the reconcile endpoint (applied state + epoch).
        fun getSession(
            ip: String,
            port: Int,
            connectionId: String,
            deviceId: String,
            hmacProof: String,
            satelliteId: String,
        ): HttpReply =
            request(
                method = "GET",
                ip = ip,
                port = port,
                path = "/api/connections/$connectionId",
                deviceId = deviceId,
                hmacProof = hmacProof,
                body = null,
                satelliteId = satelliteId,
            )

        // PUT /api/connections/{id}/controllers/{idx}: single-slot descriptor
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
        ): HttpReply =
            request(
                method = "PUT",
                ip = ip,
                port = port,
                path = "/api/connections/$connectionId/controllers/$ctrlIdx",
                deviceId = deviceId,
                hmacProof = hmacProof,
                body = descriptorJson,
                satelliteId = satelliteId,
            )

        // DELETE .../controllers/{idx}: removes the SLOT only; the session lives on.
        fun deleteController(
            ip: String,
            port: Int,
            connectionId: String,
            ctrlIdx: Int,
            deviceId: String,
            hmacProof: String,
            satelliteId: String,
        ): HttpReply =
            request(
                method = "DELETE",
                ip = ip,
                port = port,
                path = "/api/connections/$connectionId/controllers/$ctrlIdx",
                deviceId = deviceId,
                hmacProof = hmacProof,
                body = null,
                satelliteId = satelliteId,
            )

        fun disconnect(
            ip: String,
            port: Int,
            connectionId: String,
            deviceId: String,
            hmacProof: String,
            satelliteId: String,
        ): HttpReply =
            request(
                method = "DELETE",
                ip = ip,
                port = port,
                path = "/api/connections/$connectionId",
                deviceId = deviceId,
                hmacProof = hmacProof,
                body = """{"deviceId":"${jsonEscape(deviceId)}"}""",
                satelliteId = satelliteId,
            )

        // DELETE /api/pair: self-unpair (forget on the dish also forgets us on
        // the satellite, closing any live session server-side).
        fun unpair(
            ip: String,
            port: Int,
            deviceId: String,
            hmacProof: String,
            satelliteId: String,
        ): HttpReply =
            request(
                method = "DELETE",
                ip = ip,
                port = port,
                path = "/api/pair",
                deviceId = deviceId,
                hmacProof = hmacProof,
                body = null,
                satelliteId = satelliteId,
            )

        // GET /api/catalog: localized controller-type catalog. Unauthenticated by
        // design (the picker renders before pairing). `acceptLanguage` is the
        // device locale chain; ETag revalidation rides If-None-Match (304 → empty
        // body, caller serves its cache).
        fun getCatalog(
            ip: String,
            port: Int,
            acceptLanguage: String,
            etag: String?,
            satelliteId: String,
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
                extraHeaders = headers,
            )
        }

        // GET /api/server/capabilities: live host state. Unauthenticated like the catalog
        // (read before pairing); not ETag'd because it is dynamic, so fetched fresh each probe.
        fun getServerCapabilities(
            ip: String,
            port: Int,
            satelliteId: String,
        ): HttpReply =
            requestWithMeta(
                method = "GET",
                ip = ip,
                port = port,
                path = "/api/server/capabilities",
                deviceId = null,
                hmacProof = null,
                body = null,
                satelliteId = satelliteId,
            )

        // No X-Device-Id: /api/pair is the only client route that bypasses clientAuthorized.
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
            clientPin: String = "",
            protocolVersion: Int = DishProtocol.CURRENT,
        ): HttpReply =
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
                        """"protocolVersion":$protocolVersion,""" +
                        """"pin":"${jsonEscape(pin)}",""" +
                        """"clientPin":"${jsonEscape(clientPin)}"}""",
                satelliteId = satelliteId,
            )

        // Poll for the operator's accept/deny decision on a Path-B request.
        fun pairStatus(
            ip: String,
            port: Int,
            deviceId: String,
            satelliteId: String,
        ): HttpReply =
            request(
                method = "GET",
                ip = ip,
                port = port,
                path = "/api/pair/status?deviceId=" + java.net.URLEncoder.encode(deviceId, "UTF-8"),
                deviceId = null,
                hmacProof = null,
                body = null,
                satelliteId = satelliteId,
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
        ): HttpReply = requestWithMeta(method, ip, port, path, deviceId, hmacProof, body, satelliteId)

        // Never throws: transport failure surfaces as a JSON {error} body (status 0)
        // so callers' decode path stays uniform.
        private fun requestWithMeta(
            method: String,
            ip: String,
            port: Int,
            path: String,
            deviceId: String?,
            hmacProof: String?,
            body: String?,
            satelliteId: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): HttpReply {
            val url = URL("https", ip, port, path)
            Log.i(TAG, "$method https://$ip:$port$path")
            var conn: HttpsURLConnection? = null
            var pooled = false
            var pinMismatch = false
            return try {
                conn = openConnection(url, method, satelliteId, onMismatch = { pinMismatch = true })
                conn.applyHeaders(deviceId, hmacProof, extraHeaders)
                if (body != null) conn.writeBody(body)
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                Log.i(TAG, "$method $path -> HTTP $status (${text.length} bytes)")
                // Fully drained: leave the socket in the keep-alive pool so the approval poll reuses the TLS session.
                pooled = true
                HttpReply(status, text, conn.getHeaderField("ETag"))
            } catch (e: IOException) {
                Log.e(TAG, "$method $path failed: ${e.message}")
                HttpReply(0, """{"error":"${jsonEscape("request failed: ${e.message}")}"}""", null, pinMismatch)
            } finally {
                if (!pooled) conn?.disconnect()
            }
        }

        private fun openConnection(
            url: URL,
            method: String,
            satelliteId: String,
            onMismatch: () -> Unit,
        ): HttpsURLConnection =
            (url.openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = pinningSocketFactory(satelliteId, onMismatch)
                hostnameVerifier = pinnedSessionVerifier(satelliteId)
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
            }

        private fun HttpsURLConnection.applyHeaders(
            deviceId: String?,
            hmacProof: String?,
            extraHeaders: Map<String, String>,
        ) {
            if (deviceId != null) setRequestProperty("X-Device-Id", deviceId)
            if (hmacProof != null) setRequestProperty("X-Hmac-Proof", hmacProof)
            for ((k, v) in extraHeaders) setRequestProperty(k, v)
        }

        private fun HttpsURLConnection.writeBody(body: String) {
            doOutput = true
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
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

        private companion object {
            const val TAG = "SatelliteHttpClient"
            const val CONNECT_TIMEOUT_MS = 5_000
            const val READ_TIMEOUT_MS = 5_000
        }
    }
