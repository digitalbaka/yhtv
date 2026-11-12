package com.yunhu.pad.tts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 在线 TTS 的 key 本地加密存储。
 * 使用 AndroidKeyStore 生成不可导出的 AES 密钥，AES/GCM 加密后只把
 * 「IV + 密文」的 Base64 写进 SharedPreferences，任何日志和界面都不回显明文。
 *
 * AndroidKeyStore 的 AES 需要 API 23；低版本设备直接拒绝保存，
 * 绝不退化成明文存储。
 */
object SecretStore {

    private const val PREF = "yhpad_tts_secure"
    private const val FIELD = "online_key_cipher"
    private const val KEY_ALIAS = "yhpad_tts_online_key"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** API 23 以下无法使用 AndroidKeyStore 的 AES，返回 false 由调用方提示 */
    val supported: Boolean get() = android.os.Build.VERSION.SDK_INT >= 23

    fun hasKey(ctx: Context): Boolean =
        prefs(ctx).getString(FIELD, "").orEmpty().isNotEmpty()

    /** 保存明文 key（仅在内存中短暂存在），失败返回 false，不抛异常、不打印 */
    fun save(ctx: Context, plain: String): Boolean {
        if (!supported || plain.isEmpty()) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(encrypted, 0, packed, iv.size, encrypted.size)
            prefs(ctx).edit()
                .putString(FIELD, Base64.encodeToString(packed, Base64.NO_WRAP))
                .apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 读取解密后的 key；未配置或解密失败返回空串 */
    fun load(ctx: Context): String {
        if (!supported) return ""
        val packed = try {
            Base64.decode(prefs(ctx).getString(FIELD, "").orEmpty(), Base64.NO_WRAP)
        } catch (e: Exception) {
            return ""
        }
        if (packed.size <= IV_BYTES) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES)
            )
            String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    /** 清除密文，同时删除 AndroidKeyStore 里对应的那条密钥，避免残留 */
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(FIELD).apply()
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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
