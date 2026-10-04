// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

// Seals by reversing the bytes: a sealed value differs from its plain one, opens back to it, and
// a value that was never sealed by anything (see [refusing]) reads as unopenable, which is what
// the repository has to survive when the keystore key behind the real sealer is gone.
internal class ReversingSealer(
    private val refusing: Boolean = false,
) : SecretSealer {
    override fun seal(plain: ByteArray): ByteArray = plain.reversedArray()

    override fun open(sealed: ByteArray): ByteArray? = if (refusing) null else sealed.reversedArray()
}
