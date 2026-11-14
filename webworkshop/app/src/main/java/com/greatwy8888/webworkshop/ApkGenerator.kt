package com.greatwy8888.webworkshop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
        val projectIdentity: String,
        val iconBackgroundUri: Uri? = null,
        val iconBackgroundColor: String = "#FF315C9B",
        val iconBackgroundColor2: String = "#FF8A45B8",
        val iconBackgroundGradient: String = "linear",
        val iconBackgroundDirection: Int = 0,
        val iconShadowEnabled: Boolean = false,
        val iconShadowColor: String = "#99000000",
        val iconShadowBlur: Int = 12,
        val iconGlowEnabled: Boolean = false,
        val iconGlowColor: String = "#FF62B8FF",
        val iconGlowBlur: Int = 16,
        val iconOutlineEnabled: Boolean = false,
        val iconOutlineColor: String = "#FFFFFFFF",
        val iconOutlineBlur: Int = 3
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
            val json = org.json.JSONObject().put("mode", request.mode).put("hasSplash", request.splashUri != null)
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
                createAdaptiveIconResources(
                    context, decoded, it, request.iconBackgroundUri,
                    request.iconBackgroundColor, request.iconBackgroundColor2,
                    request.iconBackgroundGradient, request.iconBackgroundDirection,
                    request.iconShadowEnabled, request.iconShadowColor, request.iconShadowBlur,
                    request.iconGlowEnabled, request.iconGlowColor, request.iconGlowBlur,
                    request.iconOutlineEnabled, request.iconOutlineColor, request.iconOutlineBlur
                )
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

    private fun createAdaptiveIconResources(
        context: Context,
        decoded: File,
        iconUri: Uri,
        backgroundUri: Uri?,
        backgroundColor: String,
        backgroundColor2: String,
        backgroundGradient: String,
        backgroundDirection: Int,
        shadowEnabled: Boolean,
        shadowColor: String,
        shadowBlur: Int,
        glowEnabled: Boolean,
        glowColor: String,
        glowBlur: Int,
        outlineEnabled: Boolean,
        outlineColor: String,
        outlineBlur: Int
    ) {
        val drawableDir = File(decoded, "res/drawable").apply { mkdirs() }
        val adaptiveDir = File(decoded, "res/mipmap-anydpi-v26").apply { mkdirs() }
        val fallbackDir = File(decoded, "res/mipmap-mdpi").apply { mkdirs() }

        fun parseColor(value: String, fallback: Int): Int =
            try { Color.parseColor(value.trim()) } catch (_: Exception) { fallback }

        val original = readBitmap(context, iconUri)
        val extracted = try { removeNearWhiteEdgeBackground(original) } finally { original.recycle() }
        val foreground = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(foreground)
            // Keep the subject inside Android's adaptive-icon safe zone (about 66% of the canvas).
            val safeSize = 512f * 0.66f
            val scale = minOf(safeSize / extracted.width, safeSize / extracted.height)
            val w = extracted.width * scale
            val h = extracted.height * scale
            val left = (512f - w) / 2f
            val top = (512f - h) / 2f
            canvas.drawBitmap(extracted, null, android.graphics.RectF(left, top, left + w, top + h),
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))

            // Effects are drawn from the foreground alpha mask, behind the original artwork.
            // This keeps shadows/glows generic and independent of the uploaded image's subject.
            fun drawMaskEffect(colorValue: String, blur: Int, dx: Float = 0f, dy: Float = 0f) {
                val radius = blur.coerceIn(0, 48)
                val offset = IntArray(2)
                val maskPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    if (radius > 0) maskFilter = android.graphics.BlurMaskFilter(
                        radius.toFloat(), android.graphics.BlurMaskFilter.Blur.NORMAL
                    )
                }
                val mask = foreground.extractAlpha(maskPaint, offset)
                try {
                    val tint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = android.graphics.PorterDuffColorFilter(
                            parseColor(colorValue, Color.BLACK), android.graphics.PorterDuff.Mode.SRC_IN
                        )
                    }
                    canvas.drawBitmap(mask, offset[0] + dx, offset[1] + dy, tint)
                } finally {
                    mask.recycle()
                }
            }

            if (glowEnabled) drawMaskEffect(glowColor, glowBlur)
            if (shadowEnabled) drawMaskEffect(shadowColor, shadowBlur, 0f, 9f)
            if (outlineEnabled) drawMaskEffect(outlineColor, outlineBlur)
            canvas.drawBitmap(foreground.copy(Bitmap.Config.ARGB_8888, false), 0f, 0f, null)
            FileOutputStream(File(drawableDir, "ic_launcher_foreground.png")).use {
                check(foreground.compress(Bitmap.CompressFormat.PNG, 100, it)) { "自适应图标前景层生成失败" }
            }
        } finally {
            foreground.recycle()
            extracted.recycle()
        }

        val color1 = parseColor(backgroundColor, Color.rgb(49, 92, 155))
        val color2 = parseColor(backgroundColor2, Color.rgb(138, 69, 184))
        val background = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            val bgCanvas = android.graphics.Canvas(background)
            if (backgroundUri != null) {
                val bgSource = readBitmap(context, backgroundUri)
                try {
                    val scale = maxOf(512f / bgSource.width, 512f / bgSource.height)
                    val w = bgSource.width * scale
                    val h = bgSource.height * scale
                    val dst = android.graphics.RectF((512f - w) / 2f, (512f - h) / 2f,
                        (512f + w) / 2f, (512f + h) / 2f)
                    bgCanvas.drawBitmap(bgSource, null, dst,
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
                } finally { bgSource.recycle() }
            } else if (backgroundGradient == "radial") {
                val shader = android.graphics.RadialGradient(
                    256f, 256f, 370f, color1, color2, android.graphics.Shader.TileMode.CLAMP
                )
                bgCanvas.drawPaint(android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            } else if (backgroundGradient == "linear") {
                val points = when (backgroundDirection.coerceIn(0, 3)) {
                    1 -> floatArrayOf(0f, 256f, 512f, 256f)
                    2 -> floatArrayOf(256f, 0f, 256f, 512f)
                    3 -> floatArrayOf(512f, 0f, 0f, 512f)
                    else -> floatArrayOf(0f, 0f, 512f, 512f)
                }
                val shader = android.graphics.LinearGradient(
                    points[0], points[1], points[2], points[3],
                    color1, color2, android.graphics.Shader.TileMode.CLAMP
                )
                bgCanvas.drawPaint(android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            } else {
                bgCanvas.drawColor(color1)
            }

            // When a background photo is supplied, lightly blend the chosen gradient over it.
            if (backgroundUri != null && backgroundGradient != "solid") {
                val overlayColors = intArrayOf(
                    (color1 and 0x00FFFFFF) or 0x55000000,
                    (color2 and 0x00FFFFFF) or 0x55000000
                )
                val overlayShader: android.graphics.Shader = if (backgroundGradient == "radial") {
                    android.graphics.RadialGradient(256f, 256f, 370f, overlayColors[0], overlayColors[1], android.graphics.Shader.TileMode.CLAMP)
                } else {
                    val p = when (backgroundDirection.coerceIn(0, 3)) {
                        1 -> floatArrayOf(0f, 256f, 512f, 256f)
                        2 -> floatArrayOf(256f, 0f, 256f, 512f)
                        3 -> floatArrayOf(512f, 0f, 0f, 512f)
                        else -> floatArrayOf(0f, 0f, 512f, 512f)
                    }
                    android.graphics.LinearGradient(p[0], p[1], p[2], p[3], overlayColors[0], overlayColors[1], android.graphics.Shader.TileMode.CLAMP)
                }
                bgCanvas.drawPaint(android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { shader = overlayShader })
            }

            FileOutputStream(File(drawableDir, "ic_launcher_background.png")).use {
                check(background.compress(Bitmap.CompressFormat.PNG, 100, it)) { "自适应图标背景层生成失败" }
            }

            // Flatten both layers for launchers that do not support adaptive icons.
            val flattened = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            try {
                val flatCanvas = android.graphics.Canvas(flattened)
                flatCanvas.drawBitmap(background, 0f, 0f, null)
                val sourceForeground = readBitmapFromFile(File(drawableDir, "ic_launcher_foreground.png"))
                try { flatCanvas.drawBitmap(sourceForeground, 0f, 0f, null) }
                finally { sourceForeground.recycle() }
                FileOutputStream(File(fallbackDir, "ic_launcher.png")).use {
                    check(flattened.compress(Bitmap.CompressFormat.PNG, 100, it)) { "传统 PNG 图标生成失败" }
                }
            } finally { flattened.recycle() }
        } finally { background.recycle() }

        File(adaptiveDir, "ic_launcher.xml").writeText(
            """<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/ic_launcher_background"/><foreground android:drawable="@drawable/ic_launcher_foreground"/></adaptive-icon>""",
            Charsets.UTF_8
        )

        val manifest = File(decoded, "AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest)
        val app = document.getElementsByTagName("application").item(0) as Element
        app.setAttributeNS(ANDROID_NS, "android:icon", "@mipmap/ic_launcher")
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(document), StreamResult(manifest))
    }

    private fun readBitmapFromFile(file: File): Bitmap =
        file.inputStream().use { BitmapFactory.decodeStream(it) }
        
    private fun readBitmap(context: Context, uri: Uri): Bitmap {
        val input = if (uri.scheme == "file") File(uri.path ?: "").takeIf { it.isFile }?.inputStream()
            else context.contentResolver.openInputStream(uri)
        return input?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("无法读取图标图片：$uri")
    }

    private fun replaceImage(context: Context, uri: Uri, oldResource: File, maxWidth: Int, maxHeight: Int, removeWhiteBackground: Boolean = false) {
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
            val prepared = if (removeWhiteBackground) removeNearWhiteEdgeBackground(decoded) else decoded
            try {
                val canvasBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = android.graphics.Canvas(canvasBitmap)
                    if (!removeWhiteBackground) canvas.drawColor(Color.BLACK)
                    val scale = minOf(maxWidth.toFloat() / prepared.width, maxHeight.toFloat() / prepared.height)
                    val drawWidth = prepared.width * scale
                    val drawHeight = prepared.height * scale
                    val left = (maxWidth - drawWidth) / 2f
                    val top = (maxHeight - drawHeight) / 2f
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(prepared, null, android.graphics.RectF(left, top, left + drawWidth, top + drawHeight), paint)
                    val target = File(oldResource.parentFile, oldResource.nameWithoutExtension + ".png")
                    FileOutputStream(target).use {
                        check(canvasBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片转换失败" }
                    }
                    oldResource.delete()
                } finally {
                    canvasBitmap.recycle()
                }
            } finally {
                if (prepared !== decoded) prepared.recycle()
                decoded.recycle()
            }
        } finally {
            source.delete()
        }
    }


    // Generic white-background cleanup for uploaded app icons:
    // only near-white pixels connected to the outer image edge become transparent.
    // The rule depends on pixel color and edge connectivity, never on the depicted subject.
    private fun removeNearWhiteEdgeBackground(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0
        fun isBackground(index: Int): Boolean {
            val color = pixels[index]
            val a = Color.alpha(color)
            return a > 0 && Color.red(color) >= 238 && Color.green(color) >= 238 && Color.blue(color) >= 238
        }
        fun add(index: Int) {
            if (!visited[index] && isBackground(index)) {
                visited[index] = true
                queue[tail++] = index
            }
        }
        for (x in 0 until width) { add(x); add((height - 1) * width + x) }
        for (y in 0 until height) { add(y * width); add(y * width + width - 1) }
        while (head < tail) {
            val index = queue[head++]
            pixels[index] = Color.TRANSPARENT
            val x = index % width
            val y = index / width
            if (x > 0) add(index - 1)
            if (x + 1 < width) add(index + 1)
            if (y > 0) add(index - width)
            if (y + 1 < height) add(index + width)
            if (x > 0 && y > 0) add(index - width - 1)
            if (x + 1 < width && y > 0) add(index - width + 1)
            if (x > 0 && y + 1 < height) add(index + width - 1)
            if (x + 1 < width && y + 1 < height) add(index + width + 1)
        }
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (Color.alpha(pixels[y * width + x]) != 0) {
                minX = minOf(minX, x); minY = minOf(minY, y)
                maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
            }
        }
        require(maxX >= minX && maxY >= minY) { "图标背景处理失败：没有识别到主体图案" }
        val cropped = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val result = Bitmap.createBitmap(cropped, minX, minY, maxX - minX + 1, maxY - minY + 1)
        // Android may return the original bitmap when the crop covers the full image.
        // Recycling 'cropped' unconditionally would then recycle 'result' as well.
        if (result !== cropped) cropped.recycle()
        return result
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
