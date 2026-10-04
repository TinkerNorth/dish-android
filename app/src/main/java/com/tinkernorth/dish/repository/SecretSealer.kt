// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals a secret for a store that anything with the app's files can read, and opens it again. */
interface SecretSealer {
    fun seal(plain: ByteArray): ByteArray

    /** The bytes [seal] was given, or null when [sealed] is not this sealer's or was tampered with. */
    fun open(sealed: ByteArray): ByteArray?
}

/**
 * AES-GCM under a keystore key that never leaves the device; the 12-byte IV rides in front of the
 * ciphertext. Exercised on device only (KeystoreSecretSealerTest), like the Moonlight identity.
 */
class KeystoreSecretSealer : SecretSealer {
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }.onFailure { Log.w(TAG, "a sealed secret did not open: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "KeystoreSecretSealer"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "dish-satellite-keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
