// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net

private const val IPV4_OCTETS = 4
private const val OCTET_MAX_DIGITS = 3
private const val OCTET_MAX = 255
private const val IPV6_GROUPS = 8
private const val IPV6_BYTES = 16
private const val HEXTET_MAX_DIGITS = 4
private const val HEX_RADIX = 16
private const val BYTE_MASK = 0xFF

// RFC 1918 private blocks, plus the link-local and loopback blocks a LAN receiver may sit on.
private const val PRIVATE_10_FIRST_OCTET = 10
private const val PRIVATE_172_FIRST_OCTET = 172
private val PRIVATE_172_SECOND_OCTETS = 16..31
private const val PRIVATE_192_168_FIRST_OCTET = 192
private const val PRIVATE_192_168_SECOND_OCTET = 168
private const val LINK_LOCAL_FIRST_OCTET = 169
private const val LINK_LOCAL_SECOND_OCTET = 254
private const val LOOPBACK_FIRST_OCTET = 127

// fc00::/7 unique local, fe80::/10 link-local.
private const val UNIQUE_LOCAL_FIRST_BYTE_FC = 0xfc
private const val UNIQUE_LOCAL_FIRST_BYTE_FD = 0xfd
private const val LINK_LOCAL_FIRST_BYTE = 0xfe
private const val LINK_LOCAL_PREFIX_MASK = 0xc0
private const val LINK_LOCAL_PREFIX = 0x80

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
    if (parts.size != IPV4_OCTETS) return null
    val octets = IntArray(IPV4_OCTETS)
    for (i in 0 until IPV4_OCTETS) {
        val p = parts[i]
        if (p.isEmpty() || p.length > OCTET_MAX_DIGITS || !p.all { it in '0'..'9' }) return null
        val v = p.toIntOrNull() ?: return null
        if (v > OCTET_MAX) return null
        octets[i] = v
    }
    return octets
}

private fun isPrivateIpv4(o: IntArray): Boolean =
    when {
        o[0] == PRIVATE_10_FIRST_OCTET -> true
        o[0] == PRIVATE_172_FIRST_OCTET && o[1] in PRIVATE_172_SECOND_OCTETS -> true
        o[0] == PRIVATE_192_168_FIRST_OCTET && o[1] == PRIVATE_192_168_SECOND_OCTET -> true
        o[0] == LINK_LOCAL_FIRST_OCTET && o[1] == LINK_LOCAL_SECOND_OCTET -> true
        o[0] == LOOPBACK_FIRST_OCTET -> true
        else -> false
    }

private fun parseIpv6(host: String): IntArray? {
    val groups = ipv6Groups(host) ?: return null
    val bytes = IntArray(IPV6_BYTES)
    for (i in 0 until IPV6_GROUPS) {
        bytes[i * 2] = (groups[i] ushr Byte.SIZE_BITS) and BYTE_MASK
        bytes[i * 2 + 1] = groups[i] and BYTE_MASK
    }
    return bytes
}

// A literal carries at most one "::", and a zone index ("fe80::1%eth0") is not part of an
// address this parser accepts.
private fun isWellFormedIpv6(
    host: String,
    doubleColon: Int,
): Boolean {
    if (host.isEmpty() || host.contains('%')) return false
    val hasASecondRun = doubleColon != -1 && host.indexOf("::", doubleColon + 1) != -1
    return !hasASecondRun
}

// "::" stands for the run of zero groups that makes the address eight long, and for at least one
// of them; null means it stood for none, which is not a legal literal.
private fun expandGroups(
    head: List<Int>,
    tail: List<Int>,
    hasDoubleColon: Boolean,
): List<Int>? {
    val groups = ArrayList<Int>(IPV6_GROUPS)
    groups.addAll(head)
    if (hasDoubleColon) {
        val missing = IPV6_GROUPS - head.size - tail.size
        if (missing < 1) return null
        repeat(missing) { groups.add(0) }
    }
    groups.addAll(tail)
    return groups
}

private fun ipv6Groups(host: String): IntArray? {
    val doubleColon = host.indexOf("::")
    if (!isWellFormedIpv6(host, doubleColon)) return null
    val headStr = if (doubleColon == -1) host else host.substring(0, doubleColon)
    val tailStr = if (doubleColon == -1) "" else host.substring(doubleColon + 2)
    val head = splitHextets(headStr) ?: return null
    val tail = splitHextets(tailStr) ?: return null
    val groups = expandGroups(head, tail, doubleColon != -1) ?: return null
    return if (groups.size == IPV6_GROUPS) groups.toIntArray() else null
}

private fun splitHextets(fragment: String): List<Int>? {
    if (fragment.isEmpty()) return emptyList()
    val tokens = fragment.split(':')
    val groups = ArrayList<Int>(tokens.size + 1)
    for ((index, token) in tokens.withIndex()) {
        if (token.contains('.')) {
            val isLastToken = index == tokens.size - 1
            if (!isLastToken) return null
            val v4 = parseIpv4(token) ?: return null
            groups.add((v4[0] shl Byte.SIZE_BITS) or v4[1])
            groups.add((v4[2] shl Byte.SIZE_BITS) or v4[3])
        } else {
            if (token.isEmpty() || token.length > HEXTET_MAX_DIGITS || !token.all { it.isHexDigit() }) return null
            groups.add(token.toInt(HEX_RADIX))
        }
    }
    return groups
}

private fun isPrivateIpv6(b: IntArray): Boolean {
    val isLoopback = b.copyOfRange(0, IPV6_BYTES - 1).all { it == 0 } && b[IPV6_BYTES - 1] == 1
    val isUniqueLocal = b[0] == UNIQUE_LOCAL_FIRST_BYTE_FC || b[0] == UNIQUE_LOCAL_FIRST_BYTE_FD
    val isLinkLocal = b[0] == LINK_LOCAL_FIRST_BYTE && (b[1] and LINK_LOCAL_PREFIX_MASK) == LINK_LOCAL_PREFIX
    return isLoopback || isUniqueLocal || isLinkLocal
}
