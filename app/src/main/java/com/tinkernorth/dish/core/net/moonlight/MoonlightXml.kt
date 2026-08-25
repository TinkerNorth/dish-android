// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses the XML the Moonlight host returns from /serverinfo, /pair and
 * /applist (Wolf moonlight.cpp). Uses the platform DOM parser (present on
 * Android and the host JVM), so it is exercised in unit tests with no network.
 */
object MoonlightXml {
    data class ServerInfo(
        val hostname: String,
        val uniqueId: String,
        val pairStatus: Int,
        val currentGame: Int,
        val state: String,
        val httpsPort: Int?,
        val externalPort: Int?,
        val mac: String?,
        val localIp: String?,
    ) {
        val paired: Boolean get() = pairStatus == 1
        val busy: Boolean get() = currentGame != 0 || state.endsWith("SERVER_BUSY")
    }

    data class App(
        val id: String,
        val title: String,
        val hdrSupported: Boolean,
    )

    /** A /pair phase reply: `paired` plus whichever field that phase carries. */
    data class PairReply(
        val paired: Boolean,
        val plainCert: String?,
        val challengeResponse: String?,
        val pairingSecret: String?,
        val statusMessage: String?,
    )

    fun parseServerInfo(xml: String): ServerInfo? {
        val root = rootOf(xml) ?: return null
        return ServerInfo(
            hostname = text(root, "hostname").orEmpty(),
            uniqueId = text(root, "uniqueid").orEmpty(),
            pairStatus = intText(root, "PairStatus") ?: 0,
            currentGame = intText(root, "currentgame") ?: 0,
            state = text(root, "state").orEmpty(),
            httpsPort = intText(root, "HttpsPort"),
            externalPort = intText(root, "ExternalPort"),
            mac = text(root, "mac"),
            localIp = text(root, "LocalIP"),
        )
    }

    fun parsePairReply(xml: String): PairReply? {
        val root = rootOf(xml) ?: return null
        return PairReply(
            paired = (intText(root, "paired") ?: 0) == 1,
            plainCert = text(root, "plaincert"),
            challengeResponse = text(root, "challengeresponse"),
            pairingSecret = text(root, "pairingsecret"),
            statusMessage = root.getAttribute("status_message").takeIf { it.isNotEmpty() },
        )
    }

    fun parseAppList(xml: String): List<App> {
        val root = rootOf(xml) ?: return emptyList()
        val apps = mutableListOf<App>()
        val nodes = root.getElementsByTagName("App")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val id = childText(el, "ID") ?: continue
            apps += App(id = id, title = childText(el, "AppTitle").orEmpty(), hdrSupported = (childInt(el, "IsHdrSupported") ?: 0) == 1)
        }
        return apps
    }

    private fun rootOf(xml: String): Element? =
        runCatching {
            val factory =
                DocumentBuilderFactory.newInstance().apply {
                    // Harden the parser: this input comes off the network.
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    isExpandEntityReferences = false
                }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            doc.documentElement
        }.getOrNull()

    private fun text(
        root: Element,
        tag: String,
    ): String? = childText(root, tag)

    private fun intText(
        root: Element,
        tag: String,
    ): Int? = childInt(root, tag)

    private fun childText(
        parent: Element,
        tag: String,
    ): String? {
        val nodes = parent.getElementsByTagName(tag)
        if (nodes.length == 0) return null
        return nodes
            .item(0)
            .textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun childInt(
        parent: Element,
        tag: String,
    ): Int? = childText(parent, tag)?.toIntOrNull()
}
