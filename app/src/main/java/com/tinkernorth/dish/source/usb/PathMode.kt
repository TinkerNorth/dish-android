// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

enum class PathMode {
    Routed,
    Direct,
}

enum class PathReason {
    None,
    Bluetooth,
    OnScreen,
    PermissionDenied,
    UnknownModel,
    Busy,
    InitFailed,
    Detached,
    SupportedNoFastPathYet,
    Eligible,
}
