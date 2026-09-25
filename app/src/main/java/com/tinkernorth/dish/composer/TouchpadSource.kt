// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

// Who produces a slot's touch data. The phone screen is a FALLBACK, not a sibling: a pad that
// has its own trackpad never gets the overlay, because two producers on one slot would fight
// over the single MSG_TOUCHPAD stream.
enum class TouchpadSource { PHONE, PAD, NONE }
