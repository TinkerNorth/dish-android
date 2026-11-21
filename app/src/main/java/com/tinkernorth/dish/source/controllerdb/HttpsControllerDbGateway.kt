// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.controllerdb

import android.util.Log
import com.tinkernorth.dish.core.controllerdb.CONTROLLER_DB_FILE_MAX_BYTES
import com.tinkernorth.dish.core.controllerdb.ControllerDbGateway
import com.tinkernorth.dish.core.controllerdb.FileReply
import com.tinkernorth.dish.core.controllerdb.MANIFEST_FILE
import com.tinkernorth.dish.core.controllerdb.MANIFEST_MAX_BYTES
import com.tinkernorth.dish.core.controllerdb.ManifestReply
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

const val CONTROLLER_DB_BASE_URL = "https://dish.tinkernorth.com/controllers/v1/"

// Plain HTTPS through the platform URL stack to dish.tinkernorth.com, in every flavour. What a
// request carries is documented in PRIVACY.md: no query, no identifier, the platform's default
// User-Agent, and on the manifest the ETag of the last one (If-None-Match).
@Singleton
class HttpsControllerDbGateway
    @Inject
    constructor(
        @IoDispatcher private val io: CoroutineDispatcher,
    ) : ControllerDbGateway {
        override suspend fun manifest(etag: String?): ManifestReply = withContext(io) { fetchManifest(etag) }

        override suspend fun file(path: String): FileReply = withContext(io) { fetchFile(path) }

        private fun fetchManifest(etag: String?): ManifestReply {
            val connection = open(MANIFEST_FILE) ?: return ManifestReply.Unreachable
            return try {
                etag?.let { connection.setRequestProperty("If-None-Match", it) }
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> ManifestReply.NotModified
                    HttpURLConnection.HTTP_OK ->
                        ManifestReply.Fresh(
                            connection.inputStream.use { readCapped(it, MANIFEST_MAX_BYTES.toLong()) },
                            connection.getHeaderField("ETag"),
                        )
                    else -> unreachableManifest(status)
                }
            } catch (e: IOException) {
                Log.w(TAG, "controller database manifest: fetch failed", e)
                ManifestReply.Unreachable
            } finally {
                connection.disconnect()
            }
        }

        private fun unreachableManifest(status: Int): ManifestReply {
            Log.w(TAG, "controller database manifest: HTTP $status")
            return ManifestReply.Unreachable
        }

        private fun fetchFile(path: String): FileReply {
            val connection = open(path) ?: return FileReply.Unreachable
            return try {
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "controller database $path: HTTP $status")
                    FileReply.Unreachable
                } else {
                    FileReply.Body(connection.inputStream.use { readCapped(it, CONTROLLER_DB_FILE_MAX_BYTES) })
                }
            } catch (e: IOException) {
                Log.w(TAG, "controller database $path: fetch failed", e)
                FileReply.Unreachable
            } finally {
                connection.disconnect()
            }
        }

        private fun open(path: String): HttpsURLConnection? =
            try {
                (URI(CONTROLLER_DB_BASE_URL + path).toURL().openConnection() as HttpsURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    useCaches = false
                    instanceFollowRedirects = false
                }
            } catch (e: IOException) {
                Log.w(TAG, "controller database: cannot open a connection", e)
                null
            }

        // Reads just past the cap and stops; the size check against the manifest then refuses it.
        private fun readCapped(
            stream: InputStream,
            cap: Long,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (out.size() <= cap) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        private companion object {
            const val TAG = "ControllerDb"
            const val TIMEOUT_MS = 10_000
            const val READ_CHUNK_BYTES = 8 * 1024
        }
    }
