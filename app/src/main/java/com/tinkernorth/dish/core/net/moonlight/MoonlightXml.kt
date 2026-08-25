// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.w3c.dom.Element
import org.xml.sax.InputSource
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
                    // Harden the parser: this input comes off the network. Each
                    // switch is best-effort because the two parsers this code runs
                    // on do not admit the same ones. Android's
                    // DocumentBuilderFactoryImpl recognizes only the SAX namespaces
                    // and validation features and throws ParserConfigurationException
                    // for everything else, so demanding the Apache DTD switch here
                    // would abort EVERY parse on device (returning null out of this
                    // runCatching) while still passing on the JVM, where Xerces does
                    // support it. That is a silent, device-only failure, so the
                    // portable guarantee is enforced below instead.
                    harden(DISALLOW_DOCTYPE, true)
                    harden(EXTERNAL_GENERAL_ENTITIES, false)
                    harden(EXTERNAL_PARAMETER_ENTITIES, false)
                    isExpandEntityReferences = false
                }
            val builder =
                factory.newDocumentBuilder().apply {
                    // The half that always holds, whatever the factory would admit:
                    // every external entity resolves to nothing, so no DTD or entity
                    // in a host's reply can make the parser read a file or open a
                    // connection. This is the actual XXE gate.
                    setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
                }
            builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
        }.getOrNull()

    /** Applies one parser switch, tolerating a parser that cannot express it. */
    private fun DocumentBuilderFactory.harden(
        feature: String,
        value: Boolean,
    ) {
        runCatching { setFeature(feature, value) }
    }

    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
    private const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
    private const val EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"

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
