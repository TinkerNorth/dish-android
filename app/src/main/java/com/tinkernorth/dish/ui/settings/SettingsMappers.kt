// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.settings

import androidx.annotation.IdRes
import com.tinkernorth.dish.R
import com.tinkernorth.dish.source.store.ThemeMode

private const val SCHEME_HTTPS = "https://"
private const val SCHEME_HTTP = "http://"
private const val PATH_SEPARATOR = "/"

// Null for an id that is not a theme chip, which a configuration-change rebind can briefly hand
// over as an empty selection.
internal fun themeModeForChip(
    @IdRes chipId: Int?,
): ThemeMode? =
    when (chipId) {
        R.id.chipThemeLight -> ThemeMode.LIGHT
        R.id.chipThemeDark -> ThemeMode.DARK
        R.id.chipThemeSystem -> ThemeMode.SYSTEM
        else -> null
    }

@IdRes
internal fun chipForThemeMode(mode: ThemeMode): Int =
    when (mode) {
        ThemeMode.LIGHT -> R.id.chipThemeLight
        ThemeMode.DARK -> R.id.chipThemeDark
        ThemeMode.SYSTEM -> R.id.chipThemeSystem
    }

internal fun hostOf(url: String): String =
    url
        .removePrefix(SCHEME_HTTPS)
        .removePrefix(SCHEME_HTTP)
        .removeSuffix(PATH_SEPARATOR)
