// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
