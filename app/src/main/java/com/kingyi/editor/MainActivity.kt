package com.kingyi.editor
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.view.WindowManager
class MainActivity: Activity() {
 private lateinit var web: WebView
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); web=WebView(this); web.settings.javaScriptEnabled=true; web.settings.domStorageEnabled=true; web.settings.allowFileAccess=true; web.webViewClient=WebViewClient(); web.webChromeClient=WebChromeClient(); setContentView(web); if(savedInstanceState==null) web.loadUrl("file:///android_asset/index.html") else web.restoreState(savedInstanceState) }
 override fun onSaveInstanceState(outState: Bundle) { web.saveState(outState); super.onSaveInstanceState(outState) }
 @Deprecated("Deprecated in Java") override fun onBackPressed() { web.evaluateJavascript("window.handleAndroidBack && window.handleAndroidBack()", null) }
}
