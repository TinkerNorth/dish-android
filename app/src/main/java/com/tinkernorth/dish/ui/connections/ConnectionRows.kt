// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.connections

import android.graphics.drawable.GradientDrawable
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.databinding.RowConnectionBinding
import com.tinkernorth.dish.ui.common.dotColorForState
import com.tinkernorth.dish.ui.common.glyphForConnection

internal fun RowConnectionBinding.paintConnection(
    title: String,
    detail: String,
    status: String,
    kind: ConnectionKind,
    state: LinkState,
) {
    tvRowTitle.text = title
    tvRowDetail.text = detail
    tvRowStatus.text = status
    (dotRow.background as GradientDrawable).setColor(root.context.getColor(dotColorForState(state)))
    ivRowGlyph.setImageResource(glyphForConnection(kind, state))
}
