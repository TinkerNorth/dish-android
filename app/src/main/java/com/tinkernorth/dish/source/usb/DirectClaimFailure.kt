// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

// Why a Direct-mode claim could not be completed. Surfaced on the controller card so the user gets the
// real cause instead of a blanket "unplug and replug".
enum class DirectClaimFailure {
    PermissionDenied, // USB permission was refused or revoked before the interface could be opened.
    Busy, // Another app or driver holds the interface; the open/claim was rejected.
    InitFailed, // The interface was claimed but the controller never produced a decodable report.
    Dropped, // The OS detached the device on the claim and never returned it; needs a physical replug.
}
