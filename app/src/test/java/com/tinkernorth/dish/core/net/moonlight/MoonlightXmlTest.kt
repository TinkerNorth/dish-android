// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MoonlightXmlTest {
    @Test
    fun `parses serverinfo`() {
        val xml =
            """<?xml version="1.0"?>
            <root status_code="200">
              <hostname>living-room-pc</hostname>
              <uniqueid>0123456789abcdef</uniqueid>
              <HttpsPort>47984</HttpsPort>
              <ExternalPort>47989</ExternalPort>
              <mac>aa:bb:cc:dd:ee:ff</mac>
              <LocalIP>192.168.1.50</LocalIP>
              <PairStatus>1</PairStatus>
              <currentgame>0</currentgame>
              <state>SUNSHINE_SERVER_FREE</state>
            </root>"""
        val info = MoonlightXml.parseServerInfo(xml)!!
        assertEquals("living-room-pc", info.hostname)
        assertEquals("0123456789abcdef", info.uniqueId)
        assertEquals(47984, info.httpsPort)
        assertEquals(47989, info.externalPort)
        assertEquals("192.168.1.50", info.localIp)
        assertTrue(info.paired)
        assertFalse(info.busy)
    }

    @Test
    fun `serverinfo reports busy when a game is running`() {
        val xml =
            """<root status_code="200"><PairStatus>1</PairStatus>
               <currentgame>881448767</currentgame><state>SUNSHINE_SERVER_BUSY</state></root>"""
        val info = MoonlightXml.parseServerInfo(xml)!!
        assertTrue(info.busy)
    }

    @Test
    fun `parses a phase-1 pair reply with plaincert`() {
        val xml = """<root status_code="200"><paired>1</paired><plaincert>2d2d2d2d2d</plaincert></root>"""
        val reply = MoonlightXml.parsePairReply(xml)!!
        assertTrue(reply.paired)
        assertEquals("2d2d2d2d2d", reply.plainCert)
    }

    @Test
    fun `parses a failed pair reply`() {
        val xml = """<root status_code="400" status_message="Invalid client hash"><paired>0</paired></root>"""
        val reply = MoonlightXml.parsePairReply(xml)!!
        assertFalse(reply.paired)
        assertEquals("Invalid client hash", reply.statusMessage)
    }

    @Test
    fun `parses an applist`() {
        val xml =
            """<root status_code="200">
                 <App><IsHdrSupported>0</IsHdrSupported><AppTitle>Desktop</AppTitle><ID>881448767</ID></App>
                 <App><IsHdrSupported>1</IsHdrSupported><AppTitle>Steam Big Picture</AppTitle><ID>1</ID></App>
               </root>"""
        val apps = MoonlightXml.parseAppList(xml)
        assertEquals(2, apps.size)
        assertEquals("Desktop", apps[0].title)
        assertEquals("881448767", apps[0].id)
        assertFalse(apps[0].hdrSupported)
        assertTrue(apps[1].hdrSupported)
    }

    @Test
    fun `malformed xml decodes to null or empty, not a crash`() {
        assertNull(MoonlightXml.parseServerInfo("not xml at all"))
        assertNull(MoonlightXml.parsePairReply("<root>"))
        assertTrue(MoonlightXml.parseAppList("garbage").isEmpty())
    }

    /**
     * Captured verbatim off Sunshine 7.1 on the wire, single-line and with the
     * fields in the order it really sends them, so a parser that only copes with
     * the pretty-printed samples above cannot pass.
     */
    @Test
    fun `parses a real Sunshine serverinfo body`() {
        val xml =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<root status_code=\"200\"><hostname>Samus Aran</hostname><appversion>7.1.431.-1</appversion>" +
                "<GfeVersion>3.23.0.74</GfeVersion><uniqueid>61651FD7-3927-3E2E-FD1A-6464FCEDE28F</uniqueid>" +
                "<HttpsPort>47984</HttpsPort><ExternalPort>47989</ExternalPort>" +
                "<mac>00:00:00:00:00:00</mac><LocalIP>192.168.68.98</LocalIP>" +
                "<ServerCodecModeSupport>2032385</ServerCodecModeSupport><PairStatus>0</PairStatus>" +
                "<currentgame>0</currentgame><state>SUNSHINE_SERVER_FREE</state></root>"

        val info = MoonlightXml.parseServerInfo(xml)!!

        assertEquals("Samus Aran", info.hostname)
        assertEquals("61651FD7-3927-3E2E-FD1A-6464FCEDE28F", info.uniqueId)
        assertEquals(47984, info.httpsPort)
        assertEquals(47989, info.externalPort)
        assertEquals("192.168.68.98", info.localIp)
        assertFalse(info.paired)
        assertFalse(info.busy)
    }

    /**
     * Byte for byte what a live Sunshine host answered a second /launch with,
     * over an HTTP 200. Reading only the HTTP status called this a success and
     * then failed downstream on the missing sessionUrl0.
     */
    @Test
    fun `an app-already-running refusal is read out of the body, not the status line`() {
        val xml =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<root status_code=\"400\" status_message=\"An app is already running on this host\">" +
                "<resume>0</resume></root>"

        val status = MoonlightXml.parseStatus(xml)!!

        assertEquals(400, status.code)
        assertEquals("An app is already running on this host", status.message)
        assertFalse(status.ok)
        assertTrue(status.appAlreadyRunning)
        assertFalse(status.resume)
    }

    @Test
    fun `a resumable session is flagged so the client takes it over instead of failing`() {
        val xml =
            "<root status_code=\"400\" status_message=\"An app is already running on this host\">" +
                "<resume>1</resume></root>"

        val status = MoonlightXml.parseStatus(xml)!!

        assertTrue(status.appAlreadyRunning)
        assertTrue(status.resume)
    }

    @Test
    fun `a successful launch reads as ok`() {
        val xml =
            "<root status_code=\"200\"><sessionUrl0>rtsp://192.168.68.98:48010</sessionUrl0>" +
                "<gamesession>1</gamesession></root>"

        val status = MoonlightXml.parseStatus(xml)!!

        assertTrue(status.ok)
        assertFalse(status.appAlreadyRunning)
    }

    @Test
    fun `a reply naming no status code at all is a plain success`() {
        // Wolf answers /applist this way.
        assertTrue(MoonlightXml.parseStatus("<root><App><ID>1</ID></App></root>")!!.ok)
    }

    @Test
    fun `an unparsable reply has no status`() {
        assertNull(MoonlightXml.parseStatus("not xml at all"))
    }

    @Test
    fun `a refusal that is not about a running app is not mistaken for one`() {
        val status = MoonlightXml.parseStatus("<root status_code=\"401\" status_message=\"Unauthorized\"></root>")!!
        assertFalse(status.ok)
        assertFalse(status.appAlreadyRunning)
    }

    /**
     * The parser is hardened best-effort, because Android's DOM factory rejects
     * most feature switches and the old code let that abort every parse on
     * device. Whatever the factory admits, no external entity may ever be
     * fetched: this fails if a host's reply can make the parser read a file.
     */
    @Test
    fun `an external entity in a host reply is never resolved`() {
        val secret = File.createTempFile("moonlight-xxe", ".txt")
        secret.writeText("TOP-SECRET")
        secret.deleteOnExit()
        val secretUri = secret.absolutePath.replace('\\', '/')
        val xml =
            "<?xml version=\"1.0\"?>" +
                "<!DOCTYPE root [<!ENTITY leak SYSTEM \"file:///$secretUri\">]>" +
                "<root status_code=\"200\"><hostname>&leak;</hostname></root>"

        // Either the DTD is refused outright (null) or it parses with the entity
        // unresolved. What must never happen is the file's contents coming back.
        val hostname = MoonlightXml.parseServerInfo(xml)?.hostname
        assertFalse("leaked the file into the parsed document", hostname.orEmpty().contains("TOP-SECRET"))
    }
}
