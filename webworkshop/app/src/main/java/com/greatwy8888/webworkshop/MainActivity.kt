package com.greatwy8888.webworkshop

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private var showingPreview = false

    private val htmlPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { openLocalHtml(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showHome() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(20))
            setBackgroundColor(Color.rgb(247, 250, 253))
        }
        root.addView(TextView(this).apply {
            text = "网页工坊"
            textSize = 28f
            setTextColor(Color.rgb(25, 52, 78))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "把网页变成独立的 Android 应用"
            textSize = 14f
            setTextColor(Color.rgb(105, 124, 142))
            setPadding(0, dp(5), 0, dp(24))
        })

        val tabs = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val urlMode = RadioButton(this).apply { text = "输入网址"; id = View.generateViewId(); isChecked = true }
        val htmlMode = RadioButton(this).apply { text = "本地 HTML"; id = View.generateViewId() }
        tabs.addView(urlMode); tabs.addView(htmlMode)
        root.addView(tabs)

        address = EditText(this).apply {
            hint = "https://www.example.com"
            singleLine = true
            textSize = 16f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(address, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(12) })

        val primary = Button(this).apply {
            text = "打开网址预览"
            setOnClickListener {
                val value = address.text.toString().trim()
                if (!value.startsWith("https://") && !value.startsWith("http://")) {
                    address.error = "请输入完整网址，例如 https://example.com"
                } else openUrl(value)
            }
        }
        root.addView(primary, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })

        val local = Button(this).apply {
            text = "选择本地 HTML"
            setOnClickListener { htmlPicker.launch(arrayOf("text/html", "application/xhtml+xml", "*/*")) }
        }
        root.addView(local, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = "说明：网址模式打开原网站，需要网络；本地 HTML 模式从手机文件中选择页面。"
            textSize = 12f
            setTextColor(Color.rgb(112, 128, 144))
            setPadding(0, dp(18), 0, 0)
        })
        setContentView(root)
    }

    private fun openUrl(url: String) {
        showWebView()
        webView.loadUrl(url)
    }

    private fun openLocalHtml(uri: Uri) {
        showWebView()
        webView.loadUrl(uri.toString())
    }

    private fun showWebView() {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(Button(this).apply {
            text = "返回网页工坊"
            setOnClickListener { showHome() }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        page.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        showingPreview = true
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (showingPreview && ::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else if (showingPreview) {
            showingPreview = false
            showHome()
        } else {
            super.onBackPressed()
        }
    }
}
