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
    private var splashUri: Uri? = null
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
                status.text = "已选择本地 HTML：${it.lastPathSegment ?: "页面"}"
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
                status.text = "网页文件夹已导入，资源文件一并复制"
            } catch (e: Exception) {
                status.text = "导入失败：${e.localizedMessage ?: "请检查文件夹内容"}"
            }
        }
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            if (pendingImage == "icon") {
                iconUri = uri
                prefs.edit().putString("icon_uri", uri.toString()).apply()
                status.text = "应用图标已选择"
            } else {
                splashUri = uri
                prefs.edit().putString("splash_uri", uri.toString()).apply()
                status.text = "启动画面已选择"
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
            } finally { password.fill('\\u0000'); pendingSigningPassword = null }
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
            } finally { password.fill('\\u0000'); pendingSigningPassword = null }
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
        iconUri = prefs.getString("icon_uri", null)?.let(Uri::parse)
        splashUri = prefs.getString("splash_uri", null)?.let(Uri::parse)
        prefs.getString("local_html_path", null)?.let { path ->
            val file = File(path)
            if (file.exists()) {
                localHtml = file.readText()
                localHtmlBaseUrl = prefs.getString("local_html_base", "file://${filesDir.absolutePath}/")
                mode = "html"
            }
        }
        showHome()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(value: String) = TextView(this).apply {
        text = value; textSize = 14f; setTextColor(Color.rgb(49, 69, 89))
        setPadding(0, dp(14), 0, dp(5))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; setOnClickListener { action() }
    }

    private fun showHome() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(24))
            setBackgroundColor(Color.rgb(247, 250, 253))
        }
        root.addView(TextView(this).apply {
            text = "网页工坊"; textSize = 28f
            setTextColor(Color.rgb(25, 52, 78))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "简单几步，把网页制作成独立应用"
            textSize = 13f; setTextColor(Color.rgb(105, 124, 142))
            setPadding(0, dp(5), 0, dp(12))
        })

        val scroll = ScrollView(this)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val urlMode = RadioButton(this).apply { text = "输入网址"; id = View.generateViewId(); isChecked = mode == "url" }
        val htmlMode = RadioButton(this).apply { text = "本地 HTML"; id = View.generateViewId(); isChecked = mode == "html" }
        tabs.addView(urlMode); tabs.addView(htmlMode)
        tabs.setOnCheckedChangeListener { _, checked -> mode = if (checked == htmlMode.id) "html" else "url" }
        form.addView(tabs)

        form.addView(label("网址"))
        address = EditText(this).apply {
            hint = "https://www.example.com"; isSingleLine = true; textSize = 15f
            setText(prefs.getString("last_url", ""))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        form.addView(address, LinearLayout.LayoutParams(-1, dp(52)))
        form.addView(button("选择本地 HTML 文件") {
            htmlPicker.launch(arrayOf("text/html", "application/xhtml+xml"))
        })
        form.addView(button("导入完整网页文件夹（含 CSS、JS、图片）") {
            siteFolderPicker.launch(null)
        })

        form.addView(label("应用名称"))
        appName = EditText(this).apply {
            hint = "例如：我的网站"; isSingleLine = true; textSize = 15f
            setText(prefs.getString("app_name", ""))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        form.addView(appName, LinearLayout.LayoutParams(-1, dp(52)))

        form.addView(label("应用图标与启动画面"))
        form.addView(button("自定义应用图标") { pendingImage = "icon"; imagePicker.launch(arrayOf("image/*")) })
        form.addView(button("自定义启动画面") { pendingImage = "splash"; imagePicker.launch(arrayOf("image/*")) })

        form.addView(label("自动设置"))
        form.addView(TextView(this).apply {
            text = "包名自动生成 · 签名密钥自动创建并保存在本机 · 版本号自动递增"
            textSize = 12f; setTextColor(Color.rgb(105, 124, 142))
        })
        form.addView(button("备份当前项目签名（用于换机/恢复）") { backupCurrentSigning() })
        form.addView(button("恢复项目签名备份（用于继续升级）") { restoreCurrentSigning() })

        status = TextView(this).apply {
            text = "请选择网址或本地 HTML"
            textSize = 12f; setTextColor(Color.rgb(38, 130, 105))
            setPadding(0, dp(12), 0, dp(8))
        }
        form.addView(status)
        form.addView(button("预览网页") { preview() }, LinearLayout.LayoutParams(-1, dp(50)))
        form.addView(button("生成 APK") { generateApk() }, LinearLayout.LayoutParams(-1, dp(52)))
        form.addView(TextView(this).apply {
            text = "网址模式运行时直接打开原网站，需要网络。本地 HTML 模式将页面内容载入本机 WebView。"
            textSize = 11f; setTextColor(Color.rgb(120, 135, 150))
            setPadding(0, dp(12), 0, 0)
        })
        scroll.addView(form)
        root.addView(scroll)
        setContentView(root)
    }


    private fun currentProjectPackage(): String {
        val identity = if (mode == "url") "url:" + address.text.toString().trim()
            else prefs.getString("local_project_identity", "") ?: ""
        require(identity.isNotBlank()) { "请先选择网址或导入本地网页项目" }
        return ApkGenerator.packageNameFor(mode, identity)
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
            projectIdentity = if (mode == "url") "url:" + url.trim() else
                prefs.getString("local_project_identity", "") ?: ""
        )
        status.text = "正在本机离线构建 APK，请稍候……"
        Thread {
            try {
                val apk = ApkGenerator.build(this, request)
                Handler(Looper.getMainLooper()).post {
                    generatedApk = apk
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
                Handler(Looper.getMainLooper()).post {
                    status.text = "生成失败：" + (e.localizedMessage ?: e.javaClass.simpleName)
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
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(button("返回网页工坊") { showHome() }, LinearLayout.LayoutParams(-1, dp(48)))
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
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            showHome()
        }
    }
}
