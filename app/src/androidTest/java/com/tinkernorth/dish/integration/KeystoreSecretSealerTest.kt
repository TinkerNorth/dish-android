// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkernorth.dish.repository.KeystoreSecretSealer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

// The keystore-held sealer, which no JVM test can drive.
@RunWith(AndroidJUnit4::class)
class KeystoreSecretSealerTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun a_sealed_key_opens_back_to_itself_and_is_not_stored_in_the_clear() {
        val sealer = KeystoreSecretSealer()

        val sealed = sealer.seal(key)

        assertFalse("the plain bytes must not appear in the sealed ones", sealed.toList().windowed(key.size).any { it == key.toList() })
        assertArrayEquals(key, sealer.open(sealed))
    }

    @Test
    fun a_second_sealer_over_the_same_keystore_opens_what_the_first_sealed() {
        val sealed = KeystoreSecretSealer().seal(key)

        assertArrayEquals(key, KeystoreSecretSealer().open(sealed))
    }

    @Test
    fun a_tampered_or_foreign_value_does_not_open() {
        val sealer = KeystoreSecretSealer()
        val sealed = sealer.seal(key)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()

        assertNull(sealer.open(sealed))
        assertNull(sealer.open(ByteArray(4)))
    }
}
