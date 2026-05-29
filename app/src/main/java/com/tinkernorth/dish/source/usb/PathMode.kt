// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

// How a controller's input reaches the host. Direct = the native USB fast lane; Routed = Android's
// standard input layer.
enum class PathMode {
    Routed,
    Direct,
}

// Why a routed controller isn't on the direct fast lane, used to pick the explanation shown under
// the Standard-mode badge. None means no explanation is needed.
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

    // Recognised USB controller that could move to Direct mode but hasn't been claimed yet. The
    // dashboard offers a "Try Direct mode" action for this state.
    Eligible,
}
