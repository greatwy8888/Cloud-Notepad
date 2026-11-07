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
import com.android.apksig.ApkVerifier
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
        val splashUri: Uri?,
        val projectIdentity: String
    )

    fun packageNameFor(mode: String, projectIdentity: String): String {
        require(projectIdentity.isNotBlank()) { "项目身份信息缺失，请重新选择网页来源" }
        val identity = mode + "|" + projectIdentity.trim()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "com.webworkshop.app.p" + digest.take(12)
    }

    fun resolvePackageName(context: Context, request: Request): String {
        val stablePackage = packageNameFor(request.mode, request.projectIdentity)
        val prefs = context.getSharedPreferences("webworkshop", Context.MODE_PRIVATE)
        val mappingKey = "legacy_package_for_" + stablePackage.substringAfterLast('.')
        prefs.getString(mappingKey, null)?.let { return it }

        // The first prototype included the display name and copied-file path in the package hash.
        // If its original AndroidKeyStore alias is still present, retain that package for an in-place upgrade.
        val oldProjectIdentity = if (request.mode == "url") {
            request.url.trim().lowercase()
        } else {
            val selected = File(request.localPath ?: "")
            val base = request.localBase.orEmpty()
            if (base.contains("site_project")) {
                "local-folder:" + File(context.filesDir, "site_project").canonicalPath
            } else {
                "local-file:" + selected.canonicalPath
            }
        }
        val oldIdentity = request.mode + "|" + request.appName.trim().lowercase() + "|" + oldProjectIdentity
        val oldDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(oldIdentity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val legacyPackage = "com.webworkshop.app.p" + oldDigest.take(12)
        if (SigningKeyManager.hasLegacyKey(legacyPackage)) {
            prefs.edit().putString(mappingKey, legacyPackage).apply()
            return legacyPackage
        }
        return stablePackage
    }

    fun build(context: Context, request: Request): File {
        var stage = "检查输入"
        try {
            require(request.appName.isNotBlank()) { "请填写应用名称" }
            if (request.mode == "url") {
                require(request.url.startsWith("https://") || request.url.startsWith("http://")) {
                    "请输入完整网址（http:// 或 https://）"
                }
            } else {
                require(!request.localPath.isNullOrBlank() && File(request.localPath).exists()) {
                    "请先选择本地 HTML 或导入网页文件夹"
                }
            }
            require(request.projectIdentity.isNotBlank()) { "项目身份信息缺失，请重新选择网页来源" }

            val prefs = context.getSharedPreferences("webworkshop", Context.MODE_PRIVATE)
            val packageName = resolvePackageName(context, request)
            val versionKey = "version_" + packageName.substringAfterLast('.').take(12)
            val versionCode = maxOf(
                prefs.getInt(versionKey, 0) + 1,
                (System.currentTimeMillis() / 1000L).toInt()
            )

            stage = "准备模板 APK"
            val work = File(context.cacheDir, "apk_generation").apply {
                deleteRecursively()
                check(mkdirs() || exists()) { "无法创建临时工作目录：$absolutePath" }
            }
            val template = File(work, "template.apk")
            context.assets.open("generated-template.apk").use { input ->
                template.outputStream().use { output -> input.copyTo(output) }
            }
            check(template.exists() && template.length() > 0L) { "内置模板 APK 为空或不存在" }

            stage = "初始化 APK 解包引擎"
            val config = ApktoolAndroid.configure(context)
            config.isForced = true

            stage = "解包模板 APK"
            val decoded = File(work, "decoded")
            ApkDecoder(template, config).decode(decoded)
            check(File(decoded, "AndroidManifest.xml").exists()) { "模板解包后没有 AndroidManifest.xml" }

            stage = "修改应用清单"
            val manifest = File(decoded, "AndroidManifest.xml")
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val document = factory.newDocumentBuilder().parse(manifest)
            val root = document.documentElement
            root.setAttribute("package", packageName)
            root.setAttributeNS(ANDROID_NS, "android:versionCode", versionCode.toString())
            root.setAttributeNS(ANDROID_NS, "android:versionName", "1.0." + (versionCode - 1))
            val app = root.getElementsByTagName("application").item(0) as? Element
                ?: throw IllegalStateException("模板清单中找不到 application 节点")
            app.setAttributeNS(ANDROID_NS, "android:label", request.appName.trim())
            app.setAttributeNS(ANDROID_NS, "android:allowBackup", "false")
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                setOutputProperty(OutputKeys.INDENT, "yes")
            }.transform(DOMSource(document), StreamResult(manifest))

            stage = "写入网页内容"
            val assets = File(decoded, "assets").apply { mkdirs() }
            val json = org.json.JSONObject().put("mode", request.mode)
            if (request.mode == "url") {
                json.put("url", request.url.trim())
            } else {
                val source = File(request.localPath!!)
                val site = File(assets, "site").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val base = request.localBase.orEmpty()
                if (base.contains("site_project")) {
                    val rootDir = File(context.filesDir, "site_project")
                    require(rootDir.exists()) { "本地网页目录已不存在，请重新导入" }
                    copyTree(rootDir, site)
                    val entry = listOf("index.html", "index.htm", "main.html")
                        .firstOrNull { File(site, it).exists() }
                        ?: throw IllegalStateException("找不到网页入口文件")
                    json.put("entry", entry)
                } else {
                    require(source.isFile) { "选择的 HTML 文件不存在或不是文件" }
                    source.copyTo(File(site, "index.html"), overwrite = true)
                    json.put("entry", "index.html")
                }
            }
            File(assets, "webworkshop.json").writeText(json.toString(), Charsets.UTF_8)

            stage = "处理自定义图片"
            request.iconUri?.let {
                replaceImage(context, it, File(decoded, "res/drawable/ic_webworkshop.xml"), 512, 512)
            }
            request.splashUri?.let {
                replaceImage(context, it, File(decoded, "res/drawable/splash.xml"), 1080, 1920)
            }

            stage = "重新打包 APK"
            val unsigned = File(work, "unsigned.apk")
            ApkBuilder(decoded, config).build(unsigned)
            check(unsigned.exists() && unsigned.length() > 0L) { "APK 解包引擎未生成有效文件" }

            stage = "生成项目签名"
            val signed = File(
                context.filesDir,
                "exports/" + safeName(request.appName) + "_" + packageName.substringAfterLast('.') + ".apk"
            )
            signed.parentFile?.let { check(it.mkdirs() || it.exists()) { "无法创建 APK 导出目录" } }

            stage = "签名 APK"
            sign(context, packageName, unsigned, signed)
            check(signed.exists() && signed.length() > 0L) { "签名后 APK 文件不存在或为空" }

            stage = "校验 APK 签名"
            val verification = ApkVerifier.Builder(signed).build().verify()
            check(verification.isVerified) {
                "APK 签名校验失败：" + verification.errors.joinToString("; ")
            }
            prefs.edit().putInt(versionKey, versionCode).apply()
            return signed
        } catch (e: Exception) {
            val detail = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            throw IllegalStateException("[$stage] $detail", e)
        }
    }

    private fun sign(context: Context, packageName: String, input: File, output: File) {
        val identity = SigningKeyManager.signer(context, packageName)
        val signer = ApkSigner.SignerConfig.Builder(
            "WebWorkshop", identity.privateKey, listOf(identity.certificate)
        ).build()
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

    private fun replaceImage(context: Context, uri: Uri, oldResource: File, maxWidth: Int, maxHeight: Int) {
        // Read the selected image once into a private temporary file. Some document providers
        // revoke transient URI grants or do not support a second openInputStream call.
        val source = File(context.cacheDir, "webworkshop-image-${System.nanoTime()}.img")
        try {
            val input = when (uri.scheme) {
                "file" -> File(uri.path ?: "").takeIf { it.isFile }?.inputStream()
                else -> context.contentResolver.openInputStream(uri)
            } ?: throw IllegalStateException("无法读取所选图片（URI：${uri.scheme ?: "未知"}）")
            input.use { stream -> source.outputStream().use { stream.copyTo(it) } }
            check(source.length() > 0L) { "所选图片文件为空" }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            source.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "图片格式无法识别，请使用 PNG、JPG 或 WebP"
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth &&
                bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = source.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IllegalStateException("图片解码失败")
            // The generated splash resource is always a 9:16 canvas. Fit the original
            // image inside it (never crop or stretch), center it, and use black letterboxing.
            val canvasBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(canvasBitmap)
                canvas.drawColor(Color.BLACK)
                val scale = minOf(maxWidth.toFloat() / decoded.width, maxHeight.toFloat() / decoded.height)
                val drawWidth = decoded.width * scale
                val drawHeight = decoded.height * scale
                val left = (maxWidth - drawWidth) / 2f
                val top = (maxHeight - drawHeight) / 2f
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(decoded, null, android.graphics.RectF(left, top, left + drawWidth, top + drawHeight), paint)
                val target = File(oldResource.parentFile, oldResource.nameWithoutExtension + ".png")
                FileOutputStream(target).use {
                    check(canvasBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片转换失败" }
                }
                oldResource.delete()
            } finally {
                canvasBitmap.recycle()
                decoded.recycle()
            }
        } finally {
            source.delete()
        }
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
