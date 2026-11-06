package com.greatwy8888.webworkshop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import brut.androlib.ApkBuilder
import brut.androlib.ApkDecoder
import brut.androlib.android.ApktoolAndroid
import com.android.apksig.ApkSigner
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Date
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.security.auth.x500.X500Principal

object ApkGenerator {
    data class Request(
        val appName: String,
        val mode: String,
        val url: String,
        val localPath: String?,
        val localBase: String?,
        val iconUri: Uri?,
        val splashUri: Uri?
    )

    fun build(context: Context, request: Request): File {
        require(request.appName.isNotBlank()) { "请填写应用名称" }
        if (request.mode == "url") {
            require(request.url.startsWith("https://") || request.url.startsWith("http://")) { "请输入完整网址（http:// 或 https://）" }
        } else {
            require(!request.localPath.isNullOrBlank() && File(request.localPath).exists()) { "请先选择本地 HTML 或导入网页文件夹" }
        }

        val prefs = context.getSharedPreferences("webworkshop", Context.MODE_PRIVATE)
        val identity = request.mode + "|" + request.appName.trim().lowercase() + "|" +
            if (request.mode == "url") request.url.trim().lowercase() else "local-html"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val packageName = "com.webworkshop.app.p" + digest.take(12)
        val versionKey = "version_" + digest.take(16)
        val versionCode = prefs.getInt(versionKey, 0) + 1
        prefs.edit().putInt(versionKey, versionCode).apply()

        val work = File(context.cacheDir, "apk_generation").apply { deleteRecursively(); mkdirs() }
        val template = File(work, "template.apk")
        context.assets.open("generated-template.apk").use { input -> template.outputStream().use { input.copyTo(it) } }

        val config = ApktoolAndroid.configure(context)
        config.isForced = true
        val decoded = File(work, "decoded").apply { mkdirs() }
        ApkDecoder(template, config).decode(decoded)

        val manifest = File(decoded, "AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest)
        val root = document.documentElement
        root.setAttribute("package", packageName)
        root.setAttributeNS(ANDROID_NS, "android:versionCode", versionCode.toString())
        root.setAttributeNS(ANDROID_NS, "android:versionName", "1.0." + (versionCode - 1))
        val app = root.getElementsByTagName("application").item(0) as Element
        app.setAttributeNS(ANDROID_NS, "android:label", request.appName.trim())
        app.setAttributeNS(ANDROID_NS, "android:allowBackup", "false")
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }.transform(DOMSource(document), StreamResult(manifest))

        val assets = File(decoded, "assets").apply { mkdirs() }
        val json = org.json.JSONObject().put("mode", request.mode)
        if (request.mode == "url") {
            json.put("url", request.url)
        } else {
            val source = File(request.localPath!!)
            val site = File(assets, "site").apply { deleteRecursively(); mkdirs() }
            val base = request.localBase.orEmpty()
            if (base.contains("site_project")) {
                val rootDir = File(context.filesDir, "site_project")
                require(rootDir.exists()) { "本地网页目录已不存在，请重新导入" }
                copyTree(rootDir, site)
                val entry = listOf("index.html", "index.htm", "main.html").firstOrNull { File(site, it).exists() }
                    ?: throw IllegalStateException("找不到网页入口文件")
                json.put("entry", entry)
            } else {
                source.copyTo(File(site, "index.html"), overwrite = true)
                json.put("entry", "index.html")
            }
        }
        File(assets, "webworkshop.json").writeText(json.toString(), Charsets.UTF_8)

        request.iconUri?.let { replaceImage(context, it, File(decoded, "res/drawable/ic_webworkshop.xml")) }
        request.splashUri?.let { replaceImage(context, it, File(decoded, "res/drawable/splash.xml")) }

        val unsigned = File(work, "unsigned.apk")
        ApkBuilder(decoded, config).build(unsigned)
        val signed = File(context.filesDir, "exports/" + safeName(request.appName) + "_" + packageName.substringAfterLast('.') + ".apk")
        signed.parentFile?.mkdirs()
        sign(context, packageName, unsigned, signed)
        return signed
    }

    private fun sign(context: Context, packageName: String, input: File, output: File) {
        val alias = "webworkshop_" + packageName.substringAfterLast('.')
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=WebWorkshop " + packageName))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 86400000L))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 30L * 365 * 86400000L))
                    .build()
            )
            generator.generateKeyPair()
        }
        val privateKey = store.getKey(alias, null) as PrivateKey
        val certificate = store.getCertificate(alias)
        val signer = ApkSigner.SignerConfig.Builder("WebWorkshop", privateKey, listOf(certificate as java.security.cert.X509Certificate)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    private fun replaceImage(context: Context, uri: Uri, oldResource: File) {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("无法读取所选图片")
        val target = File(oldResource.parentFile, oldResource.nameWithoutExtension + ".png")
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        oldResource.delete()
        bitmap.recycle()
    }

    private fun copyTree(source: File, destination: File) {
        if (source.isDirectory) {
            destination.mkdirs()
            source.listFiles()?.forEach { child -> copyTree(child, File(destination, child.name)) }
        } else {
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
    }

    private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "web-app" }
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
}
