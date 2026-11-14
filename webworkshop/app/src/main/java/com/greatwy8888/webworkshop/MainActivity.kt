package com.greatwy8888.webworkshop

import android.os.Bundle
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import androidx.documentfile.provider.DocumentFile
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import android.content.Intent
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : androidx.activity.ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var address: EditText
    private lateinit var appName: EditText
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var mode = "url"
    private var localHtml: String? = null
    private var localHtmlBaseUrl: String? = null
    private var iconUri: Uri? = null
    private var iconFitMode = "manual" // original = untouched upload; manual = exact user-selected preview crop
    private var splashUri: Uri? = null
    private var showingPreview = false
    private val prefs by lazy { getSharedPreferences("webworkshop", Context.MODE_PRIVATE) }
    private var historyDialog: AlertDialog? = null
    private var activationInProgress = false
    private var homeScrollView: ScrollView? = null
    private var savedHomeScrollY = 0
    private var previewPageUrl: String? = null
    private var activePreviewWebView: WebView? = null
    private var pendingClearAfterInstall = false
    private var pendingClearAfterSave = false

    private val htmlPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val htmlFile = File(filesDir, "imported_page.html")
                contentResolver.openInputStream(it)?.use { input -> htmlFile.outputStream().use { output -> input.copyTo(output) } }
                localHtml = htmlFile.readText()
                localHtmlBaseUrl = "file://${filesDir.absolutePath}/"
                prefs.edit().putString("local_html_path", htmlFile.absolutePath)
                    .putString("local_project_identity", "file:" + it.toString()).apply()
                mode = "html"
                val displayName = it.lastPathSegment?.substringAfterLast("/") ?: "页面.html"
                prefs.edit().putString("local_html_name", displayName).putString("mode", "html").apply()
                status.text = "已选择本地 HTML：$displayName"
                showHome()
                status.text = "已选择本地 HTML：$displayName，可立即预览或生成 APK"
            } catch (e: Exception) {
                status.text = "读取失败：${e.localizedMessage ?: "无法读取文件"}"
            }
        }
    }
    private val siteFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val source = DocumentFile.fromTreeUri(this, it) ?: throw IllegalStateException("无法打开文件夹")
                val target = File(filesDir, "site_project")
                target.deleteRecursively()
                target.mkdirs()
                copySiteTree(source, target)
                val entry = listOf("index.html", "index.htm", "main.html").map { name -> File(target, name) }.firstOrNull { file -> file.exists() }
                    ?: throw IllegalStateException("文件夹中没有 index.html、index.htm 或 main.html")
                localHtml = entry.readText()
                localHtmlBaseUrl = "file://${target.absolutePath}/"
                prefs.edit().putString("local_html_path", entry.absolutePath)
                    .putString("local_html_base", localHtmlBaseUrl)
                    .putString("local_project_identity", "tree:" + it.toString()).apply()
                mode = "html"
                prefs.edit().putString("local_html_name", entry.name).putString("mode", "html").apply()
                status.text = "已导入网页文件夹：${entry.name}"
                showHome()
                status.text = "已导入网页文件夹：${entry.name}，可立即预览或生成 APK"
            } catch (e: Exception) {
                status.text = "导入失败：${e.localizedMessage ?: "请检查文件夹内容"}"
            }
        }
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                // Copy immediately into app-private storage. Do not depend on a document
                // provider's persistable URI permission remaining valid until APK generation.
                val folder = File(filesDir, "custom_images").apply {
                    check(mkdirs() || exists()) { "无法创建自定义图片目录" }
                }
                val name = if (pendingImage == "icon") "app_icon_original" else "app_splash"
                val mime = contentResolver.getType(uri).orEmpty()
                val extension = when (mime.lowercase()) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/jpeg", "image/jpg" -> "jpg"
                    else -> when (uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()) {
                        "png" -> "png"
                        "webp" -> "webp"
                        "jpg", "jpeg" -> "jpg"
                        else -> "img"
                    }
                }
                if (pendingImage == "icon") {
                    folder.listFiles()?.filter { it.name.startsWith("app_icon_original.") }?.forEach { it.delete() }
                }
                val saved = File(folder, "$name.$extension")
                contentResolver.openInputStream(uri)?.use { input ->
                    saved.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("系统无法读取所选图片")
                check(saved.length() > 0L) { "所选图片为空" }
                val savedUri = Uri.fromFile(saved)
                if (pendingImage == "icon") {
                    if (iconFitMode == "manual" || iconFitMode == "fit") {
                        showIconCropEditor(savedUri)
                    } else {
                        iconUri = savedUri
                        prefs.edit().putString("icon_uri", savedUri.toString()).apply()
                        showHome()
                        status.text = if (iconFitMode == "original") "已选择原始模式：图标文件将按原始图片数据写入，不进行图片处理" else "已选择图标原图；铺满模式保持原有处理方式"
                    }
                } else {
                    splashUri = savedUri
                    prefs.edit().putString("splash_uri", savedUri.toString()).apply()
                    showHome()
                    status.text = "启动画面已保存到本机，已更新预览"
                }
            } catch (e: Exception) {
                Toast.makeText(this, "图片导入失败：" + (e.localizedMessage ?: "无法读取图片"), Toast.LENGTH_LONG).show()
            }
        }
    }
    private var pendingImage = "icon"

    private var pendingSigningPackage: String? = null
    private var pendingSigningPassword: CharArray? = null
    private val signingBackupSavePicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pkcs12")
    ) { uri ->
        val pkg = pendingSigningPackage
        val password = pendingSigningPassword
        if (uri != null && pkg != null && password != null) Thread {
            try {
                val temp = File(cacheDir, "webworkshop-signing-backup.p12")
                SigningKeyManager.exportBackup(this, pkg, password, temp)
                contentResolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("无法写入备份文件")
                temp.delete()
                Handler(Looper.getMainLooper()).post { Toast.makeText(this, "签名备份已保存，请妥善保管", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { Toast.makeText(this, "备份失败：" + (e.localizedMessage ?: "未知错误"), Toast.LENGTH_LONG).show() }
            } finally { password.fill('\u0000'); pendingSigningPassword = null }
        }.start()
    }
    private val signingBackupOpenPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pkg = pendingSigningPackage
        val password = pendingSigningPassword
        if (uri != null && pkg != null && password != null) Thread {
            try {
                val temp = File(cacheDir, "webworkshop-signing-restore.p12")
                contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                    ?: throw IllegalStateException("无法读取备份文件")
                SigningKeyManager.importBackup(this, pkg, password, temp)
                temp.delete()
                Handler(Looper.getMainLooper()).post { Toast.makeText(this, "签名密钥恢复成功；该项目后续 APK 可继续覆盖升级", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { Toast.makeText(this, "恢复失败：" + (e.localizedMessage ?: "密码错误或文件无效"), Toast.LENGTH_LONG).show() }
            } finally { password.fill('\u0000'); pendingSigningPassword = null }
        }.start()
    }
    private var generatedApk: File? = null
    private val apkSavePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        if (uri != null) {
            try {
                val source = generatedApk ?: throw IllegalStateException("没有可保存的 APK")
                contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                    ?: throw IllegalStateException("无法写入目标文件")
                status.text = "APK 已保存到所选位置"
                Toast.makeText(this, "APK 保存成功", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                status.text = "保存失败：" + (e.localizedMessage ?: "未知错误")
            }
        }
        if (pendingClearAfterSave) {
            pendingClearAfterSave = false
            clearAfterGeneratedApkClosed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingClearAfterInstall) {
            pendingClearAfterInstall = false
            clearAfterGeneratedApkClosed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A fresh launch starts with a clean form. Keep build history, generated
        // APKs, per-project signing keys, package mappings and version counters.
        if (savedInstanceState == null) resetTransientOptions() else restoreTransientOptions()
        if (ActivationStore.isActivated(this)) showHome() else showActivationScreen()
    }

    private fun restoreTransientOptions() {
        mode = prefs.getString("mode", "url") ?: "url"
        val path = prefs.getString("local_html_path", null)
        if (!path.isNullOrBlank() && File(path).isFile) {
            localHtml = File(path).readText()
            localHtmlBaseUrl = prefs.getString("local_html_base", null)
                ?: "file://${File(path).parentFile?.absolutePath}/"
        }
        iconUri = prefs.getString("icon_uri", null)?.let { Uri.parse(it) }
        iconFitMode = (prefs.getString("icon_fit_mode", "manual") ?: "manual").let { if (it == "fit") "manual" else if (it == "fill") "original" else it }
        splashUri = prefs.getString("splash_uri", null)?.let { Uri.parse(it) }
    }

    private fun resetTransientOptions() {
        // Clear only the current form selection, not durable project identity/signing data.
        prefs.edit()
            .remove("icon_uri")
            .remove("icon_fit_mode")
            .remove("splash_uri")
            .remove("last_url")
            .remove("app_name")
            .remove("local_html_path")
            .remove("local_html_base")
            .remove("local_project_identity")
            .remove("local_html_name")
            .remove("mode")
            .apply()
        iconUri = null
        iconFitMode = "manual"
        splashUri = null
        localHtml = null
        localHtmlBaseUrl = null
        mode = "url"
        File(filesDir, "custom_images").deleteRecursively()
        File(filesDir, "imported_page.html").delete()
        File(filesDir, "site_project").deleteRecursively()
        File(cacheDir, "apk_generation").deleteRecursively()
        File(cacheDir, "webworkshop-preview-icon.img").delete()
        cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("webworkshop-image-") }?.forEach { it.delete() }
    }

    private fun clearAfterGeneratedApkClosed() {
        resetTransientOptions()
        activePreviewWebView?.let { view -> runCatching {
            view.stopLoading(); view.clearHistory(); view.clearFormData(); view.clearCache(true)
            view.loadUrl("about:blank"); view.removeAllViews(); view.destroy()
        } }
        activePreviewWebView = null
        showingPreview = false
        savedHomeScrollY = 0
        homeScrollView = null
        showHome()
        Toast.makeText(this, "已清空本次网页、上传图片和缓存；制作历史及签名信息已保留", Toast.LENGTH_LONG).show()
    }

    private fun captureHomeScrollPosition() { homeScrollView?.let { savedHomeScrollY = it.scrollY.coerceAtLeast(0) } }

    private fun imagePreview(uri: Uri?, caption: String, previewHeight: Int, kind: String): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        panel.addView(TextView(this).apply {
            text = caption
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(75, 96, 115))
        })
        val frame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(239, 244, 248))
        }
        if (kind == "icon") {
            frame.layoutParams = LinearLayout.LayoutParams(dp(180), dp(180))
            frame.background = roundedBackground(Color.rgb(239, 244, 248), Color.rgb(215, 225, 235))
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
        }
        if (uri != null) {
            try {
                (if (uri.scheme == "file") File(uri.path ?: "").takeIf { it.isFile }?.inputStream()
                    else contentResolver.openInputStream(uri))?.use { input ->
                    image.setImageBitmap(android.graphics.BitmapFactory.decodeStream(input))
                }
            } catch (_: Exception) { }
        }
        if (image.drawable == null) {
            image.setImageResource(android.R.drawable.ic_menu_gallery)
            image.alpha = 0.45f
        }
        frame.addView(image, android.widget.FrameLayout.LayoutParams(-1, -1))
        if (uri != null) {
            val remove = TextView(this).apply {
                text = "×"
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.rgb(218, 82, 82))
                    setStroke(dp(1), Color.WHITE)
                }
                elevation = dp(3).toFloat()
                contentDescription = "删除已选图片"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    when (kind) {
                        "icon" -> { iconUri = null; prefs.edit().remove("icon_uri").apply() }
                        else -> { splashUri = null; prefs.edit().remove("splash_uri").apply() }
                    }
                    captureHomeScrollPosition()
                    showHome()
                    Toast.makeText(this@MainActivity, "图片已删除", Toast.LENGTH_SHORT).show()
                }
            }
            frame.addView(remove, android.widget.FrameLayout.LayoutParams(dp(28), dp(28),
                android.view.Gravity.TOP or android.view.Gravity.END).apply {
                setMargins(0, dp(7), dp(7), 0)
            })
        }
        frame.isClickable = true
        frame.isFocusable = true
        frame.contentDescription = if (kind == "icon") "点击添加或更换应用图标" else "点击添加或更换启动画面"
        frame.setOnClickListener {
            captureHomeScrollPosition()
            pendingImage = kind
            imagePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
        }
        val previewParams = if (kind == "icon") LinearLayout.LayoutParams(dp(180), dp(180))
            else LinearLayout.LayoutParams(dp(124), previewHeight)
        previewParams.gravity = android.view.Gravity.CENTER_HORIZONTAL
        panel.addView(frame, previewParams)
        if (kind == "icon" && (iconFitMode == "manual" || iconFitMode == "fit") && uri != null) {
            panel.addView(button("调整图标位置 / 缩放 / 裁切") {
                captureHomeScrollPosition()
                val original = File(filesDir, "custom_images").listFiles()
                    ?.firstOrNull { it.name.startsWith("app_icon_original.") }
                if (original != null) showIconCropEditor(Uri.fromFile(original))
                else showIconCropEditor(uri)
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        }
        panel.addView(TextView(this).apply {
            text = if (uri == null) "点击上方方框选择图片" else "已选择图片 · 点击图片可更换，右上角 × 删除"
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(120, 135, 150))
        })
        return panel
    }

    private fun showIconCropEditor(sourceUri: Uri) {
        try {
            val bitmap = if (sourceUri.scheme == "file") {
                BitmapFactory.decodeFile(sourceUri.path)
            } else contentResolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("无法解码图标图片")
            if (bitmap == null) throw IllegalStateException("图片格式不受支持")
            val cropView = IconCropView(this, bitmap)
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(dp(18), dp(8), dp(18), dp(4))
                addView(TextView(this@MainActivity).apply {
                    text = "拖动图片调整位置，双指缩放。白色方框内保留，框外遮罩区域将被裁掉。仅裁切原图像素，不缩放、不调色、不添加滤镜、圆角、阴影或背景。"
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.rgb(83, 99, 116))
                    setPadding(0, 0, 0, dp(10))
                })
                addView(cropView, LinearLayout.LayoutParams(-1, dp(300)))
                addView(TextView(this@MainActivity).apply {
                    text = "导出将保留裁切区域的原始像素尺寸"
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.rgb(83, 99, 116))
                    setPadding(0, dp(10), 0, dp(2))
                })
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("手动模式 · 图标裁切")
                .setView(content)
                .setPositiveButton("应用裁切", null)
                .setNegativeButton("取消") { _, _ -> bitmap.recycle() }
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    try {
                        val cropped = cropView.exportOriginalPixelCrop()
                        val folder = File(filesDir, "custom_images").apply { mkdirs() }
                        val output = File(folder, "app_icon_manual_crop.png")
                        FileOutputStream(output).use { stream ->
                            check(cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "图标保存失败" }
                        }
                        cropped.recycle()
                        iconUri = Uri.fromFile(output)
                        prefs.edit().putString("icon_uri", iconUri.toString()).remove("icon_crop_size").apply()
                        bitmap.recycle()
                        dialog.dismiss()
                        showHome()
                        status.text = "手动模式：已应用原图像素裁切；未添加滤镜、圆角、阴影或背景"
                    } catch (e: Exception) {
                        Toast.makeText(this, e.localizedMessage ?: "裁切失败", Toast.LENGTH_LONG).show()
                    }
                }
            }
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(this, "打开图标编辑器失败：" + (e.localizedMessage ?: "图片读取失败"), Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(value: String) = TextView(this).apply {
        text = value; textSize = 14f; setTextColor(Color.rgb(49, 69, 89))
        setPadding(0, dp(14), 0, dp(5))
    }
    private fun roundedBackground(color: Int, stroke: Int = Color.TRANSPARENT) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(color)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(43, 83, 122))
        background = roundedBackground(Color.rgb(232, 241, 250), Color.rgb(213, 228, 242))
        elevation = dp(2).toFloat()
        minimumHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun primaryButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 15f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = roundedBackground(Color.rgb(43, 117, 190))
        elevation = dp(4).toFloat()
        minimumHeight = dp(52)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun showActivationScreen() {
        val alreadyActivated = ActivationStore.isActivated(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(242, 247, 252))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(22))
            background = roundedBackground(Color.WHITE, Color.rgb(225, 234, 243))
            elevation = dp(3).toFloat()
        }
        panel.addView(TextView(this).apply {
            text = "网页工坊授权激活"
            textSize = 23f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(28, 70, 111))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-1, dp(48)))
        panel.addView(TextView(this).apply {
            text = if (alreadyActivated) {
                "当前设备已激活。输入新的序列号可更换授权；如果取消或激活失败，当前授权仍然保留。"
            } else {
                "首次使用需要联网激活。每个序列号只能成功使用一次；激活成功后会保存在本机，可离线使用。"
            }
            textSize = 13f
            setTextColor(Color.rgb(91, 108, 125))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        })
        val serialInput = EditText(this).apply {
            hint = "请输入序列号，例如 WW-XXXX-XXXX"
            isSingleLine = true
            textSize = 15f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(Color.rgb(248, 251, 254), Color.rgb(220, 230, 240))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        panel.addView(serialInput, LinearLayout.LayoutParams(-1, dp(54)))
        val message = TextView(this).apply {
            text = "版本：${BuildConfig.VERSION_NAME}"
            textSize = 12f
            setTextColor(Color.rgb(105, 124, 142))
            setPadding(0, dp(10), 0, dp(10))
        }
        panel.addView(message)
        val activate = primaryButton(if (alreadyActivated) "使用新序列号重新激活" else "联网激活") {
            val serial = serialInput.text.toString().trim()
            if (serial.isBlank()) {
                message.text = "请先输入序列号"
                return@primaryButton
            }
            if (activationInProgress) return@primaryButton
            activationInProgress = true
            message.text = "正在连接授权服务器，请稍候……"
            serialInput.isEnabled = false
            activateSerial(serial) { success, result ->
                activationInProgress = false
                serialInput.isEnabled = true
                if (success) {
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                    showHome()
                } else {
                    message.text = result
                }
            }
        }
        panel.addView(activate, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) })
        if (alreadyActivated) {
            panel.addView(button("返回已激活的网页工坊") { showHome() },
                LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        }
        page.addView(panel, LinearLayout.LayoutParams(-1, -2))
        setContentView(page)
    }

    private fun activateSerial(serial: String, callback: (Boolean, String) -> Unit) {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val deviceId = MessageDigest.getInstance("SHA-256")
            .digest((androidId + "|" + packageName).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val payload = org.json.JSONObject()
                    .put("serial", serial)
                    .put("build_id", BuildConfig.VERSION_NAME)
                    .put("device_id", deviceId)
                    .put("package_name", packageName)
                    .put("app_version", BuildConfig.VERSION_NAME)
                connection = (URL("https://cloud-notepad.3985196992.workers.dev/api/webworkshop/activate")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val response = org.json.JSONObject(responseText)
                if (code in 200..299 && response.optBoolean("ok")) {
                    val receipt = org.json.JSONObject()
                        .put("activation_id", response.optString("activation_id"))
                        .put("build_id", response.optString("build_id"))
                        .put("activated_at", response.optLong("activated_at"))
                        .put("device_id", response.optString("device_id"))
                        .put("app_version", BuildConfig.VERSION_NAME)
                    ActivationStore.save(this, receipt.toString())
                    Handler(Looper.getMainLooper()).post { callback(true, "激活成功，网页工坊现在可以使用") }
                } else {
                    val error = response.optString("error").ifBlank { "激活失败（HTTP $code）" }
                    Handler(Looper.getMainLooper()).post { callback(false, error) }
                }
            } catch (e: Exception) {
                val error = "连接或保存激活信息失败：${e.localizedMessage ?: e.javaClass.simpleName}。请检查网络后重试。"
                Handler(Looper.getMainLooper()).post { callback(false, error) }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun showHome() {
        homeScrollView?.let { savedHomeScrollY = it.scrollY }
        showingPreview = false
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
            setBackgroundColor(Color.rgb(242, 247, 252))
        }
        val header = android.widget.FrameLayout(this)
        val menu = TextView(this).apply {
            text = "☰"; textSize = 25f; gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(43, 83, 122)); isClickable = true; isFocusable = true
            contentDescription = "历史制作"
            setOnClickListener { showHistoryDialog() }
        }
        header.addView(menu, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL))
        val heading = TextView(this).apply {
            text = "网页工坊"; textSize = 25f; gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(28, 70, 111)); setTypeface(null, android.graphics.Typeface.BOLD)
        }
        header.addView(heading, android.widget.FrameLayout.LayoutParams(-1, dp(48)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(52)))

        val scroll = ScrollView(this)
        homeScrollView = scroll
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ -> savedHomeScrollY = scrollY.coerceAtLeast(0) }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
            background = roundedBackground(Color.WHITE, Color.rgb(225, 234, 243))
            elevation = dp(2).toFloat()
        }
        val tabs = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val urlMode = RadioButton(this).apply { text = "输入网址"; id = View.generateViewId(); isChecked = mode == "url" }
        val htmlMode = RadioButton(this).apply { text = "本地 HTML"; id = View.generateViewId(); isChecked = mode == "html" }
        tabs.addView(urlMode); tabs.addView(htmlMode)
        tabs.setOnCheckedChangeListener { _, checked ->
            captureHomeScrollPosition()
            mode = if (checked == htmlMode.id) "html" else "url"
            prefs.edit().putString("mode", mode).apply()
        }
        form.addView(tabs)

        form.addView(label("网址"))
        address = EditText(this).apply {
            hint = "https://www.example.com"; isSingleLine = true; textSize = 15f
            setText(prefs.getString("last_url", "https://")?.ifBlank { "https://" } ?: "https://")
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(Color.rgb(248, 251, 254), Color.rgb(220, 230, 240))
        }
        address.addTextChangedListener(object : android.text.TextWatcher {
            private var fixing = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (fixing || s == null) return
                val raw = s.toString().trim()
                val normalized = when {
                    raw.isEmpty() -> "https://"
                    raw.startsWith("https://", true) -> "https://" + raw.substring(8)
                    raw.startsWith("http://", true) -> "https://" + raw.substring(7)
                    else -> "https://$raw"
                }
                if (normalized != raw) {
                    fixing = true
                    address.setText(normalized)
                    address.setSelection(address.text.length)
                    fixing = false
                }
                prefs.edit().putString("last_url", normalized).apply()
            }
        })
        form.addView(address, LinearLayout.LayoutParams(-1, dp(52)))
        form.addView(button(prefs.getString("local_html_name", null)?.let { "已选择 HTML：$it" } ?: "选择本地 HTML 文件") {
            htmlPicker.launch(arrayOf("text/html", "application/xhtml+xml"))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(8); bottomMargin = dp(7)
        })
        form.addView(button("导入完整网页文件夹（含 CSS、JS、图片）") {
            siteFolderPicker.launch(null)
        }, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(7); bottomMargin = dp(10)
        })

        form.addView(label("应用名称"))
        appName = EditText(this).apply {
            hint = "例如：我的网站"; isSingleLine = true; textSize = 15f
            setText(prefs.getString("app_name", ""))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(Color.rgb(248, 251, 254), Color.rgb(220, 230, 240))
        }
        appName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                prefs.edit().putString("app_name", s?.toString().orEmpty()).apply()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        form.addView(appName, LinearLayout.LayoutParams(-1, dp(52)))

        form.addView(label("应用图标与启动画面"))
        form.addView(TextView(this).apply {
            text = "原始模式：预览框完整显示上传原图，生成时直接使用原始图片数据，不做裁切、滤镜、圆角或背景处理。手动模式：可在方形预览框中拖动、双指缩放并确认裁切；确认后，预览框中的裁切结果将原样写入图标资源，不再二次处理。\n说明：Android 启动器可能按系统图标规格整体缩放或套用形状遮罩，应用无法控制桌面最终渲染。\n启动画面：推荐 1080×1920 px（9:16）PNG/JPG，主体放在中央安全区域，建议 ≤3 MB."
            textSize = 12f
            setTextColor(Color.rgb(105, 124, 142))
            setPadding(0, dp(2), 0, dp(8))
        })
        val iconModeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val originalMode = RadioButton(this).apply {
            id = View.generateViewId()
            text = "原始模式（预览框完整显示原图，原始图片数据直接作为图标）"
            isChecked = iconFitMode == "original"
        }
        val manualMode = RadioButton(this).apply {
            id = View.generateViewId()
            text = "手动模式（调整预览框中的裁切内容，确认后按预览结果原样生成图标）"
            isChecked = iconFitMode == "manual" || iconFitMode == "fit"
        }
        iconModeGroup.addView(originalMode)
        iconModeGroup.addView(manualMode)
        iconModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = if (checkedId == originalMode.id) "original" else "manual"
            if (selected != iconFitMode) {
                captureHomeScrollPosition()
                iconFitMode = selected
                prefs.edit().putString("icon_fit_mode", selected).apply()
                // Always switch back to the untouched upload when changing modes.
                // Manual mode then opens the existing crop editor; fill/original use
                // the untouched source file as their input.
                val original = File(filesDir, "custom_images").listFiles()
                    ?.firstOrNull { it.name.startsWith("app_icon_original.") && it.isFile }
                if (original != null) {
                    iconUri = Uri.fromFile(original)
                    prefs.edit().putString("icon_uri", iconUri.toString()).apply()
                }
                if (selected == "manual" && iconUri != null) {
                    showIconCropEditor(iconUri!!)
                } else {
                    showHome()
                }
            }
        }
        form.addView(label("图标适配方式"))
        form.addView(iconModeGroup)
        form.addView(imagePreview(iconUri, "APP 图标（上传单张图片）", dp(150), "icon"))
        form.addView(imagePreview(splashUri, "启动画面（点击图片区域添加或更换）", dp(220), "splash"))
        form.addView(label("授权状态"))
        form.addView(TextView(this).apply {
            text = "已激活 · 本机保存授权回执，后续可离线使用"
            textSize = 12f
            setTextColor(Color.rgb(38, 130, 105))
        })
        form.addView(button("更换 / 重新激活序列号") { showActivationScreen() },
            LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(7) })
        form.addView(label("自动设置"))
        form.addView(TextView(this).apply {
            text = "包名自动生成 · 签名密钥自动创建并保存在本机 · 版本号自动递增"
            textSize = 12f; setTextColor(Color.rgb(105, 124, 142))
        })
        form.addView(button("备份当前项目签名（用于换机/恢复）") { backupCurrentSigning() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8); bottomMargin = dp(7) })
        form.addView(button("恢复项目签名备份（用于继续升级）") { restoreCurrentSigning() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7); bottomMargin = dp(8) })

        status = TextView(this).apply {
            text = prefs.getString("local_html_name", null)?.let { "已选择本地 HTML：$it" } ?: "请选择网址或本地 HTML"
            textSize = 12f; setTextColor(Color.rgb(38, 130, 105))
            setPadding(0, dp(12), 0, dp(8))
        }
        form.addView(status)
        form.addView(button("预览网页") { preview() }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(5) })
        form.addView(primaryButton("生成 APK") { generateApk() }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) })
        form.addView(TextView(this).apply {
            text = "网址模式运行时直接打开原网站，需要网络。本地 HTML 模式将页面内容载入本机 WebView。"
            textSize = 11f; setTextColor(Color.rgb(120, 135, 150))
            setPadding(0, dp(12), 0, 0)
        })
        scroll.addView(form)
        // Keep the form comfortably centered on tablets and full-width on phones.
        val screenWidthDp = resources.configuration.screenWidthDp
        val contentWidth = if (screenWidthDp >= 600) dp(760) else -1
        root.addView(scroll, LinearLayout.LayoutParams(contentWidth, 0, 1f).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
        setContentView(root)
        scroll.post { scroll.scrollTo(0, savedHomeScrollY.coerceAtLeast(0)) }
    }



    private fun saveBuildHistory(request: ApkGenerator.Request, apk: File) {
        try {
            val packageName = ApkGenerator.resolvePackageName(this, request)
            val items = org.json.JSONArray(prefs.getString("build_history", "[]"))
            val item = org.json.JSONObject().put("name", request.appName).put("mode", request.mode)
                .put("package", packageName).put("path", apk.absolutePath)
                .put("created", System.currentTimeMillis())
                .put("source", if (request.mode == "url") request.url else "本地 HTML")
            val updated = org.json.JSONArray().put(item)
            for (i in 0 until minOf(items.length(), 49)) {
                val old = items.optJSONObject(i) ?: continue
                if (old.optString("path") != apk.absolutePath) updated.put(old)
            }
            prefs.edit().putString("build_history", updated.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.w("WebWorkshop", "Unable to save build history", e)
        }
    }

    private fun showHistoryDialog() {
        val items = try { org.json.JSONArray(prefs.getString("build_history", "[]")) }
            catch (_: Exception) { org.json.JSONArray() }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        if (items.length() == 0) {
            content.addView(TextView(this).apply {
                text = "暂时没有制作记录。成功生成 APK 后，会自动保存在这里。"
                textSize = 14f
                setTextColor(Color.rgb(105, 115, 125))
                setPadding(dp(8), dp(18), dp(8), dp(18))
            })
        } else {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val apk = File(item.optString("path"))
                val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(item.optLong("created")))
                val title = item.optString("name", "未命名")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(7), dp(8), dp(7))
                    background = roundedBackground(Color.rgb(242, 243, 245))
                    isClickable = true
                    isFocusable = true
                }
                val summary = TextView(this).apply {
                    text = "$title  ·  $date"
                    textSize = 14f
                    setTextColor(Color.rgb(45, 50, 56))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(summary, LinearLayout.LayoutParams(0, dp(42), 1f))
                row.setOnClickListener { showHistoryItemActions(item, index, items) }
                content.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(5); bottomMargin = dp(5)
                })
            }
        }
        val scroll = ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }
        historyDialog?.dismiss()
        historyDialog = AlertDialog.Builder(this)
            .setTitle("制作历史（${items.length()}）")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create()
        historyDialog?.show()
    }

    private fun downloadHistoryApk(apk: File) {
        if (!apk.isFile) {
            Toast.makeText(this, "APK 文件已不存在，请重新生成", Toast.LENGTH_LONG).show()
            return
        }
        generatedApk = apk
        apkSavePicker.launch(apk.name)
    }

    private fun showHistoryItemActions(
        item: org.json.JSONObject,
        index: Int,
        items: org.json.JSONArray
    ) {
        val apk = File(item.optString("path"))
        val actionsDialog = AlertDialog.Builder(this)
            .setTitle(item.optString("name", "历史 APK"))
            .setMessage("包名：${item.optString("package")}\n来源：${item.optString("source")}\n文件：${apk.name}")
            .setPositiveButton("安装 APK") { _, _ ->
                if (apk.isFile) installGeneratedApk(apk)
                else Toast.makeText(this, "APK 文件已不存在，请重新生成", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("下载 APK") { _, _ -> downloadHistoryApk(apk) }
            .setNegativeButton("删除记录", null)
            .create()

        actionsDialog.setOnShowListener {
            actionsDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                // Persist first, then close both dialogs before rebuilding the history.
                // This avoids opening a refreshed list underneath the still-visible action dialog.
                val next = org.json.JSONArray()
                for (i in 0 until items.length()) {
                    if (i != index) items.optJSONObject(i)?.let { next.put(it) }
                }
                prefs.edit().putString("build_history", next.toString()).commit()
                actionsDialog.dismiss()
                historyDialog?.dismiss()
                Toast.makeText(this, "历史记录已删除", Toast.LENGTH_SHORT).show()
                Handler(Looper.getMainLooper()).post { showHistoryDialog() }
            }
        }
        actionsDialog.show()
    }

    private fun currentProjectPackage(): String {
        val title = appName.text.toString().trim()
        val url = address.text.toString().trim()
        val identity = if (mode == "url") "url:" + url
            else prefs.getString("local_project_identity", "") ?: ""
        require(identity.isNotBlank()) { "请先选择网址或导入本地网页项目" }
        require(title.isNotBlank()) { "请先填写应用名称" }
        val request = ApkGenerator.Request(
            appName = title, mode = mode, url = url,
            localPath = prefs.getString("local_html_path", null),
            localBase = prefs.getString("local_html_base", null),
            iconUri = iconUri, iconFitMode = iconFitMode, splashUri = splashUri, projectIdentity = identity,
        )
        return ApkGenerator.resolvePackageName(this, request)
    }

    private fun askSigningPassword(title: String, action: (CharArray) -> Unit) {
        val field = EditText(this).apply {
            hint = "至少 8 位，请妥善记住"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle(title).setView(field)
            .setPositiveButton("继续") { _, _ ->
                val value = field.text.toString().toCharArray()
                if (value.size < 8) Toast.makeText(this, "密码至少需要 8 位", Toast.LENGTH_SHORT).show()
                else action(value)
            }.setNegativeButton("取消", null).show()
    }

    private fun backupCurrentSigning() {
        try {
            pendingSigningPackage = currentProjectPackage()
            askSigningPassword("设置签名备份密码") { password ->
                pendingSigningPassword = password
                signingBackupSavePicker.launch("webworkshop-signing-backup.p12")
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.localizedMessage ?: "请先选择项目", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreCurrentSigning() {
        try {
            pendingSigningPackage = currentProjectPackage()
            askSigningPassword("输入签名备份密码") { password ->
                pendingSigningPassword = password
                signingBackupOpenPicker.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.localizedMessage ?: "请先选择项目", Toast.LENGTH_LONG).show()
        }
    }

    private fun generateApk() {
        val title = appName.text.toString().trim()
        val url = address.text.toString().trim()
        prefs.edit().putString("app_name", title).putString("last_url", url).apply()
        val request = ApkGenerator.Request(
            appName = title,
            mode = mode,
            url = url,
            localPath = prefs.getString("local_html_path", null),
            localBase = prefs.getString("local_html_base", null),
            iconUri = iconUri,
            iconFitMode = iconFitMode,
            splashUri = splashUri,
            projectIdentity = if (mode == "url") "url:" + url.trim() else
                prefs.getString("local_project_identity", "") ?: ""
        )
        status.text = "正在本机离线构建 APK，请稍候……"
        Thread {
            try {
                val apk = ApkGenerator.build(this, request)
                Handler(Looper.getMainLooper()).post {
                    generatedApk = apk
                    saveBuildHistory(request, apk)
                    status.text = "生成成功：" + apk.name + "（" + apk.length() / 1024 + " KB）"
                    val buildDialog = AlertDialog.Builder(this)
                        .setTitle("APK 已生成")
                        .setMessage("文件已在本机生成。可以立即安装，也可以保存到手机。关闭此界面后，本次网页内容、上传图片和临时缓存将自动清空；制作历史及签名信息保留。")
                        .setPositiveButton("立即安装", null).setNeutralButton("保存到手机", null)
                        .setNegativeButton("稍后", null).create()
                    var handedOffToSystem = false
                    buildDialog.setOnShowListener {
                        buildDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            handedOffToSystem = true; pendingClearAfterInstall = true
                            buildDialog.dismiss(); installGeneratedApk(apk)
                        }
                        buildDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            handedOffToSystem = true; pendingClearAfterSave = true
                            buildDialog.dismiss(); apkSavePicker.launch(apk.name)
                        }
                        buildDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                            handedOffToSystem = true; buildDialog.dismiss(); clearAfterGeneratedApkClosed()
                        }
                    }
                    buildDialog.setOnDismissListener { if (!handedOffToSystem) clearAfterGeneratedApkClosed() }
                    buildDialog.show()
                }
            } catch (e: Exception) {
                android.util.Log.e("WebWorkshop", "APK generation failed", e)
                val detail = generateSequence(e as Throwable?) { it.cause }
                    .mapNotNull { it.localizedMessage?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }
                    .distinct().joinToString("\n")
                val fullError = buildString {
                    append("网页工坊 APK 生成失败\n")
                    append("时间：").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())).append("\n")
                    append("模式：").append(request.mode).append("\n")
                    append("应用名称：").append(request.appName).append("\n\n")
                    append(detail)
                    append("\n\n完整堆栈：\n")
                    var current: Throwable? = e
                    while (current != null) {
                        append(current.javaClass.name).append(": ").append(current.localizedMessage).append("\n")
                        current.stackTrace.take(35).forEach { append("  at ").append(it.toString()).append("\n") }
                        current = current.cause
                        if (current != null) append("Caused by: ")
                    }
                }
                runCatching { File(filesDir, "last_generation_error.txt").writeText(fullError) }
                Handler(Looper.getMainLooper()).post {
                    status.text = "生成失败：错误详情已保存，可点击复制后发给开发者"
                    AlertDialog.Builder(this)
                        .setTitle("APK 生成失败")
                        .setMessage(detail.take(1800))
                        .setNeutralButton("复制完整错误") { _, _ ->
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("网页工坊生成错误", fullError))
                            Toast.makeText(this, "完整错误已复制，请粘贴发送", Toast.LENGTH_LONG).show()
                        }
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }.start()
    }

    private fun installGeneratedApk(apk: File) {
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun copySiteTree(source: DocumentFile, destination: File) {
        if (source.isDirectory) {
            destination.mkdirs()
            source.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                copySiteTree(child, File(destination, name))
            }
        } else {
            destination.parentFile?.mkdirs()
            contentResolver.openInputStream(source.uri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取：${source.name}")
        }
    }

    private fun preview() {
        showingPreview = true
        val title = appName.text.toString().trim()
        prefs.edit().putString("app_name", title).putString("last_url", address.text.toString().trim()).apply()
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
        activePreviewWebView = webView
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(242, 247, 252))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val toolbar = android.widget.FrameLayout(this)
        toolbar.addView(button("‹  返回网页工坊") { showHome() },
            android.widget.FrameLayout.LayoutParams(-1, dp(46), android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL).apply {
                rightMargin = dp(58)
            })
        val refreshButton = TextView(this).apply {
            text = "↻"
            textSize = 27f
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(43, 83, 122))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.rgb(232, 241, 250))
                setStroke(dp(1), Color.rgb(213, 228, 242))
            }
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "刷新并清理网页"
            setOnClickListener { refreshPreviewPage() }
        }
        toolbar.addView(refreshButton, android.widget.FrameLayout.LayoutParams(dp(46), dp(46),
            android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL))
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(50)))
        page.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        var pullStartY = 0f
        var pullStartX = 0f
        var singleFingerPull = false
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { singleFingerPull = true; pullStartY = event.rawY; pullStartX = event.rawX }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> singleFingerPull = false
                android.view.MotionEvent.ACTION_UP -> {
                    val dy = event.rawY - pullStartY; val dx = event.rawX - pullStartX
                    if (singleFingerPull && dy > dp(88) && kotlin.math.abs(dx) < dp(70) && webView.scrollY <= 0) refreshPreviewPage()
                    singleFingerPull = false
                }
                android.view.MotionEvent.ACTION_CANCEL -> singleFingerPull = false
            }
            false
        }
        setContentView(page)
        if (mode == "html") {
            val html = localHtml
            if (html.isNullOrBlank()) {
                Toast.makeText(this, "请先选择 HTML 文件", Toast.LENGTH_SHORT).show()
                showHome()
            } else {
                previewPageUrl = null
                webView.loadDataWithBaseURL(localHtmlBaseUrl ?: "file:///android_asset/", html, "text/html", "UTF-8", null)
            }
        } else {
            val url = address.text.toString().trim()
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                Toast.makeText(this, "请输入完整网址", Toast.LENGTH_SHORT).show()
                showHome()
            } else {
                previewPageUrl = url
                webView.loadUrl(url)
            }
        }
    }

    private fun refreshPreviewPage() {
        if (!::webView.isInitialized) return
        val currentUrl = webView.url?.takeIf { it != "about:blank" } ?: previewPageUrl
        webView.stopLoading(); webView.clearHistory(); webView.clearFormData(); webView.clearCache(true)
        android.webkit.WebStorage.getInstance().deleteAllData()
        val reloadPage = {
            if (mode == "html") {
                val html = localHtml
                if (!html.isNullOrBlank()) webView.loadDataWithBaseURL(localHtmlBaseUrl ?: "file:///android_asset/", html, "text/html", "UTF-8", null)
                else webView.loadUrl("about:blank")
            } else if (!currentUrl.isNullOrBlank()) { previewPageUrl = currentUrl; webView.loadUrl(currentUrl) }
            else webView.loadUrl("about:blank")
        }
        android.webkit.CookieManager.getInstance().removeAllCookies { reloadPage() }
        Toast.makeText(this, "网页已刷新，缓存、Cookie 和网页存储已清理", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (showingPreview) {
            if (::webView.isInitialized && webView.canGoBack()) {
                webView.goBack()
            } else {
                showHome()
            }
            return
        }
        AlertDialog.Builder(this)
            .setTitle("退出网页工坊")
            .setMessage("确定要退出网页工坊吗？")
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("取消", null)
            .show()
    }
}
