package com.greatwy8888.webworkshop.template

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

import org.json.JSONObject

class GeneratedActivity : Activity() {
    private var filePathCallback: ValueCallback<Array<android.net.Uri>>? = null
    private val fileChooserRequestCode = 7301

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = assets.open("webworkshop.json").bufferedReader().use { JSONObject(it.readText()) }
        val container = FrameLayout(this)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@GeneratedActivity.filePathCallback?.onReceiveValue(null)
                this@GeneratedActivity.filePathCallback = filePathCallback ?: return false

                try {
                    val accepted = fileChooserParams?.acceptTypes
                        ?.flatMap { it.split(",") }
                        ?.map { it.trim().lowercase() }
                        ?.mapNotNull { type ->
                            when {
                                type.startsWith(".") -> when (type) {
                                    ".jpg", ".jpeg" -> "image/jpeg"
                                    ".png" -> "image/png"
                                    ".gif" -> "image/gif"
                                    ".webp" -> "image/webp"
                                    ".pdf" -> "application/pdf"
                                    ".txt" -> "text/plain"
                                    ".mp4" -> "video/mp4"
                                    else -> null
                                }
                                type.contains("/") -> type
                                else -> null
                            }
                        }
                        ?.distinct()
                        .orEmpty()

                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                        if (accepted.isEmpty()) {
                            type = "*/*"
                        } else if (accepted.size == 1) {
                            type = accepted.first()
                        } else {
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, accepted.toTypedArray())
                        }
                    }
                    startActivityForResult(intent, fileChooserRequestCode)
                    return true
                } catch (error: Exception) {
                    this@GeneratedActivity.filePathCallback?.onReceiveValue(null)
                    this@GeneratedActivity.filePathCallback = null
                    return false
                }
            }
        }
        web.webViewClient = WebViewClient()
        container.addView(web, FrameLayout.LayoutParams(-1, -1))
        val splashId = resources.getIdentifier("splash", "drawable", packageName)
        if (config.optBoolean("hasSplash", false) && splashId != 0) {
            splash = ImageView(this).apply {
                setImageResource(splashId)
                // The source image is prepared as 9:16. FIT_CENTER preserves its ratio;
                // any unused screen area remains black rather than distorting the artwork.
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            container.addView(splash, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        setContentView(container)
        // Keep a configured splash visible for exactly three seconds, independent of
        // network/page load speed. With no custom splash resource, enter the app directly.
        if (splash != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                splash?.let { image ->
                    image.animate().alpha(0f).setDuration(180).withEndAction {
                        container.removeView(image)
                        splash = null
                    }.start()
                }
            }, 3000L)
        }
        if (config.optString("mode") == "url") {
            web.loadUrl(config.getString("url"))
        } else {
            web.loadUrl("file:///android_asset/site/" + config.optString("entry", "index.html"))
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != fileChooserRequestCode) return

        val callback = filePathCallback ?: return
        filePathCallback = null
        val results = if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            when {
                clip != null -> Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else null
        callback.onReceiveValue(results)
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        super.onDestroy()
    }

    private var splash: ImageView? = null

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        val content = findViewById<FrameLayout>(android.R.id.content)
        val web = content?.getChildAt(0) as? FrameLayout
        val child = web?.getChildAt(0)
        if (child is WebView && child.canGoBack()) child.goBack() else super.onBackPressed()
    }
}
