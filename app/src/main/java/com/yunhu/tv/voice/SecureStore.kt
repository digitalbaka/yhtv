package com.yunhu.tv.voice

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 AndroidKeyStore 里的 AES/GCM 密钥加密本地保存的 token。
 *
 * 不引入第三方加密库；密钥材料由系统 Keystore 托管，不落在应用私有目录里。
 * 密文格式：Base64(iv + ciphertext)。
 */
object SecureStore {

    private const val ALIAS = "yunhu_voice_secret"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** 加密；失败返回空串（等同于未填写，不会把明文写进去） */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + enc, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    /** 解密；密文损坏或密钥失效时返回空串，按「未填写」处理 */
    fun decrypt(blob: String): String {
        if (blob.isEmpty()) return ""
        return try {
            val all = Base64.decode(blob, Base64.NO_WRAP)
            if (all.size <= IV_BYTES) return ""
            val iv = all.copyOfRange(0, IV_BYTES)
            val data = all.copyOfRange(IV_BYTES, all.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(ALIAS, null)
        if (existing is KeyStore.SecretKeyEntry) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
