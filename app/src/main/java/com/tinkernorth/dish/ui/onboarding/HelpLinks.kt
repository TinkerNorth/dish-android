// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.onboarding

private const val SCHEME_HTTPS = "https://"
private const val SCHEME_HTTP = "http://"

// A link card's subtitle: the address without its scheme or trailing slash.
internal fun hostLabel(url: String): String =
    url
        .removePrefix(SCHEME_HTTPS)
        .removePrefix(SCHEME_HTTP)
        .removeSuffix("/")
