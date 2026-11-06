package com.greatwy8888.webworkshop

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Encrypted, exportable PKCS#12 identity for each generated Android app. */
object SigningKeyManager {
    data class Signer(val privateKey: PrivateKey, val certificate: X509Certificate)
    private const val MASTER_ALIAS = "webworkshop_signing_vault_aes"
    private const val STORE_ALIAS = "webworkshop"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun signer(context: Context, packageName: String): Signer {
        // Compatibility: keep using keys created by the first prototype on this same device.
        // AndroidKeyStore private keys cannot be exported, so these legacy projects cannot be backed up.
        legacySigner(packageName)?.let { return it }
        val file = encryptedFile(context, packageName)
        if (!file.exists()) createLocalStore(context, packageName, file)
        val buffer = ByteBuffer.wrap(decrypt(context, file.readBytes()))
        val passwordLength = buffer.int
        require(passwordLength in 8..128 && passwordLength <= buffer.remaining()) { "签名密钥数据损坏" }
        val password = ByteArray(passwordLength).also(buffer::get).toString(Charsets.UTF_8).toCharArray()
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        val store = KeyStore.getInstance("PKCS12").apply { load(bytes.inputStream(), password) }
        val key = store.getKey(STORE_ALIAS, password) as? PrivateKey
            ?: throw IllegalStateException("找不到项目签名私钥")
        val cert = store.getCertificate(STORE_ALIAS) as? X509Certificate
            ?: throw IllegalStateException("找不到项目签名证书")
        password.fill('\u0000')
        return Signer(key, cert)
    }

    fun exportBackup(context: Context, packageName: String, backupPassword: CharArray, output: File) {
        if (legacySigner(packageName) != null) {
            throw IllegalStateException("此项目使用旧版 AndroidKeyStore 签名，私钥不可导出。请保留原手机及网页工坊数据；新项目可使用可备份签名。")
        }
        require(backupPassword.size >= 8) { "备份密码至少需要 8 位" }
        val buffer = ByteBuffer.wrap(decrypt(context, encryptedFile(context, packageName).readBytes()))
        val length = buffer.int
        val localPassword = ByteArray(length).also(buffer::get).toString(Charsets.UTF_8).toCharArray()
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        val source = KeyStore.getInstance("PKCS12").apply { load(bytes.inputStream(), localPassword) }
        val key = source.getKey(STORE_ALIAS, localPassword)
        val cert = source.getCertificate(STORE_ALIAS)
        val backup = KeyStore.getInstance("PKCS12").apply {
            load(null, backupPassword)
            setKeyEntry(STORE_ALIAS, key, backupPassword, arrayOf(cert))
        }
        FileOutputStream(output).use { backup.store(it, backupPassword) }
        localPassword.fill('\u0000')
    }

    fun importBackup(context: Context, packageName: String, backupPassword: CharArray, input: File) {
        if (legacySigner(packageName) != null) {
            throw IllegalStateException("本机已有该项目的旧版签名，不能直接替换，否则已安装 APK 将无法升级。")
        }
        val source = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(input).use { load(it, backupPassword) }
        }
        val key = source.getKey(STORE_ALIAS, backupPassword) as? PrivateKey
            ?: throw IllegalArgumentException("备份文件中没有签名私钥")
        val cert = source.getCertificate(STORE_ALIAS) as? X509Certificate
            ?: throw IllegalArgumentException("备份文件中没有签名证书")
        require(cert.subjectX500Principal.name.contains(packageName)) { "该签名备份不属于当前项目，请选择正确的备份文件" }
        val localPassword = randomPassword()
        val localStore = KeyStore.getInstance("PKCS12").apply {
            load(null, localPassword)
            setKeyEntry(STORE_ALIAS, key, localPassword, arrayOf(cert))
        }
        encryptedFile(context, packageName).apply {
            parentFile?.mkdirs()
            writeBytes(encrypt(context, localStoreBytes(localStore, localPassword)))
        }
        localPassword.fill('\u0000')
    }

    private fun legacySigner(packageName: String): Signer? {
        val alias = "webworkshop_" + packageName.substringAfterLast('.')
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) return null
        val key = store.getKey(alias, null) as? PrivateKey ?: return null
        val cert = store.getCertificate(alias) as? X509Certificate ?: return null
        return Signer(key, cert)
    }

    private fun createLocalStore(context: Context, packageName: String, file: File) {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val pair = generator.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=WebWorkshop $packageName")
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(now), Date(now - 86_400_000L),
            Date(now + 30L * 365 * 86_400_000L), subject, pair.public
        )
        val certificate = JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        )
        certificate.verify(pair.public)
        val password = randomPassword()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry(STORE_ALIAS, pair.private, password, arrayOf(certificate))
        }
        file.parentFile?.mkdirs()
        file.writeBytes(encrypt(context, localStoreBytes(store, password)))
        password.fill('\u0000')
    }

    private fun localStoreBytes(store: KeyStore, password: CharArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        store.store(out, password)
        val passBytes = String(password).toByteArray(Charsets.UTF_8)
        val storeBytes = out.toByteArray()
        return ByteBuffer.allocate(4 + passBytes.size + storeBytes.size)
            .putInt(passBytes.size).put(passBytes).put(storeBytes).array()
    }

    private fun randomPassword(): CharArray {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%+="
        val random = SecureRandom()
        return CharArray(32) { chars[random.nextInt(chars.length)] }
    }

    private fun encryptedFile(context: Context, packageName: String) =
        File(context.filesDir, "project_signing/${packageName.substringAfterLast('.')}.p12.enc")

    private fun masterKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(MASTER_ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build()
            )
            generator.generateKey()
        }
        return store.getKey(MASTER_ALIAS, null) as SecretKey
    }

    private fun encrypt(context: Context, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(context: Context, encrypted: ByteArray): ByteArray {
        require(encrypted.size > 12) { "本机签名密钥文件损坏" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), javax.crypto.spec.GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
    }
}
