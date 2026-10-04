// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

// Reverses the bytes; with [refusing] set, open() refuses, as a sealer whose keystore key is gone does.
internal class ReversingSealer(
    private val refusing: Boolean = false,
) : SecretSealer {
    override fun seal(plain: ByteArray): ByteArray = plain.reversedArray()

    override fun open(sealed: ByteArray): ByteArray? = if (refusing) null else sealed.reversedArray()
}
