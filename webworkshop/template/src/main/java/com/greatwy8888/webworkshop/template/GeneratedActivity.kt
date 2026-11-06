package com.greatwy8888.webworkshop.template

import android.app.Activity
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class GeneratedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = assets.open("webworkshop.json").bufferedReader().use { JSONObject(it.readText()) }
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = false
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        setContentView(web)
        if (config.optString("mode") == "url") {
            web.loadUrl(config.getString("url"))
        } else {
            web.loadUrl("file:///android_asset/site/" + config.optString("entry", "index.html"))
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        val web = (if (window.decorView is android.view.ViewGroup) window.decorView as android.view.ViewGroup else null)
        val child = web?.getChildAt(0)
        if (child is WebView && child.canGoBack()) child.goBack() else super.onBackPressed()
    }
}
