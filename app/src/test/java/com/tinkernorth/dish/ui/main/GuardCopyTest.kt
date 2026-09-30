// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// What the link guard's scrim says for every guard kind and every blamed link.
class GuardCopyTest {
    private data class Header(
        val iconRes: Int,
        val colorRes: Int,
        val titleRes: Int,
    )

    private fun copyFor(
        kind: GuardKind,
        detail: GuardDetail = GuardDetail.GENERIC,
    ) = guardCopy(OverlayGuardUi(kind, hostLabel = HOST_LABEL, detail = detail))

    private fun headerOf(copy: GuardCopy) = Header(copy.iconRes, copy.colorRes, copy.titleRes)

    @Test
    fun `every guard kind has a pinned header`() {
        assertEquals(GuardKind.entries.toSet(), EXPECTED_HEADERS.keys)
    }

    @Test
    fun `a lost host is the error card with the lost-host title`() {
        assertEquals(EXPECTED_HEADERS.getValue(GuardKind.HOST_LOST), headerOf(copyFor(GuardKind.HOST_LOST)))
    }

    @Test
    fun `a reconnecting link wears the refresh glyph in the primary colour`() {
        assertEquals(EXPECTED_HEADERS.getValue(GuardKind.RECONNECTING), headerOf(copyFor(GuardKind.RECONNECTING)))
    }

    @Test
    fun `an unplugged pad is the input-lost warning`() {
        assertEquals(EXPECTED_HEADERS.getValue(GuardKind.UNPLUGGED), headerOf(copyFor(GuardKind.UNPLUGGED)))
    }

    @Test
    fun `a departed pad is the input-lost warning`() {
        assertEquals(EXPECTED_HEADERS.getValue(GuardKind.DEPARTED), headerOf(copyFor(GuardKind.DEPARTED)))
    }

    @Test
    fun `an unbound slot is the link-off warning`() {
        assertEquals(EXPECTED_HEADERS.getValue(GuardKind.UNBOUND), headerOf(copyFor(GuardKind.UNBOUND)))
    }

    @Test
    fun `a gone connection is the error card with the gone title`() {
        assertEquals(EXPECTED_HEADERS.getValue(GuardKind.GONE), headerOf(copyFor(GuardKind.GONE)))
    }

    @Test
    fun `no guard falls back to the gone title in the warning colour`() {
        assertEquals(EXPECTED_HEADERS.getValue(GuardKind.NONE), headerOf(copyFor(GuardKind.NONE)))
    }

    @Test
    fun `a lost host blames the network, the host radio, the session, or the host by name`() {
        assertLinkDetails(GuardKind.HOST_LOST)
    }

    @Test
    fun `a reconnecting link blames the network, the host radio, the session, or the host by name`() {
        assertLinkDetails(GuardKind.RECONNECTING)
    }

    private fun assertLinkDetails(kind: GuardKind) {
        val wifi = copyFor(kind, GuardDetail.WIFI_DOWN)
        assertEquals(R.string.overlay_guard_wifi_detail, wifi.detailRes)
        assertNull(wifi.detailArg)
        val bluetooth = copyFor(kind, GuardDetail.BLUETOOTH_HOST)
        assertEquals(R.string.overlay_guard_bt_detail, bluetooth.detailRes)
        assertNull(bluetooth.detailArg)
        val session = copyFor(kind, GuardDetail.MOONLIGHT_SESSION)
        assertEquals(R.string.overlay_guard_ml_detail, session.detailRes)
        assertNull(session.detailArg)
        val generic = copyFor(kind, GuardDetail.GENERIC)
        assertEquals(R.string.binding_edge_host_lost_detail, generic.detailRes)
        assertEquals(HOST_LABEL, generic.detailArg)
    }

    @Test
    fun `an unplugged pad asks for a replug without naming the host`() {
        val copy = copyFor(GuardKind.UNPLUGGED)
        assertEquals(R.string.overlay_guard_replug_detail, copy.detailRes)
        assertNull(copy.detailArg)
    }

    @Test
    fun `a departed pad says goodbye without naming the host`() {
        val copy = copyFor(GuardKind.DEPARTED)
        assertEquals(R.string.overlay_guard_departed_detail, copy.detailRes)
        assertNull(copy.detailArg)
    }

    @Test
    fun `an unbound slot names the host it left`() {
        val copy = copyFor(GuardKind.UNBOUND)
        assertEquals(R.string.overlay_guard_unbound_detail, copy.detailRes)
        assertEquals(HOST_LABEL, copy.detailArg)
    }

    @Test
    fun `a gone connection explains itself without naming the host`() {
        val copy = copyFor(GuardKind.GONE)
        assertEquals(R.string.overlay_guard_gone_detail, copy.detailRes)
        assertNull(copy.detailArg)
    }

    @Test
    fun `no guard carries the gone detail without naming the host`() {
        val copy = copyFor(GuardKind.NONE)
        assertEquals(R.string.overlay_guard_gone_detail, copy.detailRes)
        assertNull(copy.detailArg)
    }

    private companion object {
        const val HOST_LABEL = "PC"

        // The scrim header as the overlay painted it before GuardCopy was lifted out.
        val EXPECTED_HEADERS =
            mapOf(
                GuardKind.HOST_LOST to Header(R.drawable.ic_error, R.color.colorError, R.string.binding_edge_host_lost_title),
                GuardKind.RECONNECTING to Header(R.drawable.ic_refresh, R.color.colorPrimary, R.string.chip_status_connecting),
                GuardKind.UNPLUGGED to
                    Header(R.drawable.ic_gamepad, R.color.colorWarning, R.string.binding_edge_input_lost_title),
                GuardKind.DEPARTED to
                    Header(R.drawable.ic_gamepad, R.color.colorWarning, R.string.binding_edge_input_lost_title),
                GuardKind.UNBOUND to Header(R.drawable.ic_link_off, R.color.colorWarning, R.string.overlay_guard_unbound_title),
                GuardKind.GONE to Header(R.drawable.ic_error, R.color.colorError, R.string.overlay_guard_gone_title),
                GuardKind.NONE to Header(R.drawable.ic_gamepad, R.color.colorWarning, R.string.overlay_guard_gone_title),
            )
    }
}
