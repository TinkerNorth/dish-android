// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

/**
 * True only for a numeric IP literal in a private/local range, parsed without
 * DNS resolution. Hostnames, public IPs, and malformed literals all return
 * false. Intended to vet mDNS/broadcast-discovered addresses before a caller
 * opens a socket to them.
 */
fun isPrivateHostLiteral(host: String): Boolean {
    // IPv6 literals may arrive bracketed (e.g. from a URL authority).
    val candidate =
        if (host.startsWith("[") && host.endsWith("]")) {
            host.substring(1, host.length - 1)
        } else {
            host
        }
    parseIpv4(candidate)?.let { return isPrivateIpv4(it) }
    parseIpv6(candidate)?.let { return isPrivateIpv6(it) }
    return false
}

private fun parseIpv4(host: String): IntArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val octets = IntArray(4)
    for (i in 0 until 4) {
        val p = parts[i]
        if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
        val v = p.toIntOrNull() ?: return null
        if (v > 255) return null
        octets[i] = v
    }
    return octets
}

private fun isPrivateIpv4(o: IntArray): Boolean =
    when {
        o[0] == 10 -> true // 10.0.0.0/8
        o[0] == 172 && o[1] in 16..31 -> true // 172.16.0.0/12 (172.16 .. 172.31 inclusive)
        o[0] == 192 && o[1] == 168 -> true // 192.168.0.0/16
        o[0] == 169 && o[1] == 254 -> true // 169.254.0.0/16 link-local
        o[0] == 127 -> true // 127.0.0.0/8 loopback
        else -> false
    }

private fun parseIpv6(host: String): IntArray? {
    val groups = ipv6Groups(host) ?: return null
    val bytes = IntArray(16)
    for (i in 0 until 8) {
        bytes[i * 2] = (groups[i] ushr 8) and 0xFF
        bytes[i * 2 + 1] = groups[i] and 0xFF
    }
    return bytes
}

private fun ipv6Groups(host: String): IntArray? {
    val doubleColon = host.indexOf("::")
    if (
        host.isEmpty() ||
        host.contains('%') ||
        (doubleColon != -1 && host.indexOf("::", doubleColon + 1) != -1)
    ) {
        return null
    }
    val headStr = if (doubleColon == -1) host else host.substring(0, doubleColon)
    val tailStr = if (doubleColon == -1) "" else host.substring(doubleColon + 2)
    val head = splitHextets(headStr) ?: return null
    val tail = splitHextets(tailStr) ?: return null
    val groups = ArrayList<Int>(8)
    groups.addAll(head)
    if (doubleColon != -1) {
        val missing = 8 - head.size - tail.size
        if (missing < 1) return null // "::" must stand for at least one group
        repeat(missing) { groups.add(0) }
    }
    groups.addAll(tail)
    return if (groups.size == 8) groups.toIntArray() else null
}

private fun splitHextets(fragment: String): List<Int>? {
    if (fragment.isEmpty()) return emptyList()
    val tokens = fragment.split(':')
    val groups = ArrayList<Int>(tokens.size + 1)
    for ((index, token) in tokens.withIndex()) {
        if (token.contains('.')) {
            // Embedded IPv4 is only legal as the final token.
            if (index != tokens.size - 1) return null
            val v4 = parseIpv4(token) ?: return null
            groups.add((v4[0] shl 8) or v4[1])
            groups.add((v4[2] shl 8) or v4[3])
        } else {
            if (token.isEmpty() || token.length > 4 || !token.all { it.isHexDigit() }) return null
            groups.add(token.toInt(16))
        }
    }
    return groups
}

private fun isPrivateIpv6(b: IntArray): Boolean =
    when {
        b.copyOfRange(0, 15).all { it == 0 } && b[15] == 1 -> true // ::1 loopback
        b[0] == 0xfc || b[0] == 0xfd -> true // fc00::/7 unique local
        b[0] == 0xfe && (b[1] and 0xc0) == 0x80 -> true // fe80::/10 link-local
        else -> false
    }
