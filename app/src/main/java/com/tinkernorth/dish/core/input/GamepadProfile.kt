// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.input

enum class GamepadProfile(
    val profileName: String,
    val sdpName: String,
    val sdpDescription: String,
    val sdpProvider: String,
) {
    XBOX(
        profileName = "Xbox",
        sdpName = "Dish Xbox Controller",
        sdpDescription = "Wireless Xbox Controller",
        sdpProvider = "TinkerNorth",
    ),
    PLAYSTATION(
        profileName = "PlayStation",
        sdpName = "Dish PS Controller",
        sdpDescription = "Wireless PlayStation Controller",
        sdpProvider = "TinkerNorth",
    ),
}
