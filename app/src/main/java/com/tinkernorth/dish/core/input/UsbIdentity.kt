// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.input

private const val VENDOR_SHIFT = 16
private const val PRODUCT_MASK = 0xFFFF

/** The one packing of a USB vendor:product pair that every per-model table in the app keys on. */
fun vidPidKey(
    vendorId: Int,
    productId: Int,
): Int = (vendorId shl VENDOR_SHIFT) or (productId and PRODUCT_MASK)
