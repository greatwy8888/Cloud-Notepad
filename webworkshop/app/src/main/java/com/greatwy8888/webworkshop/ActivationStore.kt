package com.greatwy8888.webworkshop

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the server-issued activation receipt encrypted with an Android Keystore key.
 * The receipt survives app upgrades, but is lost when app data is cleared or the app is reinstalled.
 */
object ActivationStore {
    private const val KEY_ALIAS = "webworkshop_activation_receipt_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun isActivated(context: Context): Boolean = load(context) != null

    fun save(context: Context, receiptJson: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.iv + cipher.doFinal(receiptJson.toByteArray(Charsets.UTF_8))
        val target = receiptFile(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(encrypted)
        if (target.exists() && !target.delete()) throw IllegalStateException("无法更新本机授权文件")
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("无法保存本机授权文件")
        }
    }

    fun load(context: Context): String? {
        val file = receiptFile(context)
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size <= 12) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, bytes.copyOfRange(0, 12))
            )
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun receiptFile(context: Context) = File(context.filesDir, "license/activation.receipt")

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        }
        return store.getKey(KEY_ALIAS, null) as SecretKey
    }
}
