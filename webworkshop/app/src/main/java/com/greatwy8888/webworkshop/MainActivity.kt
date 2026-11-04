package com.greatwy8888.webworkshop

import android.os.Bundle
import android.content.Context
import android.net.Uri
import android.graphics.Color
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : androidx.activity.ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var address: EditText
    private lateinit var appName: EditText
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var mode = "url"
    private var localHtml: String? = null
    private var iconUri: Uri? = null
    private var splashUri: Uri? = null
    private val prefs by lazy { getSharedPreferences("webworkshop", Context.MODE_PRIVATE) }

    private val htmlPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                localHtml = contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() }
                mode = "html"
                status.text = "已选择本地 HTML：${it.lastPathSegment ?: "页面"}"
            } catch (e: Exception) {
                status.text = "读取失败：${e.localizedMessage ?: "无法读取文件"}"
            }
        }
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        iconUri = prefs.getString("icon_uri", null)?.let(Uri::parse)
        splashUri = prefs.getString("splash_uri", null)?.let(Uri::parse)
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

        form.addView(label("应用名称"))
        appName = EditText(this).apply {
            hint = "例如：我的网站"; isSingleLine = true; textSize = 15f
            setText(prefs.getString("app_name", ""))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        form.addView(appName, LinearLayout.LayoutParams(-1, dp(52)))

        form.addView(label("应用图标与启动画面"))
        form.addView(button("自定义应用图标") { pendingImage = "icon"; imagePicker.launch("image/*") })
        form.addView(button("自定义启动画面") { pendingImage = "splash"; imagePicker.launch("image/*") })

        form.addView(label("自动设置"))
        form.addView(TextView(this).apply {
            text = "包名自动生成 · 签名密钥自动创建并保存在本机 · 版本号自动递增"
            textSize = 12f; setTextColor(Color.rgb(105, 124, 142))
        })
        status = TextView(this).apply {
            text = "请选择网址或本地 HTML"
            textSize = 12f; setTextColor(Color.rgb(38, 130, 105))
            setPadding(0, dp(12), 0, dp(8))
        }
        form.addView(status)
        form.addView(button("预览网页") { preview() }, LinearLayout.LayoutParams(-1, dp(50)))
        form.addView(button("生成 APK") {
            prefs.edit().putString("app_name", appName.text.toString().trim())
                .putString("last_url", address.text.toString().trim()).apply()
            status.text = "APK 本地构建引擎尚在集成中；当前版本可预览网页，暂不能导出 APK。"
        }, LinearLayout.LayoutParams(-1, dp(52)))
        form.addView(TextView(this).apply {
            text = "网址模式运行时直接打开原网站，需要网络。本地 HTML 模式将页面内容载入本机 WebView。"
            textSize = 11f; setTextColor(Color.rgb(120, 135, 150))
            setPadding(0, dp(12), 0, 0)
        })
        scroll.addView(form)
        root.addView(scroll)
        setContentView(root)
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
            } else webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
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
