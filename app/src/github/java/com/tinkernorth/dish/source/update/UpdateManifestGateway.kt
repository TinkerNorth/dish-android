// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.update

import android.util.Log
import com.tinkernorth.dish.BuildConfig
import com.tinkernorth.dish.core.update.LATEST_MANIFEST_URL
import com.tinkernorth.dish.core.update.MANIFEST_MAX_BYTES
import com.tinkernorth.dish.core.update.ParsedManifest
import com.tinkernorth.dish.core.update.UpdateError
import com.tinkernorth.dish.core.update.UpdateManifest
import com.tinkernorth.dish.core.update.parseUpdateManifest
import com.tinkernorth.dish.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

sealed interface ManifestFetch {
    data class Ok(
        val manifest: UpdateManifest,
    ) : ManifestFetch

    data class Failed(
        val error: UpdateError,
    ) : ManifestFetch
}

// The boundary to GitHub: one GET of the release permalink, nothing else.
interface UpdateManifestGateway {
    suspend fun fetchLatest(): ManifestFetch
}

// Plain HTTPS through the platform URL stack (system trust anchors; cleartext
// is refused by network_security_config). GitHub answers the permalink with a
// redirect to its download CDN, which the connection follows. What the request
// carries is documented in PRIVACY.md: the User-Agent below and nothing else.
// No cookies, no conditional-request identifiers, no query parameters.
@Singleton
class GitHubManifestGateway
    @Inject
    constructor(
        @IoDispatcher private val io: CoroutineDispatcher,
    ) : UpdateManifestGateway {
        override suspend fun fetchLatest(): ManifestFetch = withContext(io) { fetchBlocking() }

        private fun fetchBlocking(): ManifestFetch {
            val connection =
                try {
                    URI(LATEST_MANIFEST_URL).toURL().openConnection() as HttpsURLConnection
                } catch (e: IOException) {
                    Log.w(TAG, "update manifest: cannot open a connection", e)
                    return ManifestFetch.Failed(UpdateError.Http)
                }
            return try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.useCaches = false
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "update manifest: HTTP $status")
                    ManifestFetch.Failed(UpdateError.Http)
                } else {
                    parsed(connection.inputStream.use { readCapped(it) })
                }
            } catch (e: IOException) {
                Log.w(TAG, "update manifest: fetch failed", e)
                ManifestFetch.Failed(UpdateError.Http)
            } finally {
                connection.disconnect()
            }
        }

        private fun parsed(body: ByteArray): ManifestFetch =
            when (val result = parseUpdateManifest(body)) {
                is ParsedManifest.Ok -> ManifestFetch.Ok(result.manifest)
                is ParsedManifest.Rejected -> {
                    Log.w(TAG, "update manifest rejected: ${result.error}")
                    ManifestFetch.Failed(UpdateError.ManifestInvalid)
                }
            }

        // Reads just past the cap and stops, so the parser sees an oversize
        // body without the stream being drained on a captive portal's splash.
        private fun readCapped(stream: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (out.size() <= MANIFEST_MAX_BYTES) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        private companion object {
            const val TAG = "UpdateManifest"
            const val TIMEOUT_MS = 10_000
            const val READ_CHUNK_BYTES = 8 * 1024
            val USER_AGENT = "Dish/${BuildConfig.VERSION_NAME} (Android)"
        }
    }
