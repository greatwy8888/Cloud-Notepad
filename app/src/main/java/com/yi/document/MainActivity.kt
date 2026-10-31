package com.yi.document

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private lateinit var editor: WebView
    private lateinit var status: TextView
    private var currentName = "未命名文档.html"
    private var dirty = false
    private var pendingMedia = "image"
    private var pendingSave = false

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadPickedFile(uri)
    }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) writeDocument(uri)
    }
    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) insertMedia(uri, pendingMedia)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        editor.loadDataWithBaseURL("https://editor.local/", EDITOR_HTML, "text/html", "UTF-8", null)
        intent?.data?.let { loadPickedFile(it) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 248, 252))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "毅的文档"
            textSize = 20f
            setTextColor(Color.rgb(35, 61, 91))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(0, 48, 1f))
        header.addView(actionButton("打开") { openFile.launch(arrayOf("*/*")) })
        header.addView(actionButton("保存") { askSave() })
        root.addView(header)

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 5, 8, 5)
            setBackgroundColor(Color.rgb(235, 243, 252))
        }
        listOf(
            "撤销" to "undo()", "重做" to "redo()", "粗体" to "bold()",
            "斜体" to "italic()", "下划线" to "underline()",
            "字号" to "fontSize()", "颜色" to "color()",
            "图片" to "image()", "视频" to "video()"
        ).forEach { (label, js) -> tools.addView(actionButton(label) { runJs(js) }) }
        root.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52))

        editor = WebView(this).apply {
            setBackgroundColor(Color.rgb(245, 248, 252))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(EditorBridge(), "Native")
        }
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status = TextView(this).apply {
            text = "就绪 · 支持文字、网页与代码编辑"
            textSize = 12f
            setTextColor(Color.rgb(100, 119, 143))
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(status)
        setContentView(root)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.rgb(48, 105, 174))
        setPadding(7, 0, 7, 0)
        setOnClickListener { action() }
    }

    private fun runJs(code: String) { editor.evaluateJavascript("Editor.$code", null) }

    private fun loadPickedFile(uri: Uri) {
        val name = queryName(uri)
        currentName = name
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp")) {
            try {
                val mime = contentResolver.getType(uri) ?: "*/*"
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                status.text = "$name · 已交由兼容应用预览"
            } catch (_: Exception) {
                Toast.makeText(this, "未找到可打开此文件的应用", Toast.LENGTH_LONG).show()
            }
            return
        }
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val mode = if (ext in listOf("html", "htm", "xhtml")) "html" else "code"
            editor.evaluateJavascript("Editor.loadText('$encoded','$mode')", null)
            dirty = false
            status.text = "$name · 已打开"
        } catch (_: Exception) {
            Toast.makeText(this, "文件读取失败或编码不受支持", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryName(uri: Uri): String {
        var result = uri.lastPathSegment ?: "document.txt"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) result = c.getString(i) ?: result
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun askSave() {
        AlertDialog.Builder(this).setTitle("保存文档")
            .setMessage("是否保存当前文档？")
            .setPositiveButton("保存") { _, _ -> pendingSave = true; saveFile.launch(currentName.substringAfterLast('/').ifBlank { "文档.html" }) }
            .setNegativeButton("不保存") { _, _ -> dirty = false; status.text = "未保存的修改已放弃" }
            .setNeutralButton("取消", null).show()
    }

    private fun writeDocument(uri: Uri) {
        editor.evaluateJavascript("Editor.exportHtml()") { encoded ->
            try {
                val clean = encoded?.trim('"')?.replace("\\n", "\n")?.replace("\\"", "\"") ?: ""
                val html = String(Base64.decode(clean, Base64.DEFAULT), Charsets.UTF_8)
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(html) }
                dirty = false
                currentName = queryName(uri)
                status.text = "$currentName · 已保存"
                Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { Toast.makeText(this, "保存失败", Toast.LENGTH_LONG).show() }
        }
    }

    private fun insertMedia(uri: Uri, type: String) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    total += n
                    if (total > 15 * 1024 * 1024) throw IllegalArgumentException("文件超过15MB")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: return
            val mime = contentResolver.getType(uri) ?: if (type == "image") "image/*" else "video/*"
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            editor.evaluateJavascript("Editor.insertMedia('data:$mime;base64,$b64','$type')", null)
            dirty = true
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "媒体插入失败", Toast.LENGTH_LONG).show()
        }
    }

    inner class EditorBridge {
        @JavascriptInterface fun changed() { runOnUiThread { dirty = true; status.text = "$currentName · 有未保存修改" } }
        @JavascriptInterface fun chooseImage() { runOnUiThread { pendingMedia = "image"; pickMedia.launch("image/*") } }
        @JavascriptInterface fun chooseVideo() { runOnUiThread { pendingMedia = "video"; pickMedia.launch("video/*") } }
        @JavascriptInterface fun setStatus(message: String) { runOnUiThread { status.text = message } }
    }

    override fun onBackPressed() {
        if (dirty) askSave() else super.onBackPressed()
    }

    companion object {
        private val EDITOR_HTML = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
*{box-sizing:border-box}body{margin:0;background:#f5f8fc;color:#25364b;font-family:Arial,sans-serif}
#paper{background:white;max-width:980px;min-height:calc(100vh - 28px);margin:14px auto;padding:28px 24px;box-shadow:0 4px 22px #dce6f2;border-radius:14px;outline:none;line-height:1.7;overflow-wrap:anywhere}
#paper:empty:before{content:'在这里开始写作……';color:#a9b7c8}
.media{display:inline-block;position:relative;max-width:100%;min-width:80px;vertical-align:middle;resize:both;overflow:auto;border:1px dashed #91b8ec;margin:8px;touch-action:pan-y}
.media img,.media video{display:block;width:100%;height:auto;max-width:100%}
pre{white-space:pre-wrap;overflow-wrap:anywhere;font-family:monospace;background:#f5f7fa;padding:14px;border-radius:8px}
@media(min-width:700px){#paper{margin:24px auto;padding:44px 56px;min-height:calc(100vh - 48px)}}
</style></head><body><div id="paper" contenteditable="true" spellcheck="false"></div>
<script>
const paper=document.getElementById('paper');
let history=[],pos=-1,lock=false;
function snap(){if(lock)return;let v=paper.innerHTML;if(history[pos]===v)return;history=history.slice(0,pos+1);history.push(v);if(history.length>20)history.shift();pos=history.length-1;Native.changed();}
function restore(i){if(i<0||i>=history.length)return;lock=true;pos=i;paper.innerHTML=history[pos];lock=false;}
paper.addEventListener('input',()=>{clearTimeout(window.st);window.st=setTimeout(snap,180)});
paper.addEventListener('pointerup',()=>{});
paper.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='z'){e.preventDefault();e.shiftKey?Editor.redo():Editor.undo()}});
const Editor={
undo(){restore(pos-1)},redo(){restore(pos+1)},
bold(){document.execCommand('bold')},italic(){document.execCommand('italic')},underline(){document.execCommand('underline')},
fontSize(){let s=prompt('输入字号（1-7）','4');if(s)document.execCommand('fontSize',false,Math.max(1,Math.min(7,parseInt(s)||4)))},
color(){let c=prompt('输入颜色（例如 #2878d0）','#2878d0');if(c)document.execCommand('foreColor',false,c)},
image(){Native.chooseImage()},video(){Native.chooseVideo()},
insertMedia(data,type){let wrap=document.createElement('span');wrap.className='media';wrap.contentEditable='false';let el=document.createElement(type==='image'?'img':'video');el.src=data;if(type==='video'){el.controls=true;el.playsInline=true}wrap.appendChild(el);paper.appendChild(wrap);snap()},
loadText(b64,mode){let text=decodeURIComponent(escape(atob(b64)));paper.innerHTML='';if(mode==='html'){paper.innerHTML=text}else{let pre=document.createElement('pre');pre.contentEditable='true';pre.textContent=text;paper.appendChild(pre)}history=[];pos=-1;snap()},
exportHtml(){let doc='<!doctype html><meta charset="utf-8"><title>毅的文档</title><style>body{font-family:Arial;line-height:1.7}.media{display:inline-block;max-width:100%;resize:both;overflow:auto}.media img,.media video{width:100%;height:auto}</style>'+paper.innerHTML;return btoa(unescape(encodeURIComponent(doc)))}
};
window.Editor=Editor;snap();
</script></body></html>
        """.trimIndent()
    }
}
