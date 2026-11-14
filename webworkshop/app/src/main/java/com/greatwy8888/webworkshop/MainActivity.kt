package com.greatwy8888.webworkshop

import android.os.Bundle
import android.content.Context
import android.net.Uri
import java.io.File
import androidx.documentfile.provider.DocumentFile
import android.graphics.Color
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import android.content.Intent
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
    private var iconBackgroundUri: Uri? = null
    private var splashUri: Uri? = null
    private var showingPreview = false
    private var liveIconPreview: ImageView? = null
    private val prefs by lazy { getSharedPreferences("webworkshop", Context.MODE_PRIVATE) }

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
                val name = when (pendingImage) { "icon" -> "app_icon"; "icon_background" -> "app_icon_background"; else -> "app_splash" }
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
                val saved = File(folder, "$name.$extension")
                contentResolver.openInputStream(uri)?.use { input ->
                    saved.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("系统无法读取所选图片")
                check(saved.length() > 0L) { "所选图片为空" }
                val savedUri = Uri.fromFile(saved)
                if (pendingImage == "icon") {
                    iconUri = savedUri
                    prefs.edit().putString("icon_uri", savedUri.toString()).apply()
                    showHome()
                    status.text = "应用图标前景层已保存，已更新预览"
                } else if (pendingImage == "icon_background") {
                    iconBackgroundUri = savedUri
                    prefs.edit().putString("icon_background_uri", savedUri.toString()).apply()
                    showHome()
                    status.text = "应用图标背景层已保存，已更新预览"
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A fresh launch starts with a clean form. Keep build history, generated
        // APKs, per-project signing keys, package mappings and version counters.
        if (savedInstanceState == null) resetTransientOptions() else restoreTransientOptions()
        showHome()
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
        iconBackgroundUri = prefs.getString("icon_background_uri", null)?.let { Uri.parse(it) }
        splashUri = prefs.getString("splash_uri", null)?.let { Uri.parse(it) }
    }

    private fun resetTransientOptions() {
        // Clear only the current form selection, not durable project identity/signing data.
        prefs.edit()
            .remove("icon_uri")
            .remove("icon_background_uri")
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
        iconBackgroundUri = null
        splashUri = null
        localHtml = null
        localHtmlBaseUrl = null
        mode = "url"
        File(filesDir, "custom_images").deleteRecursively()
        File(filesDir, "imported_page.html").delete()
        File(filesDir, "site_project").deleteRecursively()
    }

    private fun imagePreview(uri: Uri?, caption: String, previewHeight: Int, kind: String): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        panel.addView(TextView(this).apply {
            text = caption
            textSize = 12f
            setTextColor(Color.rgb(75, 96, 115))
        })
        val frame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(239, 244, 248))
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
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
                        "icon_background" -> { iconBackgroundUri = null; prefs.edit().remove("icon_background_uri").apply() }
                        else -> { splashUri = null; prefs.edit().remove("splash_uri").apply() }
                    }
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
        frame.contentDescription = when (kind) { "icon" -> "点击添加或更换图标前景层"; "icon_background" -> "点击添加或更换图标背景层"; else -> "点击添加或更换启动画面" }
        frame.setOnClickListener {
            pendingImage = kind
            imagePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
        }
        panel.addView(frame, LinearLayout.LayoutParams(-1, previewHeight))
        panel.addView(TextView(this).apply {
            text = if (uri == null) "点击上方区域选择图片" else "已选择图片 · 点击图片可更换，右上角 × 删除"
            textSize = 11f
            setTextColor(Color.rgb(120, 135, 150))
        })
        return panel
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

    private fun showHome() {
        showingPreview = false
        liveIconPreview = null
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
            text = "图标：推荐 512×512 px 正方形 PNG，透明背景，主体居中，建议 ≤1 MB。\n启动画面：推荐 1080×1920 px（9:16）PNG/JPG，主体放在中央安全区域，建议 ≤3 MB。图片会等比适配，不会拉伸。"
            textSize = 12f; setTextColor(Color.rgb(105, 124, 142))
            setPadding(0, dp(2), 0, dp(8))
        })
        form.addView(imagePreview(iconUri, "图标前景层（自动去除边缘白底）", dp(112), "icon"))
        val singleImageMode = CheckBox(this).apply {
            text = "只使用上传的单张图片作为完整图标（不叠加背景和特效）"
            textSize = 13f
            isChecked = prefs.getBoolean("icon_single_image_mode", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("icon_single_image_mode", checked).apply()
                refreshIconPreview()
            }
        }
        form.addView(singleImageMode)
        form.addView(label("图标实时预览"))
        val previewFrame = android.widget.FrameLayout(this).apply {
            background = roundedBackground(Color.rgb(239, 244, 248), Color.rgb(220, 230, 240))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        liveIconPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "图标效果实时预览"
        }
        previewFrame.addView(liveIconPreview, android.widget.FrameLayout.LayoutParams(dp(180), dp(180), android.view.Gravity.CENTER))
        form.addView(previewFrame, LinearLayout.LayoutParams(-1, dp(204)).apply { bottomMargin = dp(8) })
        form.addView(label("图标背景层"))
        form.addView(TextView(this).apply {
            text = "支持纯色、线性渐变、径向渐变及自定义背景图片；前景可叠加阴影、发光和描边效果。颜色可填写 #RRGGBB 或 #AARRGGBB。"
            textSize = 12f; setTextColor(Color.rgb(105, 124, 142))
        })
        fun addColorField(title: String, key: String, defaultValue: String): EditText {
            form.addView(label(title))
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val field = EditText(this).apply {
                hint = "#RRGGBB 或 #AARRGGBB"
                isSingleLine = true
                textSize = 14f
                setText(prefs.getString(key, defaultValue))
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = roundedBackground(Color.rgb(248, 251, 254), Color.rgb(220, 230, 240))
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val value = s?.toString()?.trim().orEmpty()
                        prefs.edit().putString(key, value).apply()
                        refreshPaletteSwatch(swatch, value, defaultValue)
                        refreshIconPreview()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            val swatch = TextView(this).apply {
                text = "调色板"
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(8), 0, dp(8), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { showColorPalette(field, swatch, key, defaultValue) }
            }
            refreshPaletteSwatch(swatch, field.text.toString(), defaultValue)
            line.addView(field, LinearLayout.LayoutParams(0, dp(46), 1f))
            line.addView(swatch, LinearLayout.LayoutParams(dp(76), dp(40)).apply { leftMargin = dp(8) })
            form.addView(line)
            return field
        }
        fun addEffectSwitch(title: String, key: String, defaultValue: Boolean = false) =
            CheckBox(this).apply {
                text = title
                textSize = 14f
                isChecked = prefs.getBoolean(key, defaultValue)
                setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply(); refreshIconPreview() }
                form.addView(this)
            }
        fun addEffectSlider(title: String, key: String, defaultValue: Int, maxValue: Int) {
            val caption = TextView(this).apply {
                textSize = 12f
                setTextColor(Color.rgb(105, 124, 142))
                setPadding(0, dp(5), 0, 0)
            }
            val slider = SeekBar(this).apply {
                max = maxValue
                progress = prefs.getInt(key, defaultValue).coerceIn(0, maxValue)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        caption.text = "$title：$progress"
                        if (fromUser) { prefs.edit().putInt(key, progress).apply(); refreshIconPreview() }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            caption.text = "$title：${slider.progress}"
            form.addView(caption)
            form.addView(slider, LinearLayout.LayoutParams(-1, dp(42)))
        }

        form.addView(label("背景样式"))
        val gradientChoices = listOf("纯色" to "solid", "线性渐变" to "linear", "径向渐变" to "radial")
        val gradientSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                gradientChoices.map { it.first })
            val saved = prefs.getString("icon_background_gradient", "linear")
            setSelection(gradientChoices.indexOfFirst { it.second == saved }.coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.edit().putString("icon_background_gradient", gradientChoices[position].second).apply()
                    refreshIconPreview()
                }
            }
        }
        form.addView(gradientSpinner, LinearLayout.LayoutParams(-1, dp(46)))
        val directionChoices = listOf("左上到右下" to 0, "左到右" to 1, "上到下" to 2, "右上到左下" to 3)
        val directionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                directionChoices.map { it.first })
            val saved = prefs.getInt("icon_background_direction", 0)
            setSelection(saved.coerceIn(0, directionChoices.lastIndex))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.edit().putInt("icon_background_direction", directionChoices[position].second).apply()
                    refreshIconPreview()
                }
            }
        }
        form.addView(directionSpinner, LinearLayout.LayoutParams(-1, dp(46)))
        addColorField("背景颜色一", "icon_background_color", "#FF315C9B")
        addColorField("背景颜色二（渐变终点）", "icon_background_color2", "#FF8A45B8")
        form.addView(label("前景效果"))
        addEffectSwitch("启用前景渐变染色", "icon_foreground_gradient_enabled")
        addColorField("前景渐变颜色一", "icon_foreground_color", "#FFFFFFFF")
        addColorField("前景渐变颜色二", "icon_foreground_color2", "#FFB8D9FF")
        addEffectSwitch("启用柔和阴影", "icon_shadow_enabled")
        addColorField("阴影颜色", "icon_shadow_color", "#99000000")
        addEffectSlider("阴影模糊", "icon_shadow_blur", 12, 32)
        addEffectSwitch("启用发光效果", "icon_glow_enabled")
        addColorField("发光颜色", "icon_glow_color", "#FF62B8FF")
        addEffectSlider("发光范围", "icon_glow_blur", 16, 40)
        addEffectSwitch("启用前景描边", "icon_outline_enabled")
        addColorField("描边颜色", "icon_outline_color", "#FFFFFFFF")
        addEffectSlider("描边宽度", "icon_outline_blur", 3, 16)
        form.addView(button("上传图标背景图片（可选）") {
            pendingImage = "icon_background"
            imagePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
        if (iconBackgroundUri != null) {
            form.addView(imagePreview(iconBackgroundUri, "自定义图标背景图片", dp(92), "icon_background"))
        }
        form.addView(imagePreview(splashUri, "启动画面（点击图片区域添加或更换）", dp(220), "splash"))
        refreshIconPreview()

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
    }



    private fun refreshPaletteSwatch(view: TextView, value: String, fallback: String) {
        val color = try { Color.parseColor(value.trim()) } catch (_: Exception) {
            try { Color.parseColor(fallback) } catch (_: Exception) { Color.GRAY }
        }
        view.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(color)
            setStroke(dp(1), Color.rgb(190, 200, 210))
        }
    }

    private fun showColorPalette(field: EditText, swatch: TextView, key: String, fallback: String) {
        val palette = listOf(
            "#FFFFFFFF", "#FF000000", "#FFF44336", "#FFE91E63", "#FF9C27B0",
            "#FF673AB7", "#FF3F51B5", "#FF2196F3", "#FF03A9F4", "#FF00BCD4",
            "#FF009688", "#FF4CAF50", "#FF8BC34A", "#FFCDDC39", "#FFFFEB3B",
            "#FFFFC107", "#FFFF9800", "#FFFF5722", "#FF795548", "#FF607D8B",
            "#FF315C9B", "#FF8A45B8", "#FF62B8FF", "#99000000"
        )
        val labels = palette + "自定义颜色…"
        AlertDialog.Builder(this).setTitle("选择颜色")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < palette.size) {
                    field.setText(palette[which])
                    field.setSelection(field.text.length)
                } else {
                    val custom = EditText(this).apply {
                        hint = "#RRGGBB 或 #AARRGGBB"
                        setText(field.text)
                        isSingleLine = true
                    }
                    AlertDialog.Builder(this).setTitle("输入自定义颜色").setView(custom)
                        .setPositiveButton("应用") { _, _ ->
                            val value = custom.text.toString().trim()
                            try {
                                Color.parseColor(value)
                                field.setText(value)
                                field.setSelection(field.text.length)
                            } catch (_: Exception) {
                                Toast.makeText(this, "颜色格式无效，请使用 #RRGGBB 或 #AARRGGBB", Toast.LENGTH_SHORT).show()
                            }
                        }.setNegativeButton("取消", null).show()
                }
            }.show()
    }

    private fun refreshIconPreview() {
        val target = liveIconPreview ?: return
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            fun color(key: String, fallback: String): Int =
                try { Color.parseColor(prefs.getString(key, fallback) ?: fallback) } catch (_: Exception) { Color.parseColor(fallback) }
            val single = prefs.getBoolean("icon_single_image_mode", false)
            val c1 = color("icon_background_color", "#FF315C9B")
            val c2 = color("icon_background_color2", "#FF8A45B8")
            if (single) {
                canvas.drawColor(Color.rgb(238, 241, 245))
            } else {
                when (prefs.getString("icon_background_gradient", "linear")) {
                    "radial" -> canvas.drawPaint(android.graphics.Paint().apply {
                        shader = android.graphics.RadialGradient(128f, 128f, 190f, c1, c2, android.graphics.Shader.TileMode.CLAMP)
                    })
                    "linear" -> {
                        val points = when (prefs.getInt("icon_background_direction", 0)) {
                            1 -> floatArrayOf(0f, 128f, 256f, 128f)
                            2 -> floatArrayOf(128f, 0f, 128f, 256f)
                            3 -> floatArrayOf(256f, 0f, 0f, 256f)
                            else -> floatArrayOf(0f, 0f, 256f, 256f)
                        }
                        canvas.drawPaint(android.graphics.Paint().apply {
                            shader = android.graphics.LinearGradient(points[0], points[1], points[2], points[3], c1, c2, android.graphics.Shader.TileMode.CLAMP)
                        })
                    }
                    else -> canvas.drawColor(c1)
                }
            }
            val uri = iconUri
            if (uri != null) {
                val input = if (uri.scheme == "file") File(uri.path ?: "").inputStream() else contentResolver.openInputStream(uri)
                val decoded = input?.use { android.graphics.BitmapFactory.decodeStream(it) }
                if (decoded != null) {
                    val foreground = if (single) decoded else removeNearWhiteEdgeBackground(decoded).also { decoded.recycle() }
                    try {
                        val safe = if (single) size.toFloat() else size * 0.66f
                        val scale = minOf(safe / foreground.width, safe / foreground.height)
                        val w = foreground.width * scale
                        val h = foreground.height * scale
                        val rect = android.graphics.RectF((size-w)/2f, (size-h)/2f, (size+w)/2f, (size+h)/2f)
                        val artwork = foreground.copy(Bitmap.Config.ARGB_8888, true)
                        try {
                            if (!single) {
                                if (prefs.getBoolean("icon_foreground_gradient_enabled", false)) {
                                    val p = when (prefs.getInt("icon_background_direction", 0)) {
                                        1 -> floatArrayOf(0f,128f,256f,128f)
                                        2 -> floatArrayOf(128f,0f,128f,256f)
                                        3 -> floatArrayOf(256f,0f,0f,256f)
                                        else -> floatArrayOf(0f,0f,256f,256f)
                                    }
                                    val paint = android.graphics.Paint().apply {
                                        shader = android.graphics.LinearGradient(p[0],p[1],p[2],p[3],
                                            color("icon_foreground_color","#FFFFFFFF"), color("icon_foreground_color2","#FFB8D9FF"), android.graphics.Shader.TileMode.CLAMP)
                                        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
                                    }
                                    android.graphics.Canvas(artwork).drawRect(0f,0f,size.toFloat(),size.toFloat(),paint)
                                }
                                val mask = artwork.extractAlpha()
                                try {
                                    fun effect(enabledKey:String, colorKey:String, fallback:String, blurKey:String, defaultBlur:Int, dy:Float=0f) {
                                        if (!prefs.getBoolean(enabledKey,false)) return
                                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                            val blur = prefs.getInt(blurKey,defaultBlur).coerceAtLeast(0)
                                            if (blur > 0) maskFilter = android.graphics.BlurMaskFilter(blur.toFloat(),android.graphics.BlurMaskFilter.Blur.NORMAL)
                                        }
                                        val offset = IntArray(2)
                                        val layer = mask.extractAlpha(paint,offset)
                                        try {
                                            val tint = android.graphics.Paint().apply {
                                                colorFilter = android.graphics.PorterDuffColorFilter(color(colorKey,fallback),android.graphics.PorterDuff.Mode.SRC_IN)
                                            }
                                            canvas.drawBitmap(layer,rect.left+offset[0],rect.top+offset[1]+dy,tint)
                                        } finally { layer.recycle() }
                                    }
                                    effect("icon_glow_enabled","icon_glow_color","#FF62B8FF","icon_glow_blur",16f.toInt())
                                    effect("icon_shadow_enabled","icon_shadow_color","#99000000","icon_shadow_blur",12,5f)
                                    effect("icon_outline_enabled","icon_outline_color","#FFFFFFFF","icon_outline_blur",3)
                                } finally { mask.recycle() }
                            }
                            canvas.drawBitmap(artwork,null,rect,android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
                        } finally { artwork.recycle() }
                    } finally { foreground.recycle() }
                }
            }
            target.setImageBitmap(bitmap.copy(Bitmap.Config.ARGB_8888, false))
        } catch (e: Exception) {
            android.util.Log.w("WebWorkshop", "Live icon preview failed", e)
        } finally { bitmap.recycle() }
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
        AlertDialog.Builder(this)
            .setTitle("制作历史（${items.length()}）")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
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
        AlertDialog.Builder(this)
            .setTitle(item.optString("name", "历史 APK"))
            .setMessage("包名：${item.optString("package")}\n来源：${item.optString("source")}\n文件：${apk.name}")
            .setPositiveButton("安装 APK") { _, _ ->
                if (apk.isFile) installGeneratedApk(apk)
                else Toast.makeText(this, "APK 文件已不存在，请重新生成", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("下载 APK") { _, _ -> downloadHistoryApk(apk) }
            .setNegativeButton("删除记录") { _, _ ->
                val next = org.json.JSONArray()
                for (i in 0 until items.length()) if (i != index) next.put(items.optJSONObject(i))
                prefs.edit().putString("build_history", next.toString()).apply()
                Toast.makeText(this, "历史记录已删除", Toast.LENGTH_SHORT).show()
                showHistoryDialog()
            }
            .show()
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
            iconUri = iconUri, splashUri = splashUri, projectIdentity = identity,
            iconBackgroundUri = iconBackgroundUri,
            iconBackgroundColor = prefs.getString("icon_background_color", "#00000000") ?: "#00000000"
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
            splashUri = splashUri,
            iconBackgroundUri = iconBackgroundUri,
            iconSingleImageMode = prefs.getBoolean("icon_single_image_mode", false),
            iconBackgroundColor = prefs.getString("icon_background_color", "#FF315C9B") ?: "#FF315C9B",
            iconBackgroundColor2 = prefs.getString("icon_background_color2", "#FF8A45B8") ?: "#FF8A45B8",
            iconBackgroundGradient = prefs.getString("icon_background_gradient", "linear") ?: "linear",
            iconBackgroundDirection = prefs.getInt("icon_background_direction", 0),
            iconForegroundGradientEnabled = prefs.getBoolean("icon_foreground_gradient_enabled", false),
            iconForegroundColor = prefs.getString("icon_foreground_color", "#FFFFFFFF") ?: "#FFFFFFFF",
            iconForegroundColor2 = prefs.getString("icon_foreground_color2", "#FFB8D9FF") ?: "#FFB8D9FF",
            iconShadowEnabled = prefs.getBoolean("icon_shadow_enabled", false),
            iconShadowColor = prefs.getString("icon_shadow_color", "#99000000") ?: "#99000000",
            iconShadowBlur = prefs.getInt("icon_shadow_blur", 12),
            iconGlowEnabled = prefs.getBoolean("icon_glow_enabled", false),
            iconGlowColor = prefs.getString("icon_glow_color", "#FF62B8FF") ?: "#FF62B8FF",
            iconGlowBlur = prefs.getInt("icon_glow_blur", 16),
            iconOutlineEnabled = prefs.getBoolean("icon_outline_enabled", false),
            iconOutlineColor = prefs.getString("icon_outline_color", "#FFFFFFFF") ?: "#FFFFFFFF",
            iconOutlineBlur = prefs.getInt("icon_outline_blur", 3),
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
                    AlertDialog.Builder(this)
                        .setTitle("APK 已生成")
                        .setMessage("文件已在本机生成。可以立即安装，也可以保存到手机。")
                        .setPositiveButton("立即安装") { _, _ -> installGeneratedApk(apk) }
                        .setNeutralButton("保存到手机") { _, _ -> apkSavePicker.launch(apk.name) }
                        .setNegativeButton("稍后") { _, _ -> }
                        .show()
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
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(242, 247, 252))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        page.addView(button("‹  返回网页工坊") { showHome() }, LinearLayout.LayoutParams(-1, dp(48)))
        page.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        if (mode == "html") {
            val html = localHtml
            if (html.isNullOrBlank()) {
                Toast.makeText(this, "请先选择 HTML 文件", Toast.LENGTH_SHORT).show()
                showHome()
            } else webView.loadDataWithBaseURL(localHtmlBaseUrl ?: "file:///android_asset/", html, "text/html", "UTF-8", null)
        } else {
            val url = address.text.toString().trim()
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                Toast.makeText(this, "请输入完整网址", Toast.LENGTH_SHORT).show()
                showHome()
            } else webView.loadUrl(url)
        }
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
