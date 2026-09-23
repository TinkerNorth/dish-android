// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

// Ranked best-first: satellite links are the fastest and most stable path, a Moonlight
// host's control stream is next, and the Bluetooth HID gamepad is the last resort.
enum class LinkTier { FASTEST, FAST, BASIC }
