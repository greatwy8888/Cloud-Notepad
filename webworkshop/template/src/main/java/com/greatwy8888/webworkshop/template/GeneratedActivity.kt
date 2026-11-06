package com.greatwy8888.webworkshop.template

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class GeneratedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = assets.open("webworkshop.json").bufferedReader().use { JSONObject(it.readText()) }
        val container = FrameLayout(this)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = false
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                splash?.animate()?.alpha(0f)?.setDuration(220)?.withEndAction {
                    container.removeView(splash)
                    splash = null
                }?.start()
            }
        }
        container.addView(web, FrameLayout.LayoutParams(-1, -1))
        val splashId = resources.getIdentifier("splash", "drawable", packageName)
        if (splashId != 0) {
            splash = ImageView(this).apply {
                setImageResource(splashId)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFFFFFFF.toInt())
            }
            container.addView(splash, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        setContentView(container)
        if (config.optString("mode") == "url") {
            web.loadUrl(config.getString("url"))
        } else {
            web.loadUrl("file:///android_asset/site/" + config.optString("entry", "index.html"))
        }
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
