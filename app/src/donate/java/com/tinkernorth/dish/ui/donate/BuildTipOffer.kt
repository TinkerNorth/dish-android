// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.donate

import android.content.Context
import com.tinkernorth.dish.R

internal val TIP_OFFER: TipOffer =
    TipOffer.Ask(
        pillAsk = R.string.donate_bar_ask,
        settingsBody = R.string.settings_support_body,
        supporters = SupporterCheck(Context::isSupporter),
    )
