// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * A minimal blocking HTTP/1.1 GET spoken over a raw [Socket], one socket per
 * request. Carries both Moonlight halves: the plaintext pairing phases on port
 * 47989 and, once [upgrade] wraps the socket in TLS, the mutual-TLS calls on
 * 47984.
 *
 * WHY NOT HttpURLConnection. Two independent reasons, one per half.
 *
 * Plaintext: res/xml/network_security_config.xml denies cleartext app-wide and
 * that denial is deliberate. It is the signal Play's pre-launch security checks
 * and Android's PlatformVal validator read, and every URL-stack request the app
 * makes really must be TLS. Relaxing it (or carving out a per-domain exception,
 * which cannot be done anyway for a user-typed LAN address) would trade a real
 * app-wide guarantee for one protocol's needs. Raw sockets are not gated by
 * that config, the same way the encrypted UDP gamepad wire and the LAN
 * discovery beacons already are not, so the exception stays scoped to exactly
 * the four requests that need it.
 *
 * TLS: the URL stack pools connections and decides reuse from an Address that
 * includes the SSLSocketFactory and HostnameVerifier instances. The gateway
 * necessarily supplies a per-host verifier, so no two calls ever shared a
 * pooled connection; every call dialled a new TLS connection and `disconnect()`
 * parked the old one in the pool instead of closing it, leaving the host a
 * growing pile of open sessions (see [MoonlightHttpGateway.getHttps]). A socket
 * this class opens is a socket it closes, and the `Connection: close` below
 * makes the host drop its half as soon as it has answered.
 *
 * WHY CLEARTEXT IS SAFE HERE. Pairing phases 1-4 are plaintext by protocol
 * (Wolf http-pairing.adoc): NVIDIA's GameStream protocol fixes them on the
 * plaintext port because there is no shared secret to build a TLS session on
 * yet. What crosses the wire is a random salt, the public client certificate,
 * AES challenges and signatures over them. The PIN itself is never sent: it is
 * shown on the dish and typed into the host's own UI, and both ends only prove
 * knowledge of it through the challenge exchange. Everything from phase 5 on
 * (pairchallenge, /applist, /launch) runs over the pinned mutual TLS channel
 * [MoonlightHttpGateway] builds with [upgrade]. A LAN eavesdropper learns
 * nothing it can replay, and an active attacker cannot complete the exchange
 * without the PIN.
 *
 * Blocking; call from Dispatchers.IO. Never throws: transport failures, TLS
 * handshake failures and a refused certificate pin all come back as
 * `Reply(0, "")`.
 */
internal class MoonlightHttp11Client(
    private val connectTimeoutMs: Int,
    private val defaultReadTimeoutMs: Int,
    /**
     * Wraps the connected socket before the request goes out, and returns the
     * socket to speak HTTP over. Null leaves the request in cleartext. The
     * gateway passes the mutual-TLS handshake plus its certificate pin check,
     * which rejects by throwing, so a refused host never sees a request.
     */
    private val upgrade: ((socket: Socket, host: String, port: Int) -> Socket)? = null,
) {
    /**
     * GETs [urlString], or `Reply(0, "")` if the host never answered.
     *
     * [readTimeoutMs] overrides the default for requests the host deliberately
     * holds open, such as the pairing phase that blocks on a human typing the
     * PIN. The connect timeout is unaffected: an unreachable host still fails
     * fast.
     */
    fun get(
        urlString: String,
        readTimeoutMs: Int = defaultReadTimeoutMs,
    ): MoonlightHttpGateway.Reply {
        val uri = runCatching { URI(urlString) }.getOrNull()
        val host = uri?.host
        val port = if (uri != null && uri.port > 0) uri.port else DEFAULT_HTTP_PORT
        if (uri == null || host == null || port > MAX_PORT) {
            Log.w(TAG, "not a usable http url: $urlString")
            return UNREACHABLE
        }
        return try {
            exchange(uri, host, port, readTimeoutMs)
        } catch (e: IOException) {
            // Connect refused, DNS failure, both timeouts (SocketTimeoutException
            // is an IOException), and every TLS failure including the pin
            // mismatch [MoonlightHttpGateway] throws, all land here.
            Log.w(TAG, "GET failed for ${uri.path}: ${e.message}")
            UNREACHABLE
        }
    }

    /**
     * One request over one socket: connect, hand it to [upgrade], ask, read the
     * answer, close. Nested `use` on purpose, so the close that reaches the host
     * first is the TLS one and it gets a close_notify before the socket under it
     * goes away.
     */
    private fun exchange(
        uri: URI,
        host: String,
        port: Int,
        readTimeoutMs: Int,
    ): MoonlightHttpGateway.Reply =
        Socket().use { raw ->
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
            raw.soTimeout = readTimeoutMs
            (upgrade?.invoke(raw, host, port) ?: raw).use { socket ->
                socket.soTimeout = readTimeoutMs
                socket.getOutputStream().apply {
                    write(head(uri, host, port).toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                readReply(socket.getInputStream().buffered())
            }
        }

    /** The request line and headers, CRLF-terminated per RFC 9112. */
    private fun head(
        uri: URI,
        host: String,
        port: Int,
    ): String {
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val target = uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { "$path?$it" } ?: path
        // Host carries the port whenever it is not the scheme default. URI.getHost
        // already returns an IPv6 literal in its bracketed form, which is what the
        // header wants too.
        val authority = if (port == DEFAULT_HTTP_PORT) host else "$host:$port"
        return "GET $target HTTP/1.1\r\n" +
            "Host: $authority\r\n" +
            "User-Agent: $USER_AGENT\r\n" +
            "Accept: */*\r\n" +
            // Ask the host to close once it has answered: it keeps the socket from
            // idling in a keep-alive pool and makes the read-to-EOF body path below
            // well defined for a response that carries no Content-Length.
            "Connection: close\r\n" +
            "\r\n"
    }

    private fun readReply(input: InputStream): MoonlightHttpGateway.Reply {
        val lines = readHead(input)
        if (lines == null) {
            Log.w(TAG, "host closed before finishing a response head")
            return UNREACHABLE
        }
        val status = parseStatus(lines.firstOrNull())
        if (status == null) {
            Log.w(TAG, "host answered but not with HTTP: ${lines.firstOrNull()?.take(STATUS_LOG_LEN)}")
            return UNREACHABLE
        }
        return MoonlightHttpGateway.Reply(status, readBody(input, parseHeaders(lines)))
    }

    /**
     * Reads up to and including the blank line that ends the head, and splits it.
     * Returns null if the peer hung up first or the head never ended, both of
     * which mean there is no reply to report.
     */
    private fun readHead(input: InputStream): List<String>? {
        val raw = ByteArrayOutputStream()
        var newlines = 0
        while (raw.size() < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            raw.write(b)
            when (b) {
                LF -> if (++newlines == 2) return splitHead(raw.toByteArray())
                CR -> Unit // half of a CRLF; does not reset the run
                else -> newlines = 0
            }
        }
        return null
    }

    // Tolerates bare-LF line ends as well as CRLF. Header text is ISO-8859-1 by
    // spec; only the body is decoded as UTF-8.
    private fun splitHead(raw: ByteArray): List<String> =
        raw
            .toString(Charsets.ISO_8859_1)
            .split("\r\n", "\n")
            .filter { it.isNotEmpty() }

    /** "HTTP/1.1 200 OK" -> 200; anything that is not a status line -> null. */
    private fun parseStatus(line: String?): Int? {
        if (line == null || !line.startsWith("HTTP/")) return null
        return line
            .split(' ')
            .getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in MIN_STATUS..MAX_STATUS }
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> =
        lines
            .drop(1)
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) {
                    null
                } else {
                    line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
                }
            }.toMap()

    /**
     * Body framing, in the precedence RFC 9112 gives it: chunked wins over
     * Content-Length, and with neither the body runs to the close we asked for.
     * A body cut short comes back as the bytes that did arrive, under the real
     * status, exactly as HttpURLConnection would hand it over; the XML parse
     * above the gateway then rejects it.
     */
    private fun readBody(
        input: InputStream,
        headers: Map<String, String>,
    ): String {
        val chunked = headers[TRANSFER_ENCODING]?.contains(CHUNKED, ignoreCase = true) == true
        val declared = headers[CONTENT_LENGTH]?.toIntOrNull()
        val bytes =
            when {
                chunked -> readChunked(input)
                declared != null -> readExactly(input, declared.coerceIn(0, MAX_BODY_BYTES))
                else -> readToEnd(input)
            }
        return bytes.toString(Charsets.UTF_8)
    }

    /** Sunshine sends Content-Length today; this keeps a chunked host working. */
    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (out.size() < MAX_BODY_BYTES) {
            // A chunk header is the hex size, optionally followed by ";extension".
            val size =
                readLine(input)
                    ?.substringBefore(';')
                    ?.trim()
                    ?.toIntOrNull(HEX)
                    ?: break
            if (size <= 0) break // the terminating 0-chunk, or a size we cannot read
            out.write(readExactly(input, size.coerceAtMost(MAX_BODY_BYTES)))
            readLine(input) // the CRLF that closes the chunk
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val raw = ByteArrayOutputStream()
        while (raw.size() < MAX_LINE_BYTES) {
            val b = input.read()
            if (b < 0) break
            if (b == LF) return raw.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            raw.write(b)
        }
        return null
    }

    private fun readExactly(
        input: InputStream,
        count: Int,
    ): ByteArray {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val n = input.read(out, filled, count - filled)
            if (n < 0) return out.copyOf(filled) // truncated; hand back what arrived
            filled += n
        }
        return out
    }

    private fun readToEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(COPY_BUFFER)
        while (out.size() < MAX_BODY_BYTES) {
            val n = input.read(chunk)
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "MoonlightHttp11"
        const val USER_AGENT = "Dish/1.0"
        const val DEFAULT_HTTP_PORT = 80
        const val MAX_PORT = 65535
        const val CR = '\r'.code
        const val LF = '\n'.code
        const val HEX = 16
        const val MIN_STATUS = 100
        const val MAX_STATUS = 599
        const val STATUS_LOG_LEN = 64
        const val MAX_HEAD_BYTES = 16 * 1024
        const val MAX_LINE_BYTES = 1024
        const val MAX_BODY_BYTES = 1024 * 1024
        const val COPY_BUFFER = 8 * 1024
        const val CONTENT_LENGTH = "content-length"
        const val TRANSFER_ENCODING = "transfer-encoding"
        const val CHUNKED = "chunked"

        val UNREACHABLE = MoonlightHttpGateway.Reply(0, "")
    }
}
