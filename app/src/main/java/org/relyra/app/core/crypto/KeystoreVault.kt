package org.relyra.app.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps secrets with a non-exportable AES-256-GCM key held in Android Keystore. */
object KeystoreVault {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "relyra_master_v1"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun wrap(plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, masterKey())
        c.updateAAD(aad)
        return c.iv + c.doFinal(plain)
    }

    fun unwrap(blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "blob too short" }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LEN)))
        c.updateAAD(aad)
        return c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }
}
