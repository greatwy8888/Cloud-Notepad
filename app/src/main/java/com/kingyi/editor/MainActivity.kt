package com.kingyi.editor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var exportContent: String? = null
    private var exportMime: String = "text/plain"
    @Volatile private var editorUnlocked = false
    @Volatile private var pendingTxt: JSONObject? = null
    private val prefs by lazy { getSharedPreferences("local_access", MODE_PRIVATE) }
    private val notesDir by lazy { File(filesDir, "notes").apply { mkdirs() } }

    companion object {
        private const val FILE_PICKER = 501
        private const val EXPORT_PICKER = 502
        private const val ITERATIONS = 210_000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.settings.setSupportMultipleWindows(false)
        web.addJavascriptInterface(OfflineBridge(), "AndroidBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                url == null || !(url.startsWith("file:///android_asset/") || url.startsWith("about:blank"))
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                this@MainActivity.fileChooserCallback = filePathCallback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/json", "application/xml", "image/*"))
                }
                startActivityForResult(intent, FILE_PICKER)
                return true
            }
        }
        setContentView(web)
        handleIncomingIntent(intent)
        if (savedInstanceState == null) web.loadUrl("file:///android_asset/index.html")
        else web.restoreState(savedInstanceState)
    }

    private inner class OfflineBridge {
        @JavascriptInterface fun exitApp() { runOnUiThread { finish() } }

        @JavascriptInterface fun setEditorUnlocked(unlocked: Boolean) { editorUnlocked = unlocked }

        @JavascriptInterface fun takePendingFile(): String {
            val item = pendingTxt ?: return JSONObject().put("success", false).toString()
            pendingTxt = null
            return item.toString()
        }

        @JavascriptInterface fun hasPassword(): Boolean = !prefs.getString("password_hash", null).isNullOrEmpty()

        @JavascriptInterface fun setPassword(password: String): Boolean {
            if (password.length < 4) return false
            return try {
                val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val hash = derive(password, salt)
                prefs.edit().putString("password_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString("password_hash", Base64.encodeToString(hash, Base64.NO_WRAP)).apply()
                true
            } catch (_: Exception) { false }
        }

        @JavascriptInterface fun verifyPassword(password: String): Boolean {
            val saltText = prefs.getString("password_salt", null) ?: return false
            val hashText = prefs.getString("password_hash", null) ?: return false
            return try {
                val salt = Base64.decode(saltText, Base64.NO_WRAP)
                val expected = Base64.decode(hashText, Base64.NO_WRAP)
                MessageDigest.isEqual(expected, derive(password, salt))
            } catch (_: Exception) { false }
        }

        @JavascriptInterface fun changePassword(oldPassword: String, newPassword: String): Boolean {
            if (!verifyPassword(oldPassword) || newPassword.length < 4) return false
            return setPassword(newPassword)
        }

        @JavascriptInterface fun listFiles(): String {
            val arr = JSONArray()
            notesDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach {
                arr.put(JSONObject().put("name", it.name).put("ext", it.extension.lowercase()))
            }
            return arr.toString()
        }

        @JavascriptInterface fun saveFile(name: String, content: String, format: String): String {
            return try {
                val ext = if (format.equals("txt", true)) "txt" else "html"
                val safe = safeBaseName(name)
                val file = File(notesDir, if (safe.lowercase().endsWith(".$ext")) safe else "$safe.$ext")
                file.writeText(content, Charsets.UTF_8)
                JSONObject().put("success", true).put("filename", file.name).put("message", "已保存到本机").toString()
            } catch (e: Exception) {
                JSONObject().put("success", false).put("message", e.message ?: "保存失败").toString()
            }
        }

        @JavascriptInterface fun readFile(name: String): String {
            return try {
                val file = safeNoteFile(name)
                if (!file.exists() || !file.isFile) throw IllegalArgumentException("文件不存在")
                JSONObject().put("success", true).put("filename", file.name)
                    .put("ext", file.extension.lowercase()).put("content", file.readText(Charsets.UTF_8)).toString()
            } catch (e: Exception) {
                JSONObject().put("success", false).put("message", e.message ?: "打开失败").toString()
            }
        }

        @JavascriptInterface fun deleteFile(name: String): Boolean = try {
            safeNoteFile(name).delete()
        } catch (_: Exception) { false }

        @JavascriptInterface fun exportFile(name: String, mime: String, content: String) {
            runOnUiThread {
                exportContent = content
                exportMime = mime
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(Intent.EXTRA_TITLE, safeBaseName(name))
                }
                startActivityForResult(intent, EXPORT_PICKER)
            }
        }

        @JavascriptInterface fun exportPdf(name: String) {
            runOnUiThread {
                try {
                    val manager = getSystemService(PRINT_SERVICE) as PrintManager
                    val adapter = web.createPrintDocumentAdapter(safeBaseName(name))
                    manager.print(safeBaseName(name), adapter, PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(android.print.PrintAttributes.Resolution("pdf", "PDF", 300, 300))
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build())
                } catch (_: Exception) { }
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("无法读取所选 TXT 文件")
            if (bytes.size > 10 * 1024 * 1024) throw IllegalArgumentException("TXT 文件不能超过 10 MB")
            var name = "打开的文件.txt"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index) ?: name
                }
            }
            val text = decodeText(bytes)
            pendingTxt = JSONObject().put("success", true).put("filename", name).put("content", text)
        } catch (e: Exception) {
            pendingTxt = JSONObject().put("success", false).put("message", e.message ?: "打开 TXT 失败")
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        val data = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        val candidates = listOf(Charsets.UTF_8, Charset.forName("GB18030"), Charset.forName("Big5"))
        for (charset in candidates) {
            try {
                return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString()
            } catch (_: Exception) { }
        }
        return String(data, Charsets.UTF_8)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        if (::web.isInitialized) {
            web.evaluateJavascript(
                "(function(){if(window.receiveExternalTxt){window.receiveExternalTxt();}})()",
                null
            )
        }
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun safeBaseName(raw: String): String {
        val cleaned = raw.trim().filter { it >= ' ' && it !in charArrayOf('/', '\\', ':', '*', '?', '\"', '<', '>', '|') }.trim('.', ' ')
        return (cleaned.ifBlank { "未命名" }).take(100)
    }

    private fun safeNoteFile(name: String): File {
        val safe = File(name).name
        require(safe == name && safe.isNotBlank() && !name.contains('\\') && !name.any { it.code == 0 }) { "无效文件名" }
        return File(notesDir, safe)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICKER) {
            val result = if (resultCode == RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
            fileChooserCallback?.onReceiveValue(result)
            fileChooserCallback = null
        } else if (requestCode == EXPORT_PICKER) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (uri != null) {
                try { contentResolver.openOutputStream(uri)?.use { it.write((exportContent ?: "").toByteArray(Charsets.UTF_8)) } }
                catch (_: Exception) { }
            }
            exportContent = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Once logged out, the system Back button exits immediately.
        if (!editorUnlocked || !::web.isInitialized) {
            finish()
            return
        }
        web.evaluateJavascript(
            "(function(){return !!(window.handleAndroidBack && window.handleAndroidBack());})()"
        ) { result ->
            if (result != "true") finish()
        }
    }
}
