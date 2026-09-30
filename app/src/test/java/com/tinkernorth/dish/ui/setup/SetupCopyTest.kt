// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.setup

import com.tinkernorth.dish.R
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.input.GamepadProfile
import com.tinkernorth.dish.source.usb.DirectClaimFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupCopyTest {
    @Test
    fun `every direct claim failure has one reason string of its own`() {
        val reasons = DirectClaimFailure.entries.map { directFailureReasonRes(it) }
        assertEquals(DirectClaimFailure.entries.size, reasons.toSet().size)
    }

    @Test
    fun `a dropped claim asks for a replug`() {
        assertEquals(R.string.path_needs_replug, directFailureReasonRes(DirectClaimFailure.Dropped))
    }

    @Test
    fun `a refused permission and a busy or failed init each name their cause`() {
        assertEquals(R.string.path_reason_permission_denied, directFailureReasonRes(DirectClaimFailure.PermissionDenied))
        assertEquals(R.string.path_reason_busy, directFailureReasonRes(DirectClaimFailure.Busy))
        assertEquals(R.string.path_reason_init_failed, directFailureReasonRes(DirectClaimFailure.InitFailed))
    }

    @Test
    fun `a Moonlight host captions the type step with its own name`() {
        assertEquals(R.string.ml_type_caption, typeSubtitleRes(ConnectionKind.MOONLIGHT))
    }

    @Test
    fun `a Bluetooth host says its type is locked`() {
        assertEquals(R.string.setup_cfg_type_locked_subtitle, typeSubtitleRes(ConnectionKind.BLUETOOTH))
    }

    @Test
    fun `a satellite or a not-yet-chosen host gets the plain subtitle`() {
        assertEquals(R.string.setup_cfg_type_subtitle, typeSubtitleRes(ConnectionKind.SATELLITE))
        assertEquals(R.string.setup_cfg_type_subtitle, typeSubtitleRes(null))
    }

    @Test
    fun `each Bluetooth host type carries its own title, badge and glyph`() {
        assertEquals(
            BtHostTypeCopy(R.string.setup_bth_type_xbox, R.string.setup_bth_badge_xbox, R.drawable.ic_ctrl_xbox),
            btHostTypeCopy(GamepadProfile.XBOX),
        )
        assertEquals(
            BtHostTypeCopy(R.string.setup_bth_type_playstation, R.string.setup_bth_badge_playstation, R.drawable.ic_ctrl_ds4),
            btHostTypeCopy(GamepadProfile.PLAYSTATION),
        )
    }

    @Test
    fun `a host row reads reconnecting, connected, needs pairing or ready from its link`() {
        assertEquals(R.string.setup_conn_status_reconnecting, hostStatusRes(LinkState.Connecting))
        assertEquals(R.string.setup_conn_status_connected, hostStatusRes(LinkState.Connected))
        assertEquals(R.string.setup_conn_status_connected, hostStatusRes(LinkState.Unstable))
        assertEquals(R.string.setup_conn_status_needs_pairing, hostStatusRes(LinkState.Stale))
        assertEquals(R.string.setup_conn_status_ready, hostStatusRes(LinkState.Ready))
        assertEquals(R.string.setup_conn_status_ready, hostStatusRes(LinkState.Found))
        assertEquals(R.string.setup_conn_status_ready, hostStatusRes(LinkState.Saved))
    }
}
