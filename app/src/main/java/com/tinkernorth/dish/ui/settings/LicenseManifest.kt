// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.settings

import kotlinx.serialization.Serializable

@Serializable
data class LicenseManifest(
    val generatedBy: String? = null,
    val libraries: List<LicenseEntry> = emptyList(),
)

@Serializable
data class LicenseEntry(
    val group: String? = null,
    val artifact: String? = null,
    val version: String? = null,
    val name: String? = null,
    val url: String? = null,
    val licenses: List<LicenseInfo> = emptyList(),
)

@Serializable
data class LicenseInfo(
    val name: String? = null,
    val url: String? = null,
)
